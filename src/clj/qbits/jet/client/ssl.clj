(ns qbits.jet.client.ssl
  (:import ;; (java.net Socket Proxy Proxy$Type InetSocketAddress)
           ;; (java.security KeyStore)
           ;; (java.security.cert X509Certificate)
           ;; (javax.net.ssl SSLSession SSLSocket)
           (org.eclipse.jetty.util.ssl SslContextFactory)
))

(def ^SslContextFactory insecure-ssl-context-factory
  (SslContextFactory. true))
