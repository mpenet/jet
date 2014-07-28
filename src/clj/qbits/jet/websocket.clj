(ns qbits.jet.websocket
  "This listener could be shared by the server or/and the client impl"
  (:require
   [clojure.core.async :as async])
  (:import
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
  (session ^Session [this] "Session instance")
  (remote-addr [this] "Address of remote client")
  (idle-timeout! [this ms] "Set idle timeout on client"))

(defprotocol WebSocketSend
  (-send! [x ^WebSocket ws] "How to encode content sent to the WebSocket clients"))

(defn close-chans!
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
     ^Session ^:volatile-mutable session]

  WebSocketListener
  (onWebSocketConnect [this s]
    (set! session s)
    (async/put! ctrl [::connect this])
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
    (handler {:in in :out out :ctrl ctrl :ws this}))
  (onWebSocketError [this e]
    (async/put! ctrl [::error e])
    (close-chans! in out ctrl))

  (onWebSocketClose [this code reason]
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

(defn make-websocket
  [handler]
  (WebSocket. (async/chan) (async/chan) (async/chan) handler nil))
