(ns slacker.client.common
  (:refer-clojure :exclude [send])
  (:use [clojure.string :only [split]])
  (:use [slacker serialization common protocol])
  (:use [slacker.client.state])
  (:use [link.core :exclude [close]])
  (:use [link.tcp])
  (:use [slingshot.slingshot :only [throw+ try+]])
  (:require [clojure.tools.logging :as log])
  (:import [java.net ConnectException InetSocketAddress InetAddress]
           [java.nio.channels ClosedChannelException]
           [java.util.concurrent ScheduledThreadPoolExecutor
            TimeUnit ScheduledFuture]
           [clojure.lang IDeref IBlockingDeref IPending]))

(defn- handle-valid-response [response]
  (let [[content-type code data] (second response)]
    (case code
      :success {:result (deserialize content-type data)}
      :not-found {:cause {:code code}}
      :exception {:cause {:code code :error (deserialize content-type data)}}
      {:cause {:code :invalid-result-code}})))

(defn make-request [tid content-type func-name params]
  (let [serialized-params (serialize content-type params)]
    [version tid [:type-request [content-type func-name serialized-params]]]))

(def ping-packet [version 0 [:type-ping]])

(defn make-inspect-request [tid cmd args]
  [version tid [:type-inspect-req
                [cmd (serialize :clj args :string)]]])

(defn parse-inspect-response [response]
  {:result (deserialize :clj (-> response
                                 second
                                 first)
                        :string)})

(defn handle-response [response]
  (case (first response)
    :type-response (handle-valid-response response)
    :type-inspect-ack (parse-inspect-response response)
    :type-pong (log/debug "pong")
    :type-error {:cause {:code (-> response second first)}}
    nil))

(defprotocol SlackerClientProtocol
  (sync-call-remote [this ns-name func-name params options])
  (async-call-remote [this ns-name func-name params cb options])
  (inspect [this cmd args])
  (ping [this])
  (close [this]))

(defn- channel-hostport [ch]
  (let [addr (link.core/remote-addr ch)]
    (str (.getHostAddress ^InetAddress
                          (.getAddress ^InetSocketAddress addr))
         ":" (.getPort ^InetSocketAddress addr))))

(defn- next-trans-id [trans-id-gen]
  (swap! trans-id-gen unchecked-inc))

(deftype SlackerClient [conn rmap trans-id-gen content-type]
  SlackerClientProtocol
  (sync-call-remote [this ns-name func-name params options]
    (let [fname (str ns-name "/" func-name)
          tid (next-trans-id trans-id-gen)
          request (make-request tid content-type fname params)
          prms (promise)]
      (swap! rmap assoc tid {:promise prms})
      (send conn request)
      (deref prms *timeout* nil)
      (if (realized? prms)
        @prms
        (do
          (swap! rmap dissoc tid)
          {:cause {:error :timeout}}))))
  (async-call-remote [this ns-name func-name params sys-cb options]
    (let [fname (str ns-name "/" func-name)
          tid (next-trans-id trans-id-gen)
          request (make-request tid content-type fname params)
          prms (promise)]
      (swap! rmap assoc tid {:promise prms :callback sys-cb :async? true})
      (send conn request)
      prms))
  (inspect [this cmd args]
    (let [tid (next-trans-id trans-id-gen)
          request (make-inspect-request tid cmd args)
          prms (promise)]
      (swap! rmap assoc tid {:promise prms :type :inspect})
      (send conn request)
      (deref prms *timeout* nil)
      (if (realized? prms)
        @prms
        (do
          (swap! rmap dissoc tid)
          {:cause {:error :timeout}}))))
  (ping [this]
    (send conn ping-packet)
    (log/debug "ping"))
  (close [this]
    (let [k (channel-hostport conn)]
      (when (<= (swap! (:refs (@server-requests k)) dec) 0)
        (swap! server-requests dissoc k)))
    (link.core/close conn)))

(defn- create-link-handler
  "The event handler for client"
  []
  (create-handler
   (on-message [ch msg]
               (when-let [rmap (-> ch
                                   (channel-hostport)
                                   (@server-requests)
                                   :pendings)]
                 (let [tid (second msg)
                       msg-body (nth msg 2)
                       handler (get @rmap tid)]
                   (swap! rmap dissoc tid)
                   (let [result (handle-response msg-body)]
                     (if-not (nil? handler)
                       (do
                         (deliver (:promise handler) result)
                         (when (:async? handler)
                           (when-let [cb (:callback handler)]
                             (cb result))))
                       ;; pong
                       result)))))
   (on-error [ch ^Exception exc]
             (if (or
                  (instance? ConnectException exc)
                  (instance? ClosedChannelException exc))
               (log/warn "Failed to connect to server or connection lost.")
               (log/error exc "Unexpected error in event loop")))))

