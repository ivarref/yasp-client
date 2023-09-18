(ns com.github.ivarref.yasp-client
  (:refer-clojure :exclude [println])
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.github.ivarref.server :as server]
            [com.github.ivarref.yasp.utils :as u])
  (:import (java.io BufferedInputStream BufferedOutputStream)
           (java.lang AutoCloseable)
           (java.net Socket)))

(defonce proxy-state (atom {}))

(defn handler [{:keys [endpoint remote-host remote-port]}
               {:keys [^Socket sock closed?]}]
  (let [res (client/post endpoint
                         {:body               (json/generate-string {:op      "connect"
                                                                     :payload (u/pr-str-safe {:host remote-host
                                                                                              :port remote-port})})
                          :content-type       :json
                          :socket-timeout     5000          ;; in milliseconds
                          :connection-timeout 3000          ;; in milliseconds
                          :accept             :json
                          :as                 :json})
        {:keys [res session]} (:body res)]
    (if (not= "ok-connect" res)
      (do
        (log/error "Client: Could not connect, aborting. Response was:" res))
      (with-open [in (BufferedInputStream. (.getInputStream sock))
                  out (BufferedOutputStream. (.getOutputStream sock))]
        (loop []
          (let [chunk (u/read-max-bytes in 1024)]
            (if chunk
              (do
                (if (pos-int? (count chunk))
                  (log/debug "Client: Send" (count chunk) "bytes over HTTP")
                  (log/trace "Client: Send" (count chunk) "bytes over HTTP"))
                (let [resp (client/post endpoint
                                        {:body               (json/generate-string {:op      "send"
                                                                                    :session session
                                                                                    :payload (u/bytes->base64-str chunk)})
                                         :content-type       :json
                                         :socket-timeout     5000 ;; in milliseconds
                                         :connection-timeout 3000 ;; in milliseconds
                                         :accept             :json
                                         :as                 :json})
                      {:keys [res payload]} (:body resp)]
                  (cond (= "eof" res)
                        (log/info "Client: Remote EOF, closing connection")

                        (= "unknown-session" res)
                        (log/debug "Client: Remote unknown session, closing connection")

                        (= "ok-send" res)
                        (do
                          (u/copy-bytes (u/base64-str->bytes payload) out)
                          (recur))

                        :else
                        (do
                          (log/error "Client: Unhandled result" res)))))
              (do
                (log/info "Client: Got EOF from local connection")))))
        (log/debug "Client: Pump thread exiting")))))

(defn start-client!
  "Document"
  ^AutoCloseable
  [{:keys [endpoint remote-host remote-port socket-timeout]
    :or   {socket-timeout 100}
    :as   cfg}]
  (assert (and (string? endpoint)
               (or
                 (str/starts-with? endpoint "http://")
                 (str/starts-with? endpoint "https://")))
          "Expected :endpoint to be present")
  (assert (string? remote-host) "Expected :remote-host to be present")
  (assert (some? remote-port) "Expected :remote-port to be present")
  (server/start-server! proxy-state (select-keys cfg [:socket-timeout]) (fn [cb-args] (handler cfg cb-args))))

