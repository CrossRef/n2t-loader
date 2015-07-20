(ns n2t-loader.core
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.xml :as xml]
            [clojure.zip :as zip]
            [clojure.data.zip :refer [children children-auto]]
            [clojure.data.zip.xml :refer [xml-> xml1-> text]]
            [clojure.core.async :as async]
            [org.httpkit.client :as hc]))

;; 1. load in xml file paths.

;; 2. async chan - take off file path, process it by reading in as
;;                 xml and then putting each [doi, res-url] pair on to
;;                 next chan

;; 3. async chan - take off [doi, res-url], try and add to n2t.

;; Configuration of n2t end point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def n2t-binder "xref")
(def n2t-user "xref")
(def n2t-password "")
(def n2t-path "https://n2t-stg.n2t.net/a/")
(def n2t-batch-size 1000)

(def n2t-target-attr "_t")
(def n2t-title-attr "title")

(defn n2t-set-path [identifier attr value]
  (str n2t-path
       n2t-binder
       "/b?"
       identifier
       ".set"
       "%20"
       attr
       "%20"
       value))

(def n2t-batch-path
  (str n2t-path
       n2t-binder
       "/b?-"))

(defn n2t-get-path [identifier attr]
  (str n2t-path
       n2t-binder
       "/b?"
       identifier
       ".fetch"
       "%20"
       attr))

;; Processing actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn xml-files [f]
  (->> f
       io/file
       file-seq
       (filter #(and (.isFile %) (.endsWith (.getPath %) ".xml")))))

(defn doi-data->doi [doi-data-loc]
  [(xml1-> doi-data-loc :doi text)
   (xml1-> doi-data-loc :resource text)])

(defn xml-file->dois [f]
  (with-open [rdr (io/reader f)]
    (let [doc (-> rdr xml/parse zip/xml-zip)
          doi-datas (xml-> doc
                           :ListRecords :record :metadata
                           :crossref_result :query_result :body
                           :crossref_metadata :doi_record :crossref
                           children-auto children-auto :doi_data)]
      (map doi-data->doi doi-datas))))

(defn normalize-doi [doi]
  (->> doi
       str/upper-case
       (str "doi:")))

(defn register-with-n2t [doi res-url]
  (let [{:keys [status body error]}
        @(hc/get (n2t-set-path (normalize-doi doi)
                               n2t-target-attr
                               res-url)
                 {:basic-auth [n2t-user n2t-password]
                  :insecure? true})]
    (when-not (= 200 status)
      (str doi
           " to "
           res-url
           " status code "
           status
           " body "
           body
           " exception "
           error))))

(defn check-with-n2t [doi]
  (let [{:keys [status body error]}
        @(hc/get (n2t-get-path (normalize-doi doi)
                               n2t-target-attr)
                 {:basic-auth [n2t-user n2t-password]
                  :insecure? true})]
    (if (= 200 status)
      body
      (println "Error looking up:"
               doi
               "status code"
               status
               "body"
               body
               "exception"
               error))))

;; Batch n2t action
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ->set-target-line [doi res-url]
  (str (normalize-doi doi) ".set _t " res-url))

(defn ->set-target-batch-body [doi-res-url-vectors]
  (->> doi-res-url-vectors
       (map #(apply ->set-target-line %))
       (str/join \newline)))

(defn register-batch-with-n2t [doi-res-url-vectors]
  (let [{:keys [status body error]}
        @(hc/post n2t-batch-path
                  {:basic-auth [n2t-user n2t-password]
                   :insecure? true
                   :body (->set-target-batch-body doi-res-url-vectors)})]
    (when-not (= 200 status)
      (str (str/join ", " (map first doi-res-url-vectors))
           " status code "
           status
           " body "
           body
           " exception "
           error))))

;; Plumbing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-files [file-chan doi-chan]
  (dotimes [_ 5]
    (async/go-loop [f (async/<! file-chan)]
      (try
        (doseq [doi (xml-file->dois f)]
          (async/>! doi-chan doi))
        (catch Throwable t
          (println f t)))
      (recur (async/<! file-chan)))))

(defn process-dois [doi-chan fail-chan count-atom]
  (async/go-loop [doi (async/<! doi-chan)]
    (when-let [fail (apply register-with-n2t doi)]
      (async/>! fail-chan fail))
    (swap! count-atom inc)
    (recur (async/<! doi-chan))))

(defn process-doi-batches [doi-batch-chan fail-chan count-atom]
  (async/go-loop [doi-res-url-list (async/<! doi-batch-chan)]
    (when-let [fail (register-batch-with-n2t doi-res-url-list)]
      (async/>! fail-chan fail))
    (swap! count-atom + (count doi-res-url-list))
    (recur (async/<! doi-batch-chan))))

(defn process-dois-in-batches [doi-list-atom doi-chan
                               doi-batch-chan]
  (async/go-loop [doi (async/<! doi-chan)]
    (swap! doi-list-atom
           #(if (zero? (mod (count %) n2t-batch-size))
              (do
                (async/>!! doi-batch-chan %)
                [doi])
              (conj % doi)))
    (recur (async/<! doi-chan))))

(defn process-fails [fail-chan fail-file]
  (async/go-loop [fail (async/<! fail-chan)]
    (spit fail-file (str fail \newline) :append true)
    (recur (async/<! fail-chan))))

(defn create-plumbing []
  (let [p {:count (atom 0)
           :fail-file (io/file "fails.log")
           :fail-chan (async/chan (async/buffer 10000))
           :file-chan (async/chan (async/buffer 1000))
           :doi-chan (async/chan (async/buffer 10000))}]
    (process-fails (:fail-chan p) (:fail-file p))
    (process-dois (:doi-chan p) (:fail-chan p) (:count p))
    (process-files (:file-chan p) (:doi-chan p))
    p))

(defn create-batch-plumbing []
  (let [p {:count (atom 0)
           :doi-list (atom [])
           :fail-file (io/file "fails.log")
           :fail-chan (async/chan (async/buffer 10000))
           :file-chan (async/chan (async/buffer 1000))
           :doi-chan (async/chan (async/buffer 10000))
           :doi-batch-chan (async/chan (async/buffer 100))}]
    (process-fails (:fail-chan p) (:fail-file p))
    (process-dois-in-batches (:doi-list p)
                             (:doi-chan p)
                             (:doi-batch-chan p))
    (process-doi-batches (:doi-batch-chan p)
                         (:fail-chan p)
                         (:count p))
    (process-files (:file-chan p) (:doi-chan p))
    p))

(defn run-directory [f plumbing]
  (doseq [ff (-> f xml-files)]
    (async/>!! (:file-chan plumbing) ff)))

