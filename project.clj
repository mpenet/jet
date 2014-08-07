(defproject cc.qbits/jet "0.3.0-beta3"
  :description ""
  :url "https://github.com/mpenet/jet"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.303.0-886421-alpha"]
                 [ring/ring-core "1.3.0"
                  :exclusions [javax.servlet/servlet-api]]
                 [ring/ring-servlet "1.3.0"
                  :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-server "9.2.2.v20140723"]
                 [org.eclipse.jetty.websocket/websocket-server "9.2.2.v20140723"]
                 [org.eclipse.jetty.websocket/websocket-servlet "9.2.2.v20140723"]
                 [org.eclipse.jetty.websocket/websocket-client "9.2.2.v20140723"]
                 [org.eclipse.jetty/jetty-client "9.2.2.v20140723"]
                 [cheshire "5.3.1"]]
  :profiles {:1.4  {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :1.5  {:dependencies [[org.clojure/clojure "1.5.0"]]}
             :1.6  {:dependencies [[org.clojure/clojure "1.6.0"]]}
             :dev  {:dependencies [[codox "0.8.10"]]}
             :test  {:dependencies []}}
  :codox {:src-dir-uri "https://github.com/mpenet/jet/blob/master/"
          :src-linenum-anchor-prefix "L"
          :output-dir "doc"
          :defaults {:doc/format :markdown}}
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  ;; :javac-options ["-source" "1.6" "-target" "1.6" "-g"]
  :global-vars {*warn-on-reflection* true})
