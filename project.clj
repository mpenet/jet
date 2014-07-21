(defproject cc.qbits/jet "0.1.0-SNAPSHOT"
  :description ""
  :url "https://github.com/mpenet/jet"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.clojure/core.async "0.1.303.0-886421-alpha"]
                 [ring/ring-core "1.2.1"
                  :exclusions [javax.servlet/servlet-api]]
                 [ring/ring-servlet "1.2.1"
                  :exclusions [javax.servlet/servlet-api]]
                 [org.eclipse.jetty/jetty-server "9.2.1.v20140609"]
                 [org.eclipse.jetty.websocket/websocket-server "9.2.1.v20140609"]
                 [org.eclipse.jetty.websocket/websocket-servlet "9.2.1.v20140609"]
                 [org.eclipse.jetty/jetty-client "9.2.1.v20140609"]]
  :profiles {:1.4  {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :1.5  {:dependencies [[org.clojure/clojure "1.5.0"]]}
             :1.6  {:dependencies [[org.clojure/clojure "1.6.0"]]}
             :dev  {:dependencies []}
             :test  {:dependencies []}}
  :codox {:src-dir-uri "https://github.com/mpenet/jet/blob/master"
          :src-linenum-anchor-prefix "L"
          :output-dir "doc/codox"}
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  ;; :javac-options ["-source" "1.6" "-target" "1.6" "-g"]
  :global-vars {*warn-on-reflection* true}
  )
