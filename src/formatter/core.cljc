(ns formatter.core
  (:require
   [clojure.string :as str]
   [clojure.set :as set]
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
             :Statement #(vector :Statement (apply conj {} %&))
             :QualifiedName #(apply conj {} %&)})))))

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


(defn returning?
  [line]
  (= (line-type line) :Returning))


(defn leaving?
  [line]
  (= (line-type line) :Leaving))


(defn sql?
  [line]
  (= (line-type line) :SQL))


(defn ->statement
  ([m]
   [:Line [:Statement m]])
  ([m children]
   [:Line [:Statement m] children]))


(defn line-map
  "Gets a line's map information. Many lines have format [:Line [:Type <map>]], and this is a helper to get the map inside"
  [[_ [_ m]]]
  (when (map? m)
    m))


(defn submap?
  "Checks whether m contains all entries in sub"
  [m sub]
  (set/subset? (set sub) (set m)))


(defn match-entering-tf
  "line is a Call statement line"
  [line children rest-stack]
  (let [prev-statement (line-map (first rest-stack))
        pre-execute-po {:Action "Compare"
                        :Name "MNU Dynamic Call = \"Yes\"?"
                        :Result "PASSED"}]
    (if (submap? prev-statement pre-execute-po)
      (->statement
       {:Action "Dynamic Call"
        :Name "Execute PO"
        :LineNumber (get prev-statement :NextLineNumber "N/A")
        :Result "N/A"
        :NextLineNumber -1}
       (list
        (conj (update-in line [1 1] ;; [:Line [:Statement {}]] ;; is why [1 1] works
                         assoc :LineNumber "N/A")
              children)))
      (conj line children))))


(defn dynamic-call-tf
  "line is a Dynamic Call statement line"
  [line children _]
  (let [{result :Result} (line-map line)
        {name :Name} (->> children
                          (filter leaving?)
                          (last)
                          (line-map))
        call (->statement
              {:LineNumber "N/A"
               :Action "Call"
               :Name (or name "...")
               :Result result
               :NextLineNumber -1}
              children)]
    (conj line (list call))))


(defn nest-until
  ([line pred stack]
   (nest-until line pred stack
               (fn [line children _]
                 (conj line children))))
  ([line pred stack tf]
   (let [[taken [start & rest-stack]] (split-with (complement pred) stack)
         children (conj (reverse taken)
                        (or start
                            [:Line [:Unknown]]))
         new-line (tf line children rest-stack)]
     (conj rest-stack new-line))))


(comment
  (nest-until
   [:Line [:Statement {:Action "Dynamic Call" :Name "Execute PO"}]]
   entering?
   '([:Line [:Statement {:Action "Return"}]]
     [:Line [:Leaving {:Application "WANextGeneration"
                       :Name "_Directed Move - DMR"}]]
     [:Line [:Statement {:Action "Calculate" :Name "x += 1"}]]
     [:Line [:Entering {:Application "WANextGeneration" :Name "_Directed Move - DMR"}]])
   dynamic-call-tf)
  ;
  )


(defn stack-line-reducer
  [stack line]
  (case (stmt-type line)
    "Call" (nest-until line entering? stack)
    "Database" (nest-until line sql? stack)
    "Dynamic Call" (nest-until line entering? stack dynamic-call-tf)
    "Execute" (nest-until line entering? stack) ;; TODO - this might need similar treatment to Dynamic Call
    (conj stack line)))


(defn match-entering
  [stack]
  (if-some [[idx entering] (->> stack
                                (map vector (range)) ;; could do (map-indexed vector), too
                                (filter (comp entering? second))
                                first)]
    (let [{name :Name} (line-map entering) ;; [:Line [:Entering {:Application app :Name name}]]
          {line-number :NextLineNumber} (line-map (nth stack (inc idx) nil)) ;; [:Line [:Statement {...}]]
          line (->statement
                {:Action "Call"
                 :Name name
                 :LineNumber (or line-number 1)
                 :Result "N/A"})]
      (nest-until line entering? stack match-entering-tf))
    stack))


(defn match-entering-until-done
  [stack]
  (let [more-unmatched-entering? #(seq (filter entering? %))]
    (->> stack
         (iterate match-entering)
         (drop-while more-unmatched-entering?)
         (first))))


(defn infer-returning
  [stack]
  (let [returnings (->> stack
                        (filter (comp #{"Call" "Dynamic Call"} stmt-type)) ;; probs not needed
                        (mapcat #(nth % 2 ())) ;; get children of top-level calls
                        (filter returning?) ;; there will be RETURNING TO statements inside nested calls
                        (distinct) ;; get unique ones - there *should* only be one, ever
                        )]
    (if (= (count returnings) 1) ;; if there are more than one, don't try and guess
      (let [{name :Name} (line-map (first returnings))
            children (conj (reverse stack) [:Line [:Unknown]])]
        (list
         (->statement
          {:Action "Call"
           :Name name
           :LineNumber "N/A"
           :Result "N/A"
           :NextLineNumber -1}
          children)))
      stack)))


(comment
  (->> '([:Line [:x]]
         [:Line [:Statement {:Action "Calculate" :Name "x += 1" :LineNumber 1 :Result "PASSED" :NextLineNumber 20}]]
         [:Line [:z]]
         [:Line [:Entering {:Name "Dialog"}]]
         [:Line [:Entering {:Name "Something Else"}]]
         [:Line [:Returning {:Name "_MPT Move Pallet"}]]
         [:Line [:Statement {:Action "Compare" :LineNumber 12 :Name "x > 0" :Result "PASSED" :NextLineNumber 20}]])
       (match-entering-until-done)
       (infer-returning)
       (reverse))
  ;
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


(defn other->str
  [[_ other]]
  other)


(defn line->str-lines
  [[_ contents :as line]]
  (case (line-type line)
    :Statement [(statement->str contents)]
    :SQL (sql->str-lines contents) ;; this is where you can use `format-sql`. Will need to refactor, though, b/c multiple lines created
    :Row [(row->str contents)]
    :RowsAffected [(rows-affected->str contents)]
    :Unknown ["..."]
    :Other [(other->str contents)]
    [(pr-str line)]))


(defn indent-line->str-lines
  [[level line]]
  (let [indentation (str/join (repeat level "\t"))
        indent #(str indentation %)]
    (->> (line->str-lines line)
         (map indent))))


;; Line = (Blank | Statement | SQL | Returning | Leaving | Entering | Row | RowsAffected | Error | Debug | Go | Working | Watched) (* / Other *)
(def keep-line-types
  #{:Statement
    :SQL
    :Row
    :RowsAffected
    :Unknown
    #_:Other})


(defn format-debug-log
  [input]
  (->> (parse input)
       (reduce stack-line-reducer ()) ;; get top-level calls only in first level of stack
       (match-entering-until-done)
       (infer-returning)
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

  (->> (read-file "/mnt/c/Users/DerekVance/Documents/Four Hands/cross-dock-sscc/crossdock-sscc-unf.archlog")
       (format-debug-log)
       (write-file "demo/crossdock.archlog"))

  (->> (read-file "demo/cycle-count-fixed.txt")
       (format-debug-log)
       (write-file "demo/cycle-count-fixed.archlog"))

  (->> (read-file "demo/more-ops.archlog")
       (format-debug-log)
       (write-file "demo/more-ops-formatted.archlog"))

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
