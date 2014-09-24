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
   (java.io ByteArrayInputStream)
   (clojure.lang Keyword Sequential)))

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
  (encode-body [x])
  (encode-content-type [x]))

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
  (encode-content-type [x]
    (str "Content-Type: " x))

  Sequential
  (encode-content-type [[content-type charset]]
    (str (encode-content-type content-type) "; charset=" (name charset)))

  Object
  (encode-body [x]
    (throw (ex-info "Body content no supported by encoder")))
  (encode-content-type [content-type]
    (encode-content-type (subs (str content-type) 1))))

(defn ^:no-doc add-cookies!
  [^HttpClient client url cookies]
  (cookies/add-cookies! (.getCookieStore client)
                        url cookies))

(defn ^:no-doc add-auth!
  [^HttpClient client url {:keys [type user password realm]}]
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

       (.setRemoveIdleDestinations client remove-idle-destinations?)
       (.setDispatchIO client dispatch-io?)
       (.setFollowRedirects client follow-redirects?)
       (.setStrictEventOrdering client strict-event-ordering?)
       (.setTCPNoDelay client tcp-no-delay?)
       (.start client)
       client))
  ([] (client {})))

(defn stop-client!
  [^HttpClient cl]
  (.stop cl))

(defn request
  [^HttpClient client
   {:keys [url method query-string form-params headers body
           content-type
           accept
           as
           timeout]
    :or {method :get
         as :string}
    :as request-map}]
  (let [ch (async/chan)
        content-ch (async/chan)
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

    (when content-type
      (.header request "Content-Type"
               (name content-type)))

    (doseq [[k v] query-string]
      (.param request (name k) v))

    (.onResponseContent request
                        (reify Response$ContentListener
                          (onContent [this response bytebuffer]
                            (async/put! content-ch (decode-body bytebuffer as)))))

    (.onComplete request
                 (reify Response$CompleteListener
                   (onComplete [this result]
                     (async/close! content-ch)
                     (async/put! ch
                                 (if (.isSucceeded ^Result result)
                                   (result->response result content-ch)
                                   {:error result}))
                     (async/close! ch))))

    (.send request)
    ch))

(defn get
  ([client url request-map]
     (request client
              (into {:method :get :url url}
                    request-map)))
  ([client url]
     (get client url {})))

(defn post
  ([client url request-map]
     (request client
              (into {:method :post :url url}
                    request-map)))
  ([client url]
     (post client url {})))

(defn put
  ([client url request-map]
     (request client
              (into {:method :put :url url}
                    request-map)))
  ([client url]
     (put client url {})))

(defn delete
  ([client url request-map]
     (request client
              (into {:method :delete :url url}
                    request-map)))
  ([client url]
     (delete client url {})))

(defn head
  ([client url request-map]
     (request client
              (into {:method :head :url url}
                    request-map)))
  ([client url]
     (head client url {})))

(defn trace
  ([client url request-map]
     (request client
              (into {:method :trace :url url}
                    request-map)))
  ([client url]
     (trace client url {})))
