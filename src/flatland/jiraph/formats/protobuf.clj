(ns flatland.jiraph.formats.protobuf
  (:use [flatland.jiraph.formats :only [revisioned-format tidy-node add-revisioning-modes]]
        [flatland.jiraph.codex :only [encode decode] :as codex]
        [flatland.useful.utils :only [adjoin]]
        [flatland.useful.map :only [keyed update]]
        [flatland.io.core :only [catbytes]]
        [flatland.protobuf.core :only [protodef protobuf-dump]])
  (:require [gloss.core :as gloss]
            [flatland.protobuf.codec :as protobuf]
            [flatland.schematic.core :as schema]))

(def ^:private ^:const len-key :proto_length)

(defn- wrap-tidying [f]
  (fn [opts]
    (-> (f opts)
        (update :codec codex/wrap identity tidy-node))))

(defn- wrap-revisioning-modes [f]
  (fn [opts]
    (-> (f opts)
        (add-revisioning-modes))))

(defn- num-bytes-to-encode-length [proto]
  (let [proto (protodef proto)
        min   (alength (protobuf-dump proto {len-key 0}))
        max   (alength (protobuf-dump proto {len-key Integer/MAX_VALUE}))]
    (letfn [(check [test msg]
              (when-not test
                (throw (Exception. (format "In %s: %s %s"
                                           (.getFullName proto) (name len-key) msg)))))]
      (check (pos? min)
             "field is required for repeated protobufs")
      (check (= min max)
             "must be of type fixed32 or fixed64")
      max)))

;; NB doesn't currently work if you do a full/optimized read with _reset keys.
;; plan is to fall back to non-optimized reads in that case, but support an
;; option to protobuf-codec to never try an optimized read if you expect _resets

(defn- length-for-revision [node goal-revision header-len]
  (loop [target-len 0,
         [[rev len] :as pairs] (map vector
                                    (:revisions node)
                                    (len-key node))]
    (if (or (not pairs)
            (> rev goal-revision))
      target-len
      (recur (+ len target-len header-len)
             (next pairs)))))

(defn- proto-format*
  [proto]
  (let [schema (-> (protobuf/codec-schema proto)
                   (schema/dissoc-fields :revisions))
        codec (protobuf/protobuf-codec proto)]
    (keyed [codec schema])))

(defn basic-protobuf-format
  "This is a format that can be used for :assoc-mode :overwrite while still keeping track of
  revisions."
  [proto]
  (let [proto-format (proto-format* proto)]
    (wrap-revisioning-modes
     (wrap-tidying
      (fn [{:keys [revision]}]
        (if (nil? revision)
          proto-format
          (-> proto-format
              (update :codec codex/wrap
                (fn [node]
                  (assoc node :revisions
                         (conj (:revisions (meta node))
                               revision)))
                identity))))))))

;; TODO temporarily threw away code for handling non-adjoin reduce-fn
(defn protobuf-format
  ([proto]
     (protobuf-format proto adjoin))
  ([proto reduce-fn]
     (when-not (= reduce-fn adjoin)
       (throw (IllegalArgumentException. (format "Unsupported reduce-fn %s" reduce-fn))))
     (let [proto-format (-> (proto-format* proto)
                            (assoc :reduce-fn reduce-fn))
           header-len (num-bytes-to-encode-length proto)]
       (wrap-revisioning-modes
        (wrap-tidying
         (fn [{:keys [revision] :as opts}]
           (if (nil? revision)
             proto-format
             (update proto-format :codec
                     (fn [codec]
                       {:read (fn [^bytes bytes]
                                (let [full-node (decode codec bytes)
                                      goal-length (length-for-revision full-node
                                                                       revision header-len)]
                                  (if (= goal-length (alength bytes))
                                    full-node
                                    (let [read-target (byte-array goal-length)]
                                      (System/arraycopy bytes 0 read-target 0 goal-length)
                                      (decode codec read-target)))))
                        :write (fn [node]
                                 (let [node (assoc node :revisions revision)
                                       ^bytes encoded (encode codec node)]
                                   (catbytes (encode codec {len-key (alength encoded)})
                                             encoded)))})))))))))