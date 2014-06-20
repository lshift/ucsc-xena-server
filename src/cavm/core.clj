(ns cavm.core
  (:require [clojure.string :as s])
  (:require [cavm.h2 :as h2])
  (:require [clojure.java.io :as io])
  (:require [ring.adapter.jetty :refer [run-jetty]])
  (:require [clojure.data.json :as json])
  (:require [me.raynes.fs :as fs])
  (:require [clojure.tools.cli :refer [parse-opts]])
  (:require [cgdata.core :as cgdata])
  (:require [ring.middleware.resource :refer [wrap-resource]])
  (:require [ring.middleware.content-type :refer [wrap-content-type]])
  (:require [ring.middleware.not-modified :refer [wrap-not-modified]])
  (:require [ring.middleware.params :refer [wrap-params]])
  (:require [cavm.views.datasets])
  (:require [ring.middleware.gzip :refer [wrap-gzip]])
  (:require [ring.middleware.stacktrace :refer [wrap-stacktrace]]) ; XXX only in dev
  (:require [liberator.dev :refer [wrap-trace]])                   ; XXX only in dev
  (:require [filevents.core :refer [watch]])
  (:require [cavm.readers :as cr])
  (:require [cavm.loader :as cl])
  (:require [cavm.fs-utils :refer [normalized-path]])
  (:require [cavm.cgdata])
  (:require [clj-http.client :as client])
  (:gen-class))

(defn- in-data-path [root path]
  (boolean (fs/child-of? (normalized-path root) (normalized-path path))))

;
; web services

; XXX change Access-Control-Allow-Origin in production.
(defn wrap-access-control [handler]
  (fn [request]
    (let [response (handler request)]
      (-> response
          (assoc-in [:headers "Access-Control-Allow-Origin"] "https://tcga1.kilokluster.ucsc.edu")
          (assoc-in [:headers "Access-Control-Allow-Headers"] "Cancer-Browser-Api")))))

(defn- attr-middleware [app k v]
  (fn [req]
    (app (assoc req k v))))

(comment (defn- del-datasets [args]
   (dorun (map del-exp args))))

(comment (defn- print-datasets []
   (dorun (map println (datasets)))))

; XXX add ring jsonp?
(defn- get-app [db loader]
  (-> cavm.views.datasets/routes
      (wrap-trace :header :ui)
      (attr-middleware :db db)
      (attr-middleware :loader loader)
      (wrap-params)
      (wrap-resource "public")
      (wrap-content-type)
      (wrap-not-modified)
      (wrap-gzip)
      (wrap-stacktrace)
      (wrap-access-control)))

(defn- serv [app host port]
  (ring.adapter.jetty/run-jetty app {:host host :port port :join? true}))

(comment (defn- load-report [load-fn root file]
   (try
     (load-fn root file)
     (catch java.lang.Exception e
       (binding [*out* *err*]
         (println "Error loading file" file)
         (println (str "message " (.getMessage e)))
         (.printStackTrace e))))))