(def ^:dynamic *options*
  {:tcp-nodelay true
   :so-reuseaddr true
   :so-keepalive true
   :write-buffer-high-water-mark (int 0xFFFF) ; 65kB
   :write-buffer-low-water-mark (int 0xFFF)       ; 4kB
   :connect-timeout-millis (int 5000)})

(defn create-client-factory [ssl-context]
  (let [handler (create-link-handler)]
    (tcp-client-factory handler
                        :codec slacker-base-codec
                        :options *options*
                        :ssl-context ssl-context)))

(defn create-client [client-factory host port content-type]
  (let [client (tcp-client client-factory host port :lazy-connect true)
        k (str host ":" port)]
    (if-not (@server-requests k)
      (swap! server-requests assoc k
             {:pendings (atom {})
              :idgen (atom (long 0))
              :refs (atom 1)})
      (swap! (-> @server-requests (get k) :refs) inc))
    (SlackerClient. client
                    (:pendings (@server-requests k))
                    (:idgen (@server-requests k))
                    content-type)))

(defn- parse-exception [einfo]
  (doto (Exception. ^String (:msg einfo))
    (.setStackTrace (:stacktrace einfo))))

(defn user-friendly-cause [call-result]
  (when (:cause call-result)
    (if (and (= :exception (-> call-result :cause :code))
             (map? (-> call-result :cause :error)))
      (parse-exception (-> call-result :cause :error))
      (:cause call-result))))

(defn process-call-result [call-result]
  (if (nil? (:cause call-result))
    (:result call-result)
    (throw+ (user-friendly-cause call-result))))

(deftype ExceptionEnabledPromise [prms]
  IDeref
  (deref [_]
    (process-call-result @prms))
  IBlockingDeref
  (deref [_ timeout timeout-var]
    (deref prms timeout nil)
    (if (realized? prms)
      (deref _)
      timeout-var))
  IPending
  (isRealized [_]
    (realized? prms)))

(defn exception-enabled-promise
  ([] (exception-enabled-promise (promise)))
  ([prms] (ExceptionEnabledPromise. prms)))

(def ^:dynamic *sc* nil)
(defn invoke-slacker
  "Invoke remote function with given slacker connection.
  A call-info tuple should be passed in. Usually you don't use this
  function directly. You should define remote call facade with defremote"
  [sc remote-call-info
   & {:keys [async? callback]
      :or {async? false callback nil}
      :as options}]
  (let [sc @(or *sc* sc)  ;; allow local binding to override client
        [nsname fname args] remote-call-info]
    (if (or async? (not (nil? callback)))
      ;; async
      (let [sys-cb (fn [call-result]
                     (let [cb (or callback (constantly true))]
                       (cb (assoc call-result
                             :cause (user-friendly-cause call-result)))))]
        (exception-enabled-promise
         (async-call-remote sc nsname fname args sys-cb options)))

      ;; sync
      (process-call-result (sync-call-remote sc nsname fname args options)))))

(defn meta-remote
  "get metadata of a remote function by inspect api"
  [sc f]
  (let [fname (if (fn? f)
                (name (:name (meta f)))
                (str f))]
    (let [call-result (inspect @sc :meta fname)]
      (if (nil? (:cause call-result))
        (:result call-result)
        (throw+ (:cause call-result))))))

(defn functions-remote
  "get functions of a remote namespace"
  [sc n]
  (let [call-result (inspect @sc :functions n)]
    (if (nil? (:cause call-result))
      (:result call-result)
      (throw+ (:cause call-result)))))

(defn host-port
  "get host and port from connection string"
  [connection-string]
  (let [[host port] (split connection-string #":")]
    [host (Integer/valueOf ^String port)]))

(defonce schedule-pool
  (ScheduledThreadPoolExecutor.
   (.availableProcessors (Runtime/getRuntime))))

(defn schedule-ping [delayed-client interval]
  (let [cancelable (.scheduleAtFixedRate
                    ^ScheduledThreadPoolExecutor schedule-pool
                    #(try+
                       (when (realized? delayed-client)
                         (ping @delayed-client))
                       (catch Exception e nil))
                    0 ;; initial delay
                    interval
                    TimeUnit/SECONDS)]
    (swap! scheduled-clients assoc delayed-client cancelable)
    cancelable))

(defn cancel-ping [client]
  (when-let [cancelable (@scheduled-clients client)]
    (.cancel ^ScheduledFuture cancelable true)))
