(defproject wedding_site "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [http-kit "2.1.16"]
                 [de.ubercode.clostache/clostache "1.3.1"]
                 [compojure "1.1.5"]]
  :uberjar-name "wedding-site.jar"
  :min-lein-version "2.0.0"
  :javac-options ["-XX:+TieredCompilation" "-XX:+AggressiveOpts"]
  :jvm-opts ["-Xmx500M" "-Djava.awt.headless=true"]
  :aot :all
  :main wedding-site.core)
