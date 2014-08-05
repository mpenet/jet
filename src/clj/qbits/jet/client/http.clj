(ns qbits.jet.client.http
  (:require
   [clojure.core.async :as async]
   [clojure.string :as string])
  (:import
   (org.eclipse.jetty.client
    HttpClient
    HttpRequest)
   (org.eclipse.jetty.client.util
    StringContentProvider
    BytesContentProvider
    ByteBufferContentProvider
    InputStreamContentProvider
    PathContentProvider)
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

(defn byte-buffer->bytes
  [^ByteBuffer bb]
  (let [ba (byte-array (.remaining bb))]
    (.get bb ba)
    ba))

(defn byte-buffer->string
  [^ByteBuffer bb]
  (String. (byte-buffer->bytes bb) "UTF-8"))

(defn decode-body [bb as]
  (case as
    :string (byte-buffer->string bb)
    bb))

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
  (encode-body [ba]
    (BytesContentProvider.
     (into-array (Class/forName "[B") [ba])))

  ByteBuffer
  (encode-body [bb]
    (ByteBufferContentProvider. (into-array ByteBuffer [bb])))

  java.nio.file.Path
  (encode-body [p]
    (PathContentProvider. p))

  java.io.InputStream
  (encode-body [s]
    (InputStreamContentProvider. s))

  String
  (encode-body [x]
    (StringContentProvider. x "UTF-8"))

  Object
  (encode-body [x]
    (throw (ex-info "Body content no supported by encoder"))))

(defn request
  [{:keys [url method scheme server-name server-port uri
           query-string form-parms
           headers body file
           address-resolution-timeout
           connect-timeout
           follow-redirects?
           max-redirects
           idle-timeout
           max-connections-per-destination
           max-requests-queued-per-destination
           request-buffer-size
           response-buffer-size
           scheduler
           user-agent
           as
           remove-idle-destinations?
           dispatch-io?
           tcp-no-delay?
           strict-event-ordering?]
    :or {method :get
         remove-idle-destinations? true
         dispatch-io? true
         follow-redirects? true
         tcp-no-delay? true
         strict-event-ordering? false}
    :as r}]
  (let [ch (async/chan)
        content-ch (async/chan)
        client (HttpClient.)
        request ^Request (.newRequest client ^String url)]

    (when address-resolution-timeout
      (.setAddressResolutionTimeout client (long address-resolution-timeout)))

    (when connect-timeout
      (.setConnectTimeout client (long connect-timeout)))

    (when max-redirects
      (.setMaxRedirects client (int max-redirects)))

    (when idle-timeout
      (.setIdleTimeout client (long idle-timeout)))

    (when max-connections-per-destination
      (.setMaxConnectionsPerDestination client (int max-connections-per-destination)))

    (when max-requests-queued-per-destination
      (.setMaxRequestsQueuedPerDestination client (int max-requests-queued-per-destination)))

    (when request-buffer-size
      (.setRequestBufferSize client (int request-buffer-size)))

    (when response-buffer-size
      (.setResponseBufferSize client (int response-buffer-size)))

    (when scheduler
      (.setScheduler client scheduler))

    (when user-agent
      (.setUserAgentField client (HttpField. "User-Agent" ^String user-agent)))

    (.setRemoveIdleDestinations client remove-idle-destinations?)
    (.setDispatchIO client dispatch-io?)
    (.setFollowRedirects client follow-redirects?)
    (.setStrictEventOrdering client strict-event-ordering?)
    (.setTCPNoDelay client tcp-no-delay?)

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
             (async/put! content-ch (decode-body bytebuffer as)))))

        (.send
         (reify Response$CompleteListener
           (onComplete [this result]
             (async/put! ch (if (.isSucceeded ^Result result)
                              (result->response result content-ch)
                              (.getRequestFailure result)))))))
    ch))


;; (prn (-> (request {:url "http://localhost:8000/"
;;                    :method :get
;;                    :body (java.nio.file.Path. (java.io.File. "/home/mpenet/.bash_history"))
;;                    :as :string})
;;          async/<!!
;;          :body
;;          async/<!!))
