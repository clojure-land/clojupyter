(ns clojupyter.core
  (:require [beckon]
    [cheshire.core :as json]
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [clojupyter.misc.unrepl-comm :as unrepl-comm]
    [clojupyter.unrepl.elisions :as elisions]
    [clojupyter.misc.messages :as msg]
    [cheshire.core :as json]
    [clojure.stacktrace :as st]
    [clojure.walk :as walk]
    [clojure.core.async :as a]
    [clojure.string :as str]
    [taoensso.timbre :as log]
    [zeromq.zmq :as zmq]
    [net.cgrand.packed-printer :as pp]
    [clojure.tools.deps.alpha :as deps])
  (:import [java.net ServerSocket])
  (:gen-class :main true))

(defn prep-config [args]
  (-> args
      first
      slurp
      (json/parse-string keyword)))

(defn exception-handler [e]
  (log/error (with-out-str (st/print-stack-trace e 20))))

(defn parts-to-message [parts]
  (let [delim "<IDS|MSG>"
        delim-bytes (.getBytes delim "UTF-8")
        [idents [_ & more-parts]] (split-with #(not (java.util.Arrays/equals delim-bytes ^bytes %)) parts)
        blobs (map #(new String % "UTF-8") more-parts)
        blob-names [:signature :header :parent-header :metadata :content]
        message (merge
                 {:idents idents :delimiter delim}
                 (zipmap blob-names blobs)
                 {:buffers (drop (count blob-names) blobs)})]
    message))

#_(defn process-event [alive sockets socket key handler]
   (let [message        (parts-to-message (zmq/receive-all (sockets socket)))
         parsed-message (msg/parse-message message)
         parent-header  (:header parsed-message)
         session-id     (:session parent-header)]
     (send-message (:iopub-socket sockets) "status"
       {:execution_state "busy"} parent-header session-id {} key)
     (handler parsed-message)
     (send-message (:iopub-socket sockets) "status"
       {:execution_state "idle"} parent-header session-id {} key)))

(defmacro ^:private while-some [binding & body]
  `(loop []
     (when-some ~binding
       ~@body
       (recur))))

(def zmq-out
  (let [ch (a/chan)]
    (a/thread
      (while-some [args (a/<!! ch)]
        (try
          (apply msg/send-message args)
          (catch Exception e
            (prn 'FAIL args)))))
    ch))

(defn zmq-ch [socket]
  (let [ch (a/chan)]
    (a/thread
      (try
        (while (->> socket zmq/receive-all parts-to-message msg/parse-message (a/>!! ch)))
        (catch Exception e
          (exception-handler e))
        (finally
          (zmq/set-linger socket 0)
          (zmq/close socket))))
    ch))

(defn heartbeat-loop [alive hb-socket]
  (a/thread
    (try
      (while @alive
        (zmq/send hb-socket (zmq/receive hb-socket)))
      (catch Exception e
        (exception-handler e))
      (finally
        (zmq/set-linger hb-socket 0)
        (zmq/close hb-socket)))))

(defn address [config service]
  (str (:transport config) "://" (:ip config) ":" (service config)))

(defn aux-eval [{:keys [in]} form]
  (let [edn-out (a/chan 1 (filter (fn [[tag payload id]] (case tag (:eval :exception) true false))))]
    (a/go
      (when (some-> in (a/>! [(prn-str form) edn-out]))
        (a/<! edn-out)))))

(defn elision? [x]
  (and (tagged-literal? x) (= 'unrepl/... (:tag x))))

(defn elision-expand-1 [aux x]
  (cond
    (elision? x)
    (if-some [form (-> x :form :get)]
      (let [[tag payload] (a/<!! (aux-eval aux form))]
        (case tag
          :eval (recur aux payload)
          :exception (throw (ex-info "Error while resolving elision." {:ex payload}))))
      (throw (ex-info "Unresolvable elision" {})))
    
    (map? x)
    (if-some [e (get x (tagged-literal 'unrepl/... nil))]
      (-> x (dissoc (tagged-literal 'unrepl/... nil))
        (into (elision-expand-1 aux e)))
      x)
    
    (vector? x)
    (if-let [last (when-some [last (peek x)]
                    (and (elision? x) last))]
      (recur aux (into (pop x) (elision-expand-1 aux last)))
      x)
    
    (seq? x)
    (lazy-seq
      (when-some [s (seq x)]
        (let [x (first s)]
          (if (elision? x)
            (elision-expand-1 aux x)
            (cons x (elision-expand-1 aux (rest s)))))))
    
    :else x))

(defn elision-expand-all [aux x]
  (walk/prewalk
    (fn [x]
      (if (and (tagged-literal? x) (not (elision? x)))
        (tagged-literal (:tag x) (elision-expand-all aux (:form x)))
        (elision-expand-1 aux x)))
    x))

(defn is-complete?
  "Returns whether or not what the user has typed is complete (ready for execution).
   Not yet implemented. May be that it is just used by jupyter-console."
  [code]
  (try
    (or (re-matches #"\s*/(\S+)\s*(.*)" code) ; /command
      (read-string code))
    true
    (catch Exception _
      false)))

(defn handle-prompt [state payload]
  (when-some [ns (get payload 'clojure.core/*ns*)]
    (swap! state assoc :ns (:form ns))))

(defn framed-eval-process [code ctx state]
  (let [{:keys [execution-count repl aux]} (swap! state update :execution-count inc)
        edn-out (a/chan)]
    (a/go
      (a/>! ctx [:broadcast "execute_input" {:execution_count execution-count :code code}])
      (a/>! (:in repl) [code edn-out])
      (loop [done false]
        (if-some [[tag payload id :as msg] (a/<! edn-out)]
          (do (prn 'GOT2 msg)
            (case tag
             :prompt (do ; should not occur: prompts have no id
                       (handle-prompt state payload)
                       (when-not done (recur done)))
             :started-eval (do (swap! state assoc :interrupt-form (-> payload :actions :interrupt)) (recur done))
             :eval (let [_ (prn 'PAYLOAD payload)
                         extra-reps (when-some [{:keys [content-type content]} (and (tagged-literal? payload) (= 'unrepl/object (:tag payload))
                                                                                 (-> payload :form (nth 2) :attachment :form))]
                                      (let [content (elision-expand-all aux content)] 
                                        ; TODO fix: this assules content is set (could be file) and is unrepl/base64
                                        (prn 'MIME content-type content)
                                        {(or content-type "application/octet-stream") (:form content)}))]
                     (a/>! ctx [:broadcast "execute_result"
                                {:execution_count execution-count
                                 :data (into {:text/plain (with-out-str (pp/pprint payload :as :unrepl/edn :strict 20 :width 72))}
                                         extra-reps)
                                 :metadata {}
                                 :transient {}}])
                     (a/>! ctx [:reply {:status "ok"
                                        :execution_count execution-count
                                        :user_expressions {}}])
                     (recur true))
             :exception (let [error
                              {:status "error"
                               :ename "Oops"
                               :evalue ""
                               :execution_count execution-count
                               :traceback (let [{:keys [ex phase]} payload]
                                            [(str "Exception while " (case phase :read "reading the expression" :eval "evaluating the expression"
                                                                       :print "printing the result" "doing something unexpected") ".")
                                             (with-out-str (pp/pprint ex :as :unrepl/edn :strict 20 :width 72))])}]
                          (a/>! ctx [:broadcast "error" (dissoc error :status :execution_count)])
                          (a/>! ctx [:reply error])
                          (recur true))
             :out
             (do
               (a/>! ctx [:broadcast "stream" {:name "stdout" :text payload}])
               (recur done))
             :err
             (do
               (a/>! ctx ["stream" {:name "stderr" :text payload}])
               (recur done))
             (recur done)))
         (throw (ex-info "edn output from unrepl unexpectedly closed; the connection to the repl has probably been interrupted.")))))))

(defn action-call [form args-map]
  (walk/prewalk (fn [x]
                  (if (and (tagged-literal? x) (= 'unrepl/param (:tag x)))
                    (args-map (:form x))
                    x))
    form))

(defn- self-connector []
  (let [{in-writer :writer in-reader :reader} (unrepl-comm/pipe)
        {out-writer :writer out-reader :reader} (unrepl-comm/pipe)]
    (a/thread
      (binding [*out* out-writer *in* (clojure.lang.LineNumberingPushbackReader. in-reader)]
        (clojure.main/repl)))
    {:in in-writer
     :out out-reader}))

(defn- connect [state connector]
  (let [repl-in (a/chan)
        repl-out (a/chan)]
    (swap! state assoc :connector connector :repl nil :aux nil :class-loader nil)
    (unrepl-comm/unrepl-process (unrepl-comm/unrepl-connect connector) repl-in repl-out)
    (swap! state assoc :repl {:in repl-in :out repl-out})
    (a/go
      (while-some [[tag payload id] (a/<! repl-out)]
        (case tag
          :unrepl/hello
          (let [{:keys [start-aux :unrepl.jvm/start-side-loader] :as actions} (:actions payload)
                aux-in (a/chan)
                aux-out (a/chan)]
            (prn 'GOTHELLO)
            (swap! state assoc :actions actions)
            (when start-aux
              (prn 'STARTAUX)
              (unrepl-comm/unrepl-process (unrepl-comm/aux-connect connector start-aux) aux-in aux-out)
              (swap! state assoc :aux {:in aux-in :out aux-out})
              (a/go
                (prn 'STARTEDAUX)
                (while-some [[tag payload id] (a/<! aux-out)]
                  (prn 'AUX-DROPPED [tag payload id]))))
            (when start-side-loader
              (prn 'STARTSIDELOADER)
              (let [class-loader (clojure.lang.DynamicClassLoader. nil)
                    {:keys [^java.io.Writer in ^java.io.Reader out]} (connector)]
                (binding [*out* in] (prn start-side-loader)) ; send upgrade form
                (unrepl-comm/sideloader-loop in out class-loader)
                (swap! state assoc :class-loader class-loader))))
          
          :prompt (handle-prompt state payload)
          
          (prn 'DROPPED [tag payload id]))))))

(defn run-kernel [config]
  (let [hb-addr      (address config :hb_port)
       shell-addr   (address config :shell_port)
       iopub-addr   (address config :iopub_port)
       control-addr (address config :control_port)
       stdin-addr   (address config :stdin_port)
       key          (:key config)]
   (let [alive  (atom true)
         context (zmq/context 1)
         shell-socket (doto (zmq/socket context :router) (zmq/bind shell-addr))
         shell (zmq-ch shell-socket)
         control-socket (doto (zmq/socket context :router) (zmq/bind control-addr))
         control (zmq-ch control-socket)
         iopub-socket (doto (zmq/socket context :pub) (zmq/bind iopub-addr))
         stdin-socket (doto (zmq/socket context :router) (zmq/bind stdin-addr))
         stdin (zmq-ch stdin-socket)
         status-sleep 1000
         unrepl-comm (unrepl-comm/make-unrepl-comm)
         state (doto
                 (atom {:execution-count 1
                       :repl nil
                       :aux nil
                       :interrupt-form nil
                       :actions nil})
                 (connect self-connector))
         msg-context
         (fn [socket {{msg-type :msg_type session :session :as header} :header idents :idents :as request}]
           (let [ctx (a/chan)
                 zmq-msg (fn [[tag arg1 arg2]]
                           (case tag
                             :reply
                             (let [content arg1
                                   metadata (or arg2 {})
                                   [_ msg-prefix] (re-matches #"(.*)_request" msg-type)]
                               [socket (str msg-prefix "_reply")
                                content header session metadata key idents])
                             :broadcast
                             (let [msg-type arg1, content arg2]
                               [iopub-socket msg-type content header session {} key])))]
             (a/go
               (a/>! zmq-out (zmq-msg [:broadcast "status" {:execution_state "busy"}]))
               (while-some [msg (a/<! ctx)]
                 (a/>! zmq-out (zmq-msg msg)))
               (a/>! zmq-out (zmq-msg [:broadcast "status" {:execution_state "idle"}])))
             ctx))
         ;; WIP : I'm in the middle of turning shell-handler into porcesses to allow
         ;; concurrent handling of interrupt and eval
         ;; thus serialization should only occur around repl connections
         shell-handler
         (fn [socket]
           (let [msgs-ch (a/chan)]
             (a/go-loop []
               (when-some [{{msg-type :msg_type session :session :as header} :header idents :idents :as request} (a/<! msgs-ch)]
                 (let [ctx (msg-context socket request)]
                   (try
                     (case msg-type
                       "execute_request"
                       (let [code (get-in request [:content :code])
                             silent (str/ends-with? code ";")
                             [_ command args] (re-matches #"(?s)\s*/(\S+?)([\s,\[{(].*)?" code)
                             elided (some-> command elisions/lookup :form :get)]
                         (if (or (nil? command) elided)
                           (framed-eval-process (prn-str (or elided `(eval (read-string ~code)))) ctx state)
                           (let [{:keys [execution-count]} (swap! state update :execution-count inc)]
                             (case command
                               "connect" (let [args (re-seq #"\S+" args)]
                                           (try
                                             (let [[_ host port inner] (re-matches #"(?:(?:(\S+):)?(\d+)|(-))" (first args))]
                                               (connect state (if inner
                                                                self-connector
                                                                #(let [socket (java.net.Socket. ^String host (Integer/parseInt port))]
                                                                   {:in (-> socket .getOutputStream io/writer)
                                                                    :out (-> socket .getInputStream io/reader)})))
                                               (a/>! ctx [:broadcast "stream" {:name "stdout" :text "Successfully connected!"}]))
                                             (catch Exception e
                                               (a/>! ctx [:broadcast "stream" {:name "stderr" :text "Failed connection."}])))
                                           (a/>! ctx [:reply {:status "ok"
                                                              :execution_count execution-count
                                                              :user_expressions {}}]))

                               "cp" (try
                                      (let [arg (edn/read-string args)
                                            arg (if (seq? arg)
                                                  (let [{:keys [ns aux]} @state
                                                        [tag payload] (a/<! (aux-eval aux `(do (in-ns '~ns) ~arg)))]
                                                    (case tag
                                                      :eval (elision-expand-all aux payload)
                                                      :exception (throw (ex-info "Exception on aux." {:form arg :payload payload}))))
                                                  arg)
                                            paths
                                            (cond
                                              (map? arg)
                                              (let [deps (if (every? symbol? (keys arg))
                                                           {:deps arg}
                                                           arg)
                                                    libs (deps/resolve-deps deps {})]
                                                (into [] (mapcat :paths) (vals libs)))
                                              
                                              (string? arg) [arg]
                                              :else (throw (IllegalArgumentException. (str "Unsupported /cp argument: " arg))))]
                                        (doseq [path paths]
                                          (.addURL ^clojure.lang.DynamicClassLoader (:class-loader @state) (-> path java.io.File. .toURI .toURL)))
                                        (a/>! ctx [:broadcast "stream" {:name "stdout" :text (str paths " added to the classpath!")}])
                                        {:result "nil"})
                                      (catch Exception e
                                        (prn 'FAIL e)
                                        (a/>! ctx [:broadcast "stream" {:name "stderr" :text "Something unexpected happened."}])
                                        {:result "nil"}))
                               
                              (do ; default
                                (a/>! ctx [:broadcast "stream" {:name "stderr" :text (str "Unknown command: /" command ".")}])
                                (a/>! ctx [:reply {:status "ok"
                                                   :execution_count execution-count
                                                   :user_expressions {}}]))))))
                       
                       "kernel_info_request"
                       (a/>! ctx [:reply (msg/kernel-info-content)])

                       "shutdown_request"
                       (do
                         (reset! alive false)
                         #_(nrepl.server/stop-server server)
                         (a/>! ctx [:reply {:status "ok" :restart false}])
                         (Thread/sleep 100)) ; magic timeout! TODO fix
                
                      ; COMMs were not handled anyway
                      ; see http://jupyter-notebook.readthedocs.io/en/stable/comms.html
                      ; and http://jupyter-client.readthedocs.io/en/stable/messaging.html
                      #_#_"comm_info_request"
                        (send-message socket "comm_info_reply"
                          {:comms {:comm_id {:target_name ""}}} header session {} key) ; no idents?
                      #_#_"comm_msg"
                        (send-message socket "comm_msg_reply"
                          {} header session {} key)
                      #_#_"comm_open"           (comm-open-reply   sockets
                                                  socket message key)
              
                      "is_complete_request"
                      (a/>! ctx [:reply {:status (if (-> request :content :code is-complete?) "complete" "incomplete")}])

                      "complete_request"
                      (let [{{:keys [complete]} :actions aux :aux ns :ns} @state
                            {:keys [code cursor_pos]} (:content request)
                            left (subs code 0 cursor_pos)
                            right (subs code cursor_pos)
                            [tag payload] (a/<! (aux-eval aux (action-call complete {:unrepl.complete/ns (list 'quote ns)
                                                                                     :unrepl.complete/before left
                                                                                     :unrepl.complete/after right})))
                            payload (elision-expand-all aux (case tag :eval payload nil))
                            _ (prn payload)
                            max-left-del (transduce (map :left-del) max 0 payload)
                            max-right-del (transduce (map :right-del) max 0 payload)
                            candidates (map 
                                         (fn [{:keys [candidate left-del right-del]}]
                                           (str (subs left (- cursor_pos (- max-left-del left-del)))
                                             candidate
                                             (subs right right-del max-right-del)))
                                         payload)]
                        (a/>! ctx
                          [:reply
                           {:matches candidates
                            :cursor_start (- cursor_pos max-left-del)
                            :cursor_end (+ cursor_pos max-right-del)
                            :status "ok"}]))
                      
                      "interrupt_request"
                      (let [{aux :aux :keys [interrupt-form]} @state]
                        (a/<! (aux-eval aux interrupt-form)))
                      
                      (do
                        (log/error "Message type" msg-type "not handled yet. Exiting.")
                        (log/error "Message dump:" request)
                        (System/exit -1)))
                     (finally
                       (a/>! ctx [:broadcast "status" {:execution_state "idle"}]))))
               (recur)))
             msgs-ch))
         shell-process (shell-handler shell-socket)
         control-process (shell-handler control-socket)]
      
     (heartbeat-loop alive (doto (zmq/socket context :rep) (zmq/bind hb-addr)))
      
     (a/go-loop [state {}]
       (a/alt!
         shell ([request] (prn 'SHELL) (a/>! shell-process request))
         control ([request] (prn 'CONTROL) (a/>! control-process request))
         #_#_iopub 
         ([{{msg-type :msg_type} :header :as request}]
           (case msg-type
             #_#_"input_reply" TODO
                            
             (do
               (log/error "Message type" msg-type "not handled yet. Exiting.")
               (log/error "Message dump:" message)
               (System/exit -1)))))
       (recur state))
      
     #_(try
        (reset! (beckon/signal-atom "INT") #{(fn [] #_(pp/pprint (pnrepl/nrepl-interrupt nrepl-comm)))})
        (control-loop   alive sockets nrepl-comm key)
        ;; check every second if state
        ;; has changed to anything other than alive
        (while @alive (Thread/sleep status-sleep))
        (catch Exception e
          (exception-handler e))
        (finally (doseq [socket [shell-socket iopub-socket control-socket hb-socket]]
                   (zmq/set-linger socket 0)
                   (zmq/close socket))
                 (System/exit 0))))))

(defn -main [& args]
  (log/set-level! :error)
  (run-kernel (prep-config args)))

#_(defn bg-process
   "new-pending is a channel upon which triples [ch v resource] are sent
   when the write succeeds, resource will be put on the release channel.   "
   [new-pending release]
   ; pending-by-ch is the internal state, it's a map of channels to a collection
   ; of pending writes each pending write being a pair [value-to-write resource-to-release]
   (a/go-loop [pending-by-ch {}]
     (let [ops (into [new-pending]
                 (for [[ch [[v]]] pending-by-ch]
                   [ch v]))
           [v ch] (a/alts! ops)]
       (if (= ch new-pending)
         ; new pending write
         (let [[ch v resource] v]
           (recur (update pending-by-ch (fnil conj clojure.lang.PersistentQueue/EMPTY) [v resource])))
         ; a pending write succeeded!
         (let [q (pending-by-ch ch)
               [_ res] (peek q)
               q (pop q)]
           (a/>! release res)
           (recur (if (seq q)
                    (assoc pending-by-ch ch q)
                    (dissoc pending-by-ch ch))))))))
