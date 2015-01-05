(ns qbits.jet.websocket
  "Shared by the server and the client impl"
  (:require
   [clojure.core.async :as async]
   [qbits.jet.async :as a]
   [clojure.string :as string])
  (:import
   (org.eclipse.jetty.websocket.api
    WebSocketListener
    RemoteEndpoint
    Session
    SuspendToken
    UpgradeRequest)
   (clojure.lang IFn)
   (java.nio ByteBuffer)
   (clojure.core.async.impl.channels ManyToManyChannel)))

(defprotocol ^:no-doc PWebSocket
  (send! [this msg] "Send content to client connected to this WebSocket instance")
  (close! [this] "Close active WebSocket")
  (remote ^RemoteEndpoint [this] "Remote endpoint instance")
  (session ^Session [this] "Session instance")
  (remote-addr [this] "Address of remote client")
  (idle-timeout! [this ms] "Set idle timeout on client"))

(defprotocol ^:no-doc PBackPressure
  (suspend-reads! [this])
  (resume-reads! [this]))

(defprotocol ^:no-doc WebSocketSend
  (-send! [x ^WebSocket ws] "How to encode content sent to the WebSocket clients"))

(defn ^:no-doc close-chans!
  [& chs]
  (doseq [ch chs]
    (async/close! ch)))

(extend-protocol WebSocketSend

  (Class/forName "[B")
  (-send! [ba ws]
    (-send! (ByteBuffer/wrap ba) ws))

  ByteBuffer
  (-send! [bb ws]
    (some-> ws remote (.sendBytes ^ByteBuffer bb)))

  String
  (-send! [s ws]
    (some-> ws remote (.sendString ^String s)))

  IFn
  (-send! [f ws]
    (some-> ws remote f))

  Object
  (-send! [this ws]
    (some-> ws remote (.sendString (str this))))

  ;; "nil" could PING?
  ;; nil
  ;; (-send! [this ws] ()
  )

(defrecord WebSocketBinaryFrame [payload offset len])

(deftype WebSocket
    [^ManyToManyChannel in
     ^ManyToManyChannel out
     ^ManyToManyChannel ctrl
     ^IFn handler
     ^Session ^:volatile-mutable session
     ^SuspendToken ^:volatile-mutable reads-suspend-token]

  WebSocketListener
  (onWebSocketConnect [this s]
    (set! session s)
    (async/go
      (loop []
        ;; if we pull out of value of out, we send it and recur for
        ;; another one, otherwise that means the user closed it, in
        ;; that case we close the Socket (if not closed already)
        ;; and exit the loop.
        (if-let [x (async/<! out)]
          (do
            (send! this x)
            (recur))
          (close! this))))
    (let [request (.getUpgradeRequest s)
          uri (.getRequestURI request)
          port (.getPort uri)]
      (handler
       {:in in
        :out out
        :ctrl ctrl
        :ws this
        :server-name (.getHost uri)
        :server-port port
        :remote-addr (-> session .getRemoteAddress .getAddress .getHostAddress)
        :uri (.getPath uri)
        :scheme (if (= 443 port) :wss :ws)
        :query-string (.getQueryString request)
        :request-method :get ;; (some-> request .getMethod string/lower-case keyword)
        :headers (reduce(fn [m [k v]]
                          (assoc m (string/lower-case k) (string/join "," v)))
                        {}
                        (.getHeaders request))})))

  (onWebSocketError [this e]
    (async/put! ctrl [::error e])
    (close-chans! in out ctrl))

  (onWebSocketClose [this code reason]
    (set! session nil)
    (async/put! ctrl [::close code reason])
    (close-chans! in out ctrl))

  (onWebSocketText [this message]
    (a/put! in message
            #(suspend-reads! this)
            #(resume-reads! this)))

  (onWebSocketBinary [this payload offset len]
    (a/put! in (WebSocketBinaryFrame. payload offset len)
            #(suspend-reads! this)
            #(resume-reads! this)))

  PBackPressure
  (suspend-reads! [this]
    (set! reads-suspend-token (.suspend session)))

  (resume-reads! [this]
    (.resume reads-suspend-token)
    (set! reads-suspend-token nil))

  PWebSocket
  (remote [this]
    (when session
      (.getRemote session)))

  (session [this] session)

  (send! [this msg]
    (-send! msg this))

  (close! [this]
    (when (some-> session .isOpen)
      (.close session)))

  (remote-addr [this]
    (.getRemoteAddress session))

  (idle-timeout! [this ms]
    (.setIdleTimeout session (long ms))))

(defn ^:no-doc make-websocket
  [in out ctrl handler]
  (WebSocket. in out ctrl handler nil nil))
