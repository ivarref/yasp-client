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
  (log/info "Creating new session")
  (let [{:keys [status] :as resp} (try
                                    (client/post endpoint
                                                 {:body               (json/generate-string {:op      "connect"
                                                                                             :payload (u/pr-str-safe {:host remote-host
                                                                                                                      :port remote-port})})
                                                  :content-type       :json
                                                  :socket-timeout     5000 ;; in milliseconds
                                                  :connection-timeout 3000 ;; in milliseconds
                                                  :accept             :json
                                                  ;:throw-exceptions   false
                                                  :as                 :json})
                                    (catch Throwable t
                                      (log/error t "Error during session creation")
                                      (throw t)))
        {:keys [res payload session]} (:body resp)]
    (cond
      (not= "ok-connect" res)
      (do
        (log/error "Could not connect, aborting. Response was:" res payload))

      :else
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
                        (log/info "Remote EOF, closing connection")

                        (= "unknown-session" res)
                        (log/debug "Remote unknown session, closing connection")

                        (= "ok-send" res)
                        (do
                          (u/copy-bytes (u/base64-str->bytes payload) out)
                          (recur))

                        :else
                        (do
                          (log/error "Client: Unhandled result" res)))))
              (do
                (log/info "EOF from local connection")))))
        (log/debug "Session ending")))))

(defn start-server!
  "Document"
  ^AutoCloseable
  [{:keys [endpoint remote-host remote-port local-port local-port-file block?]
    :or   {local-port-file ".yasp-port"
           block?          true}
    :as   cfg}]
  (assert (and (string? endpoint)
               (or
                 (str/starts-with? endpoint "http://")
                 (str/starts-with? endpoint "https://")))
          "Expected :endpoint to be present")
  (assert (string? remote-host) "Expected :remote-host to be present")
  (assert (some? remote-port) "Expected :remote-port to be present")
  (let [port (server/start-server! proxy-state (select-keys cfg [:local-port :socket-timeout])
                                   (fn [cb-args] (handler cfg cb-args)))]
    (log/info "Yasp client running on port" @port)
    (when local-port-file
      (spit local-port-file (str @port)))
    (if block?
      @(promise)
      port)))
