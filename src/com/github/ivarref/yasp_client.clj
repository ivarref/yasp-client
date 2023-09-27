(ns com.github.ivarref.yasp-client
  (:refer-clojure :exclude [println])
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.github.ivarref.server :as server]
            [com.github.ivarref.yasp.tls :as tls]
            [com.github.ivarref.yasp.utils :as u]
            [babashka.process :as p]
            [babashka.process.pprint])
  (:import (java.io BufferedInputStream BufferedOutputStream)
           (java.lang AutoCloseable)
           (java.net Socket)))

(defonce proxy-state (atom {}))

(defn web-handler [{:keys [endpoint remote-host remote-port]}
                   {:keys [^Socket sock closed?]}]
  (log/info "Creating new connection")
  (let [{:keys [status] :as resp} (try
                                    (client/post endpoint
                                                 {:body               (json/generate-string {:op      "connect"
                                                                                             :payload (u/pr-str-safe {:host remote-host
                                                                                                                      :port remote-port})})
                                                  :content-type       :json
                                                  :socket-timeout     5000 ;; in milliseconds
                                                  :connection-timeout 3000 ;; in milliseconds
                                                  :accept             :json
                                                  :as                 :json})
                                    (catch Throwable t
                                      (log/error t "Error during creating connection")
                                      (throw t)))
        {:keys [res payload session]} (:body resp)]
    (cond
      (not= "ok-connect" res)
      (do
        (log/error "Could not connect, aborting. Response was:" res payload))

      :else
      (try
        (with-open [in (BufferedInputStream. (.getInputStream sock))
                    out (BufferedOutputStream. (.getOutputStream sock))]
          (loop []
            (let [chunk (u/read-max-bytes in 64000)]
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
                            (u/write-bytes (u/base64-str->bytes payload) out)
                            (recur))

                          :else
                          (do
                            (log/error "Client: Unhandled result" res)))))
                (do
                  (log/info "EOF from local connection")))))
          (log/debug "Session ending"))
        (finally
          (log/debug "Closing remote connection")
          (let [body (:body (client/post endpoint
                                         {:body               (json/generate-string {:op      "close"
                                                                                     :session session
                                                                                     :payload ""})
                                          :content-type       :json
                                          :socket-timeout     5000 ;; in milliseconds
                                          :connection-timeout 3000 ;; in milliseconds
                                          :accept             :json
                                          :as                 :json}))]
            (log/debug "Close remote connection" (get body :res) (get body :payload))))))))

(defn tls-handler [_cfg tls-context {:keys [^Socket sock]} dest-port]
  (with-open [^Socket tls-sock (tls/socket tls-context "127.0.0.1" dest-port 3000)]
    (let [fut (u/future (server/pump-socks tls-sock sock))]
      (server/pump-socks sock tls-sock)
      @fut)))

(defn fetch-remote-file [cmd-args]
  (let [{:keys [out exit]} @(p/process {:in  :inherit
                                        :out :string
                                        :err :inherit
                                        :cmd cmd-args})]
    (if (= 0 exit)
      out
      (do
        (log/error "Error code was" exit "for cmd" cmd-args)
        nil))))

(defn start-server!
  "Start a yasp client server (very concept).

  This server will bind to 127.0.0.1 at `local-port`.
  It will proxy incoming data over HTTP to `endpoint`, where
  a yasp web handler should be running.

  If `:tls-file` or `:tls-str` is given, the received data
  will be encrypted before sent over HTTP."
  ^AutoCloseable
  [{:keys [endpoint remote-host remote-port local-port tls-file tls-str tls-file-cmd local-port-file block?]
    :or   {local-port-file ".yasp-port"
           block?          true
           tls-file        :yasp/none
           tls-str         :yasp/none
           tls-file-cmd    :yasp/none}
    :as   cfg}]
  (assert (and (string? endpoint)
               (or
                 (str/starts-with? endpoint "http://")
                 (str/starts-with? endpoint "https://")))
          "Expected :endpoint to be present")
  (assert (string? remote-host) "Expected :remote-host to be present")
  (assert (some? remote-port) "Expected :remote-port to be present")
  (if (and (not= tls-file :yasp/none)
           (false? (.exists (io/file tls-file))))
    (do (log/error (str ":tls-file '" tls-file "' does not exist, exiting"))
        (log/error "Full path" (.getAbsolutePath (io/file tls-file)))
        nil)
    (let [tls-str (if (not= tls-file :yasp/none)
                    (slurp tls-file)
                    tls-str)
          tls-str (if (not= tls-file-cmd :yasp/none)
                    (fetch-remote-file tls-file-cmd)
                    tls-str)
          tls-context (when (not= tls-str :yasp/none)
                        (tls/ssl-context-or-throw tls-str nil))
          extra-forwarder (delay (server/start-server! proxy-state (assoc (select-keys cfg [:socket-timeout])
                                                                     :local-port 0)
                                                       (fn [cb-args] (web-handler cfg cb-args))))
          port (server/start-server! proxy-state (select-keys cfg [:local-port :socket-timeout])
                                     (fn [cb-args] (if (some? tls-context)
                                                     (tls-handler cfg tls-context cb-args @@extra-forwarder)
                                                     (web-handler cfg cb-args))))]
      ;(log/info "Yasp client running on port" @port)
      ;(log/info "TLS context is" tls-context)
      (when local-port-file
        (spit local-port-file (str @port)))
      (if block?
        (let [{:keys [body status]} (client/post endpoint
                                                 {:body               (json/generate-string {:op "ping"})
                                                  :content-type       :json
                                                  :socket-timeout     5000 ;; in milliseconds
                                                  :connection-timeout 3000 ;; in milliseconds
                                                  :accept             :json
                                                  :as                 :json
                                                  :throw              false})]
          (cond (not= 200 status)
                (do
                  (log/error "Got HTTP status when trying to ping endpoint" endpoint)
                  (log/error "Remote server is probably misconfigured")
                  (log/error "HTTP body response was:" body))

                (not= "pong" (get body :res))
                (do
                    (log/warn "Did not get proper pong reply. HTTP Body was:" body))

                :else
                (do
                  (log/info "Remote server ready at" endpoint)
                  (log/info "Accepting connections at" (str "127.0.0.1:" @port ",") "mTLS" (if (not= tls-str :yasp/none)
                                                                                             "enabled"
                                                                                             "disabled"))
                  @(promise))))
        (do
          ;(log/info "Returning port" @port)
          port)))))
