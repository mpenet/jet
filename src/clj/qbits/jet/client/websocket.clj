(ns qbits.jet.client.websocket
  (:require
   [qbits.jet.websocket :as ws]
   [clojure.core.async :as async])
  (:import
   (java.net URI)
   (org.eclipse.jetty.websocket.client
    WebSocketClient
    ClientUpgradeRequest)
   (qbits.jet.websocket WebSocket)))

(defrecord Client [client request socket])

(defn ws-client
  "Takes an url a handler, an option map (optional) and returns a websocket
client.

The handler receives a map of:

* `:in` - core.async chan that receives data sent by the client
* `:out` - core async chan you can use to send data to client, or close the
connection by closing the channel
* `:ctrl` - core.asyn chan that received control messages such as:
`[::connect this]`, `[::error e]`, `[::close reason]`
* `:ws` - qbits.jet.websocket/WebSocket instance"
  [url handler & [{:as options
                   :keys [executor ssl-context-factory]}]]

  (let [client (WebSocketClient.)
        request (ClientUpgradeRequest.)
        ws (ws/make-websocket handler)]

    (when executor
      (.setExecutor client executor))

    (.start client)

    (.connect client ws (URI/create url))
    (Client. client request ws)))

;; (future
;;   (ws-client "ws://foo.com:8013/api/entries/realtime/"
;;              (fn [{:keys [in out ctrl ws]}]
;;                (prn in out ctrl ws)
;;                ;; (async/go
;;                ;;   (loop []
;;                ;;     (when-let [x (async/<! ctrl)]
;;                ;;       (println :client-ctrl x ctrl)
;;                ;;       (recur))))

;;                ;; (async/go
;;                ;;   (loop []
;;                ;;     (when-let [x (async/<! in)]
;;                ;;       (println :client-recv x in)
;;                ;;       (recur))))

;;                (future (dotimes [i 3]
;;                   (async/>!! out (str "client send " (str "client-" i)))
;;                   (Thread/sleep 1000)))
;;                )))
