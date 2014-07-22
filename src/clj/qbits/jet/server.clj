(ns qbits.jet.server
  "Adapter for the Jetty 9 server, with websocket + core.async support.
Derived from ring.adapter.jetty"
  (:require
   [ring.util.servlet :as servlet]
   [clojure.string :as string]
   [clojure.core.async :as async])
  (:import
   (org.eclipse.jetty.server
    Handler
    Server
    Request
    ServerConnector
    HttpConfiguration
    HttpConnectionFactory
    SslConnectionFactory
    ConnectionFactory)
   (org.eclipse.jetty.server.handler
    HandlerCollection
    AbstractHandler
    ContextHandler
    HandlerList)
   (org.eclipse.jetty.util.thread
    QueuedThreadPool
    ScheduledExecutorScheduler)
   (org.eclipse.jetty.util.ssl SslContextFactory)
   (org.eclipse.jetty.websocket.server WebSocketHandler)
   (org.eclipse.jetty.websocket.servlet
    WebSocketServletFactory
    WebSocketCreator
    ServletUpgradeRequest
    ServletUpgradeResponse)
   (javax.servlet.http
    HttpServletRequest
    HttpServletResponse)
   (org.eclipse.jetty.websocket.api
    WebSocketListener
    RemoteEndpoint
    Session
    UpgradeRequest)
   (clojure.lang IFn)
   (java.nio ByteBuffer)
   (clojure.core.async.impl.channels ManyToManyChannel)))

(defprotocol PWebSocket
  (send! [this msg] "Send content to client connected to this WebSocket instance")
  (close! [this] "Close active WebSocket")
  (remote ^RemoteEndpoint [this] "Remote endpoint instance")
  (remote-addr [this] "Address of remote client")
  (idle-timeout! [this ms] "Set idle timeout on client"))

(defprotocol WebSocketSend
  (-send! [x ^WebSocket ws] "How to encode content sent to the WebSocket clients"))

(defrecord WebSocketBinaryFrame [payload offset len])

(defn close-chans!
  [& chs]
  (doseq [ch chs]
    (async/close! ch)))

(deftype WebSocket
    [^ManyToManyChannel in
     ^ManyToManyChannel out
     ^ManyToManyChannel ctrl
     ^IFn handler
     ^{:volatile-mutable true :tag Session} session]

  WebSocketListener
  (onWebSocketConnect [this s]
    (set! session s)
    (async/put! ctrl [::connect this])
    (handler {:in in :out out :ctrl ctrl :ws this})
    (async/go
      (loop []
        (when-let [x (async/<! out)]
          (send! this x)
          (recur)))))
  (onWebSocketError [this e]
    (async/put! ctrl [:error e])
    (close-chans! in out ctrl))
  (onWebSocketClose [this statusCode reason]
    (set! session nil)
    (async/put! ctrl [::close reason])
    (close-chans! in out ctrl))
  (onWebSocketText [this message]
    (async/put! in message))
  (onWebSocketBinary [this payload offset len]
    (async/put! in (WebSocketBinaryFrame. payload offset len)))

  PWebSocket
  (remote [this]
    (when session
      (.getRemote session)))
  (send! [this msg]
    (-send! msg this))
  (close! [this]
    (.close session))
  (remote-addr [this]
    (.getRemoteAddress session))
  (idle-timeout! [this ms]
    (.setIdleTimeout session (long ms))))

