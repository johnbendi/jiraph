(ns jiraph.graph-test
  (:use clojure.test jiraph.graph
        [retro.core :as retro :only [dotxn at-revision]])
  (:require [jiraph.stm-layer :as stm]
            [jiraph.layer :as layer]
            [jiraph.masai-layer :as masai]
            [jiraph.masai-sorted-layer :as sorted]))

(defn test-layer [master]
  (testing (str (class master))
    (truncate! master)
    (let [rev (vec (for [r (range 5)]
                     (at-revision master r)))
          mike-node {:age 21 :edges {"carla" {:rel :mom}}}
          carla-node {:age 48}]
      (dotxn (rev 1)
        (-> (rev 1)
            (assoc-node "mike" mike-node)
            (assoc-node "carla" carla-node)))
      (testing "Old revisions are untouched"
        (is (= nil (get-node (rev 0) "mike"))))
      (testing "Node data is written"
        (is (= mike-node (get-node (rev 1) "mike"))))

      (testing "Nil revision reads latest data"
        (is (= mike-node (get-node master "mike"))))
      (testing "Future revisions can be read"
        (is (= mike-node (get-node (rev 4) "mike"))))
      (testing "Basic incoming"
        (is (= #{"mike"}
               (get-incoming (rev 1) "carla")))
        (is (empty? (get-incoming (rev 1) "mike"))))

      (dotxn (rev 2)
        (let [actions (-> (rev 2)
                          (assoc-node "charles" {:edges {"carla" {:rel :mom}}})
                          (update-node "charles" assoc :age 18)
                          (update-in-node ["mike" :age] inc))]
          (testing "Writes can't be seen while queueing"
            (is (nil? (get-node actions "charles")))
            (is (nil? (get-node (rev 2) "charles")))
            (is (nil? (get-node master "charles"))))
          actions))
      (testing "Previous revisions still exist on unmodified nodes"
          (is (= carla-node (get-node (rev 1) "carla")))
          (is (= carla-node (get-node (rev 2) "carla"))))
      (testing "Previous revisions exist on modified nodes"
        (is (= mike-node (get-node (rev 1) "mike")))
        (is (= (update-in mike-node [:age] inc)
               (get-node (rev 2) "mike")
               (get-node master "mike"))))

      (testing "Updates see previous writes"
        (is (= {:age 18 :edges {"carla" {:rel :mom}}}
               (get-node (rev 2) "charles"))))
      (testing "Incoming is revisioned"
        (is (= #{"mike"} (get-incoming (rev 1) "carla")))
        (is (= #{"mike" "charles"} (get-incoming (rev 2) "carla"))))

      (testing "Changelog support"
        (testing "get-revisions"
          (is (= #{1 2} (set (get-revisions (rev 2) "mike"))))
          (testing "Don't know about future revisions"
            (is (= #{1} (set (get-revisions (rev 1) "mike")))))
          (is (= #{1} (set (get-revisions master "carla")))))
        (comment We decided not to support/implement this yet, and it's not a crucial feature.
                 Leaving tests in so that it's clear how layers *should* behave.
                 (testing "get-changed-ids"
                   (is (= #{"mike" "carla"}
                          (set (layer/get-changed-ids master 1))))
                   (is (= #{"mike" "charles"}
                          (set (layer/get-changed-ids (rev 2) 2))))))
        (testing "max-revision"
          (is (= 2 (retro/max-revision master)))
          (is (= 2 (retro/max-revision (rev 1))))))

      (testing "Can't rewrite history"
        (dotxn (rev 1)
          (-> (rev 1)
              (assoc-node "donald" {:age 72})))
        (doseq [r rev]
          (is (nil? (get-node r "donald")))))

      (testing "Transaction safety"
        (testing "Can't mutate active layer while building a transaction"
          (is (thrown? Exception
                       (dotxn (rev 3)
                         (doto (rev 3)
                           (assoc-node! "stevie" {:age 2}))))))
        (testing "Can't mutate other layer while committing a transaction"
          (is (thrown? Exception
                       (dotxn (rev 3)
                         (-> (rev 3)
                             (retro/enqueue (fn [_]
                                              (assoc-node! (rev 4) "stevie" {:age 2})))))))))

      (testing "Reporting of revision views"
        (is (= 2 (retro/current-revision (rev 2))))
        (is (nil? (retro/current-revision master)))))))


(deftest layer-impls
  (doseq [layer [;(stm/make)
                 (sorted/make-temp :layout-fns {:node (-> (constantly [[[:edges :*]], [[]]])
                                                          (sorted/wrap-default-formats)
                                                          (sorted/wrap-revisioned))})
                 (masai/make-temp)]] ;; add more layers as they're implemented
    (layer/open layer)
    (try
      (test-layer layer)
      (finally (layer/close layer)))))
