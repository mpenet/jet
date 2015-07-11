(def jetty-version "9.3.0.v20150612")
(defproject cc.qbits/jet "0.6.6"
  :description "Jetty9 ring server adapter with WebSocket support"
  :url "https://github.com/mpenet/jet"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.eclipse.jetty/jetty-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-server ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-servlet ~jetty-version]
                 [org.eclipse.jetty.websocket/websocket-client ~jetty-version]
                 [org.eclipse.jetty/jetty-client ~jetty-version]
                 [cheshire "5.5.0"]]
  :repositories {"sonatype" {:url "http://oss.sonatype.org/content/repositories/releases"
                             :snapshots false
                             :releases {:checksum :fail :update :always}}
                 "sonatype-snapshots" {:url "http://oss.sonatype.org/content/repositories/snapshots"
                                       :snapshots true
                                       :releases {:checksum :fail :update :always}}}
  :profiles {:1.5 {:dependencies [[org.clojure/clojure "1.5.0"]]}
             :1.6 {:dependencies [[org.clojure/clojure "1.6.0"]]}
             :master {:dependencies [[org.clojure/clojure "1.7.0-master-SNAPSHOT"]]}
             :dev  {:dependencies [[ring/ring-core "1.3.0"
                                     :exclusions [javax.servlet/servlet-api]]
                                    [ring/ring-servlet "1.3.0"
                                     :exclusions [javax.servlet/servlet-api]]
                                    [codox "0.8.10"]]}}
  :aliases {"all" ["with-profile" "dev:dev,1.5:dev,1.6:dev,1.7:dev,master"]}
  :codox {:src-dir-uri "https://github.com/mpenet/jet/blob/master/"
          :src-linenum-anchor-prefix "L"
          :output-dir "doc"
          :defaults {:doc/format :markdown}}
  :source-paths ["src/clj"]
  :java-source-paths ["src/java"]
  :global-vars {*warn-on-reflection* true})
