# Changelog

## 0.2.0

* Allow to pass factory functions for core.async channels used on
  WebSocket objects, see run-jetty and ws-client docstrings.

* Add options on HTTP and WebSocket clients

* Removed ::connect event from ctrl channel, it was a bit useless
  given that the ctrl channel gets created on connect, so its
  existence is basically equivalent to the event.
