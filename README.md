# jet
<!-- [![Build Status](https://secure.travis-ci.org/mpenet/jet.png?branch=master)](http://travis-ci.org/mpenet/jet) -->

Jet is a server + client library to interact/use jetty9 from clojure
using core.async channels.
The server is both a ring-adapter and has support for websocket, and
the client has yet to see the light of day.

The server part started from the code of the various
ring-jetty9-adapters existing.

The API is still subject to changes.

<!-- ## Documentation -->

<!-- [codox generated documentation](http://mpenet.github.com/jet/#docs). -->

## Installation

jet is [available on Clojars](https://clojars.org/cc.qbits/jet).

Add this to your dependencies:

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

```clojure
[cc.qbits/jet "0.1.0-SNAPSHOT"]
```

Please check the
[Changelog](https://github.com/mpenet/jet/blob/master/CHANGELOG.md)
if you are upgrading.

## License

Copyright © 2014 [Max Penet](http://twitter.com/mpenet)

Distributed under the
[Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html),
the same as Clojure.
