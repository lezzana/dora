(ns dora.refineria
  (:require [clojure.java.shell :refer :all]
            [digitalize.core :refer :all]
            [dora.data :refer :all]
            [dora.pro-file :refer :all]
            [dora.util :refer :all]
            [environ.core :refer [env]]
            [formaterr.core :refer :all]
            [monger.core :as mg]
            [mongerr.core :refer :all]
            [tentacles.repos :refer [create-org-repo]]
            [clojure.string :as str]
            [clj-time.core :as t]))

;; Por ahora los recursos vip. despues sera (db :resources)
(def mirrored-resources (map resource ["http://web.coneval.gob.mx/Informes/Pobreza/Datos_abiertos/Rezago-social-2000-2005-2010_mun_DA.csv" "http://www.cofepris.gob.mx/Transparencia/Documents/datos_abiertos/Agua_Calidad.csv" "http://201.116.60.46/DatosAbiertos/Datos_de_calidad_del_agua_de_5000_sitios_de_monitoreo.zip" "http://www.siap.gob.mx/datosAbiertos/Estadist_Produc_Agricola/Agt_cierre_2013_2014.csv" "http://www.datos.economia.gob.mx/RegulacionMinera/ConcesionesMineras.csv" "http://dsiappsdev.semarnat.gob.mx/datos/aire/datos%20RETC.csv" "http://www.sectur.gob.mx/DATOS/0116/19-Inventario-Turistico-por-entidad-federativa.csv" "http://www.sectur.gob.mx/DATOS/0116/24-Localidades-que-cuentan-con-el-nombramiento-de-Pueblo-Magico.csv" "http://www.datatur.beta.sectur.gob.mx/Documentos%20compartidos/6_1_td.csv" "http://catalogo.datos.gob.mx/dataset/54ae9a90-418c-4088-88d0-edea59814826/resource/ffc1323a-bf46-4d9d-86a8-237315c2036e/download/matriculaporinstitucionyentidadfederativa.csv" "http://data.sct.gob.mx:8082/datos/datos/abiertos/11601MexicoConectado.xlsx" "http://www.correosdemexico.gob.mx/datosabiertos/cp/cpdescarga.txt" "http://www.correosdemexico.gob.mx/datosabiertos/poligonos/mapapoligonos.zip"]))

(def ^:dynamic refineria-dir "/Users/nex/mirrors/")
(def ^:dynamic gh-org "mxabierto-mirror/")

(defn emap [& args]
  (doall (apply map args)))

(defn resource-name [resource]
  (standard-name (:name resource)))

(defn mirror-dir [resource]
  (str refineria-dir (resource-name resource)))

(defn clone-mirror [resource]
  (let [le-name (resource-name resource)]
    (clone (str gh-org le-name)
           (str refineria-dir le-name))))

(defn repo-mirror [resource]
  (let [le-name (resource-name resource)]
    (create-org-repo "mxabierto-mirror"
                     le-name
                     {:auth (env :gh)
                      :description (:description resource)})))

(defn repo [resource]
  (try (clone-mirror resource)
       (catch Exception e
         (if (= (:status (ex-data e)) :non-existant)
           (do
             (repo-mirror resource)
             (clone-mirror resource))
           (do
             (checkout (mirror-dir resource) "master")
             (pull (mirror-dir resource) "origin" (branch)))))))

(defn resource-file [resource]
  (str (mirror-dir resource)
       "/"
       (last (re-seq #"[^/]+" (:url resource)))))

(defn copy-resource [resource]
  (copy (:url resource)
       (resource-file resource)))

(defn refina-csv [file]
  (println "Digitalizando: " file)
  (csv file (digitalize (csv file))))

(defn refina-zip [dir file]
  (println "Descomprimiendo: " file)
  (shs "unzip" file "-d" dir)
  (shs "rm" file))

(defn re-filter [re coll]
  (filter #(re-find re %) coll))

(defn filter-files [dir regex]
  (re-filter regex (ls& dir)))

(defn uncompress [dir]
  (if-let [zips (seq (filter-files dir #"zip"))]
    (do (doall (map #(refina-zip dir %)
                    zips))
        (uncompress dir))))

(defn refina-tsv [file]
  (csv (str file ".csv")
       (digitalize (tsv file)))
  (rm file))

(defn refina-tsvs [files]
  (let [victims (filter #(re-find #"tsv" %) files)]
    (doall (map refina-tsv
                victims))))

(defn refina-psv [file]
  (csv (str file ".csv")
       (digitalize (psv file)))
  (rm file))

(defn refina-psvs [files]
  (let [victims (filter #(and (or (re-find #"txt" %)
                                  (re-find #"psv" %))
                              (> 15 (count (re-find #"|" (slurp %))))) files)]
    (doall (map refina-psv
                victims))))

(defn refina [dir]
  (println "refining: " (str/join ", " (ls dir)))
  (uncompress dir)
  (let [files (ls& dir)]
    (doall (map refina-csv
                (re-filter #"csv" files)))
    (refina-tsvs files)
    (refina-psvs files)))

(defn ls-buda []
  (binding [*db* (:db (mg/connect-via-uri "mongodb://localhost:27027/buda"))]
      (db)))

(defn update-buda [resource-name file]
  (let [batches (partition-all 100 (csv file))]
    (binding [*db* (:db (mg/connect-via-uri "mongodb://localhost:27027/buda"))]
      (emap #(db-insert resource-name %) batches))))

(defn apify
  [resource]
  (let [files (filter-files (mirror-dir resource) #"csv")]
    (map #(update-buda (resource-name resource) %)
         files)))

(defn mirror
  ([] (doall (map mirror mirrored-resources)))
  ([resource]
   (let [dir (mirror-dir resource)]
     (try
       (println "mirroring: " (:name resource))
       (repo resource)
       (copy-resource resource)
       (adda dir)
       (commit (str "Generado por la refinería en: " (t/now)) dir)
       (push dir "origin" "master")
       (checkout-B dir "refineria")
       (git-merge)
       (refina dir)
       (adda dir)
       (commit (str "Generado por la refinería en: " (t/now)) dir)
       (push-force dir "origin" "refineria")
       (apify dir resource)
       (catch Exception e (println e "\nCould not finish on: " (:name resource)))))))