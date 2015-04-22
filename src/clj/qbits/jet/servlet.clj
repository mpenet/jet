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
   (org.eclipse.jetty.server Request Response)
   (javax.servlet.http
    HttpServlet
    HttpServletResponse)))

(defn chan?
  [x]
  (instance? clojure.core.async.impl.channels.ManyToManyChannel x))

(defn- get-headers
  "Creates a name/value map of all the request headers."
  [^Request request]
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
  [^Request request]
  (let [length (.getContentLength request)]
    (if (>= length 0) length)))

(defn- get-client-cert
  "Returns the SSL client certificate of the request, if one exists."
  [^Request request]
  (first (.getAttribute request "javax.servlet.request.X509Certificate")))

(defn build-request-map
  "Create the request map from the HttpServletRequest object."
  [^Request request]
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
  [^HttpServletResponse servlet-response
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
  (write-body! [body servlet-response request-map]))

(defn set-response-body!
  [servlet-response request-map body]
  (write-body! body servlet-response request-map))

(defn flush-buffer!
  [^Response servlet-response]
  (.flushBuffer servlet-response))

(defn- response->output-stream-writer
  ^OutputStreamWriter
  [^Response servlet-response]
  (-> servlet-response .getOutputStream OutputStreamWriter.))

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
  (try
    (-write-stream! x stream)
    (catch Exception e
      (let [x (ex-info "Couldnt' write to stream " {:exception e})]
        (async/put! (:ctrl request-map) [::error x])
        (throw x)))))

(extend-protocol PBodyWritable
  String
  (write-body! [s servlet-response request-map]
    (let [w (response->output-stream-writer servlet-response)]
      (write-stream! w s request-map)))

  clojure.lang.ISeq
  (write-body! [coll servlet-response request-map]
    (let [w (response->output-stream-writer servlet-response)]
      (doseq [chunk coll]
        (write-stream! w chunk request-map))))

  clojure.lang.Fn
  (write-body! [f servlet-response request-map]
    (f servlet-response))

  InputStream
  (write-body! [stream ^Response servlet-response request-map]
    (with-open [^InputStream b stream]
      (io/copy b (.getOutputStream servlet-response))))

  File
  (write-body! [file servlet-response request-map]
    (with-open [stream (FileInputStream. file)]
      (write-body! stream servlet-response request-map)))

  clojure.core.async.impl.channels.ManyToManyChannel
  (write-body! [ch servlet-response request-map]
    (let [w (response->output-stream-writer servlet-response)]
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
                (flush-buffer! servlet-response))))))))

  nil
  (write-body! [body servlet-response request-map])

  Object
  (write-body! [body _]
    (throw (Exception. ^String (format "Unrecognized body: < %s > %s" (type body) body)))))

(defn ctrl-listener
  [ctrl]
  (reify AsyncListener
    (onError [this e]
      (async/put! ctrl [:error e]))
    (onTimeout [this e]
      (async/put! ctrl [:timeout e]))
    (onComplete [this e]
      (async/close! ctrl))))

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
    :keys [^Request servlet-request
           ctrl]}
   ch]
  (when-not (.isAsyncStarted servlet-request)
    (doto (.startAsync servlet-request)
      ;; Expect timing out to be handled by application code
      (.setTimeout 0)
      (.addListener (ctrl-listener ctrl))))
  (doto (.getAsyncContext servlet-request)
    (.addListener (async-listener ch))))

(defn set-body!
  [servlet-response
   request-map
   body]
  (if (chan? body)
    (let [ctx (async-context request-map body)]
      (async/take! (set-response-body! servlet-response request-map body)
                   (fn [_] (.complete ctx))))
    (do
      (set-response-body! servlet-response request-map body)
      (flush-buffer! servlet-response))))

(defn throw-invalid-response!
  [x]
  (throw (ex-info "Invalid response given." {:response x})))

(defprotocol PResponse
  (-update-response [x servlet-response]))

(defn update-response
  [x request-map]
  (-update-response x request-map))

(extend-protocol PResponse
  clojure.core.async.impl.channels.ManyToManyChannel
  (-update-response [response-ch
                     request-map]
    (let [ctx (async-context request-map response-ch)]
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
    (let [{:keys [status headers body]} response-map
          ^Request servlet-request (:servlet-request request-map)
          servlet-response  (.getServletResponse servlet-request)]
      (set-status+headers! servlet-response request-map status headers)
      (set-body! servlet-response request-map body)))

  Object
  (-update-response [x _ _ _]
    (throw-invalid-response! x))

  nil
  (-update-response [x _ _ _]
    (throw-invalid-response! x)))
