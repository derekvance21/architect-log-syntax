(ns extension.core)

(def vscode (js/require "vscode"))

(defn hello-world
  []
  (.. vscode.window (showInformationMessage "Hello World! FROM 1!")))

(defn activate
  [context]
  (.log js/console "Congratulations, your extension \"architect-debug-lang\" is now active!")
  (let [disposable (.. vscode.commands
                       (registerCommand
                        "architect-debug-lang.helloWorld"
                        #'hello-world))]
    (.. context.subscriptions (push disposable))))

(defn deactivate [])

(def exports #js {:activate activate
                  :deactivate deactivate})

(defn reload
  []
  (.log js/console "Reloading...")
  (js-delete js/require.cache (js/require.resolve "./extension")))
