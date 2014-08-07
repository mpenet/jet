(ns qbits.jet.client.auth
  (:import
   (java.net URI)
   (org.eclipse.jetty.client HttpAuthenticationStore)
   (org.eclipse.jetty.client.util
    BasicAuthentication
    DigestAuthentication)))

(defn basic-auth
  "Implementation of the HTTP Basic authentication defined in RFC 2617."
  [uri realm user password]
  (BasicAuthentication. (URI. uri) (or realm nil) user password))

(defn digest-auth
  "Implementation of the HTTP Digest authentication defined in RFC 2617"
  [uri realm user password]
  (DigestAuthentication. (URI. uri) (or realm nil) user password))
