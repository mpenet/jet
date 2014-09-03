# Changelog

## 0.3.0-beta5

* add chunked response via servlet 3.1 async + core async When you
return a core.async/chan as body the response will be chunked and
stream to the client. Calling clojure.core.async/close! on the channel
will complete the connection. In case of error/timeouts disconnects
the channel will close.

## 0.3.0-beta4

* add sugar over `:content-type` in http client:

```clojure
(qbits.jet.client.http/get "http://foo.com" {:content-type :application/json, ...})
(qbits.jet.client.http/get "http://foo.com" {:content-type [:application/json "UTF-8"], ...})
(qbits.jet.client.http/get "http://foo.com" {:content-type ["application/json" "UTF-8"], ...})
```

## 0.3.0-beta3

* add basic auth

* split client/request objects, allowing client reuse in requests

## 0.3.0-beta2

* Add cookie/cookie-store support

## 0.3.0-beta1

* `qbits.jet.websocket.client/ws-client` is now `qbits.jet.websocket.client/connect!`

* Improved http client:

    * add post/get/put/delete/trace/head sugar

    * support for post params

    * :as auto decoding of content to clojure ds

    * tests now run on jet http client (removed clj-http dependency)

## 0.2.0

* Allow to pass factory functions for core.async channels used on
  WebSocket objects, see run-jetty and client docstrings.

* Add options on HTTP and WebSocket clients

* Removed ::connect event from ctrl channel, it was a bit useless
  given that the ctrl channel gets created on connect, so its
  existence is basically equivalent to the event.

* Use async/go & >! for feeding data to channels instead of put!,
  the later could result in dropped values even with fixed size
  buffers. Additionaly this should allow for better flow control.