(extend-protocol WebSocketSend

  (Class/forName "[B")
  (-send! [ba ws]
    (-send! (ByteBuffer/wrap ba) ws))

  ByteBuffer
  (-send! [bb ws]
    (-> ws remote (.sendBytes ^ByteBuffer bb)))

  String
  (-send! [s ws]
    (-> ws remote (.sendString ^String s)))

  IFn
  (-send! [f ws]
    (-> ws remote f))

  Object
  (-send! [this ws]
    (-> ws remote (.sendString (str this))))

  ;; "nil" could PING?
  ;; nil
  ;; (-send! [this ws] ()
  )

(defn- reify-ws-creator
  [handler]
  (reify WebSocketCreator
    (createWebSocket [this _ _]
      (WebSocket. (async/chan) (async/chan) (async/chan) handler nil))))

(defprotocol RequestMapDecoder
  (build-request-map [r]))

(extend-protocol RequestMapDecoder
  HttpServletRequest
  (build-request-map [request]
    (servlet/build-request-map request))

  UpgradeRequest
  (build-request-map [request]
    {:uri (.getRequestURI request)
     :query-string (.getQueryString request)
     :origin (.getOrigin request)
     :host (.getHost request)
     :request-method (-> request .getMethod string/lower-case keyword)
     :headers (reduce(fn [m [k v]]
                       (assoc m (string/lower-case k) (string/join "," v)))
                     {}
                     (.getHeaders request))}))

(defn- make-ws-handler
  "Returns a Jetty websocket handler"
  [handlers {:as options
             :keys [ws-max-idle-time]
             :or {ws-max-idle-time 500000}}]
  (proxy [WebSocketHandler] []
    (configure [^WebSocketServletFactory factory]
      (-> (.getPolicy factory)
          (.setIdleTimeout ws-max-idle-time))
      (.setCreator factory (reify-ws-creator handlers)))))

(defn- make-handler
  "Returns an Jetty Handler implementation for the given Ring handler."
  [handler]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request request response]
      (let [request-map (build-request-map request)
            response-map (handler request-map)]
        (when response-map
          (servlet/update-servlet-response response response-map)
          (.setHandled base-request true))))))

(defn- http-config
  [{:as options
    :keys [ssl-port secure-scheme output-buffer-size request-header-size
           response-header-size send-server-version? send-date-header?
           header-cache-size]
    :or {ssl-port 443
         secure-scheme "https"
         output-buffer-size 32768
         request-header-size 8192
         response-header-size 8192
         send-server-version? true
         send-date-header? false
         header-cache-size 512}}]
  "Creates jetty http configurator"
  (doto (HttpConfiguration.)
    (.setSecureScheme secure-scheme)
    (.setSecurePort ssl-port)
    (.setOutputBufferSize output-buffer-size)
    (.setRequestHeaderSize request-header-size)
    (.setResponseHeaderSize response-header-size)
    (.setSendServerVersion send-server-version?)
    (.setSendDateHeader send-date-header?)
    (.setHeaderCacheSize header-cache-size)))

