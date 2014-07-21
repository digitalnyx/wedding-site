(ns wedding-site.core
  (:gen-class :main :true)
  (:import 
   [com.stripe Stripe]
   [com.stripe.model Charge]
   [com.stripe.exception StripeException])
  (:use [compojure.route :only [files not-found resources]]
      [compojure.handler :only [site]]
      [compojure.core :only [defroutes GET POST]]
      [clostache.parser]
      [org.httpkit.server :only [run-server]])
  (:require
   [clojure.java.jdbc :as sql]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.data.json :as json]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Resources ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn resource-path
  "checks the resource folder for a file."
  [filename]
  (if-let [res (clojure.java.io/resource filename)]
    (.getPath res)
    nil))

(defn parse-glist
 []
 (with-open [r (io/reader (resource-path "glist.csv"))]
   (let [glist-seq (doall (line-seq r))]
     (for [guest (rest glist-seq)]
       (let [guest-split (str/split guest #",")]
         {:name (str/trim (nth guest-split 0 "????"))
          :attendees (Integer/parseInt (nth guest-split 5 "0"))
          :plus_ones (Integer/parseInt (nth guest-split 6 "0"))
          :rsvp_key (str (nth guest-split 7 "0"))})))))

(defn gen-codes
  [num-codes]
  (loop [codes #{}]
    (if (> (count codes) num-codes)
      codes
      (let [code (str (format "%04d" (rand-int 9999)))]
        (recur (conj codes code))))))

;; Extra codes:
;; "2483" "7895" "8676" "0394" "1395" "0075" "2562" 
;; "4223" "7864" "4014" "2078" "6357" "8546" "7336" "1682"

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; DB ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def db-spec
  (or (System/getenv "DATABASE_URL")
      "postgresql://localhost:5432/bandm"))

(defn db-exists? []
  (-> (sql/query db-spec
                 [(str "select count(*) from information_schema.tables "
                       "where table_name='guests'")])
      first :count pos?))

(defn make-db []
  (when (not (db-exists?))
    (print "Creating database structure...") (flush)
    (sql/db-do-commands db-spec
                        (sql/create-table-ddl
                         :guests
                         [:id :serial "PRIMARY KEY"]
                         ;[:rsvp_key :varchar "PRIMARY KEY"]
                         [:name :text "NOT NULL"]
                         [:attendees :integer "NOT NULL"]
                         [:plus_ones :integer "NOT NULL"]
                         [:require_hotel :boolean "DEFAULT FALSE"]
                         [:rsvp_comment :varchar]
                         [:rsvp_attendees :integer "DEFAULT 0"])
                        (sql/create-table-ddl
                         :gifts
                         [:id :serial "PRIMARY KEY"]
                         [:name :varchar]
                         [:comment :varchar]
                         [:amount :numeric "DEFAULT 0.00"]
                         [:transaction :varchar]))
    (println " done")))

(defn select-guest
  [first-name last-name]
  (first
   (sql/query db-spec
              [(str "SELECT * FROM guests WHERE to_tsvector(name) @@ to_tsquery('" first-name " & " last-name "')")])))
              ;[(str "SELECT * FROM guests WHERE rsvp_key = '" rsvp-key "'")])))

(defn select-attending-guests
  []
  (sql/query db-spec
             [(str "SELECT * FROM guests WHERE rsvp_attendees > '0'")]))

(defn update-guest-rsvp
  [request]
  (let [attendees (Integer/parseInt (:attending request))
        plus-one (Integer/parseInt (:plus-one request "0"))
        hotel? (if (= 0 (Integer/parseInt (:hotel request "0"))) false true)
        id (Integer/parseInt (:id request))]
    (sql/update! db-spec
                 :guests
                 {:rsvp_attendees (+ attendees plus-one)
                  :require_hotel hotel?
                  :rsvp_comment (:comments request)}
                 ["id = ?" id])))

