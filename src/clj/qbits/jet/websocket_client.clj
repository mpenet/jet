(ns qbits.jet.websocket-client
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
  [url handler & [{:as options
                   :keys [executor ssl-context-factory]}]]

  (let [client (WebSocketClient.)
        request (ClientUpgradeRequest.)
        ws (ws/make-websocket handler)]

    (when executor
      (.setExecutor client executor))

    (.start client)

    (.connect client ws (URI/create url))

    ;; (Thread/sleep 1000)
    ;; (prn (async/<!! (.-ctrl ws)))
    ;; (prn :after)

    (Client. client request ws)))

(future
  (ws-client "ws://foo.com:8013/api/entries/realtime/"
             (fn [{:keys [in out ctrl ws]}]
               (prn in out ctrl ws)
               ;; (async/go
               ;;   (loop []
               ;;     (when-let [x (async/<! ctrl)]
               ;;       (println :client-ctrl x ctrl)
               ;;       (recur))))

               ;; (async/go
               ;;   (loop []
               ;;     (when-let [x (async/<! in)]
               ;;       (println :client-recv x in)
               ;;       (recur))))

               (future (dotimes [i 3]
                  (async/>!! out (str "client send " (str "client-" i)))
                  (Thread/sleep 1000)))
               )))
