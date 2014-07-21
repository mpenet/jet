(ns qbits.jet.client
  (:require [clojure.core.async :as async])
  (:import
   (org.eclipse.jetty.client
    HttpClient
    HttpRequest)
   (org.eclipse.jetty.client.api
    Response$CompleteListener
    Response$ContentListener
    Result)))

(defn result->response
  [result content-ch]
  (let [response (.getResponse result)]
    {:body (async/<!! content-ch)
     :headers (.getHeaders response)
     :status (.getStatus response)}))


(defprotocol PRequest
  (encode-body [x]))

(extend-protocol PRequest
  (Class/forName "[B")
  (encode-body [x] x)

  java.nio.file.Path

  java.io.InputStream
  (encode-body [x] x)

  String
  (encode-body [x]
    (.getBytes x))

  Object
  (encode-body [x]
    (throw (ex-info "Body content no supported by encoder"))))


(defprotocol PResponse
  (decode-body [x]))

(defn request
  [{:keys [url method scheme server-name server-port uri
           query-string form-parms
           headers body file]
    :or {method :get}
    :as r}]
  (let [ch (async/chan)
        content-ch (async/chan)
        client (HttpClient.)
        request (.newRequest client url)]

    (.start client)
    (.method request (name method))

    (when body
      (.content request (encode-body body)))

    (doseq [[k v] headers]
      (.header request (name k) v))

    (doseq [[k v] query-string]
      (.param request (name k) v))


    (-> request
        (.onResponseContent
         (reify Response$ContentListener
           (onContent [this response bytebuffer]
             (async/put! content-ch bytebuffer))))

        (.send
         (reify Response$CompleteListener
           (onComplete [this result]
             (async/put! ch (result->response result content-ch))))))
    ch))


;; (prn (async/<!! (request {:url "http://google.com" :method :get})))
