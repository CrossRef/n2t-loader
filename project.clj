(defproject crossref/n2t-loader "0.1.0"
  :description "Load DOIs into n2t"
  :url "http://github.com/CrossRef/n2t-loader"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.zip "0.1.1"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [http-kit "2.1.19"]]
  :main ^:skip-aot n2t-loader.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
