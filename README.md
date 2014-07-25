# jet
<!-- [![Build Status](https://secure.travis-ci.org/mpenet/jet.png?branch=master)](http://travis-ci.org/mpenet/jet) -->

<img src="http://i.imgur.com/gs2v6d8.gif" title="Hosted by imgur.com" align="right"/>

Jet is a server + clients library to interact/use jetty9 from clojure
using core.async channels.

Main goals are to be *lightweight*, *fast*, and *easy to use*.

## What's in the box? (@ptaoussanis™)

* **ring adapter** running on jetty9

* **Websocket Server** with a simple yet powerfull api based on core.async

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
[cc.qbits/jet "0.1.0-SNAPSHOT"]
```
## Example

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

* `out` will allow you to push content to this connected client

```clojure
(use 'qbits.jet.server)

(run some-ring-handler
  {:port 8013
   :websockets {"/foo/"
                (fn [{:keys [in out ctrl ws]
                      :as opts}]
                  (async/go
                    (loop []
                      (when-let [x (async/<! ctrl)]
                        (println :ctrl x ctrl)
                        (recur))))
                  (async/go
                    (loop []
                      (when-let [x (async/<! in)]
                        (println :recv x in)
                        (recur))))

                  (future (dotimes [i 3]
                            (async/>!! out (str "send " i))
                            (Thread/sleep 1000)))

                  ;; (close! ws)
                  )}})
```



The websocket client is used the same way

```clojure
(use 'qbits.jet.client.websocket)
(ws-client "ws://localhost:8013/foo/"
           (fn [{:keys [in out ctrl ws]}]
             (async/go
               (loop []
                 (when-let [x (async/<! ctrl)]
                   (println :client-ctrl x ctrl)
                   (recur))))

             (async/go
               (loop []
                 (when-let [x (async/<! in)]
                   (println :client-recv x in)
                   (recur))))

             (future (dotimes [i 3]
                       (async/>!! out (str "client send " (str "client-" i)))
                       (Thread/sleep 1000))))))
```

If you close the :out channel, the socket will be closed, this is true
for both client/server modes.

Please check the
[Changelog](https://github.com/mpenet/jet/blob/master/CHANGELOG.md)
if you are upgrading.

## License

Copyright © 2014 [Max Penet](http://twitter.com/mpenet)

Distributed under the
[Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html),
the same as Clojure.
