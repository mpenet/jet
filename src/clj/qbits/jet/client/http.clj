(ns qbits.jet.client.http
  (:refer-clojure :exclude [get])
  (:require
   [clojure.core.async :as async]
   [qbits.jet.client.ssl :as ssl]
   [qbits.jet.client.auth :as auth]
   [qbits.jet.client.cookies :as cookies]
   [clojure.string :as string]
   [cheshire.core :as json]
   [clojure.xml :as xml])
  (:import
   (org.eclipse.jetty.client
    HttpClient
    HttpRequest)
   (org.eclipse.jetty.util Fields)
   (org.eclipse.jetty.client.util
    StringContentProvider
    BytesContentProvider
    ByteBufferContentProvider
    InputStreamContentProvider
    PathContentProvider
    FormContentProvider)
   (org.eclipse.jetty.http
    HttpFields
    HttpField)
   (org.eclipse.jetty.client.api
    Request$FailureListener
    Response$CompleteListener
    Response$ContentListener
    Request
    ;; Response
    Result)
   (java.util.concurrent TimeUnit)
   (java.nio ByteBuffer)
   (java.io ByteArrayInputStream)))

(defn ^:no-doc byte-buffer->bytes
  [^ByteBuffer bb]
  (let [ba (byte-array (.remaining bb))]
    (.get bb ba)
    ba))

(defn ^:no-doc byte-buffer->string
  [^ByteBuffer bb]
  (String. ^bytes (byte-buffer->bytes bb) "UTF-8"))

(defn ^:no-doc decode-body [bb as]
  (case as
    :bytes (byte-buffer->bytes bb)
    :input-stream (ByteArrayInputStream. (byte-buffer->bytes bb))
    :string (byte-buffer->string bb)
    :json (json/parse-string (byte-buffer->string bb) true)
    :json-str (json/parse-string (byte-buffer->string bb) false)
    :xml (xml/parse (ByteArrayInputStream. (byte-buffer->bytes bb)))
    bb))

(defrecord Response [status headers body])

(defn ^:no-doc result->response
  [^Result result content-ch]
  (let [response (.getResponse result)]
    (Response. (.getStatus response)
               (reduce (fn [m ^HttpField h]
                         (assoc m (string/lower-case  (.getName h)) (.getValue h)))
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

(defn ^:no-doc set-cookies!
  [client url cookies]
  (let [cs (cookies/add-cookies! (or (.getCookieStore client)
                                     (cookies/cookie-store))
                                 url cookies)]
    (.setCookieStore client cs)))

(defn ^:no-doc set-auth!
  [client url {:keys [type user password realm]}]
  (.addAuthentication
   (.getAuthenticationStore client)
   (case type
     :digest (auth/digest-auth url realm user password)
     :basic (auth/basic-auth url realm user password))))

(defn ^HttpClient client
  ([{:keys [url
            address-resolution-timeout
            connect-timeout
            follow-redirects?
            max-redirects
            idle-timeout
            stop-timeout
            max-connections-per-destination
            max-requests-queued-per-destination
            request-buffer-size
            response-buffer-size
            scheduler
            user-agent
            cookie-store
            cookies
            auth
            remove-idle-destinations?
            dispatch-io?
            tcp-no-delay?
            strict-event-ordering?
            ssl-context-factory]
     :or {remove-idle-destinations? true
          dispatch-io? true
          follow-redirects? true
          tcp-no-delay? true
          strict-event-ordering? false
          ssl-context-factory ssl/insecure-ssl-context-factory}
     :as r}]
     (let [client (HttpClient. ssl-context-factory)]

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

       (when stop-timeout
         (.setStopTimeout client (long stop-timeout)))

       (when cookie-store
         (.setCookieStore client cookie-store))

       (when cookies
         (set-cookies! client url cookies))

       (when auth
         (set-auth! client url auth))

       (.setRemoveIdleDestinations client remove-idle-destinations?)
       (.setDispatchIO client dispatch-io?)
       (.setFollowRedirects client follow-redirects?)
       (.setStrictEventOrdering client strict-event-ordering?)
       (.setTCPNoDelay client tcp-no-delay?)

       (.start client)
       client))
  ([] (client {})))

(defn request
  [{:keys [url method query-string form-params headers body
           accept
           as
           timeout
           cookies]
    :or {method :get
         as :string}
    :as request-map}]
  (let [ch (async/chan)
        content-ch (async/chan)
        ^HttpClient client (or (:client request-map) (client request-map))
        request ^Request (.newRequest client ^String url)]

    (when timeout
      (.timeout request (long timeout) TimeUnit/MILLISECONDS))

    (when accept
      (.accept request (into-array String [(name accept)])))

    (.method request (name method))

    (when (seq form-params)
      (.content request
                (FormContentProvider. (let [f (Fields.)]
                                        (doseq [[k v] form-params]
                                          (.add f (name k) (str v)))
                                        f))))

    (when body
      (.content request (encode-body body)))

    (doseq [[k v] headers]
      (.header request (name k) (str v)))

    (doseq [[k v] query-string]
      (.param request (name k) v))

    (when cookies
      (set-cookies! client url cookies))

    (-> request
        (.onResponseContent
         (reify Response$ContentListener
           (onContent [this response bytebuffer]
             (async/put! content-ch (decode-body bytebuffer as)))))

        (.send
         (reify Response$CompleteListener
           (onComplete [this result]
             (async/put! ch
                         (if (.isSucceeded ^Result result)
                           (result->response result content-ch)
                           {:error result}))))))
    ch))


(defn get
  ([url request-map]
     (request (into {:method :get :url url}
                    request-map)))
  ([url]
     (get url {})))

(defn post
  ([url request-map]
     (request (into {:method :post :url url}
                    request-map)))
  ([url]
     (post url {})))

(defn put
  ([url request-map]
     (request (into {:method :put :url url}
                    request-map)))
  ([url]
     (put url {})))

(defn delete
  ([url request-map]
     (request (into {:method :delete :url url}
                    request-map)))
  ([url]
     (delete url {})))

(defn head
  ([url request-map]
     (request (into {:method :head :url url}
                    request-map)))
  ([url]
     (head url {})))

(defn trace
  ([url request-map]
     (request (into {:method :trace :url url}
                    request-map)))
  ([url]
     (trace url {})))

;; (def c (client {:url "http://graph.facebook.com/zuck"}))

;; (time (async/<!! (get "http://graph.facebook.com/zuck" {:client c})))

;; (clojure.pprint/pprint (-> (get "http://graph.facebook.com/zuck")
;;          async/<!!

;;          ))
