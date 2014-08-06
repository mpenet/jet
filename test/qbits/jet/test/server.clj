(ns qbits.jet.test.server
  (:use
   clojure.test)
  (:require
   [qbits.jet.server :refer [run-jetty]]
   [qbits.jet.client.websocket :as ws]
   [qbits.jet.client.http :as http]
   [clojure.core.async :as async])
  (:import
   (org.eclipse.jetty.util.thread QueuedThreadPool)
   (org.eclipse.jetty.server Server Request)
   (org.eclipse.jetty.server.handler AbstractHandler)))

(defn- hello-world [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

(defn- content-type-handler [content-type]
  (constantly
   {:status  200
    :headers {"Content-Type" content-type}
    :body    ""}))

(defn- echo-handler [request]
  {:status 200
   :headers {"request-map" (str (dissoc request :body))}
   :body (:body request)})

(defmacro with-server [app options & body]
  `(let [server# (run-jetty ~app ~(assoc options :join? false))]
     (try
       ~@body
       (finally (.stop server#)))))

(deftest test-run-jetty
  (testing "HTTP server"
    (with-server hello-world {:port 4347}
      (let [response (async/<!! (http/get "http://localhost:4347"))]
        (is (= (:status response) 200))
        (is (.startsWith (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (async/<!! (:body response)) "Hello World")))))

  (testing "HTTPS server"
    (with-server hello-world {:port 4347
                              :ssl-port 4348
                              :keystore "test/keystore.jks"
                              :key-password "password"}
      (let [response (async/<!! (http/get "https://localhost:4348" {:insecure? true}))]
        (is (= (:status response) 200))
        (is (= (-> response :body async/<!!) "Hello World")))))

  (testing "setting daemon threads"
    (testing "default (daemon off)"
      (let [server (run-jetty hello-world {:port 4347 :join? false})]
        (is (not (.. server getThreadPool isDaemon)))
        (.stop server)))
    (testing "daemon on"
      (let [server (run-jetty hello-world {:port 4347 :join? false :daemon? true})]
        (is (.. server getThreadPool isDaemon))
        (.stop server)))
    (testing "daemon off"
      (let [server (run-jetty hello-world {:port 4347 :join? false :daemon? false})]
        (is (not (.. server getThreadPool isDaemon)))
        (.stop server))))

   (testing "setting min-threads"
    (let [server (run-jetty hello-world {:port 4347
                                         :min-threads 3
                                         :join? false})
          thread-pool (. server getThreadPool)]
      (is (= 3 (. thread-pool getMinThreads)))
      (.stop server)))

  (testing "default min-threads"
    (let [server (run-jetty hello-world {:port 4347
                                         :join? false})
          thread-pool (. server getThreadPool)]
      (is (= 8 (. thread-pool getMinThreads)))
      (.stop server)))

  (testing "default character encoding"
    (with-server (content-type-handler "text/plain") {:port 4347}
      (let [response (async/<!! (http/get "http://localhost:4347"))]
        (is (.contains
             (get-in response [:headers "content-type"])
             "text/plain")))))

  (testing "custom content-type"
    (with-server (content-type-handler "text/plain;charset=UTF-16;version=1") {:port 4347}
      (let [response (async/<!! (http/get "http://localhost:4347"))]
        (is (= (get-in response [:headers "content-type"])
               "text/plain;charset=UTF-16;version=1")))))

  (testing "request translation"
    (with-server echo-handler {:port 4347}
      (let [response (async/<!! (http/post "http://localhost:4347/foo/bar/baz?surname=jones&age=123" {:body "hello"}))]
        (is (= (:status response) 200))
        (is (= (-> response :body async/<!!) "hello"))
        (let [request-map (read-string (get-in response [:headers "request-map"]))]
          (is (= (:query-string request-map) "surname=jones&age=123"))
          (is (= (:uri request-map) "/foo/bar/baz"))
          (is (= (:content-length request-map) 5))
          (is (= (:character-encoding request-map) "UTF-8"))
          (is (= (:request-method request-map) :post))
          (is (= (:content-type request-map) "text/plain;charset=UTF-8"))
          (is (= (:remote-addr request-map) "127.0.0.1"))
          (is (= (:scheme request-map) :http))
          (is (= (:server-name request-map) "localhost"))
          (is (= (:server-port request-map) 4347))
          (is (= (:ssl-client-cert request-map) nil))))))

  (testing "WebSocket ping-pong"
    (let [p (promise)]
      (with-server nil
        {:port 4347
         :websockets {"/" (fn [{:keys [in out ctrl]}]
                            (async/go
                              (when (= "PING" (async/<! in))
                                (async/>! out "PONG"))))}}
        (ws/ws-client "ws://localhost:4347/"
                      (fn [{:keys [in out ctrl]}]
                        (async/go
                          (async/>! out "PING")
                          (when (= "PONG" (async/<! in))
                            (async/close! out)
                            (deliver p true)))))
        (is (deref p 1000 false))))))
