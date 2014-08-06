# jet
[![Build Status](https://secure.travis-ci.org/mpenet/jet.png?branch=master)](http://travis-ci.org/mpenet/jet)

<img src="http://i.imgur.com/gs2v6d8.gif" title="Hosted by imgur.com" align="right"/>

Jet is a server + clients library to interact/use jetty9 from clojure
using core.async channels.

Main goals are to be *lightweight*, *fast*, and *easy to use*.

## What's in the box?

* **ring adapter** running on jetty9

* **Websocket Server** with a simple yet powerful api based on core.async

* **WebSocket Client** sharing the same principles/api than the WebSocket
  server handlers

* **Asynchronous HTTP Client** with streaming support (yet to come,
  it's incomplete still)

The server part started from the code of the various
`ring-jetty9-adapters` out there.

In the current state the server is fairly complete/stable, the
websocket client nearly 100%, the HTTP client still at early stages.

What it will be able to do once complete:
SPDY support, tons of options, ...

The API is still subject to changes.

## Documentation

[codox generated documentation](http://mpenet.github.io/jet/).

## Installation

jet is [available on Clojars](https://clojars.org/cc.qbits/jet).

Add this to your dependencies:

```clojure
[cc.qbits/jet "0.3.0-beta1"]
```
## Examples

### WebSocket

Here we have the equivalent of a call to run-jetty, with the first
param as your main app ring handler (coming from whatever routing lib
you might use).

In the options the `:websockets` value takes a map of path to
handlers.

The websocket handlers receive a map that hold 3 core.async channels
and the underlying WebSocketAdapter instance for potential advanced uses.

* `ctrl` will receive status messages such as `[::connect this]`
`[::error e]` `[::close reason]`

* `in` will receive content sent by this connected client

* `out` will allow you to push content to this connected client and
  close the socket


An example with a little PING/PONG between client and server:

```clojure
(use 'qbits.jet.server)

;; Simple ping/pong server, will wait for PING, reply PONG and close connection
(run-jetty some-ring-handler
  {:port 8013
   :websockets {"/"
                (fn [{:keys [in out ctrl ws]
                      :as opts}]
                    (async/go
                      (when (= "PING" (async/<! in))
                        (async/>! out "PONG")
                        (async/close! out))))}})
```

The websocket client is used the same way

```clojure
(use 'qbits.jet.client.websocket)

;; Simple PING client to our server, sends PING, waits for PONG and
;; closes the connection
(ws-client "ws://localhost:8013/"
           (fn [{:keys [in out ctrl ws]}]
              (async/go
                (async/>! out "PING")
                (when (= "PONG" (async/<! in))
                  (async/close! out))))))
```

If you close the :out channel, the socket will be closed, this is true
for both client/server modes.


### HTTP Client

The API is nearly identical to clj-http and other clients for
clojure. One of the major difference is that calls to the client
return a channel that will receive the eventual response
asynchronously.  The response is then a fairly standard ring response
map, except the body, which is also a core.async channel (potential
streaming/chunked response support in the future).

Please check the
[Changelog](https://github.com/mpenet/jet/blob/master/CHANGELOG.md)
if you are upgrading.

## License

Copyright © 2014 [Max Penet](http://twitter.com/mpenet)

Distributed under the
[Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html),
the same as Clojure.
