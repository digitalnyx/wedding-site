(ns wedding-site.core
  (:gen-class :main :true)
  (:use [compojure.route :only [files not-found]]
      [compojure.handler :only [site]]
      [compojure.core :only [defroutes GET POST]]
      [clostache.parser]
      [org.httpkit.server :only [run-server]]))

(defn show-landing-page [req]
  (render-resource "templates/index.mustache" {:person "Stinky"})
  )

(defroutes all-routes
  (GET "/" [] show-landing-page)
  (not-found "<p>Page not found.</p>")) ;; all other, return 404

(defn -main [port]
  (run-server (site #'all-routes) {:port (Integer. port)}))