; XXX call clean-sources somewhere?? Should be automated.
(comment (defn- loadfiles [load-fn root args]
   (when (not (> (count args) 0))
     (println "Usage\nload <filename>")
     (System/exit 0))

   ; Skip files outside the designated path
   (let [{in-path true, not-in-path false}
         (group-by #(in-data-path root %) args)]
     (when not-in-path
       (binding [*out* *err*]
         (println "These files are outside the CAVM data path and will not be served:")
         (println (s/join "\n" (in-path false)))))
     (create)
     (println "Loading " (count in-path) " file(s)")
     (dorun (map #(do (print %2 %1 "") (time (load-report load-fn root %1)))
                 in-path
                 (range (count in-path) 0 -1)))
     (clean-sources))))

(defn- loadfiles [port files]
  (client/post (str "http://localhost:" port "/update/")
               {:form-params {:file files}}))

(def detectors
  [cgdata/detect-cgdata
   cgdata/detect-tsv])

(defn filter-hidden
  [s]
  (filter #(not (.isHidden %)) s))

; Full reload metadata. The loader will skip
; data files with unchanged hashes.
(defn file-changed [loader docroot kind file]
  (doseq [f (-> docroot
                (io/file)
                (file-seq)
                (rest) ; skip docroot (the first element)
                (filter-hidden))]
    (println "Loading " f)
    (try (loader f)
      (catch Exception e (println (str "caught exception: " (.getMessage e))))))) ; XXX this is unhelpful. Log it somewhere.

(def xenadir-default (str (io/file (System/getProperty  "user.home") "xena")))
(def docroot-default (str (io/file xenadir-default "files")))
(def db-default (str (io/file xenadir-default "database")))
(def tmp-dir-default
  (str (io/file (System/getProperty "java.io.tmpdir") "xena-staging")))

(def ^:private argspec
  [[nil "--no-serve" "Don't start web server" :id :serve :parse-fn not :default true]
   ["-p" "--port PORT" "Server port to listen on" :default 7222 :parse-fn #(Integer/parseInt %)]
   ["-l" "--load" "Load files into running server"]
   [nil "--no-auto" "Don't auto-load files" :id :auto :parse-fn not :default true]
   ["-h" "--help" "Show help"]
   ["-H" "--host HOST" "Set host for listening socket" :default "localhost"]
   ["-r" "--root DIR" "Set document root directory" :default docroot-default]
   ["-d" "--database FILE" "Database to use" :default db-default]
   ["-j" "--json" "Fix json"]
   ["-t" "--tmp DIR" "Set tmp dir" :default tmp-dir-default]])

(defn- mkdir [dir]
  (.mkdirs (io/file dir))
  (when (not (.exists (io/file dir)))
    (str "Unable to create directory: " dir)))

; Use MVCC so our bulk writes don't block readers.
;
; Use LOG=0 because otherwise bulk writes are pathologically slow, and we don't
; need to worry about dropping data. A problem with LOG=0 is that we can't tell
; if the db shut down cleanly, so we don't know when to recover. We might be able
; to partly mitigate this by periodically closing & opening the db (maybe once or
; twice a day?). We shouldn't lose transactions committed before the last close,
; and we can detect files that we've lost due to unclean shutdown.
;
; UNDO_LOG allows us to add a dataset atomically (i.e. rollback on error).
;
; A more idiomatic usage of h2 would be to break the bulk write into chunks and use
; LOG=1 or LOG=2. This would require building another "transaction" system over
; the transaction system, flagging incompletely loaded datasets such that readers
; could filter those results out of their queries. It would add a lot of complexity.
(def default-h2-opts ";CACHE_SIZE=65536;UNDO_LOG=1;LOG=0;MVCC=TRUE")

; Might want to allow more piecemeal setting of options, by
; parsing them & allowing cli overrides. For now, if the
; user sets options they must be comprehensive. If the user
; doesn't set options, we use default-h2-opts.

(defn h2-opts
  "Add default h2 options if none are specified"
  [database]
  (if (= -1 (.indexOf database ";"))
    (str database default-h2-opts)
    database))

; XXX create dir for database as well?
(defn -main [& args]
  (let [{:keys [options arguments summary errors]} (parse-opts args argspec)
        docroot (:root options)
        port (:port options)
        host (:host options)
        tmp (:tmp options)
        database (h2-opts (:database options))]
    (if errors
      (binding [*out* *err*]
        (println (s/join "\n" errors)))
      (cond
        (:help options) (println summary)
        (:json options) (cgdata/fix-json docroot)
        (:load options) (loadfiles port arguments)
        :else (if-let [error (some mkdir [tmp docroot])]
                (binding [*out* *err*]
                  (println error))
                (do
                  (h2/set-tmp-dir! tmp)
                  (let [db (h2/create-xenadb database)
                        detector (apply cr/detector docroot detectors)
                        loader (cl/loader-agent db detector docroot)]
                    (when (:auto options)
                      (watch (partial file-changed loader docroot) docroot))
                    (when (:serve options)
                      (serv (get-app db loader) host port))))))))
  (shutdown-agents))

; When logging to the repl from a future, *err* gets lost.
; This will set it to the repl terminal, for reasons I don't understand.
(comment (defn snoop [msg x]
   (.start (Thread. #(binding [*out* *err*]
                       (println msg x)
                       (flush))))
   x))

; (def testdb (h2/create-xenadb "test;TRACE_LEVEL_FILE=3"))
; (def testdb (h2/create-xenadb "/inside/home/craft/xena/database;TRACE_LEVEL_FILE=3"))
; (def testdetector (apply cr/detector "/inside/home/craft/xena/files" detectors))
; (def testloader (cl/loader-agent testdb testdetector "/inside/home/craft/xena/files"))
;            (watch (partial file-changed #'testloader docroot-default) docroot-default)
; (def app (get-app testdb testloader))
; (defonce server (ring.adapter.jetty/run-jetty #'app {:port 7222 :join? false}))
; (.start server)
; (.stop server)
