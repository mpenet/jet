(ns qbits.jet.test.server
  (:use
   clojure.test)
  (:require
   [qbits.jet.server :refer [run-jetty]]
   [qbits.jet.client.websocket :as ws]
   [qbits.jet.client.http :as http]
   [clojure.core.async :as async]
   [ring.middleware.params :as ring-params]
   [ring.middleware.keyword-params :as ring-kw-params])
  (:import
   (org.eclipse.jetty.util.thread QueuedThreadPool)
   (org.eclipse.jetty.server Server Request)
   (org.eclipse.jetty.server.handler AbstractHandler)))

(def port 4347)
(def base-url (str "http://localhost:" port))
(def client (http/client))

(def num-chunk 2048)
(def chunk-interval-ms 5)

(defn hello-world [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "Hello World"})

(defn content-type-handler [content-type]
  (constantly
   {:status  200
    :headers {"Content-Type" content-type}
    :body    ""}))

(defn echo-handler [request]
  {:status 200
   :headers {"request-map" (str (dissoc request :body))}
   :body (:body request)})

(defn async-handler [request]
  (let [ch (async/chan)]
    (async/go
      (async/<! (async/timeout 1000))
      (async/>! ch
                {:body "foo"
                 :headers {"Content-Type" "foo"}
                 :status 202}))
    ch))

(defn async-handler+chunked-body [request]
  (let [ch (async/chan)
        body-ch (async/chan)]
    (async/go
      (async/<! (async/timeout chunk-interval-ms))
      (async/>! ch
                {:body body-ch
                 :headers {"Content-Type" "foo"}
                 :status 202})
      (dotimes [i num-chunk]
        (async/<! (async/timeout chunk-interval-ms))
        (async/>! body-ch (str i)))
      (async/close! body-ch))
    ch))

(defn chunked-handler [request]
  (let [ch (async/chan 1)]
    (async/go
      (dotimes [i num-chunk]
        (async/<! (async/timeout chunk-interval-ms))
        (async/>! ch (str i)))
      (async/close! ch))
    {:body ch
     :headers {"Content-Type" "foo"}
     :status 201}))

(defn request-map->edn
  [response]
  (-> response (get-in [:headers "request-map"]) read-string))

