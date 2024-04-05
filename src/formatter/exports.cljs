(ns formatter.exports
  (:require
   [formatter.core :as f]))


(def exports #js {:transform f/format-debug-log
                  :parse f/parse
                  :constantNum 42})
