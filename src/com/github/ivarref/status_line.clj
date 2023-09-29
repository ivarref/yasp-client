(ns com.github.ivarref.status-line
  (:import (clojure.lang IFn)
           (java.io Closeable)
           (org.jline.terminal TerminalBuilder)
           (org.slf4j.simple SimpleLogger)))

(defonce lock
         (let [field (.getDeclaredField SimpleLogger "CONFIG_PARAMS")]
           (.setAccessible field true)
           (.get field nil)))

(defn write [s]
  (locking lock
    (binding [*out* *err*]
      (print s)
      (flush))))

(defn close! [lines]
  (locking lock
    (write "\0337")                                         ; save cursor
    (write (str "\033[0;" lines "r"))                       ; drop margin
    (write (str "\033[" lines ";0f"))                       ; to bottom line
    (write "\033[0K")                                       ; clear line
    (write "\0338")))

(defn status-line-init []
  (let [lines (.getHeight (TerminalBuilder/terminal))]
    (locking lock
      (write "\n")
      (write "\0337")                                       ; save cursor position
      (write (str "\033[0;" (dec lines) "r"))               ; Reserve the bottom line
      (write "\0338")                                       ; restore cursor position
      (write "\033[1A"))                                    ; move up one line
    (.addShutdownHook
      (Runtime/getRuntime)
      (Thread. ^Runnable (fn [] (close! lines))))
    (reify
      IFn
      (invoke [_ arg]
        (locking lock
          (write "\0337")                                   ; save cursor
          (write (str "\033[" lines ";0f"))                 ; move to bottom
          (write (str arg))                                 ; write argument
          (write "\033[0K")
          (write "\0338")))                                 ; restore cursor
      Closeable
      (close [_]
        (close! lines)))))