(defmacro with-server [options & body]
  `(let [server# (run-jetty (-> (assoc ~options :join? false)
                                (assoc :ring-handler
                                  (some-> (get ~options :ring-handler)
                                          ring-kw-params/wrap-keyword-params
                                          ring-params/wrap-params))))]
     (try
       ~@body
       (finally (.stop server#)))))

(deftest test-run-jetty
  (testing "HTTP server"
    (with-server {:port port :ring-handler hello-world}
      (let [response (async/<!! (http/get client base-url))]
        (is (= (:status response) 200))
        (is (.startsWith (get-in response [:headers "content-type"])
                         "text/plain"))
        (is (= (async/<!! (:body response)) "Hello World")))))

  (testing "HTTPS server"
    (with-server {:ring-handler hello-world
                  :port port
                  :ssl-port 4348
                  :keystore "test/keystore.jks"
                  :key-password "password"}
      (let [response (async/<!! (http/get client "https://localhost:4348" {:insecure? true}))]
        (is (= (:status response) 200))
        (is (= (-> response :body async/<!!) "Hello World")))))

  (testing "setting daemon threads"
    (testing "default (daemon off)"
      (let [server (run-jetty {:port port
                               :ring-handler hello-world
                               :join? false})]
        (is (not (.. server getThreadPool isDaemon)))
        (.stop server)))
    (testing "daemon on"
      (let [server (run-jetty {:ring-handler hello-world
                               :port port
                               :join? false
                               :daemon? true})]
        (is (.. server getThreadPool isDaemon))
        (.stop server)))
    (testing "daemon off"
      (let [server (run-jetty {:ring-handler hello-world
                               :port port
                               :join? false
                               :daemon? false})]
        (is (not (.. server getThreadPool isDaemon)))
        (.stop server))))

  (testing "setting min-threads"
    (let [server (run-jetty {:ring-handler hello-world
                             :port port
                             :min-threads 3
                             :join? false})
          thread-pool (. server getThreadPool)]
      (is (= 3 (. thread-pool getMinThreads)))
      (.stop server)))

  (testing "default min-threads"
    (let [server (run-jetty {:ring-handler hello-world
                             :port port
                             :join? false})
          thread-pool (. server getThreadPool)]
      (is (= 8 (. thread-pool getMinThreads)))
      (.stop server)))

  (testing "default character encoding"
    (with-server {:ring-handler (content-type-handler "text/plain") :port port}
      (let [response (async/<!! (http/get client base-url))]
        (is (.contains
             (get-in response [:headers "content-type"])
             "text/plain")))))

  (testing "custom content-type"
    (with-server {:ring-handler (content-type-handler "text/plain;charset=UTF-16;version=1")
                  :port port}
      (let [response (async/<!! (http/get client base-url))]
        (is (= (get-in response [:headers "content-type"])
               "text/plain;charset=UTF-16;version=1")))))

  (testing "request translation"
    (with-server {:ring-handler echo-handler
                  :port port}
      (let [response (async/<!! (http/post client (str base-url "/foo/bar/baz?surname=jones&age=123") {:body "hello"}))]
        (is (= (:status response) 200))
        (is (= (-> response :body async/<!!) "hello"))
        (let [request-map (request-map->edn response)]
          (is (= (:query-string request-map) "surname=jones&age=123"))
          (is (= (:uri request-map) "/foo/bar/baz"))
          (is (= (:content-length request-map) 5))
          (is (= (:character-encoding request-map) "UTF-8"))
          (is (= (:request-method request-map) :post))
          (is (= (:content-type request-map) "text/plain;charset=UTF-8"))
          (is (= (:remote-addr request-map) "127.0.0.1"))
          (is (= (:scheme request-map) :http))
          (is (= (:server-name request-map) "localhost"))
          (is (= (:server-port request-map) port))
          (is (= (:ssl-client-cert request-map) nil))))))

  (testing "chunked response"
    (with-server {:ring-handler chunked-handler
                  :port port}
      (let [response (async/<!! (http/get client base-url))]
        (is (= (:status response) 201))
        (dotimes [i num-chunk]
          (is (= (-> response :body async/<!!) (str i))))
        (is (= (-> response :body async/<!!) nil)))))

  (testing "async response"
    (with-server {:ring-handler async-handler
                  :port port}
      (let [response (async/<!! (http/get client base-url))]
        (is (= (:status response) 202)))))

  (testing "async+chunked-body"
    (with-server {:ring-handler async-handler+chunked-body
                  :port port}
      (let [response (async/<!! (http/get client base-url))
            body (:body response)]
        (is (= (:status response) 202))
        (dotimes [i num-chunk]
          (is (= (str i) (async/<!! body))))
        (is (nil? (async/<!! body))))))

  (testing "POST+PUT requests"
    (with-server {:ring-handler echo-handler :port port}
      (let [response (async/<!! (http/post client
                                           base-url
                                           {:form-params {:foo "bar"}}))
            request-map (request-map->edn response)]
        ;; TODO post files
        (is (= "bar" (get-in request-map [:form-params "foo"]))))

      (let [response (async/<!! (http/put client base-url
                                          {:form-params {:foo "bar"}}))
            request-map (request-map->edn response)]
        (is (= "bar" (get-in request-map [:form-params "foo"]))))))

  (testing "HEAD+DELETE+TRACE requests"
    (with-server {:port port :ring-handler echo-handler}
      (let [response (async/<!! (http/head client base-url))
            request-map (request-map->edn response)]
        (is (= :head (:request-method request-map))))
      (let [response (async/<!! (http/delete client base-url))
            request-map (request-map->edn response)]
        (is (= :delete (:request-method request-map))))
      (let [response (async/<!! (http/trace client base-url))
            request-map (request-map->edn response)]
        (is (= :trace (:request-method request-map))))))


  (testing "HTTP request :as"
    (is (= "zuck" (-> (http/get client "http://graph.facebook.com/zuck" {:as :json})
                      async/<!! :body async/<!! :username)))

    (is (= "zuck" (-> (http/get client "http://graph.facebook.com/zuck" {:as :json-str})
                      async/<!! :body async/<!! (get "username")))))

  (testing "cookies"
    (with-server {:ring-handler echo-handler :port port}
      (http/add-cookies! client base-url
                         [{:name "foo" :value "bar" :max-age 5}
                          {:name "something" :value "else" :max-age 5}])
      (is (= "foo=bar; something=else"
             (get-in (-> (http/get client base-url)
                         async/<!!
                         request-map->edn)
                     [:headers "cookie"])))))

  (testing "Auth tests"
    (let [u "test-user"
          pwd "test-pwd"
          http-url (format "http://httpbin.org/basic-auth/%s/%s" u pwd)
          https-url (format "https://httpbin.org/basic-auth/%s/%s" u pwd)]
      (http/add-auth! client http-url
                      {:type :basic :user u :password pwd :realm "Fake Realm"})

      (http/add-auth! client https-url
                      {:type :basic :user u :password pwd :realm "Fake Realm"})
      (is (= 200 (-> (http/get client http-url)
                     async/<!! :status)))
      (is (= 200 (-> (http/get client https-url)
                     async/<!! :status)))))

  (testing "WebSocket ping-pong"
    (let [p (promise)]
      (with-server {:port port
                    :websocket-handler
                    (fn [{:keys [in out ctrl] :as request}]
                      (async/go
                        (when (= "PING" (async/<! in))
                          (async/>! out "PONG"))))}
        (ws/connect! (str "ws://0.0.0.0:" port "/app?foo=bar")
                     (fn [{:keys [in out ctrl]}]
                       (async/go
                         (async/>! out "PING")
                         (when (= "PONG" (async/<! in))
                           (async/close! out)
                           (deliver p true)))))
        (is (deref p 1000 false)))))

  (testing "content-type encoding"
    (is (= "Content-Type: application/json" (http/encode-content-type :application/json)))
    (is (= "Content-Type: application/json; charset=UTF-8" (http/encode-content-type [:application/json "UTF-8"])))
    (is (= "Content-Type: application/json" (http/encode-content-type "application/json")))
    (is (= "Content-Type: application/json; charset=UTF-8" (http/encode-content-type ["application/json" "UTF-8"])))))
