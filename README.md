# hello-clj


## java

```sh

sudo add-apt-repository ppa:webupd8team/java
sudo apt update
sudo apt install default-jre
sudo apt install oracle-java*-installer
sudo apt-get install oracle-java8-set-default
lein new hello-clj
```

## build

* http://tnoda-clojure.tumblr.com/post/25591629696/hello-world-in-clojure
* https://github.com/mcohen01/amazonica#lambda
* https://aws.amazon.com/jp/blogs/compute/clojure/
* http://mitchrobb.com/blog/Running-Clojure-code-on-AWS-Lambda/
* https://github.com/jebberjeb/lambda-sample
* https://github.com/technomancy/leiningen/blob/master/doc/TUTORIAL.md

```sh
lein deps
lein repl
lein run main
lein typed check
lein typed coverage
lein uberjar main
java -jar ./target/hello-clj-0.1.0-SNAPSHOT-standalone.jar
```