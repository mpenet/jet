(ns qbits.jet.client
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string])

  (:import
   (org.eclipse.jetty.client
    HttpClient
    HttpRequest)
   (org.eclipse.jetty.client.api
    Response$CompleteListener
    Response$ContentListener
    Result)))

(defrecord JetResponse [status headers body])

(defn result->response
  [result content-ch]
  (let [response (.getResponse result)]
    (JetResponse. (.getStatus response)
                  (reduce (fn [m h] (assoc m (.getName h) (.getValue h)))
                          {}
                          (.getHeaders response))
                  content-ch)))

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
