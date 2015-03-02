(ns qbits.jet.servlet
  "Compatibility functions for turning a ring handler into a Java servlet."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.core.async :as async])
  (:import
   (java.io
    File
    InputStream
    FileInputStream
    OutputStreamWriter)
   (javax.servlet
    AsyncContext
    AsyncListener)
   (javax.servlet.http
    HttpServlet
    HttpServletRequest
    HttpServletResponse)))

(defn chan?
  [x]
  (instance? clojure.core.async.impl.channels.ManyToManyChannel x))

(defn- get-headers
  "Creates a name/value map of all the request headers."
  [^HttpServletRequest request]
  (reduce
   (fn [headers ^String name]
     (assoc headers
       (.toLowerCase name)
       (->> (.getHeaders request name)
            (enumeration-seq)
            (string/join ","))))
   {}
   (enumeration-seq (.getHeaderNames request))))

(defn- get-content-length
  "Returns the content length, or nil if there is no content."
  [^HttpServletRequest request]
  (let [length (.getContentLength request)]
    (if (>= length 0) length)))

(defn- get-client-cert
  "Returns the SSL client certificate of the request, if one exists."
  [^HttpServletRequest request]
  (first (.getAttribute request "javax.servlet.request.X509Certificate")))

(defn build-request-map
  "Create the request map from the HttpServletRequest object."
  [^HttpServletRequest request]
  {:servlet-request    request
   :server-port        (.getServerPort request)
   :server-name        (.getServerName request)
   :remote-addr        (.getRemoteAddr request)
   :uri                (.getRequestURI request)
   :query-string       (.getQueryString request)
   :scheme             (keyword (.getScheme request))
   :request-method     (keyword (.toLowerCase (.getMethod request)))
   :headers            (get-headers request)
   :content-type       (.getContentType request)
   :content-length     (get-content-length request)
   :character-encoding (.getCharacterEncoding request)
   :ssl-client-cert    (get-client-cert request)
   :ctrl               (async/chan)
   :body               (.getInputStream request)})

(defn- set-status+headers!
  "Update a HttpServletResponse with a map of headers."
  [{:keys [servlet-response]}
   request-map
   status
   headers]
  (when status
      (.setStatus servlet-response status))
  (doseq [[key val-or-vals] headers]
    (if (string? val-or-vals)
      (.setHeader servlet-response key val-or-vals)
      (doseq [val val-or-vals]
        (.addHeader servlet-response key val))))
  ;; Some headers must be set through specific methods
  (when-let [content-type (get headers "Content-Type")]
    (.setContentType servlet-response content-type))

  (when-let [content-type (get headers "Content-Type")]
    (.setContentType servlet-response content-type)))

(defprotocol PBodyWritable
  (write-body! [body response-map request-map]))

(defn set-response-body!
  [response-map request-map body]
  (write-body! body response-map request-map))

(defn flush-buffer! [response-map]
  (-> response-map :servlet-response .flushBuffer))

(defn- response->output-stream-writer
  ^OutputStreamWriter
  [response]
  (-> response :servlet-response .getOutputStream OutputStreamWriter.))

(defprotocol OutputStreamWritable
  (-write-stream! [x stream-writer]))

(extend-protocol OutputStreamWritable
  String
  (-write-stream! [s ^OutputStreamWriter sw]
    (.write sw s)
    (.flush sw))

  Number
  (-write-stream! [n sw]
    (-write-stream! (str n) sw)))

(defn write-stream!
  [stream x request-map]
  ;; where is flip when you need it!
  (try (-write-stream! x stream)
       (catch Exception e
         (let [x (ex-info "Couldnt' write to stream " {:exception e})]
           (async/put! (:ctrl request-map) [::error x])
           (throw x)))))

(extend-protocol PBodyWritable
  String
  (write-body! [s response-map request-map]
    (let [w (response->output-stream-writer response-map)]
      (write-stream! w s request-map)))

  clojure.lang.ISeq
  (write-body! [coll response-map request-map]
    (let [w (response->output-stream-writer response-map)]
      (doseq [chunk coll]
        (write-stream! w chunk request-map))))

  clojure.lang.Fn
  (write-body! [f response-map request-map]
    (f response-map))

  InputStream
  (write-body! [stream response-map request-map]
    (with-open [^InputStream b stream]
      (io/copy b (.getOutputStream (:servlet-response response-map)))))

  File
  (write-body! [file response-map request-map]
    (with-open [stream (FileInputStream. file)]
      (write-body! stream response-map request-map)))

  clojure.core.async.impl.channels.ManyToManyChannel
  (write-body! [ch response-map request-map]
    (let [w (response->output-stream-writer response-map)
          servlet-response (:servlet-response response-map)]
      (async/go
        (loop [state ::connected]
          (let [x (async/<! ch)]
            (if (and x (= state ::connected))
              (recur
               (try
                 (write-stream! w x request-map)
                 state
                 (catch Exception e
                   ::disconnected)))
              (when (= ::connected state)
                (flush-buffer! response-map))))))))

  nil
  (write-body! [body response]
    nil)

  Object
  (write-body! [body _]
    (throw (Exception. ^String (format "Unrecognized body: < %s > %s" (type body) body)))))

(defn async-listener
  [ch]
  (reify AsyncListener
    (onError [this e]
      (async/close! ch))
    (onTimeout [this e]
      (async/close! ch))
    (onComplete [this e]
      (async/close! ch))))

(defn ^AsyncContext async-context
  [{:as request-map
    :keys [servlet-request]}]
  (when-not (.isAsyncStarted servlet-request)
    (.startAsync servlet-request))
  (.getAsyncContext servlet-request))

(defn set-body!
  [response-map
   request-map
   body]
  (if (chan? body)
    (let [ctx (doto (async-context request-map)
                (.setTimeout 0))]
      (.addListener ctx (async-listener body))
      (async/take! (set-response-body! response-map request-map body)
                   (fn [_] (.complete ctx))))
    (do
      (set-response-body! response-map request-map body)
      (flush-buffer! response-map))))

(defn throw-invalid-response!
  [x]
  (throw (ex-info "Invalid response given." {:response x})))

(defprotocol PResponse
  (-update-response [x response-map]))

(defn add-servlet-keys
  [response-map request-map]
  (assoc response-map :servlet-response
         (-> request-map :servlet-request .getServletResponse)))

(defn update-response
  [x request-map]
  (-update-response x request-map))

(extend-protocol PResponse
  clojure.core.async.impl.channels.ManyToManyChannel
  (-update-response [response-ch
                     request-map]
    (let [ctx (async-context request-map)]
      (async/take! response-ch
                   #(do
                      (try
                        (-update-response % request-map)
                        (catch Exception e
                          (-> request-map :ctrl (async/put! [::error e]))))
                      (when-not (chan? (:body %))
                        (.complete ctx))))))

  clojure.lang.IPersistentMap
  (-update-response [response-map request-map]
    (let [{:keys [status headers body]
           :as response-map}
          (add-servlet-keys response-map request-map)]
      (set-status+headers! response-map request-map status headers)
      (set-body! response-map request-map body)))

  Object
  (-update-response [x _ _ _]
    (throw-invalid-response! x))

  nil
  (-update-response [x _ _ _]
    (throw-invalid-response! x)))
