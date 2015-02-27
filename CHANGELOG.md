# Changelog

## 0.5.5

* HttpParser Error Buffer Bleed Vulnerability fix see https://github.com/eclipse/jetty.project/blob/cfca172dd68846b2c7f30a9a6b855f08da7e7946/advisories/2015-02-24-httpparser-error-buffer-bleed.md for details

## 0.5.4

* HTTP client can now accept a core.async/chan as :body value that will be handled as a  [DeferredContentProvider](http://download.eclipse.org/jetty/9.2.6.v20141205/apidocs/org/eclipse/jetty/client/util/DeferredContentProvider.html)

## 0.5.3

* HTTP client: When passing `:fold-chunked-response? true` the body
channel will contain a single value that will only be decoded after
accumulation of all the chunks. This makes working with `:as :json` on
a server which returns chunked response easier (ex; ElasticSearch).

Backward compatible change

## 0.5.2

* use jetty-* 9.2.6.v20141205 [changelog](https://github.com/eclipse/jetty.project/blob/master/VERSION.txt)

## 0.5.1

* better error reporting in http client
* kill reflection

## 0.5.0

* use jetty-* 9.2.5.v20141112 [changelog](https://github.com/eclipse/jetty.project/blob/master/VERSION.txt)

## 0.5.0-beta4

* use jetty-* 9.2.4.v20141103 [changelog](https://github.com/eclipse/jetty.project/blob/master/VERSION.txt)

## 0.5.0-beta3

* improve perf/resource use when using channel as response :body
  (chunked transfers)

## 0.5.0-beta2

* HTTP server chunked transfers: close body channel when user disconnects

## 0.5.0-beta1

* increase default buffer-size for response/request to 4M (same as http-kit).

## 0.5.0 *Major bug in HTTP client* Please update!

* HTTP client api overhaul, all http calls now take a mandatory client
  argument, following the "browser model" behind jetty9 client API.
  This also fixes a file descriptor leak from  0.4.0*.

* HTTP client now puts response headers as soon as headers come in.

## 0.4.2

* HTTP client would leak jetty Clients after each requests.  Note:
users who used a shared Client instance via request :client option
must make sure then .stop it once the're done.

## 0.4.1

* ring handler comes last, makes 404/ANY handling easier

## 0.4.0 ** Breaking change **

* websocket is now managed via a single handler, like a normal
  ring-handler. It now receives a RING compliant request map in
  addition of the 3 channels and the ws instance. That should make it
  compatible with RING compatible routing libraries and a large number
  of middlewares.

  This forced me to change run-jetty a bit to take a single map
  argument that contains all the usuall options, but also
  `:ring-handler` and `:websocket-handler`.

```clojure
(run-jetty {:ring-handler (fn [request] {:status 201 :body "foo"})
            :wesocket-handler (fn [{:keys [in out ctrl params headers]}] ...)
            :port 8080})
```

## 0.3.1

* Use jetty-* 9.2.3.v20140905 [changelog](https://github.com/eclipse/jetty.project/blob/master/VERSION.txt)

## 0.3.0

* Jetty9 will go in async mode when the response is a core.async
  channel. When the response map is fed to the channel the asyn
  context closes and the response is completed. This is compatible
  with chunked body, meaning you can have an async response (a
  channel), that could include a chunked body (:body is a channel as
  well in this case, and the async context is shared, and closed when
  the body streaming ends).

## 0.3.0-beta5

* add chunked response via servlet 3.1 async + core async over regular ring handlers.
When you return a core.async/chan as body the response will be chunked
and stream to the client. Calling clojure.core.async/close! on the
channel will complete the connection. In case of error/timeouts
disconnects the channel will close.

```clojure
(require '[clojure.core.async :as async])

(defn handler
  [request]
  (let [ch (async/chan 1)]
    (async/go
     (dotimes [i 5]
       (async/<! (async/timeout 300))
       (async/>! ch (str i "\n")))
     (async/close! ch))
    {:body ch
    :headers {"Content-Type" "prout"}
    :status 400}))

(qbits.jet.server/run-jetty handler {:port ...})
```

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
