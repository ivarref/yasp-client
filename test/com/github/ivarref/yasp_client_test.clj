(ns com.github.ivarref.yasp-client-test
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [cheshire.core :as json]
            [clj-commons.pretty.repl]
            [clojure.test :as t]
            [com.github.ivarref.server :as s]
            [com.github.ivarref.yasp :as yasp]
            [com.github.ivarref.yasp-client :as yasp-client])
  (:import (java.io BufferedOutputStream BufferedReader InputStream InputStreamReader PrintWriter)
           (java.lang AutoCloseable)
           (java.net InetAddress InetSocketAddress Socket)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(clj-commons.pretty.repl/install-pretty-exceptions)

(defn handler [port req]
  {:status  200
   :headers {"content-type" "application/json"}
   :body    (json/generate-string (yasp/proxy!
                                    {:socket-timeout 10
                                     :allow-connect? (fn [connect-request]
                                                       (and
                                                         (= "localhost" (:host connect-request))
                                                         (= port (:port connect-request))))}
                                    (json/decode-stream (InputStreamReader. ^InputStream (:body req) StandardCharsets/UTF_8) keyword)))})

(t/deftest round-trip-test
  (let [cfg {:socket-timeout 10}]
    (with-open [echo-server (s/start-server! (atom {}) cfg s/echo-handler)
                ^AutoCloseable ws (http/start-server (partial handler @echo-server) {:socket-address (InetSocketAddress. (InetAddress/getLoopbackAddress) 0)})
                client-server (yasp-client/start-client!
                                (merge cfg
                                       {:endpoint    (str "http://localhost:" (netty/port ws)
                                                          "/proxy")
                                        :remote-host "localhost"
                                        :remote-port @echo-server}))]
      (let [client-port @client-server]
        (with-open [sock (Socket.)]
          (.setSoTimeout sock 3000)
          (.connect sock (InetSocketAddress. "localhost" ^Integer client-port))
          (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8))
                      out (PrintWriter. (BufferedOutputStream. (.getOutputStream sock)) true StandardCharsets/UTF_8)]
            (.println out "Hello World!")
            (t/is (= "Hello World!" (.readLine in)))

            (.println out "Hallo, 你好世界")
            (t/is (= "Hallo, 你好世界" (.readLine in)))))))))
