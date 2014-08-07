(ns qbits.jet.client.cookies
  (:require [clojure.string :as str])
  (:import
   (java.net URI)
   (java.net HttpCookie)
   (org.eclipse.jetty.util HttpCookieStore)))

(defn ^HttpCookieStore cookie-store
  "Initializes an empty HttpCookieStore"
  []
  (HttpCookieStore.))

(defn decode-cookie
  [^HttpCookie cookie]
  (->> {:name (.getName cookie)
        :comment (.getComment cookie)
        :comment-url (.getCommentURL cookie)
        :discard (not (.getDiscard cookie))
        :domain (.getDomain cookie)
        :max-age (.getMaxAge cookie)
        :path (.getPath cookie)
        :ports (seq (.getPortlist cookie))
        :secure (.getSecure cookie)
        :value (.getValue cookie)
        :version (.getVersion cookie)}
       (reduce-kv (fn [m k v]
                    (if (nil? v)
                      m
                      (assoc m k v)))
                  {})))

(defn ^HttpCookie encode-cookie
  [{:keys [name domain value path max-age
           comment comment-url version
           secure? http-only? discard?
           ports]
    :or {version 0
         http-only? false
         discard? true
         secure? false}}]
  (let [cookie (HttpCookie. name value)]
    (.setDomain cookie domain)
    (.setComment cookie comment)
    (.setCommentURL cookie comment-url)
    (.setMaxAge cookie max-age)
    (.setPath cookie path)
    (.setHttpOnly cookie http-only?)
    (.setDiscard cookie discard?)
    (.setVersion cookie version)
    (.setSecure cookie secure?)
    (when ports
      (.setPortlist cookie (str/join "," ports)))

    cookie))

(defn get-cookies
  "Returns a sequence of all cookies form the store, or per URI"
  ([^HttpCookieStore cookie-store uri]
     (map decode-cookie (.get cookie-store (URI. uri))))
  ([^HttpCookieStore cookie-store]
     (map decode-cookie (.getCookies cookie-store))))

(defn add-cookies!
  "Adds cookies for a destination URI to the cookie store"
  [^HttpCookieStore cookie-store uri cookies]
  (let [uri (URI. uri)]
    (prn cookies)
    (doseq [cookie cookies]
      (.add cookie-store uri (encode-cookie cookie)))
    cookie-store))

(defn add-cookie!
  "Adds a single cookie for a destination URI to the cookie store"
  [^HttpCookieStore cookie-store uri cookie]
  (add-cookies! cookie-store uri [cookie]))

;; (-> (cookie-store)
;;     (add-cookie! "http://foo.com"
;;                  {:name "foobar" :domain "foo" :value "111" :max-age 10000 :path "/"
;;                   :comment "asdf" }))
