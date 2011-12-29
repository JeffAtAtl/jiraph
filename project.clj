(defproject jiraph "0.7.1"
  :description "embedded graph db library for clojure"
  :dependencies [[clojure "1.2.0"]
                 [useful "0.7.5"]
                 [masai "0.6.3"]
                 [cereal "0.1.10"]
                 [retro "0.5.3"]
                 [ego "0.1.7"]]
  :dev-dependencies [[protobuf "0.5.0"]
                     [tokyocabinet "1.24.1" :ext true]
                     [unk "0.9.3"]]
  :cake-plugins [[cake-protobuf "0.5.0"]])
