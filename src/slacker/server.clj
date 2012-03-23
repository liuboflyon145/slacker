(ns slacker.server
  (:use [slacker common serialization protocol])
  (:use [slacker.server http cluster])
  (:use [slacker.acl.core])
  (:use [link core tcp http])
  (:use [slingshot.slingshot :only [try+]])
  (:require [zookeeper :as zk])
  (:import [java.util.concurrent Executors]))

;; pipeline functions for server request handling
(defn- map-req-fields [req]
  (zipmap [:packet-type :content-type :fname :data] req))

(defn- look-up-function [req funcs]
  (if-let [func (funcs (:fname req))]
    (assoc req :func func)
    (assoc req :code :not-found)))

(defn- deserialize-args [req]
  (if (nil? (:code req))
    (let [data (:data req)]
      (assoc req :args
             (deserialize (:content-type req) data)))
    req))

(defn- do-invoke [req]
  (if (nil? (:code req))
    (try+
      (let [{f :func args :args} req
            r0 (apply f args)
            r (if (seq? r0) (doall r0) r0)]
        (assoc req :result r :code :success))
      (catch Exception e
        (if-not *debug*
          (assoc req :code :exception :result (.toString e))
          (assoc req :code :exception
                 :result {:msg (.getMessage e)
                          :stacktrace (.getStackTrace e)}))))
    req))

(defn- serialize-result [req]    
  (if-not (nil? (:result req))
    (assoc req :result (serialize (:content-type req) (:result req)))
    req))

(defn- map-response-fields [req]
  [version (map req [:packet-type :content-type :code :result])])

(def pong-packet [version [:type-pong]])
(def protocol-mismatch-packet [version [:type-error :protocol-mismatch]])
(def invalid-type-packet [version [:type-error :invalid-packet]])
(def acl-reject-packet [version [:type-error :acl-rejct]])
(defn make-inspect-ack [data]
  [version [:type-inspect-ack
            (serialize :clj data :string)]])

(defn build-server-pipeline [funcs interceptors]
  #(-> %
       (look-up-function funcs)
       deserialize-args
       ((:before interceptors))
       do-invoke
       ((:after interceptors))
       serialize-result
       (assoc :packet-type :type-response)))

(defn build-inspect-handler [funcs]
  #(let [[_ cmd data] %
         data (deserialize :clj data :string)]
     (make-inspect-ack
      (case cmd
        :functions
        (let [nsname (or data "")]
          (filter (fn [x] (.startsWith x nsname)) (keys funcs)))
        :meta
        (let [fname data
              metadata (meta (funcs fname))]
          (select-keys metadata [:name :doc :arglists]))
        nil))))

(defmulti -handle-request (fn [_ p & _] (first p)))
(defmethod -handle-request :type-request [server-pipeline req client-info _]
  (let [req-map (assoc (map-req-fields req) :client client-info)]
    (map-response-fields (server-pipeline req-map))))
(defmethod -handle-request :type-ping [& _]
  pong-packet)
(defmethod -handle-request :type-inspect-req [_ p _ inspect-handler]
  (inspect-handler p))
(defmethod -handle-request :default [& _]
  invalid-type-packet)

(defn handle-request [server-pipeline req client-info inspect-handler acl]
  (cond
   ;; acl enabled
   (and (not (nil? acl))
        (not (authorize client-info acl)))
   acl-reject-packet
   
   ;; handle request
   (= version (first req))
   (-handle-request server-pipeline (second req)
                    client-info inspect-handler)

   ;; version mismatch
   :else protocol-mismatch-packet))

(defn- create-server-handler [funcs interceptors acl debug]
  (let [server-pipeline (build-server-pipeline funcs interceptors)
        inspect-handler (build-inspect-handler funcs)]
    (fn [ch client-info]
      (receive-all
       ch
       #(if-let [req %]
          (enqueue ch
                   (binding [*debug* debug]
                     (handle-request server-pipeline
                                     req
                                     client-info
                                     inspect-handler
                                     acl))))))))


(defn- ns-funcs [n]
  (let [nsname (ns-name n)]
    (into {}
          (for [[k v] (ns-publics n) :when (fn? @v)]
            [(str nsname "/" (name k)) v]))))


(defn start-slacker-server
  "Start a slacker server to expose all public functions under
  a namespace. If you have multiple namespace to expose, it's better
  to combine them into one.
  Options:
  * interceptors add server interceptors
  * http http port for slacker http transport
  * cluster publish server information to zookeeper
  * acl the acl rules defined by defrules"
  [exposed-ns port
   & {:keys [http interceptors cluster acl]
      :or {http nil
           interceptors {:before identity :after identity}
           cluster nil
           acl nil}}]
  (let [exposed-ns (if (coll? exposed-ns) exposed-ns [exposed-ns])
        funcs (apply merge (map ns-funcs exposed-ns))
        handler (create-server-handler funcs interceptors acl *debug*)
        worker-pool (Executors/newCachedThreadPool)]
    
    (when *debug* (doseq [f (keys funcs)] (println f)))
    
    (tcp-server port handler 
                :codec slacker-base-codec
                :worker-pool worker-pool)
    (when-not (nil? http)
      (http-server http (wrap-http-server-handler
                         (build-server-pipeline funcs interceptors))
                   :worker-pool worker-pool))
    (when-not (nil? cluster)
      (with-zk (zk/connect (:zk cluster))
        (publish-cluster cluster port
                         (map ns-name exposed-ns) funcs)))))