(defn- ^SslContextFactory ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  [{:as options
    :keys [keystore keystore-type key-password client-auth
           truststore trust-password truststore-type]}]
  (let [context (SslContextFactory.)]
    (if (string? keystore)
      (.setKeyStorePath context keystore)
      (.setKeyStore context ^java.security.KeyStore keystore))
    (.setKeyStorePassword context key-password)
    (when keystore-type
      (.setKeyStoreType context keystore-type))
    (when truststore
      (.setTrustStore context ^java.security.KeyStore truststore))
    (when trust-password
      (.setTrustStorePassword context trust-password))
    (when truststore-type
      (.setTrustStoreType context truststore-type))
    (case client-auth
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    context))

(defn- create-server
  "Construct a Jetty Server instance."
  [{:as options
    :keys [port max-threads daemon? max-idle-time host ssl? ssl-port]
    :or {port 80
         max-threads 50
         daemon? false
         max-idle-time 200000
         ssl? false}}]
  (let [pool (doto (QueuedThreadPool. (int max-threads))
               (.setDaemon daemon?))
        server (doto (Server. pool)
                 (.addBean (ScheduledExecutorScheduler.)))

        http-configuration (http-config options)

        ^"[Lorg.eclipse.jetty.server.ConnectionFactory;" connection-factories
        (into-array ConnectionFactory [(HttpConnectionFactory. http-configuration)])

        connectors (-> []
                       (conj (doto (ServerConnector. ^Server server connection-factories)
                               (.setPort port)
                               (.setHost host)
                               (.setIdleTimeout max-idle-time)))
                       (cond-> (or ssl? ssl-port)
                               (conj (doto (ServerConnector. ^Server server
                                                             (ssl-context-factory options)
                                                             connection-factories)
                                  (.setPort ssl-port)
                                  (.setHost host)
                                  (.setIdleTimeout max-idle-time))))
                       (into-array))]
    (.setConnectors server connectors)
    server))

(defn ^Server run
  "Start a Jetty webserver to serve the given handler according to the
supplied options:


* `:port` - the port to listen on (defaults to 80)
* `:host` - the hostname to listen on
* `:join?` - blocks the thread until server ends (defaults to true)
* `:daemon?` - use daemon threads (defaults to false)
* `:ssl?` - allow connections over HTTPS
* `:ssl-port` - the SSL port to listen on (defaults to 443, implies :ssl?)
* `:keystore` - the keystore to use for SSL connections
* `:keystore-type` - the format of keystore
* `:key-password` - the password to the keystore
* `:truststore` - a truststore to use for SSL connections
* `:truststore-type` - the format of trust store
* `:trust-password` - the password to the truststore
* `:max-threads` - the maximum number of threads to use (default 50)
* `:max-idle-time`  - the maximum idle time in milliseconds for a connection (default 200000)
* `:ws-max-idle-time`  - the maximum idle time in milliseconds for a websocket connection (default 500000)
* `:client-auth` - SSL client certificate authenticate, may be set to :need, :want or :none (defaults to :none)
* `:output-buffer-size` - (default 32768)
* `:request-header-size` - (default 8192)
* `:response-header-size` - (default 8192)
* `:send-server-version?` - (default true)
* `:send-date-header?` - (default false)
* `:header-cache-size` - (default 512)
* `:websockets` - a map from context path to a map of handler fns:

     `{\"/context\" {\"foo\" (fn [{:keys [in out ctrl ws]}) ...)}}`

    * `:in`: core.async chan that receives data sent by the client
    * `:out`: core async chan you can use to send data to client
    * `:ctrl`: core.asyn chan that received control messages such as: `[::connect this]`, `[::error e]`, `[::close reason]`
"
  [ring-handler {:as options
                 :keys [max-threads websockets configurator join?]
                 :or {max-threads 50
                      join? true}}]
  (let [^Server s (create-server options)
        ^QueuedThreadPool p (QueuedThreadPool. (int max-threads))
        ring-app-handler (make-handler ring-handler)
        ws-handlers (map (fn [[context-path handler]]
                           (doto (ContextHandler.)
                             (.setContextPath context-path)
                             (.setHandler (make-ws-handler handler options))))
                         websockets)
        contexts (doto (HandlerList.)
                   (.setHandlers
                    (into-array Handler (reverse (conj ws-handlers ring-app-handler)))))]
    (.setHandler s contexts)
    (when-let [c configurator]
      (c s))
    (.start s)
    (when join?
      (.join s))
    s))

;; (run (fn [_])
;;   {:port 8013
;;    :websockets {"/api/entries/realtime/"
;;                 (fn [{:keys [in out ctrl ws]
;;                       :as opts}]
;;                   (async/go
;;                     (loop []
;;                       (when-let [x (async/<! ctrl)]
;;                         (println :ctrl x ctrl)
;;                         (recur))))
;;                   (async/go
;;                     (loop []
;;                       (when-let [x (async/<! in)]
;;                         (println :recv x in)
;;                         (recur))))

;;                   (future (dotimes [i 3]
;;                             (async/>!! out (str "send " i))
;;                             (Thread/sleep 1000)))

;;                   ;; (close! ws)
;;                   )}})
