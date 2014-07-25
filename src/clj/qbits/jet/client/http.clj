(ns qbits.jet.client.http
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string])
  (:import
   (org.eclipse.jetty.client
    HttpClient
    HttpRequest)
   (org.eclipse.jetty.http
    HttpFields
    HttpField)
   (org.eclipse.jetty.client.api
    Request$FailureListener
    Response$CompleteListener
    Response$ContentListener
    Request
    Response
    Result)
   (java.nio ByteBuffer)))

(defn byte-buffer->string
  [^ByteBuffer bb]
  (String. (.array bb) "UTF-8"))

(defrecord JetResponse [status headers body])

(defn result->response
  [^Result result content-ch]
  (let [response (.getResponse result)]
    (JetResponse. (.getStatus response)
                  (reduce (fn [m ^HttpField h]
                            (assoc m (.getName h) (.getValue h)))
                          {}
                          ^HttpFields (.getHeaders response))
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
        request ^Request (.newRequest client ^String url)]

    (.start client)
    (.method request (name method))

    (when body
      (.content request (encode-body body)))

    (doseq [[k v] headers]
      (.header request (name k) (str v)))

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
             (async/put! ch (if (.isSucceeded ^Result result)
                              (result->response result content-ch)
                              (.getRequestFailure result)))))))
    ch))


(comment
  (prn (-> (request {:url "http://google.com/1" :method :get})
          async/<!!
          ;; :body
          ;; async/<!!
          ;; byte-buffer->string
          )))
