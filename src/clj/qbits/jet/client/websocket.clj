(ns qbits.jet.client.websocket
  (:require
   [qbits.jet.websocket :as ws]
   [clojure.core.async :as async])
  (:import
   (java.net URI)
   (org.eclipse.jetty.websocket.client ClientUpgradeRequest)
   (org.eclipse.jetty.http HttpField)
   (qbits.jet.websocket WebSocket)))

(defrecord Connection [client request socket])

(defn connect!
  "Takes a WebSocketClient, a url, a handler, an option map and returns a websocket
client.

The option map can take the following keys:

* `:executor` - java.util.concurrent.Executor instance
* `:ssl-context-factory` - SSLContextFactory instance to be use to perform wss
requests http://download.eclipse.org/jetty/stable-9/apidocs/org/eclipse/jetty/util/ssl/SslContextFactory.html
* `:in` - fn that returns the :in channel that the handler will receive -
defaults to c.core.async/chan
* `:out` - fn that returns the :in channel that the handler will receive -
defaults to c.core.async/chan
* `:ctrl` - fn that returns the :in channel that the handler will receive -
defaults to c.core.async/chan

The handler receives a map of:

* `:in` - core.async chan that receives data sent by the client
* `:out` - core async chan you can use to send data to client, or close the
connection by closing the channel
* `:ctrl` - core.asyn chan that received control messages such as:
``[::error e]`, `[::close reason]`
* `:ws` - qbits.jet.websocket/WebSocket instance"
  [client url handler & [{:as options
                          :keys [in out ctrl
                                 executor
                                 ssl-context-factory
                                 async-write-timeout
                                 connect-timeout
                                 max-idle-timeout
                                 max-binary-message-buffer-size
                                 max-text-message-buffer-size
                                 subprotocols
                                 daemon?
                                 middleware]
                          :or {in async/chan
                               out async/chan
                               ctrl async/chan}}]]

  (let [request (ClientUpgradeRequest.)
        ws (ws/make-websocket (in) (out) (ctrl) handler)]

    (when subprotocols
      (.setSubProtocols ^ClientUpgradeRequest request
                        ^"[Ljava.lang.String;" (into-array String subprotocols)))

    (when executor
      (.setExecutor client executor))

    (when max-idle-timeout
      (.setMaxIdleTimeout client (long max-idle-timeout)))

    ;; async timeout has an assertion related with idle timeout, it must be set after setting max idle timeout
    (when async-write-timeout
      (.setAsyncWriteTimeout client (long async-write-timeout)))

    (when connect-timeout
      (.setConnectTimeout client (long connect-timeout)))

    (when max-binary-message-buffer-size
      (.setMaxBinaryMessageBufferSize client (int max-binary-message-buffer-size)))

    (when max-text-message-buffer-size
      (.setMaxTextMessageBufferSize client
                                    (int max-text-message-buffer-size)))

    ;; (.setDaemon client daemon?)

;; void	setBindAdddress(SocketAddress bindAddress)
;; void	setCookieStore(CookieStore cookieStore)
;; void	setEventDriverFactory(EventDriverFactory factory)
;; void	setMasker(Masker masker)
    ;; void	setSessionFactory(SessionFactory sessionFactory)

    (when (fn? middleware)
      (middleware client request))

    (.start client)

    (.connect client ws (URI/create url) request)
    (Connection. client request ws)))