(defn insert-gift
  [request transaction-resp]
  (sql/insert! db-spec
               :gifts
               {:name (:name request "")
                :comment (:comment request "")
                :amount (Double/parseDouble (:amount request "0.00"))
                :transaction (str transaction-resp)}))

(defn select-gifts
  []
  (sql/query db-spec
             [(str "SELECT * FROM gifts")]))
    

(defn populate-db
  []
  (sql/with-db-connection [db-con db-spec]
    (doseq [a (parse-glist)]
      ;(sql/db-do-commands db-spec
      (sql/insert! db-con
       :guests
       {:name (:name a)
        :attendees (:attendees a)
        :plus_ones (:plus_ones a)}))))
                       


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Stripe ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def public-key
  "pk_test_ZT54Wh0GgDlDK4NIElCdRe22") ;; Test

(def private-key
  "sk_test_jPNHXD0xRse5nVCwoQmE5J3H") ;; Test

(defn stripe-charge
  [token amount]
  (set! Stripe/apiKey private-key)
  (let [cm (java.util.HashMap.)]{"nothing" (Object.)}
    (doto cm
      (.put "currency" "usd")
      (.put "amount" amount)
      (.put "card" token))
    
    (try
      {:success true :charge (Charge/create cm)}
      (catch Exception e
        (.printStackTrace e)
        {:success false :error e}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; Pages ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn show-landing-page [req]
  (render-resource "templates/index.mustache" {:req req}))

(defn show-registry-page [req]
  (if (:token req)
    (let [charge (stripe-charge (:token req) (:amount req))
          paid (if (:success charge) (.getPaid (:charge charge)) false)
          error-msg (if (not (:success charge)) (.getMessage (:error charge)) false)]
      (insert-gift req (str (:charge charge) (:error charge)))
      (render-resource "templates/registry.mustache" {:req req
                                                      :finished true
                                                      :paid paid
                                                      :error-msg error-msg}))
    (render-resource "templates/registry.mustache" {:req req :public-key public-key})))

(defn show-rsvp-page [req]
  (cond 
   
   (and (:first-name req) (:last-name req))
   (let [guest (select-guest (:first-name req) (:last-name req))
         single (if (= 1 (:attendees guest)) true false)
         double (if (= 2 (:attendees guest)) true false)
         plus-one (if (= 1 (:plus_ones guest)) true false)]
     (if (nil? guest)
       (render-resource "templates/rsvp.mustache" {:req (dissoc req :first-name :last-name)
                                                   :bad-key true})
       (render-resource "templates/rsvp.mustache" {:req req
                                                   :guest {:name (:name guest)
                                                           :single single
                                                           :double double
                                                           :plus-one plus-one
                                                           :id (:id guest)}})))

   (:attending req)
   (do
     ;(println req)
     (update-guest-rsvp req)
     (render-resource "templates/rsvp.mustache" {:finished true}))

   :else
   (render-resource "templates/rsvp.mustache" {:req req})))

(defn show-information-page [req]
  (render-resource "templates/information.mustache" {:req req}))

(defn show-raw-page [req]
  (let [guests (select-attending-guests)
        num-guests (apply + (doall (for [x guests] (:rsvp_attendees x))))
        gifts (select-gifts)]
    (render-resource "templates/raw.mustache" {:guests guests
                                               :num-guests num-guests
                                               :gifts gifts})))

(defroutes all-routes
  (GET "/" {params :params} (show-landing-page params))
  (GET "/registry" {params :params} (show-registry-page params))
  (POST "/registry" {params :params} (show-registry-page params))
  (GET "/rsvp" {params :params} (show-rsvp-page params))
  (POST "/rsvp" {params :params} (show-rsvp-page params))
  (GET "/information" {params :params} (show-information-page params))
  (GET "/raw" {params :params} (show-raw-page params))
  (resources "/")
  (not-found "<p>Page not found.</p>")) ;; all other, return 404

(defn -main [port]
  (when (not (db-exists?))
    (make-db)
    (populate-db))
  (run-server (site #'all-routes) {:port (Integer. port)}))
