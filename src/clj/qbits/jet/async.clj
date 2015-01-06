(ns qbits.jet.async
  (:require
   [clojure.core.async :as async])
  (:import (org.eclipse.jetty.util Callback)))

(defn put!
  "Takes a `ch`, a `msg`, a fn that enables backpressure, one that
  disables it, a success callback and a no-arg function which, when
  invoked, closes the upstream source."
  ([ch msg suspend! resume! success! close!]
   (let [status (atom ::sending)]
     (async/put! ch msg
                 (fn [result]
                   (if-not result
                     (when close! (close!))
                     (do
                       (when (identical? @status ::paused)
                         ;; if it was paused resume
                         (resume!))
                       (reset! status ::sent)
                       (when success! (success!))))))
     ;; it's still sending, means it's parked, so suspend source
     (when (compare-and-set! status ::sending ::paused)
       (suspend!))
     nil))
  ([ch msg suspend! resume! success!]
   (put! ch msg suspend! resume! success! nil))
  ([ch msg suspend! resume!]
   (put! ch msg suspend! resume! nil nil)))

(defn callback
  [deferred-ch]
  (reify Callback
    (succeeded [this]
      (async/put! deferred-ch ::success))
    (failed [this ex]
      (async/put! deferred-ch ::failure))))

(defmacro in-deferred
  [sym & body]
  `(let [~sym (async/chan 1)]
     ~@body
     ~sym))
