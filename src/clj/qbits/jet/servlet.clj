(ns qbits.jet.servlet
  "Compatibility functions for turning a ring handler into a Java servlet."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.core.async :as async]
   [qbits.jet.async :as a])
  (:import
   (java.io
    File
    InputStream
    FileInputStream
    OutputStreamWriter)
   (javax.servlet
    AsyncContext
    AsyncListener
    ServletOutputStream)
   (java.nio ByteBuffer)
   (java.nio.channels  Channels)
   (org.eclipse.jetty.server Response)
   (org.eclipse.jetty.util Callback)
   (javax.servlet WriteListener)
   (org.eclipse.jetty.server
    HttpOutput
    Response
    Request)
   (javax.servlet.http
    HttpServlet
    ;; Request
    HttpServletResponse
    )))

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
  "Create the request map from the Request object."
  [^Request request]
  {:server-port        (.getServerPort request)
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
   :body               (.getInputStream request)})

(defn merge-servlet-keys
  "Associate servlet-specific keys with the request map for use with legacy
  systems."
  [request-map
   ^HttpServlet servlet
   ^Request request
   ^Response response]
  (merge request-map
         {:servlet              servlet
          :servlet-request      request
          :servlet-response     response
          :servlet-context      (.getServletContext servlet)
          :servlet-context-path (.getContextPath request)}))

(defn- set-status!
  "Update a Response with a status code."
  [^Response response status]
  (.setStatus response status))

(defn- set-headers!
  "Update a Response with a map of headers."
  [^HttpServletResponse response headers]
  (doseq [[key val-or-vals] headers]
    (if (string? val-or-vals)
      (.setHeader response key val-or-vals)
      (doseq [val val-or-vals]
        (.addHeader response key val))))
  ;; Some headers must be set through specific methods
  (when-let [content-type (get headers "Content-Type")]
    (.setContentType response content-type)))

(defprotocol PBodyWritable
  (content [x])
  (write-body-async! [x response deferred])
  (write-body! [x response]))

(defn set-response-body!
  [response body]
  (write-body! body response))

(defn ^HttpOutput http-output
  [^Response response]
  (.getHttpOutput response))

