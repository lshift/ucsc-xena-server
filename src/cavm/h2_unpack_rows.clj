(ns
  ^{:author "Brian Craft"
    :doc "Custom H2 functions for retrieving rows from blob fields."}
  cavm.h2-unpack-rows)

; The following a mechanism to avoid a direct dependency on other cavm code
; that should not be aot compiled.
; This file has to be aot compiled so h2 can find it (or so I'm lead to believe). 
; However a dependency on other files causes them to be affected by the aot. This
; messes up dynamic reloading of those files, meaning we can't develop from
; the repl reliably. The workaround is to provide a method for injecting the
; dependency, by setting a var root.

(def lookup-row nil)
(defn set-lookup-row! [f]
  (alter-var-root (var lookup-row) (fn [_] f)))

(def lookup-value nil)
(defn set-lookup-value! [f]
  (alter-var-root (var lookup-value) (fn [_] f)))

;
; Blob unpack functions for h2.
;

(defn- -unpack [field-id row]
  (lookup-row field-id row))

; Somehow the return value will be cast automatically.
(def ^:private -unpackCode -unpack)

(defn- -unpackValue [field-id row]
  (lookup-value field-id row))

; Return types must be boxed to support NULL. Primitive types will throw if we
; return nil.
; Alternatively, we could return NaN. h2 probably boxes the returns, anyway.
(gen-class :name unpack_rows
           :methods [^:static [unpackCode [int int] java.lang.Integer]
                     ^:static [unpack [int int] java.lang.Float]
                     ^:static [unpackValue [int int] String]])
