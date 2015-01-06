(ns qbits.jet.async
  (:require
   [clojure.core.async :as async])
  (:import (org.eclipse.jetty.util Callback)))

(defn put!
  "Takes a `ch`, a `msg`, a fn that enables backpressure, one that disables it,
  and a no-arg function which, when invoked, closes the upstream source."
  ([ch msg suspend! resume! close!]
   (let [status (atom ::sending)]
     (async/put! ch msg
                 (fn [result]
                   (if-not result
                     (when close! (close!))
                     (when (compare-and-set! status ::paused  ::sent)
                       (resume!)))))
     (when (compare-and-set! status ::sending ::paused)
       (suspend!))
     nil))
  ([ch msg suspend! resume!]
   (put! ch msg suspend! resume! nil)))

(defn callback
  [deferred-ch]
  (reify Callback
    (succeeded [this]
      (async/put! deferred-ch ::success))
    (failed [this ex]
      (async/put! deferred-ch ::failure))))

(defmacro in-deferred
  [sym & body]
  `(let [~sym (async/chan)]
     ~@body
     ~sym))
