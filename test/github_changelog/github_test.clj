(ns github-changelog.github-test
  (:require [clj-http.lite.client :as http]
            [clojure.test :refer :all]
            [clojure.test.check.generators :as gen]
            [github-changelog
             [github :as sut]
             [schema-generators :as sgen]]
            [jsonista.core :as j]))

(def config (sgen/complete-config {:user "raszi"
                                   :repo "changelog-test"}))

(def api-endpoint "https://api.github.com/repos/raszi/changelog-test/pulls")

(defn- sample-pull
  ([] (sample-pull (gen/generate sgen/sha)))
  ([sha]
   (sgen/complete-pull
    {:head {:sha sha}
     :base {:repo {:html_url ""}}})))

(deftest pulls-url
  (testing "with default API endpoint"
    (is (= api-endpoint (sut/pulls-url config))))
  (testing "with custom API endpoint"
    (let [alter-config (merge config {:github-api "http://enterprise.example.com/api/v3/"})]
      (is (= "http://enterprise.example.com/api/v3/repos/raszi/changelog-test/pulls" (sut/pulls-url alter-config))))))

(deftest parse-pull
  (let [sha (gen/generate sgen/sha)
        pull (sut/parse-pull (sample-pull sha))]
    (is (= sha (:sha pull)))))

(defn- matching-response [request [rule response]]
  (when (every? (fn [[key value]] (= value (get request key))) rule)
    response))

(defn- mocked-response-fn [rules]
  (fn [request]
    (if-let [response (some (partial matching-response request) rules)]
      response
      (throw (Exception. "No matching rules")))))

(defn- mock-response
  ([body] (mock-response body {}))
  ([body opts]
   (let [body-str (j/write-value-as-string body)]
     (merge {:status 200 :headers {} :body body-str} opts))))

(deftest fetch-pulls
  (testing "without multiple pages"
    (let [response (mock-response [(sample-pull)])]
      (with-redefs [http/request (mocked-response-fn {{:url api-endpoint :query-params {:state "closed"}} response})]
        (let [result (sut/fetch-pulls config)]
          (is (= 1 (count result)))))))

  (testing "with multiple pages"
    (let [links       {:last {:href "?page=2"}}
          first-resp  (mock-response (repeatedly 10 sample-pull) {:links links})
          second-resp (mock-response (repeatedly 10 sample-pull))]
      (with-redefs [http/request (mocked-response-fn
                                  {{:url api-endpoint :query-params {:state "closed"}}         first-resp
                                   {:url api-endpoint :query-params {:state "closed" :page 2}} second-resp})]
        (let [result (sut/fetch-pulls config)]
          (is (= 20 (count result))))))))
