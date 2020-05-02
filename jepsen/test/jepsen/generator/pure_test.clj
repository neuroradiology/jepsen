(ns jepsen.generator.pure-test
  (:require [jepsen.generator.pure :as gen]
            [jepsen.independent :as independent]
            [jepsen [util :as util]]
            [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [knossos.op :as op]
            [slingshot.slingshot :refer [try+ throw+]]))

(def default-test
  "A default test map."
  {})

(defn n+nemesis-context
  "A context with n numeric worker threads and one nemesis."
  [n]
  (gen/context {:concurrency n}))

(def default-context
  "A default initial context for running these tests. Two worker threads, one
  nemesis."
  (n+nemesis-context 2))

(defn invocations
  "Only invokes, not returns"
  [history]
  (filter #(= :invoke (:type %)) history))

(defn quick-ops
  "Simulates the series of ops obtained from a generator where the
  system executes every operation perfectly, immediately, and with zero
  latency."
  ([gen]
   (quick-ops default-context gen))
  ([ctx gen]
   (loop [ops []
          gen (gen/validate gen)
          ctx ctx]
     (let [[invocation gen] (gen/op gen default-test ctx)]
       (condp = invocation
         nil ops ; Done!

         :pending (assert false "Uh, we're not supposed to be here")

         (let [; Advance clock
               ctx'       (update ctx :time max (:time invocation))
               ; Update generator
               gen'       (gen/update gen default-test ctx' invocation)
               ; Pretend to do operation
               completion (assoc invocation :type :ok)
               ; Advance clock to completion
               ctx''      (update ctx' :time max (:time completion))
               ; And update generator
               gen''      (gen/update gen' default-test ctx'' completion)]
           (recur (conj ops invocation completion)
                  gen''
                  ctx'')))))))

(defn quick
  "Like quick-ops, but returns just invocations."
  ([gen]
   (quick default-context gen))
  ([ctx gen]
   (invocations (quick-ops ctx gen))))

(defn simulate
  "Simulates the series of operations obtained from a generator, given a
  function that takes a context and op and returns the completion for that op."
  ([gen complete-fn]
   (simulate default-context gen complete-fn))
  ([ctx gen complete-fn]
   (loop [ops        []
          in-flight  [] ; Kept sorted by time
          gen        (gen/validate gen)
          ctx        ctx]
     ;(binding [*print-length* 3] (prn :invoking :gen gen))
     (let [[invoke gen'] (gen/op gen default-test ctx)]
       ;(prn :invoke invoke :in-flight in-flight)
       (if (nil? invoke)
         ; We're done
         (into ops in-flight)

         ; TODO: the order of updates for worker maps here isn't correct; fix
         ; it.
         (if (and (not= :pending invoke)
                  (or (empty? in-flight)
                      (<= (:time invoke) (:time (first in-flight)))))

           ; We have an invocation that's not pending, and that invocation is
           ; before every in-flight completion
           (let [thread    (gen/process->thread ctx (:process invoke))
                 ; Advance clock, mark thread as free
                 ctx       (-> ctx
                               (update :time max (:time invoke))
                               (update :free-threads disj thread))
                 ; Update the generator with this invocation
                 gen'      (gen/update gen' default-test ctx invoke)
                 ; Add the completion to the in-flight set
                 ;_         (prn :invoke invoke)
                 complete  (complete-fn ctx invoke)
                 in-flight (sort-by :time (conj in-flight complete))]
             (recur (conj ops invoke) in-flight gen' ctx))

           ; We need to complete something before we can apply the next
           ; invocation.
           (let [op     (first in-flight)
                 _      (assert op "generator pending and nothing in flight???")
                 thread (gen/process->thread ctx (:process op))
                 ; Advance clock, mark thread as free
                 ctx    (-> ctx
                            (update :time max (:time op))
                            (update :free-threads conj thread))
                 ; Update generator with completion
                 gen'   (gen/update gen default-test ctx op)
                 ; Update worker mapping if this op crashed
                 ctx    (if (or (= :nemesis thread) (not= :info (:type op)))
                          ctx
                          (update ctx :workers
                                  assoc thread (gen/next-process ctx thread)))]
             (recur (conj ops op) (rest in-flight) gen' ctx))))))))

(def perfect-latency
  "How long perfect operations take"
  10)

(defn perfect
  "Simulates the series of ops obtained from a generator where the system
  executes every operation successfully in 10 nanoseconds. Returns only
  invocations."
  ([gen]
   (perfect default-context gen))
  ([ctx gen]
   (invocations
     (simulate ctx gen
               (fn [ctx invoke]
                 (-> invoke
                     (assoc :type :ok)
                     (update :time + perfect-latency)))))))

(defn perfect-info
  "Simulates the series of ops obtained from a generator where every operation
  crashes with :info in 10 nanoseconds. Returns only invocations."
  ([gen]
   (perfect-info default-context gen))
  ([ctx gen]
   (invocations
     (simulate ctx gen
               (fn [ctx invoke]
                 (-> invoke
                     (assoc :type :info)
                     (update :time + perfect-latency)))))))

(defn imperfect
  "Simulates the series of ops obtained from a generator where threads
  alternately fail, info, then ok, and repeat, taking 10 ns each. Returns
  invocations and completions."
  ([gen]
   (imperfect default-context gen))
  ([ctx gen]
   (let [state (atom {})]
     (simulate ctx gen
               (fn [ctx invoke]
                 (let [t (gen/process->thread ctx (:process invoke))]
                   (-> invoke
                       (assoc :type (get (swap! state update t {nil   :fail
                                                                :fail :info
                                                                :info :ok
                                                                :ok   :fail})
                                         t))
                       (update :time + perfect-latency))))))))

(deftest nil-test
  (is (= [] (perfect nil))))

(deftest map-test
  (testing "once"
    (is (= [{:time 0
             :process 0
             :type :invoke
             :f :write}]
           (perfect {:f :write}))))

  (testing "concurrent"
    (is (= [{:type :invoke, :process 0, :f :write, :time 0}
            {:type :invoke, :process 1, :f :write, :time 0}
            {:type :invoke, :process :nemesis, :f :write, :time 0}
            {:type :invoke, :process :nemesis, :f :write, :time 10}
            {:type :invoke, :process 1, :f :write, :time 10}
            {:type :invoke, :process 0, :f :write, :time 10}]
           (perfect (repeat 6 {:f :write})))))

  (testing "all threads busy"
    (is (= [:pending {:f :write}]
           (gen/op {:f :write} {} (assoc default-context
                                         :free-threads []))))))

(deftest limit-test
  (is (= [{:type :invoke :process 0 :time 0 :f :write :value 1}
          {:type :invoke :process 0 :time 0 :f :write :value 1}]
         (->> (repeat {:f :write :value 1})
              (gen/limit 2)
              quick))))

(deftest repeat-test
  (is (= [0 0 0]
         (->> (range)
              (map (partial hash-map :value))
              (gen/repeat 3)
              (perfect)
              (map :value)))))

(deftest delay-til-test
  (is (= [{:type :invoke, :process 0, :time 0, :f :write}
          {:type :invoke, :process 1, :time 0, :f :write}
          {:type :invoke, :process :nemesis, :time 0, :f :write}
          {:type :invoke, :process 0, :time 12, :f :write}
          {:type :invoke, :process 1, :time 12, :f :write}]
          (->> {:f :write}
               gen/repeat
               (gen/delay-til 3e-9)
               (gen/limit 5)
               perfect))))

(deftest seq-test
  (testing "vectors"
    (is (= [1 2 3]
           (->> [{:value 1}
                 {:value 2}
                 {:value 3}]
                quick
                (map :value)))))

  (testing "seqs"
    (is (= [1 2 3]
           (->> [{:value 1}
                 {:value 2}
                 {:value 3}]
                quick
                (map :value)))))

  (testing "nested"
    (is (= [1 2 3 4 5]
           (->> [[{:value 1}
                  {:value 2}]
                 [[{:value 3}]
                  {:value 4}]
                 {:value 5}]
                quick
                (map :value)))))

  (testing "updates propagate to first generator"
    (let [gen (->> [(gen/until-ok (gen/repeat {:f :read}))
                    {:f :done}]
                   (gen/clients))
          types (atom (concat [nil :fail :fail :ok :ok] (repeat :info)))]
      (is (= [[0 :read :invoke]
              [0 :read :invoke]
              ; Everyone fails and retries
              [10 :read :fail]
              [10 :read :invoke]
              [10 :read :fail]
              [10 :read :invoke]
              ; One succeeds and goes on to execute :done
              [20 :read :ok]
              [20 :done :invoke]
              ; The other succeeds and is finished
              [20 :read :ok]
              [30 :done :info]]
             (->> (simulate default-context gen
                            (fn [ctx op]
                              (-> op (update :time + 10)
                                  (assoc :type (first (swap! types next))))))
                  (map (juxt :time :f :type))))))))

(deftest fn-test
  (testing "returning nil"
    (is (= [] (quick (fn [])))))

  (testing "returning a literal map"
    (let [ops (->> (fn [] {:f :write, :value (rand-int 10)})
                   (gen/limit 5)
                   perfect)]
      (is (= 5 (count ops)))                      ; limit
      (is (every? #(<= 0 % 10) (map :value ops))) ; legal vals
      (is (< 1 (count (set (map :value ops)))))   ; random vals
      (is (= #{0 1 :nemesis} (set (map :process ops)))))) ; processes assigned

  (testing "returning repeat maps"
    (let [ops (->> #(gen/repeat {:f :write, :value (rand-int 10)})
                   (gen/limit 5)
                   perfect)]
      (is (= 5 (count ops)))                      ; limit
      (is (every? #(<= 0 % 10) (map :value ops))) ; legal vals
      (is (= 1 (count (set (map :value ops)))))   ; same vals
      (is (= #{0 1 :nemesis} (set (map :process ops))))))) ; processes assigned

(deftest on-update+promise-test
  ; We only fulfill p once the write has taken place.
  (let [p (promise)]
    (is (= [{:f :read, :time 0, :process 0, :type :invoke}
            {:f :write, :value :x, :time 0, :process 0, :type :invoke}
            {:f :confirm, :value :x, :time 0, :process 0, :type :invoke}
            {:f :hold, :time 0, :process 0, :type :invoke}
            {:f :hold, :time 0, :process 0, :type :invoke}]
           (->> (gen/any p
                         [{:f :read}
                          {:f :write, :value :x}
                          ; We'll do p at this point, then return to hold.
                          (repeat {:f :hold})])
                ; We don't deliver p until after the write is complete.
                (gen/on-update (fn [this test ctx event]
                                 (when (and (op/ok? event)
                                            (= :write (:f event)))
                                   (deliver p {:f      :confirm
                                               :value  (:value event)}))
                                 this))
                (gen/limit 5)
                quick)))))

(deftest delay-test
  (let [eval-ctx (promise)
        d (delay (gen/limit 3
                   (fn [test ctx]
                     (deliver eval-ctx ctx)
                     {:f :delayed})))
        h (->> (gen/phases {:f :write}
                           {:f :read}
                           d)
               gen/clients
               perfect)]
    (is (= [{:f :write, :time 0, :process 0, :type :invoke}
            {:f :read, :time 10, :process 0, :type :invoke}
            {:f :delayed, :time 20, :process 0, :type :invoke}
            {:f :delayed, :time 20, :process 1, :type :invoke}
            {:f :delayed, :time 30, :process 1, :type :invoke}]
           h))
    (is (realized? d))
    (is (= {:time 20
            :free-threads [0 1]
            :workers {0 0, 1 1}}
           @eval-ctx))))

(deftest synchronize-test
  (is (= [{:f :a, :process 0, :time 2, :type :invoke}
          {:f :a, :process 1, :time 3, :type :invoke}
          {:f :a, :process :nemesis, :time 5, :type :invoke}
          {:f :b, :process 0, :time 15, :type :invoke}
          {:f :b, :process 1, :time 15, :type :invoke}]
         (->> [(->> (fn [test ctx]
                      (let [p     (first (gen/free-processes ctx))
                            ; This is technically illegal: we should return the
                            ; NEXT event by time. We're relying on the specific
                            ; order we get called here to do this. Fragile hack!
                            delay (case p
                                    0        2
                                    1        1
                                    :nemesis 2)]
                        {:f :a
                         :process p
                         :time (+ (:time ctx) delay)}))
                    (gen/limit 3))
               ; The latest process, the nemesis, should start at time 5 and
               ; finish at 15.
               (gen/synchronize (repeat 2 {:f :b}))]
              perfect))))

(deftest clients-test
  (is (= #{0 1}
         (->> {}
              gen/repeat
              (gen/clients)
              (gen/limit 5)
              perfect
              (map :process)
              set))))

(deftest phases-test
  (is (= [[:a 0 0]
          [:a 1 0]
          [:b 0 10]
          [:c 0 20]
          [:c 1 20]
          [:c 1 30]]
         (->> (gen/phases (repeat 2 {:f :a})
                          (repeat 1 {:f :b})
                          (repeat 3 {:f :c}))
              gen/clients
              perfect
              (map (juxt :f :process :time))))))

(deftest any-test
  ; We take two generators, each of which is restricted to a single process,
  ; and each of which takes time to schedule. When we bind them together with
  ; Any, they can interleave.
  (is (= [[:a 0 0]
          [:b 1 0]
          [:a 0 20]
          [:b 1 20]]
         (->> (gen/any (gen/on #{0} (gen/delay-til 20e-9 (repeat {:f :a})))
                       (gen/on #{1} (gen/delay-til 20e-9 (repeat {:f :b}))))
              (gen/limit 4)
              perfect
              (map (juxt :f :process :time))))))

(deftest each-thread-test
  (is (= [[0 0 :a]
          [0 1 :a]
          [0 :nemesis :a]
          [10 :nemesis :b]
          [10 1 :b]
          [10 0 :b]]
         ; Each thread now gets to evaluate [a b] independently.
         (->> (gen/each-thread [{:f :a} {:f :b}])
              perfect
              (map (juxt :time :process :f)))))

  (testing "collapses when exhausted"
    (is (= nil
           (gen/op (gen/each-thread (gen/limit 0 {:f :read}))
               {}
               default-context)))))

(deftest stagger-test
  (let [n           1000
        dt          20
        concurrency (count (:workers default-context))
        ops         (->> (range n)
                         (map (fn [x] {:f :write, :value x}))
                         (gen/stagger (util/nanos->secs dt))
                         perfect)
        times       (mapv :time ops)
        max-time    (peek times)
        rate        (float (/ n max-time))
        expected-rate (float (/ dt))]
    (is (<= 0.9 (/ rate expected-rate) 1.1))))

(deftest f-map-test
  (is (= [{:type :invoke, :process 0, :time 0, :f :b, :value 2}]
         (->> {:f :a, :value 2}
              (gen/f-map {:a :b})
              perfect))))

(deftest filter-test
  (is (= [0 2 4 6 8]
         (->> (range)
              (map (fn [x] {:value x}))
              (gen/limit 10)
              (gen/filter (comp even? :value))
              perfect
              (map :value)))))

(deftest ^:logging log-test
  (is (->> (gen/phases (gen/log :first)
                       {:f :a}
                       (gen/log :second)
                       {:f :b})
           perfect
           (map :f)
           (= [:a :b]))))

(deftest mix-test
  (let [fs (->> (gen/mix [(repeat 5  {:f :a})
                          (repeat 10 {:f :b})])
                perfect
                (map :f))]
    (is (= {:a 5
            :b 10}
           (frequencies fs)))
    (is (not= (concat (repeat 5 :a) (repeat 5 :b)) fs))))

(deftest process-limit-test
  (is (= [[0 0]
          [1 1]
          [3 2]
          [2 3]
          [4 4]]
         (->> (range)
              (map (fn [x] {:value x}))
              (gen/process-limit 5)
              gen/clients
              perfect-info
              (map (juxt :process :value))))))

(deftest time-limit-test
  (is (= [[0  :a] [0  :a] [0 :a]
          [10 :a] [10 :a] [10 :a]
          [20 :b] [20 :b] [20 :b]]
         ; We use two time limits in succession to make sure they initialize
         ; their limits appropriately.
         (->> [(gen/time-limit (util/nanos->secs 20) (gen/repeat {:value :a}))
               (gen/time-limit (util/nanos->secs 10) (gen/repeat {:value :b}))]
              perfect
              (map (juxt :time :value))))))

(defn integers
  "A sequence of maps with :value 0, 1, 2, ..., and any other kv pairs."
  [& kv-pairs]
  (->> (range)
       (map (fn [x] (apply hash-map :value x kv-pairs)))))

(deftest reserve-test
  ; TODO: can you nest reserves properly? I suspect no.

  (let [as (integers :f :a)
        bs (integers :f :b)
        cs (integers :f :c)]
    (testing "only a default"
      (is (= [{:f :a, :process 0, :time 0, :type :invoke, :value 0}
              {:f :a, :process 1, :time 0, :type :invoke, :value 1}
              {:f :a, :process :nemesis, :time 0, :type :invoke, :value 2}]
             (->> (gen/reserve as)
                  (gen/limit 3)
                  perfect))))

    (testing "three ranges"
      (is (= [{:f :c, :process :nemesis, :time 0, :type :invoke, :value 0}
              {:f :c, :process 5, :time 0, :type :invoke, :value 1}
              {:f :a, :process 0, :time 0, :type :invoke, :value 0}
              {:f :a, :process 1, :time 0, :type :invoke, :value 1}
              {:f :b, :process 4, :time 0, :type :invoke, :value 0}
              {:f :b, :process 3, :time 0, :type :invoke, :value 1}
              {:f :b, :process 2, :time 0, :type :invoke, :value 2}
              {:f :b, :process 2, :time 10, :type :invoke, :value 3}
              {:f :b, :process 3, :time 10, :type :invoke, :value 4}
              {:f :b, :process 4, :time 10, :type :invoke, :value 5}
              {:f :a, :process 1, :time 10, :type :invoke, :value 2}
              {:f :a, :process 0, :time 10, :type :invoke, :value 3}
              {:f :c, :process 5, :time 10, :type :invoke, :value 2}
              {:f :c, :process :nemesis, :time 10, :type :invoke, :value 3}
              {:f :c, :process :nemesis, :time 20, :type :invoke, :value 4}]
             (->> (gen/reserve 2 as
                               3 bs
                               cs)
                  (gen/limit 15)
                  (perfect (n+nemesis-context 6))))))))

(deftest independent-sequential-test
  (is (= [[0 0 [:x 0]]
          [0 1 [:x 1]]
          [10 1 [:x 2]]
          [10 0 [:y 0]]
          [20 0 [:y 1]]
          [20 1 [:y 2]]]
         (->> (independent/pure-sequential-generator
                [:x :y]
                (fn [k]
                  (->> (range)
                       (map (partial hash-map :type :invoke, :value))
                       (gen/limit 3))))
              gen/clients
              perfect
              (map (juxt :time :process :value))))))

(deftest independent-concurrent-test
  ; All 3 groups can concurrently execute the first 2 values from k0, k1, k2
  (is (= [[0 0 [:k0 :v0]]
          [0 1 [:k0 :v1]]
          [0 3 [:k1 :v0]]
          [0 2 [:k1 :v1]]
          [0 4 [:k2 :v0]]
          [0 5 [:k2 :v1]]
          ; Worker 5 finishes k2
          [10 5 [:k2 :v2]]
          ; Worker 4 in group 2 moves on to k3
          [10 4 [:k3 :v0]]
          ; Worker 2 in group 1 finishes k1.
          [10 2 [:k1 :v2]]
          ; Worker 3 in group 1 starts k4.
          [10 3 [:k4 :v0]]
          ; Worker 1 in group 0 finishes k0
          [10 1 [:k0 :v2]]
          ; Worker 0 has no options left; there are no keys remaining for it to
          ; start afresh, and other groups still have generators, so it holds
          ; at :pending. Workers 4 & 5 finish k3, and 2 & 3 finish k4
          [20 3 [:k4 :v1]]
          [20 2 [:k4 :v2]]
          [20 4 [:k3 :v1]]
          [20 5 [:k3 :v2]]]
         (->> (independent/pure-concurrent-generator
                2                     ; 2 threads per group
                [:k0 :k1 :k2 :k3 :k4] ; 5 keys
                (fn [k]
                  (->> [:v0 :v1 :v2] ; Three values per key
                       (map (partial hash-map :type :invoke, :value)))))
              (perfect (n+nemesis-context 6)) ; 3 groups of 2 threads each
              (map (juxt :time :process :value))))))

(deftest independent-deadlock-case
  (is (= [[0 0 :meow [0 nil]]
          [0 1 :meow [0 nil]]
          [10 0 :meow [1 nil]]
          [10 1 :meow [1 nil]]
          [20 0 :meow [2 nil]]]
          (->> (independent/pure-concurrent-generator
                2
                (range)
                (fn [k] (gen/each-thread {:f :meow})))
              (gen/limit 5)
              gen/clients
              perfect
              (map (juxt :time :process :f :value))))))

(deftest at-least-one-ok-test
  ; Our goal here is to ensure that at least one OK operation happens.
  (is (= [[0   0 :invoke]
          [0   1 :invoke]
          [10  1 :fail]
          [10  1 :invoke]
          [10  0 :fail]
          [10  0 :invoke]
          [20  0 :info]
          [20  2 :invoke]
          [20  1 :info]
          [20  3 :invoke]
          [30  3 :ok]
          [30  2 :ok]] ; They complete concurrently, so we get two oks
         (->> {:f :read}
              repeat
              gen/until-ok
              (gen/limit 10)
              gen/clients
              imperfect
              (map (juxt :time :process :type))))))

(deftest flip-flop-test
  (is (= [[0 :write 0]
          [1 :read nil]
          [1 :write 1]
          [0 :finalize nil]
          [0 :write 2]]
         (->> (gen/flip-flop (map (fn [x] {:f :write, :value x}) (range))
                             [{:f :read}
                              {:f :finalize}])
              (gen/limit 10)
              gen/clients
              perfect
              (map (juxt :process :f :value))))))

(deftest pretty-print-test
  (is (= "(jepsen.generator.pure.Synchronize{\n   :gen {:f :start}}\n jepsen.generator.pure.Synchronize{\n   :gen [1 2 3]}\n jepsen.generator.pure.Synchronize{\n   :gen jepsen.generator.pure.Limit{\n     :remaining 3, :gen {:f :read}}})\n"
         (with-out-str
           (pprint (gen/phases
                     {:f :start}
                     [1 2 3]
                     (->> {:f :read}
                          (gen/limit 3))))))))
