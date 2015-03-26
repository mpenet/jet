(ns qbits.jet.server
  "Adapter for the Jetty 9 server, with websocket + core.async support.
Derived from ring.adapter.jetty"
  (:require
   [qbits.jet.servlet :as servlet]
   [clojure.core.async :as async]
   [qbits.jet.websocket :refer :all])
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
   (qbits.jet.websocket WebSocket)))

(defn- make-ws-creator
  [handler {:keys [in out ctrl]
            :or {in async/chan
                 out async/chan
                 ctrl async/chan}
            :as options}]
  (reify WebSocketCreator
    (createWebSocket [this _ _]
      (make-websocket (in) (out) (ctrl) handler))))

(defn- make-ws-handler
  "Returns a Jetty websocket handler"
  [handlers {:as options
             :keys [ws-max-idle-time]
             :or {ws-max-idle-time 500000}}]
  (proxy [WebSocketHandler] []
    (configure [^WebSocketServletFactory factory]
      (-> (.getPolicy factory)
          (.setIdleTimeout ws-max-idle-time))
      (.setCreator factory (make-ws-creator handlers options)))))

(defn- make-handler
  "Returns an Jetty Handler implementation for the given Ring handler."
  [handler options]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request request response]
      (let [request-map (servlet/build-request-map request)
            response' (handler request-map)]
        (when response'
          (servlet/update-response response' request-map)
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

(defn ^Server run-jetty
  "Start a Jetty webserver to serve the given handler according to the
supplied options:


* `:port` - the port to listen on (defaults to 80)
* `:host` - the hostname to listen on
* `:join?` - blocks the thread until server ends (defaults to true)
* `:configurator` - fn that will be passed the server instance before server.start()
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
* `:input-buffer-size` - (default 8192)
* `:request-header-size` - (default 8192)
* `:response-header-size` - (default 8192)
* `:send-server-version?` - (default true)
* `:send-date-header?` - (default false)
* `:header-cache-size` - (default 512)
* `:websocket-handler` - a handler function that will receive a RING request map with the following keys added:

    * `:in`: core.async chan that receives data sent by the client
    * `:out`: core async chan you can use to send data to client
    * `:ctrl`: core.async chan that received control messages such as: `[::error e]`, `[::close code reason]`"
  [{:as options
    :keys [websocket-handler ring-handler host port max-threads min-threads
           input-buffer-size max-idle-time ssl-port configurator
           daemon? ssl? join?]
    :or {port 80
         max-threads 50
         min-threads 8
         daemon? false
         max-idle-time 200000
         ssl? false
         join? true
         input-buffer-size 8192}}]
  (let [pool (doto (QueuedThreadPool. (int max-threads)
                                      (int min-threads))
               (.setDaemon daemon?))
        server (doto (Server. pool)
                 (.addBean (ScheduledExecutorScheduler.)))
        http-connection-factory (doto (HttpConnectionFactory. (http-config options))
                                  (.setInputBufferSize (int input-buffer-size)))
        ^"[Lorg.eclipse.jetty.server.ConnectionFactory;" connection-factories
        (into-array ConnectionFactory [http-connection-factory])
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
    (when (or websocket-handler ring-handler)
      (let [hs (HandlerList.)]
        (when websocket-handler
          (.addHandler hs (make-ws-handler websocket-handler options)))
        (when ring-handler
          (.addHandler hs (make-handler ring-handler options)))
        (.setHandler server hs)))
    (when configurator
      (configurator server))
    (.start server)
    (when join?
      (.join server))
    server))
