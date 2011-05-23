(ns dali.core
  (:require [clojure.string :as string]
            [dali.loader :as loader])
  (:use [dali.parser :exclude [partial]]
        [dali.ast :rename {render node-render
                           partial node-partial}]
        [clojure.java.io :only [resource]]
        dali.utils))

(declare render)
(declare render-string)

;; This is stupid. Clojure can't do circular dependencies between namespaces
;; at all. Some types need access to render/render-string to do what they are
;; supposed to do. But render-string depends on parser, parser depends on ast,
;; and to implement, ast would have to depend on core. So instead of doing what
;; Clojure wants you to do, and jam it all into one huge file, we're going to
;; just implement ASTNode for some of the ASTNode types here.

(extend-protocol ASTNode
  dali.ast.Section
  (render [this sb context-stack]
    (let [ctx-val (context-get context-stack (:name this))]
      (cond (or (not ctx-val) ;; "False" or the empty list -> do nothing.
                (and (sequential? ctx-val)
                     (empty? ctx-val)))
            nil
            ;; Non-empty list -> Display content once for each item in list.
            (sequential? ctx-val)
            (doseq [val ctx-val]
              ;; For each render, push the value to top of context stack.
              (node-render (:contents this) sb (conj context-stack val)))
            ;; Callable value -> Invoke it with the literal block of src text.
            (instance? clojure.lang.Fn ctx-val)
            (let [lambda-return (ctx-val (:content (:attrs this)))]
              ;; We have to manually parse because the spec says lambdas in
              ;; sections get parsed with the current parser delimiters.
              (.append sb (render (parse lambda-return
                                         (select-keys (:attrs this)
                                                      [:tag-open :tag-close]))
                                         (first context-stack))))
            ;; Non-false non-list value -> Display content once.
            :else
            (node-render (:contents this) sb (conj context-stack ctx-val)))))
  dali.ast.EscapedVariable
  (render [this sb context-stack]
    (if-let [value (context-get context-stack (:name this))]
      (if (instance? clojure.lang.Fn value)
        (.append sb (html-escape (render-string (str (value))
                                                (first context-stack))))
        ;; Otherwise, just append its html-escaped value by default.
        (.append sb (html-escape value)))))
  dali.ast.UnescapedVariable
  (render [this sb context-stack]
    (if-let [value (context-get context-stack (:name this))]
      (if (instance? clojure.lang.Fn value)
        (.append sb (render-string (str (value)) (first context-stack)))
        ;; Otherwise, just append its value.
        (.append sb value)))))

(defn render
  "Given a parsed template (output of load or parse) and map of args,
   renders the template."
  [template data-map]
  (let [sb (StringBuilder.)
        context-stack (conj '() data-map)]
    (node-render template sb context-stack)
    (.toString sb)))

(defn render-file
  "Given a template name (string) and map of args, loads and renders the named
   template."
  [template-name data-map]
  (render (loader/load template-name) data-map))

(defn render-string
  "Renders a given string containing the source of a template and a map
   of args."
  [template-src data-map]
  (render (parse template-src) data-map))

