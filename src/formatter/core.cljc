(ns formatter.core
  (:require
   [clojure.string :as str]
   #?(:clj [instaparse.core :as insta :refer [defparser]]
      :cljs [instaparse.core :as insta :refer-macros [defparser]])
   #?(:cljs ["poor-mans-t-sql-formatter" :as sql])))


(defn format-sql
  [s]
  #?(:clj s
     :cljs (let [result (sql/formatSql s)
                 error-found (.-errorFound result)
                 text (.-text result)]
             (if error-found
               text
               text))))


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

(comment
  (parse "SQL STATEMENT {EXEC usp_something '{\"x\": 4}'; SELECT @x;}")
  (parse "  22:                 End: N/A                                                          PASSED  Next Instruction: 0")
  (parse "SQL STATEMENT { UPDATE t_emp_input_log_holding SET input_date = GETDATE ( ) , user_input = CASE WHEN 0 = 0 THEN 'ALL' ELSE 'F' + CAST ( 0 AS NVARCHAR ( 5 ) ) END WHERE input_id = 4815591}")
  (parse "SQL STATEMENT { }"))


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


(defn match-entering
  [stack]
  (if-some [[idx entering] (->> stack
                                (map vector (range))
                                (filter (comp entering? second))
                                first)]
    (let [[_ [_ [_ _ [_ name]]]] entering ;; [:Line [:Entering [:QualifiedName [:Application app] [:Name name]]]]
          [_ [_ {line-number :NextLineNumber}]] (nth stack (inc idx) nil) ;; [:Line [:Statement {...}]]
          line [:Line [:Statement {:Action "Call"
                                   :Name name
                                   :LineNumber (or line-number 1)
                                   :Result "N/A"}]]]
      (nest-until line entering? stack))
    stack))


(defn match-entering-until-done
  [stack]
  (let [more-unmatched-entering? #(seq (filter entering? %))]
    (->> stack
         (iterate match-entering)
         (drop-while more-unmatched-entering?)
         (first))))


(comment
  (->> '([:Line [:x]]
         [:Line [:y]]
         [:Line [:z]]
         [:Line [:Entering "Dialog"]]
         [:Line [:Entering "Something Else"]]
         [:Line [:Statement {:Action "Compare" :NextLineNumber 20}]])
       (match-entering-until-done)
       (reverse))
  )


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


(defn sql->str-lines
  [[_ sql]]
  (let [indent #(str "\t" %)]
    (flatten
     ["SQL STATEMENT {"
      (-> (str/trim sql)
          (str/replace #"^\{|}$" "")
          (format-sql)
          (str/split-lines)
          (->> (map indent)))
      "}"])))


;; ---- AS-WHSE-XFER-B3
(defn row->str
  [[_ row]]
  (str "---- " row))


;; -------- (0 rows affected)
(defn rows-affected->str
  [[_ rows-affected]]
  (str "-------- (" rows-affected " rows affected)"))


(defn line->str-lines
  [[_ contents :as line]]
  (case (line-type line)
    :Statement [(statement->str contents)]
    :SQL (sql->str-lines contents) ;; this is where you can use `format-sql`. Will need to refactor, though, b/c multiple lines created
    :Row [(row->str contents)]
    :RowsAffected [(rows-affected->str contents)]
    :Unknown ["..."]
    [(pr-str line)]))


(defn indent-line->str-lines
  [[level line]]
  (let [indentation (str/join (repeat level "\t"))
        indent #(str indentation %)]
    (->> (line->str-lines line)
         (map indent))))


;; Line = (Blank | Statement | SQL | Returning | Leaving | Entering | Row | RowsAffected | Error | Debug | Go | Working | Watched) (* / Other *)
(def keep-line-types
  #{:Statement :SQL :Row :RowsAffected :Unknown})


(defn format-debug-log
  [input]
  (->> (parse input)
       (reduce stack-line-reducer ()) ;; get top-level calls only in first level of stack
       (match-entering-until-done)
       reverse
       (mapcat line->indentation-lines) ;; list of [<indentation> <line>]
       (filter (comp keep-line-types line-type second)) ;; remove all other line-types
       (mapcat indent-line->str-lines) ;; list of strings
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

  (let [s "SQL STATEMENT { UPDATE t_emp_input_log_holding SET input_date = GETDATE ( ) , user_input = CASE WHEN 0 = 0 THEN 'ALL' ELSE 'F' + CAST ( 0 AS NVARCHAR ( 5 ) ) END WHERE input_id = 4815591}SQL STATEMENT { UPDATE t_emp_input_log_holding SET input_date = GETDATE ( ) , user_input = CASE WHEN 0 = 0 THEN 'ALL' ELSE 'F' + CAST ( 0 AS NVARCHAR ( 5 ) ) END WHERE input_id = 4815591}"]
    (format-debug-log s))

  (->> (read-file "demo/crlf.txt")
       (format-debug-log)
       (write-file "demo/crlf.archlog"))

  (->> (read-file "demo/orphaned-call.txt")
       (format-debug-log)
       (write-file "demo/orphaned-call.archlog"))

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
       (mapcat indent-line->str-lines)
       (str/join "\n"))
  ;
  )
