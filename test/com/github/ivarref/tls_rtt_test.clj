(ns com.github.ivarref.tls-rtt-test
  (:require [aleph.http :as http]
            [aleph.netty :as netty]
            [cheshire.core :as json]
            [clj-commons.pretty.repl]
            [clojure.test :as t]
            [com.github.ivarref.locksmith :as lm]
            [com.github.ivarref.server :as s]
            [com.github.ivarref.yasp :as yasp]
            [com.github.ivarref.yasp-client :as yasp-client]
            [com.github.ivarref.yasp.utils :as u])
  (:import (java.io BufferedOutputStream BufferedReader InputStream InputStreamReader PrintWriter)
           (java.lang AutoCloseable)
           (java.net InetSocketAddress Socket)
           (java.nio.charset StandardCharsets)))

(set! *warn-on-reflection* true)

(clj-commons.pretty.repl/install-pretty-exceptions)

(defn web-handler [tls-str new-state server-port {:keys [uri body]}]
  (if (= "/proxy" uri)
    {:status  200
     :headers {"content-type" "application/json"}
     :body    (json/generate-string (yasp/proxy!
                                      {:allow-connect? #{{:port server-port :host "localhost"}}
                                       :tls-str        tls-str
                                       :state          new-state}
                                      (json/decode-stream (InputStreamReader. ^InputStream body StandardCharsets/UTF_8) keyword)))}
    {:status  404
     :headers {"content-type" "text/plain"}
     :body    "Not found"}))

(t/use-fixtures :each u/with-fut)

(defn gen-key-pair []
  (let [{:keys [ca-cert server-cert server-key client-cert client-key]} (lm/gen-certs {:duration-days 1})]
    [(str ca-cert server-cert server-key)
     (str ca-cert client-cert client-key)]))

(t/deftest tls-rtt-test
  (let [ks (gen-key-pair)
        yasp-state (atom {})]
    (with-open [echo-server (s/start-server! (atom {}) {} s/echo-handler)
                ^AutoCloseable ws (http/start-server (partial web-handler (first ks) yasp-state @echo-server)
                                                     {:socket-address (InetSocketAddress. "127.0.0.1" 0)})
                client (yasp-client/start-server! {:endpoint    (str "http://localhost:" (netty/port ws) "/proxy")
                                                   :remote-host "localhost"
                                                   :remote-port @echo-server
                                                   :tls-str     (second ks)
                                                   :block?      false})]
      (t/is (= ::none (get @yasp-state :tls-verified? ::none)))
      (with-open [sock (Socket.)]
        (.setSoTimeout sock 1000)
        (.connect sock (InetSocketAddress. "localhost" ^Integer (deref client)))
        (with-open [in (BufferedReader. (InputStreamReader. (.getInputStream sock) StandardCharsets/UTF_8))
                    out (PrintWriter. (BufferedOutputStream. (.getOutputStream sock)) true StandardCharsets/UTF_8)]
          (.println out "Hello World!")
          (t/is (= "Hello World!" (.readLine in)))
          (t/is (true? (get @yasp-state :tls-verified?)))

          (dotimes [_ 1000]
            (.println out "Hallo, 你好世界")
            (t/is (= "Hallo, 你好世界" (.readLine in)))))))))
