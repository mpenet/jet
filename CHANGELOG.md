# Changelog

## 0.3.0-beta1

* Improved http client:

** add post/get/put/delete/trace/head sugar

** support for post params

** :as auto decoding of content to clojure ds

** tests now run on jet http client (removed clj-http dependency)

## 0.2.0

* Allow to pass factory functions for core.async channels used on
  WebSocket objects, see run-jetty and ws-client docstrings.

* Add options on HTTP and WebSocket clients

* Removed ::connect event from ctrl channel, it was a bit useless
  given that the ctrl channel gets created on connect, so its
  existence is basically equivalent to the event.

* Use async/go & >! for feeding data to channels instead of put!,
  the later could result in dropped values even with fixed size
  buffers. Additionaly this should allow for better flow control.