(extend-protocol PBodyWritable
  String
  (content [s]
    (ByteBuffer/wrap (.getBytes s "UTF-8")))
  (write-body! [s ^Response response]
    (-> response http-output (.sendContent ^ByteBuffer (content s))))
  (write-body-async! [s ^Response response deferred]
    (.sendContent (http-output response)
                  ^ByteBuffer (content s)
                  ^Callback (a/callback deferred)))

  clojure.lang.ISeq
  (content [x]
    x)
  (write-body! [coll ^Response response]
    (let [sw (-> response .getOutputStream OutputStreamWriter.)]
      (doseq [chunk coll]
        (.write sw (str chunk))
        (.flush sw))))

  (write-body-async! [s ^Response response deferred]
    (.sendContent (http-output response)
                  ^ByteBuffer (content s)
                  ^Callback (a/callback deferred)))

  InputStream
  (content [is] is)
  (write-body! [is ^Response response]
    (-> response .getHttpOutput (.sendContent ^InputStream is)))
  (write-body-async! [is ^Response response deferred]
    (.sendContent (http-output response)
                  ^InputStream (content is)
                  ^Callback (a/callback deferred)))
  File
  (content [file]
    (FileInputStream. file))
  (write-body! [file ^Response response]
    (-> response .getHttpOutput (.sendContent ^InputStream (content file))))
  (write-body-async! [f ^Response response deferred]
    (.sendContent (http-output response)
                  ^InputStream (content f)
                  ^Callback (a/callback deferred)))

  ;; maybe we can do something here once core.async/promise-chan lands in master
  ;; clojure.core.async.impl.channels.ManyToManyChannel
  ;; (write-body! [ch ^Response response]
  ;;   (a/in-deferred out-ch (async/take! ch #(write-body-async! % response out-ch))))

  clojure.core.async.impl.channels.ManyToManyChannel
  (write-body! [ch ^Response response]
    (async/go
      (let [^ServletOutputStream output-stream (.getOutputStream response)
            ^HttpOutput output (http-output response)
            write-token-chan (async/chan)
            listener (reify WriteListener
                       (onError [this t]
                         (prn t))
                       (onWritePossible [this]
                         (async/put! write-token-chan ::token)))]
        (.setWriteListener output-stream listener)
        (loop [state ::connected]
          (let [x (async/<! ch)]
            (if (and x (= state ::connected))
              (recur
               (try
                 (when (not (.isReady ^ServletOutputStream output-stream))
                   (async/<! write-token-chan))
                 (.write output-stream (.getBytes (str x)))
                 (when (not (.isReady ^ServletOutputStream output-stream))
                   (async/<! write-token-chan))
                 (.flush ^ServletOutputStream output-stream)
                 state
                 (catch Exception e
                   (.printStackTrace e)
                   ::disconnected)))
              (when (= ::connected state)
                state)))))))

  nil
  (content [x]
    (ByteBuffer/allocate 0))
  (write-body! [body response]
    nil)
  (write-body-async! [x ^Response response deferred]
    (.sendContent (http-output response)
                  ^ByteBuffer (content x)
                  ^Callback (a/callback deferred)))

  Object
  (content [x]
    (write-body! x nil))
  (write-body! [body _]
    (throw (Exception. ^String (format "Unrecognized body: < %s > %s" (type body) body)))))

(defn ^AsyncContext async-context
  [^Request request]
  (when-not (.isAsyncStarted request)
    (.startAsync request))
  (.getAsyncContext request))

(defn set-body!
  [^Response response
   ^Request request
   body]
  (if (chan? body)
    (let [ctx (doto (async-context request)
                (.setTimeout 0))]
      (async/take! (set-response-body! response body)
                   (fn [_] (.complete ctx))))
    (do
      (set-response-body! response body)
      (.flushBuffer response))))

(defn set-headers+status!
  [^Response response headers status]
  (when status
    (set-status! response status))
  (set-headers! response headers))

(defn throw-invalid-response!
  [x]
  (throw (ex-info "Invalid response given." {:response x})))

(defprotocol PResponse
  (update-response [x request response]))

(extend-protocol PResponse
  clojure.core.async.impl.channels.ManyToManyChannel
  (update-response [response-ch
                    ^Request request
                    ^Response response]
    (let [ctx (async-context request)]
      (async/take! response-ch
                   #(do
                      (update-response % request response)
                      (when-not (chan? (:body %))
                        (.complete ctx))))))

  clojure.lang.IPersistentMap
  (update-response [{:keys [status headers body]}
                    ^Request request
                    ^Response response]
    (set-headers+status! response headers status)
    (set-body! response request body))

  Object
  (update-response [x _ _]
    (throw-invalid-response! x))

  nil
  (update-response [x _ _]
    (throw-invalid-response! x)))

(defn make-service-method
  "Turns a handler into a function that takes the same arguments and has the
  same return value as the service method in the HttpServlet class."
  [handler]
  (fn [^HttpServlet servlet
       ^Request request
       ^Response response]
    (let [request-map (-> request
                          (build-request-map)
                          (merge-servlet-keys servlet request response))
          response' (handler request-map)]
      (update-response response' request response))))

(defn servlet
  "Create a servlet from a Ring handler."
  [handler]
  (proxy [HttpServlet] []
    (service [request response]
      ((make-service-method handler)
       this request response))))

(defmacro defservice
  "Defines a service method with an optional prefix suitable for being used by
  genclass to compile a HttpServlet class.

  For example:

  (defservice my-handler)
    (defservice \"my-prefix-\" my-handler)"
  ([handler]
     `(defservice "-" ~handler))
  ([prefix handler]
     `(defn ~(symbol (str prefix "service"))
        [servlet# request# response#]
        ((make-service-method ~handler)
         servlet# request# response#))))
