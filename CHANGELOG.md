# Changelog

All notable changes to this project are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and this
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

While the version is `0.x` the public API may change in any release.

This library has its own version line, starting at `0.1.0`. It tracks RabbitMQ's
management API rather than AceMQ's messaging API, and the two move for different
reasons.

## [Unreleased]

### Added
- The repository: licence, notice, build, and a README that says what this is for
  and what it will not do.
- **`RabbitAdmin`** — a client for the management API over `java.net.http`, with
  no HTTP client dependency. `queue(name)` reports the arguments the broker
  actually holds, which is the thing AMQP cannot do: a passive declare says a
  queue exists and a real declare says whether your arguments match by refusing,
  and neither reports what they are.
- **`QueueInfo`** — name, vhost, type, durability, arguments, and the counters.
  `messagesReady()` rather than `messages()` is the number a depth alert wants:
  the total includes deliveries already handed to a consumer, so it stays high
  while a consumer works through a batch and says nothing about draining.

  Three things learned from the broker rather than assumed, each with a test:
  the default vhost is literally `/` and must reach the URL as `%2F`;
  `URLEncoder` turns a space into `+`, which a path reads literally; and
  **RabbitMQ 4 records `x-queue-type` itself**, so comparing a queue's arguments
  against a topology by equality would report drift on every classic queue.
- A queue that does not exist is `Optional.empty()`; a 401 is an exception that
  says these are the broker's own users and need the monitoring or administrator
  tag. Reporting bad credentials as "no queue" would send somebody looking in
  entirely the wrong place.
