(ns com.github.ivarref.yasp-client
  (:refer-clojure :exclude [println])
  (:require [babashka.process :as p]
            [babashka.process.pprint]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.github.ivarref.server :as server]
            [com.github.ivarref.status-line :as sl]
            [com.github.ivarref.yasp.tls :as tls]
            [com.github.ivarref.yasp.utils :as u])
  (:import (java.io BufferedInputStream BufferedOutputStream)
           (java.lang AutoCloseable)
           (java.net Socket)
           (java.util Date)))

(defonce proxy-state (atom {}))

(defn web-handler [{:keys [endpoint remote-host remote-port stats]}
                   {:keys [^Socket sock closed?]}]
  (log/info "Creating new connection")
  (swap! stats update :web-handlers (fnil inc 0))
  (try
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
              (let [chunk (u/read-max-bytes in 65536)]
                (if chunk
                  (do
                    (if (pos-int? (count chunk))
                      (log/debug "Send" (count chunk) "bytes over HTTP")
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
                      (swap! stats update :sent (fnil + 0) (count chunk))
                      (cond (= "eof" res)
                            (log/info "Remote EOF, closing connection")

                            (= "unknown-session" res)
                            (log/debug "Remote unknown session, closing connection")

                            (= "ok-send" res)
                            (do
                              (let [recv-bytes (u/base64-str->bytes payload)]
                                (swap! stats update :recv (fnil + 0) (count recv-bytes))
                                (when (pos-int? (count recv-bytes))
                                  (log/debug "Recv" (count recv-bytes) "bytes over HTTP"))
                                (u/write-bytes recv-bytes out))
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
              (log/debug "Close remote connection" (get body :res) (get body :payload)))))))
    (finally
      (swap! stats update :web-handlers (fnil dec 1)))))

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
    (with-open [set-status-line! (sl/status-line-init)]
      (let [tls-str (if (not= tls-file :yasp/none)
                      (slurp tls-file)
                      tls-str)
            tls-str (if (not= tls-file-cmd :yasp/none)
                      (fetch-remote-file tls-file-cmd)
                      tls-str)
            stats (atom {})
            cfg (assoc cfg :stats stats)
            tls-context (when (not= tls-str :yasp/none)
                          (tls/ssl-context-or-throw tls-str nil))
            extra-forwarder (delay (server/start-server! proxy-state (assoc (select-keys cfg [:socket-timeout])
                                                                       :local-port 0)
                                                         (fn [cb-args] (web-handler cfg cb-args))))
            port (server/start-server! proxy-state (select-keys cfg [:local-port :socket-timeout])
                                       (fn [cb-args] (if (some? tls-context)
                                                       (tls-handler cfg tls-context cb-args @@extra-forwarder)
                                                       (web-handler cfg cb-args))))]
        (add-watch stats :watcher
                   (fn [_key _ref _old {:keys [web-handlers recv sent]
                                        :or   {web-handlers 0
                                               recv         0
                                               sent         0}}]
                     (set-status-line! (str/join " "
                                                 [(str (Date.))
                                                  "web handlers:" web-handlers
                                                  "sent:" sent
                                                  "recv" recv]))))
        (future
          (loop []
            (Thread/sleep 1000)
            (swap! stats update :tick (fnil inc 0))
            (recur)))
        (when local-port-file
          (spit local-port-file (str @port)))
        (if block?
          (let [{:keys [body status throwable]} (try
                                                  (client/post endpoint
                                                               {:body               (json/generate-string {:op "ping"})
                                                                :content-type       :json
                                                                :socket-timeout     5000 ;; in milliseconds
                                                                :connection-timeout 3000 ;; in milliseconds
                                                                :accept             :json
                                                                :as                 :json
                                                                :throw-exceptions   false})
                                                  (catch Throwable t
                                                    {:throwable t}))]
            (cond
              (some? throwable)
              (do
                (log/error "Could not ping endpoint" (str "'" endpoint "'"))
                (log/error "Error message:" (ex-message throwable))
                (log/error "Is the remote server running?"))

              (not= 200 status)
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
                (reset! stats {})
                @(promise))))
          (do
            ;(log/info "Returning port" @port)
            port))))))
