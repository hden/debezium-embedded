# debezium-embedded [![CircleCI](https://circleci.com/gh/hden/debezium-embedded/tree/master.svg?style=svg)](https://circleci.com/gh/hden/debezium-embedded/tree/master) [![Clojars Project](https://img.shields.io/clojars/v/hden/debezium-embedded.svg)](https://clojars.org/hden/debezium-embedded)
A Clojure wrapper for the debezium-embedded engine.

> Not every application needs this level of fault tolerance and reliability, and they may not want to rely upon an external cluster of Kafka brokers and Kafka Connect services. Instead, some applications would prefer to embed Debezium connectors directly within the application space. They still want the same data change events, but prefer to have the connectors send them directly to the application rather than persists them inside Kafka.
> This debezium-embedded module defines a small library that allows an application to easily configure and run Debezium connectors. -- [source](https://github.com/debezium/debezium/tree/master/debezium-embedded)

See also:
- https://github.com/hden/debezium-embedded-jdbc

## License
Copyright © 2019 Haokang Den
