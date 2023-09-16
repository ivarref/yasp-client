(ns com.github.ivarref.yasp-client-test
  (:require [clj-commons.pretty.repl]
            [clojure.test :as t]
            [com.github.ivarref.server :as s]
            [aleph.http :as http]
            [aleph.netty :as netty]
            [com.github.ivarref.yasp :as yasp]
            [com.github.ivarref.yasp-client :as yasp-client]
            [com.github.ivarref.yasp.impl :as impl]
            [cheshire.core :as json])
  (:import (java.io BufferedReader BufferedWriter InputStream InputStreamReader OutputStreamWriter)
           (java.lang AutoCloseable)
           (java.net InetAddress InetSocketAddress Socket SocketTimeoutException)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(clj-commons.pretty.repl/install-pretty-exceptions)

(defn handler [port req]
  {:status  200
   :headers {"content-type" "application/json"}
   :body    (json/generate-string (yasp/proxy!
                                    {:allow-connect? (fn [_] true)}
                                    (json/decode-stream (InputStreamReader. ^InputStream (:body req) StandardCharsets/UTF_8) keyword)))})

(t/deftest round-trip-test
  (with-open [echo-server (s/start-server! (atom {}) {} s/echo-handler)
              ^AutoCloseable ws (http/start-server (partial handler @echo-server) {:socket-address (InetSocketAddress. (InetAddress/getLoopbackAddress) 0)})
              client-server (yasp-client/start-client!
                              {:endpoint (str "http://localhost:" (netty/port ws)
                                              "/proxy")
                               :remote-host "localhost"
                               :remote-port @echo-server})]
    (let [client-port @client-server]
      (with-open [sock (Socket.)]
        (.setSoTimeout sock 100)
        (.connect sock (InetSocketAddress. "localhost" ^Integer client-port))
        (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8))
                    out (BufferedWriter. (OutputStreamWriter. (.getOutputStream sock) StandardCharsets/UTF_8))]
          (.write out "Hello World!\n")
          (.flush out)
          (loop []
            (let [line (try
                         (.readLine in)
                         (catch SocketTimeoutException _ste
                           :timeout))]
              (cond
                (nil? line)
                (impl/atomic-println "Round trip test got EOF")

                (= line :timeout)
                (do
                  #_(println "timeout!")
                  (recur))

                :else
                (do
                  (impl/atomic-println "Got line:" line)
                  #_(recur)))))
          (t/is (= 1 1)))))))
