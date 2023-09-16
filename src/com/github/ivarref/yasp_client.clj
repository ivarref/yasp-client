(ns com.github.ivarref.yasp-client
  (:require [clojure.string :as str]
            [com.github.ivarref.server :as server]
            [com.github.ivarref.yasp.impl :as impl]
            [clj-http.client :as client]
            [cheshire.core :as json])
  (:refer-clojure :exclude [println])
  (:import (java.io BufferedInputStream BufferedOutputStream)
           (java.lang AutoCloseable)
           (java.net Socket)))

(defonce proxy-state (atom {}))

(defn start-client!
  "Document"
  ^AutoCloseable
  [{:keys [endpoint remote-host remote-port]}]
  (assert (and
            (string? endpoint)
            (or
              (str/starts-with? endpoint "http://")
              (str/starts-with? endpoint "https://")))
          "Expected :endpoint to be present")
  (assert (string? remote-host) "Expected :remote-host to be present")
  (assert (some? remote-port) "Expected :remote-port to be present")
  (server/start-server! proxy-state
                        {}
                        (fn [{:keys [^Socket sock closed?]}]
                          (let [res (client/post endpoint
                                                 {:body               (json/generate-string {:op "connect" :payload (str remote-host ":" remote-port)})
                                                  :content-type       :json
                                                  :socket-timeout     5000 ;; in milliseconds
                                                  :connection-timeout 3000 ;; in milliseconds
                                                  :accept             :json
                                                  :as                 :json})
                                {:keys [res session]} (:body res)]
                            (if (not= "ok-connect" res)
                              (do
                                (impl/atomic-println "Could not connect, aborting"))
                              (do
                                (impl/atomic-println "Established session")
                                (with-open [in (BufferedInputStream. (.getInputStream sock))
                                            out (BufferedOutputStream. (.getOutputStream sock))]
                                  (loop []
                                    (let [chunk (impl/read-max-bytes in 1024)]
                                      (when chunk
                                        (when (pos-int? (count chunk))
                                          (impl/atomic-println "Send chunk of length" (count chunk)))
                                        (let [resp (client/post endpoint
                                                                {:body               (json/generate-string {:op      "send"
                                                                                                            :session session
                                                                                                            :payload (impl/bytes->base64-str chunk)})
                                                                 :content-type       :json
                                                                 :socket-timeout     5000 ;; in milliseconds
                                                                 :connection-timeout 3000 ;; in milliseconds
                                                                 :accept             :json
                                                                 :as                 :json})
                                              {:keys [res] :as body} (:body resp)]
                                          (impl/atomic-println "Client handle" (:res body))
                                          (cond (= "eof" res)
                                                (impl/atomic-println "Remote EOF, closing connection")

                                                (= "unknown-session" res)
                                                (impl/atomic-println "Remote unknown session, closing connection")

                                                :else
                                                (do
                                                  (impl/atomic-println "Handle" res)
                                                  (recur)))))))
                                  (impl/atomic-println "pump thread exiting"))))))))

#_(defonce server (start-client! {}))
