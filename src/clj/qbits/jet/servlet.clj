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
   (javax.servlet AsyncContext)
   (javax.servlet.http
    HttpServlet
    HttpServletRequest
    HttpServletResponse)))

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
   ^HttpServletRequest request
   ^HttpServletResponse response]
  (merge request-map
         {:servlet              servlet
          :servlet-request      request
          :servlet-response     response
          :servlet-context      (.getServletContext servlet)
          :servlet-context-path (.getContextPath request)}))

(defn- set-status!
  "Update a HttpServletResponse with a status code."
  [^HttpServletResponse response status]
  (.setStatus response status))

(defn- set-headers!
  "Update a HttpServletResponse with a map of headers."
  [^HttpServletResponse response headers]
  (doseq [[key val-or-vals] headers]
    (if (string? val-or-vals)
      (.setHeader response key val-or-vals)
      (doseq [val val-or-vals]
        (.addHeader response key val))))
  ; Some headers must be set through specific methods
  (when-let [content-type (get headers "Content-Type")]
    (.setContentType response content-type)))

(defprotocol PBodyWritable
  (write-body! [body response]))

(defn set-response-body!
  [response body]
  (write-body! body response))

(extend-protocol PBodyWritable
  String
  (write-body! [body ^HttpServletResponse response]

    (with-open [writer (.getWriter response)]
      (.print writer body)))

  clojure.lang.ISeq
  (write-body! [body ^HttpServletResponse response]
    (with-open [writer (.getWriter response)]
      (doseq [chunk body]
        (.print writer (str chunk))
        (.flush writer))))

  InputStream
  (write-body! [body ^HttpServletResponse response]
    (with-open [^InputStream b body]
      (io/copy b (.getOutputStream response))))

  File
  (write-body! [body ^HttpServletResponse response]
      (let [^File f body]
        (with-open [stream (FileInputStream. f)]
          (write-body! stream response))))

  clojure.core.async.impl.channels.ManyToManyChannel
  (write-body! [ch ^HttpServletResponse response]
    (let [^OutputStreamWriter w (-> response .getOutputStream OutputStreamWriter.)]
      (async/go
        (loop []
          (if-let [x (async/<! ch)]
            (do
              (prn :x x)
              (.write w x)
                (.flush w)
                (.flushBuffer response)
                (recur)))))))

  nil
  (write-body! [body response]
    nil)

  Object
  (write-body! [body _]
    (throw (Exception. ^String (format "Unrecognized body: <%s> %s" (type body) body)))))

(defn update-servlet-response
  "Update the HttpServletResponse using a response map."
  [^HttpServletResponse response
   {:keys [status headers body] :as response-map}
   request]
  (when-not response
    (throw (Exception. "Null response given.")))
  (when status
    (set-status! response status))

  (set-headers! response headers)

  (if (instance? clojure.core.async.impl.protocols.Channel body)
    (do
      (let [ctx (doto (.startAsync request)
                  (.setTimeout 0))]
        (async/take! (set-response-body! response body )
                     (fn [_] (.complete ctx)))))
    (set-response-body! response body)))

(defn make-service-method
  "Turns a handler into a function that takes the same arguments and has the
  same return value as the service method in the HttpServlet class."
  [handler]
  (fn [^HttpServlet servlet
       ^HttpServletRequest request
       ^HttpServletResponse response]
    (let [request-map (-> request
                          (build-request-map)
                          (merge-servlet-keys servlet request response))]
      (if-let [response-map (handler request-map)]
        (update-servlet-response response response-map request)
        (throw (NullPointerException. "Handler returned nil"))))))

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
