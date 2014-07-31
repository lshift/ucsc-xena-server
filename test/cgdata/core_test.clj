(ns cgdata.core-test
  (:require [clojure.test :as ct])
  (:require [clojure.java.io :as io])
  (:require [cgdata.core :as cgdata]))

(def cwd (.getCanonicalFile (io/file ".")))

;
; references
;

(ct/deftest test-reference-samedir
  (ct/is (=
          {":a" "one/two/ack"}
          (cgdata/references cwd "one/two/three" {":a" "ack"}))))

(ct/deftest test-reference-parentdir
  (ct/is (=
          {":a" "one/ack"}
          (cgdata/references cwd "one/two/three" {":a" "../ack"}))))

(ct/deftest test-reference-absolute-ref
  (ct/is (=
          {":a" "foo/ack"}
          (cgdata/references cwd "one/two/three" {":a" "/foo/ack"}))))

(ct/deftest test-reference-top-of-path
  (ct/is (=
          {":a" "ack"}
          (cgdata/references cwd "one/two/three" {":a" "../../ack"}))))

(ct/deftest test-reference-nil
  (ct/is (=
          {":a" "one/two/ack" ":b" nil}
          (cgdata/references cwd "one/two/three" {":a" "ack" ":b" nil}))))

(ct/deftest test-reference-outside-path
  (ct/is (thrown?
           IllegalArgumentException
           (cgdata/references cwd "one/two/three" {":a" "../../../ack"}))))

(ct/deftest test-mutation-header
  (ct/is (=
          [:chrom :chromStart :chromEnd]
          (cgdata/columns-from-header " chrom\tstart\tend" cgdata/mutation-columns)))
  (ct/is (=
          [:genes :ref :alt]
          (cgdata/columns-from-header "genes \tref\talternate" cgdata/mutation-columns)))
  (ct/is (=
          [:effect :dna-vaf :rna-vaf]
          (cgdata/columns-from-header " effect \tDNA-VAF\tRNAVAF" cgdata/mutation-columns))))

(ct/deftest test-pick-header
  (ct/is (=
          " # foo bar"
          (cgdata/pick-header [" " " \t" " # foo bar"]))))

(ct/deftest test-pick-header-empty
  (ct/is (=
          nil
          (cgdata/pick-header [" " "\t" "12\t4.4"]))))

(ct/deftest test-find-position-field
  (ct/is (= #{{:header :chromStart :i 6}
              {:header :foo :i 3}
              {:header :position
               :type :position
               :columns #{{:header :chrom :i 2}
                          {:header :chromStart :i 4}
                          {:header :chromEnd :i 7}}}}
            (set (cgdata/find-position-field
                   [{:header :chrom :i 2}
                    {:header :foo :i 3}
                    {:header :chromStart :i 4}
                    {:header :chromStart :i 6}
                    {:header :chromEnd :i 7}])))))


(ct/deftest test-position-spec
  (let [field {:name "position"
               :header :position
               :type :position
               :columns [{:header :chrom :i 0}
                         {:header :chromStart :i 1}
                         {:header :chromEnd :i 2}]}
        rows [["chr1" "100" "200"]
              [" chr1" "200 " "300"]
              ["chr2 " "100" " 200"]
              ["chr3" "500" "600"]]
        spec (cgdata/field-spec field rows)]
    (ct/is (= {:field "position"
               :valueType "position"
               :rows [{:chrom "chr1" :chromStart 100 :chromEnd 200}
                      {:chrom "chr1" :chromStart 200 :chromEnd 300}
                      {:chrom "chr2" :chromStart 100 :chromEnd 200}
                      {:chrom "chr3" :chromStart 500 :chromEnd 600}]}
              spec))))
