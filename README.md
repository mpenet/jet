# jet
<!-- [![Build Status](https://secure.travis-ci.org/mpenet/jet.png?branch=master)](http://travis-ci.org/mpenet/jet) -->

Jet is a server + client library to interact/use jetty9 from clojure
using core.async channels.
The server is both a ring-adapter and has support for websocket, and
the client has yet to see the light of day.

The server part started from the code of the various
ring-jetty9-adapters existing.

<!-- ## Documentation -->

<!-- [codox generated documentation](http://mpenet.github.com/jet/#docs). -->

## Installation

jet is [available on Clojars](https://clojars.org/cc.qbits/jet).

Add this to your dependencies:

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
