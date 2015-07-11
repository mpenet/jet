(ns qbits.jet.client.ssl
  (:import (org.eclipse.jetty.util.ssl SslContextFactory)))

(defn ^SslContextFactory insecure-ssl-context-factory
  []
  (SslContextFactory. true))
