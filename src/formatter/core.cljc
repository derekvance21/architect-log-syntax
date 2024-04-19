(ns formatter.core
  (:require
   [clojure.string :as str]
   #?(:clj [instaparse.core :as insta :refer [defparser]]
      :cljs [instaparse.core :as insta :refer-macros [defparser]])))


(defn str->int [s]
  #?(:clj  (java.lang.Integer/parseInt s)
     :cljs (js/parseInt s)))

(defn exception
  [msg]
  #?(:clj (Exception. msg)
     :cljs (js/Error. msg)))


(defparser parser "resources/parser.bnf")


(defn parse
  [s]
  (let [result (insta/parse parser s)]
    (if (insta/failure? result)
      (throw (exception (print-str (insta/get-failure result))))
      (->> result
           (insta/transform
            {:Name #(vector :Name (str/trim %))
             :Integer str->int
             :Statement #(vector :Statement (apply conj {} %&))})))))


(defn line-type
  [line]
  (first (second line)))


(defn stmt-type
  [line]
  (:Action (second (second line))))


(defn entering?
  [line]
  (= (line-type line) :Entering))


(defn statement?
  [line]
  (= (line-type line) :Statement))


(defn sql?
  [line]
  (= (line-type line) :SQL))


(defn nest-until
  [line pred stack]
  (let [[popped kept] (split-with (complement pred) stack)
        children (conj (reverse popped)
                       (nth kept 0 [:Line [:Unknown]]))]
    (conj (rest kept)
          (conj line children))))


(def nesting-actions
  #{"Call" "Database" "Dynamic Call" "Execute"})


(defn stack-line-reducer
  [stack line]
  (case (stmt-type line)
    "Call" (nest-until line entering? stack)
    "Database" (nest-until line sql? stack)
    "Dynamic Call" (nest-until line entering? stack)
    "Execute" (nest-until line entering? stack)
    (conj stack line)))


(defn line->indentation-lines
  ([line]
   (line->indentation-lines 0 line))
  ([level line]
   (if-some [children (nth line 2 nil)]
     (conj (mapcat #(line->indentation-lines (inc level) %) children)
           [level (vec (take 2 line))])
     [[level line]])))


(defn postfix-spaces
  [n s]
  (str/join (take n (concat s (repeat " ")))))


(defn pad-spaces
  [p n s]
  (str p
       (str/join (repeat (- n (count p) (count s)) " "))
       s))


(defn statement->str
  [[_ {:keys [LineNumber Action Name Result]}]]
  (str (pad-spaces (str LineNumber ":") 23 Action)
       ": "
       (postfix-spaces 61 Name)
       Result))


(defn sql->str
  [[_ sql]]
  (str "SQL STATEMENT" sql))


;; ---- AS-WHSE-XFER-B3
(defn row->str
  [[_ row]]
  (str "---- " row))


;; -------- (0 rows affected)
(defn rows-affected->str
  [[_ rows-affected]]
  (str "-------- (" rows-affected " rows affected)"))


(defn line->str
  [[_ contents :as line]]
  (case (line-type line)
    :Statement (statement->str contents)
    :SQL (sql->str contents)
    :Row (row->str contents)
    :RowsAffected (rows-affected->str contents)
    :Unknown "..."
    (pr-str line)))


(defn indent-line->str
  [[level line]]
  (str (str/join (repeat level "\t")) (line->str line)))


(def keep-line-types
  #{:Statement :SQL :Row :RowsAffected :Unknown})


(defn format-debug-log
  [input]
  (->> (parse input)
       (reduce stack-line-reducer ()) ;; get top-level calls only in first level of stack
       reverse
       (mapcat line->indentation-lines) ;; list of [<indentation> <line>]
       (filter (comp keep-line-types line-type second)) ;; remove all other line-types
       (map indent-line->str) ;; list of strings
       (str/join "\n")))


(comment

  (def read-file
    #?(:clj slurp
       :cljs #(.readFileSync (js/require "fs") % "utf8")))

  (def write-file
    #?(:clj spit
       :cljs #(.writeFileSync (js/require "fs") %1 %2)))

  (->> (read-file "demo/cycle-count-fixed.txt")
       (format-debug-log)
       (write-file "demo/cycle-count-fixed.archlog"))

  (->> (read-file "demo/crlf.txt")
       (format-debug-log)
       (write-file "demo/crlf.archlog"))

  (->> (read-file "demo/parse-fail.txt")
       (format-debug-log)
       (write-file "demo/parse-fail.archlog"))

  (->> (read-file "demo/short-unformatted.txt")
       (format-debug-log)
       (write-file "demo/short-formatted.archlog"))

  (->> (.readFileSync (js/require "fs") "new.archlog" "utf8")
       (parse)
       (reduce stack-line-reducer ())
       reverse
       (mapcat line->indentation-lines)
       (remove (comp #{:Go :Error :Debug :Entering :Leaving :Returning :Blank} line-type second))
       (map indent-line->str)
       (str/join "\n")))