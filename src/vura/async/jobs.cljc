(ns vura.async.jobs
  #?(:cljs
      (:require-macros [dreamcatcher.core :refer defstm]))
  (:require
    [dreamcatcher.util :refer [has-transition?
                               get-transition
                               get-validators
                               get-states]]
    [dreamcatcher.core :refer :all]
    #?@(:clj [[clj-time.core :as t]
              [clj-time.local :refer (local-now)]])
    #?@(:cljs [[cljs-time.core :as t]
               [cljs-time.local :refer (local-now)]])))

(def ^:private start-mark start-mark)
(def ^:private end-mark end-mark)
(def ^:private running-mark running-mark)


(def ^:private blank-job-machine
  (make-state-machine
    [:initialize :start (fn [x] (assoc-in x [:data start-mark] (local-now)))
     :start :end identity
     :end :finished (fn [x] (-> x
                                (assoc-in [:data end-mark] (local-now))
                                (assoc-in [:data running-mark] false)))]))

(defn job-life [x]
  (if (get-in x [:data running-mark])
    (if (= :finished (state? x))
      (do
        (send-off *agent* #'job-life)
        (assoc-in x [:data running-mark] false))
      (do
        (send-off *agent* #'job-life)
        (act! x)))
    x))

(defn- get-next-phase [job phase]
  (let [job (assoc job :state phase)
        t (-> job choices?)]
    (when (seq t) (first t))))

(defn- get-job-phases [job]
  (let [job (assoc job :state :start)]
    (loop [phase :start
           phases nil]
      (if (= phase :end) (-> phases reverse rest)
        (recur (get-next-phase job phase) (conj phases phase))))))

(defn- get-previous-phase [job phase]
  (or (first (filter #(has-transition? job % phase) (get-job-phases job))) :start))


(defn add-phase
  "Functions adds phase to the end
  of the phase chain that completes
  job"
  ([job new-phase [function validator]]
   (assert (not-any? #(= % new-phase) (get-states @job)) "Phase already defined")
   (let [last-phase (first (filter #(has-transition? @job % :end) (get-states @job)))]
     (println new-phase last-phase)
     (remove-transition job last-phase :end)
     (remove-validator job last-phase :end)
     (add-state job new-phase)
     (add-transition job last-phase new-phase function)
     (when validator (add-validator job last-phase new-phase validator))
     job)))

(defn insert-phase
  "Inserts new phase after at-phase"
  ([job new-phase at-phase function validator]
   (assert (not-any? #(= % new-phase) (get-states @job)) "Phase already defined")
   (let [next-phase (get-next-phase @job at-phase)
         transition (get-transition @job at-phase next-phase)
         p-validator (first (get-validators @job at-phase))]
     (remove-transition job at-phase next-phase)
     (remove-validator job at-phase next-phase)
     (add-state job new-phase)
     (add-transition job at-phase new-phase function)
     (add-transition job new-phase next-phase transition)
     (when validator (add-validator job new-phase next-phase validator))
     (when p-validator (add-validator job at-phase new-phase validator))
     job)))

(defn remove-phase
  "Removes phase from job"
  [job phase]
  (assert (some #(= % phase) (get-states job)) "Phase is not defined for this job")
  (let [next-phase (get-next-phase job phase)
        previous-phase (get-previous-phase job phase)
        tp (get-transition job phase next-phase)
        vp (first (get-validators job phase))]
    (-> job
        (remove-transition previous-phase phase)
        (remove-validator previous-phase phase)
        (remove-state phase)
        (add-transition previous-phase next-phase tp)
        (add-validator previous-phase next-phase vp))))

(defprotocol JobInfo
  (at-phase? [this] "Returns current phase that job-agent is working on")
  (before-phase? [this phase] "Returns boolean true if current phase that job-agent is working on is before input phase")
  (after-phase? [this phase] "Returns boolean true if current phase that job-agent is working on is before input phase")
  (get-phases [this] "Lists all Job phases")
  (started-at? [this] "Returns org.joda.time.DateTime timestamp")
  (ended-at? [this] "Returns org.joda.time.DateTime timestamp")
  (started? [thsi] "Returns true if job is not in phase start")
  (finished? [this] "Returns true is job is in :finished state")
  (active? [this] "Returns true if job is running")
  (duration? [this] "Returns duration of job in milliseconds")
  (in-error? [this] "Returns error exception if it happend. Otherwise nil"))

(defprotocol JobActions
  (start! [this] "Starts Job. That is sends-off job-agent to do the job. If Job
                 was previously stoped than it continues from last phase.")
  (stop! [this] "Stops running Job. Job will allways try to complete current phase.
                If validator doesn't allow execution to continue than Job is stoped
                at current phase.")
  (reset-job! [this] [this params] "Returns job to initialized state. Optional parameter
                                   params is for data initialization."))



;; Functions allow mutable job definition
;; Job shell returns job phases that can
;; be wrapped with atom, and changed over time
;;
;; Real job instances are produced with job
;; function.
(defn make-job-shell [phases]
  (let [job (atom blank-job-machine)]
    (loop [phases phases]
      (if (empty? phases)
        @job
        (recur
          (do
            (add-phase job (first phases) (take-while fn? (rest phases)))
            (drop-while fn? (rest phases))))))))


(defn job
  "Returns real Job record with life. It
  is possible to initialize data for job.
  Pass in params after job definition."
  ([j] (Job. (-> j (make-machine-instance :initialize) give-life! agent)))
  ([j & params] (-> j
                    (make-machine-instance :initialize (hash-map params))
                    give-life!)))


;; Intended for static job definition
(defmacro defjob
  "Defines job shell that represents blueprint for
  successively executing functions that are defined
  as transitions from phase to phase. Transitions are
  valid if validator function returns true or if
  validator is not defined."
  [name & phases]
  `(def ~name (let [job# (atom ~blank-job-machine)
                    job-shell# (loop [phases# ~@phases]
                                 (if (empty? phases#)
                                   @job#
                                   (recur (do
                                            (add-phase job# (first phases#) (take-while fn? (rest phases#)))
                                            (drop-while fn? (rest phases#))))))]
                (-> job-shell#
                    (make-machine-instance :initialize)
                    give-life!))))

;; Reason for this macro is if "I" don't wan't to make
;; chage to Job. data durring Job. phases than this
;; macro evaluates body while returning Job. data as it was.

(defn depend-on
  "depend-on function is wrapper for job
  validator, that ties execution of next
  phase to successfull finish of other
  jobs."
  [check-timer & jobs] (fn [_] (Thread/sleep check-timer) (every? finished? jobs)))

(defn start-jobs
  "Returns function that takes one parameter
  and returns the same parameter. In mean time
  it starts all jobs that are put in as jobs
  argument of this function"
  [& jobs] (fn [x] (doseq [j jobs] (start! j)) x))

(defn reset-jobs
  "Returns function that takes one parameter
  and returns the same parameter. In mean time
  it resets all jobs that are put in as jobs
  argument of this function"
  [& jobs] (fn [x] (doseq [j jobs] (reset-job! j)) x))

(defn stop-jobs
  "Returns function that takes one parameter
  and returns the same parameter. In mean time
  it stops all jobs that are put in as jobs
  argument of this function"
  [& jobs] (fn [x] (doseq [j jobs] (stop! j)) x))

(defn restart-job!
  "Restarts job combining functions stop! restart-job!
  start!"
  [job]
  (comp start! reset-job! stop!))

(defn restart-jobs
  "Returns function that takes one parameter
  and returns the same parameter. In mean time
  it restarts all jobs that are put in as jobs
  argument of this function"
  [& jobs]
  (fn [x] (doseq [j jobs] (restart-job! j)) x))

(defn wait-for
  "Wait for is another wrapper that can
  be used as validator for job. It periodicaly
  checks if condition fn or condition is valid
  and. If condition is not provided than it is true."
  ([check-timer] (fn [_] (do (Thread/sleep check-timer) true)))
  ([check-timer condition] (fn [_] (Thread/sleep check-timer) (if (fn? condition) (condition) condition))))



(def test-job [:test1 (safe (println "Testis 1"))
                  :test2 (safe (println "Testis 2"))
                  :test3 (safe (println "Testis 3"))])
