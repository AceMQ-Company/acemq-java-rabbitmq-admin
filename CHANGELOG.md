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
- **Federation**: `federationUpstreams()`, `putFederationUpstream(...)`,
  `deleteFederationUpstream(...)` and `federationLinks()`.

  An upstream on its own federates nothing — it names a broker, and a *policy*
  carrying `federation-upstream` is what links anything to it. That two-step is
  the usual reason federation appears not to work, and nothing reports it because
  nothing is wrong. `federationLinks()` is the only proof it is working: an
  upstream can be declared and a policy can match and there can still be no link.
- **Shovel writes**: `declareShovel(...)` and `deleteShovel(...)`, which is the
  half a migration needs. A shovel *consumes*, so a message it moves is gone from
  the source — right for draining a queue, wrong for copying a stream of events,
  which is federation's job.
- **`ParameterInfo`** and `parameters(component)`. Federation upstreams and
  dynamic shovels are both runtime parameters, so a topology export that walks
  queues, exchanges and bindings misses both — and a broker restored from one
  comes back without its federation and looks complete.
- **Credentials in a federation URI are redacted in every printed form.** The
  broker stores and returns `amqp://user:password@host`, so these objects hold a
  password whether anybody wanted them to or not. `uri()` returns it because
  comparing two upstreams needs it; `redactedUri()`, `toString()` and
  `ParameterInfo.toString()` never do.
- **`PrometheusMetrics`** — reads the Prometheus endpoint on port 15692, which is
  a different port and a different plugin from the management API, and needs no
  credentials. `scrapeDetailed()` answers "how many messages are waiting in this
  queue", which the aggregate `/metrics` cannot: it reports one broker-wide total
  with no queue label, so a dashboard built on it shows a backlog without saying
  where. The detailed scrape is always filtered to named metric families, because
  an unfiltered one asks the broker to enumerate every object it has.
- **`MetricsSnapshot` / `MetricSample`** — a parsed scrape, queryable by metric,
  by queue, or summed. The parser handles the shapes that break a naive one: a
  comma or an escaped quote inside a label value, and a trailing timestamp that
  is not the value.
- **`Alerts`, `Alert`, `AlertRule`** — a fluent DSL for alert rules that are used
  twice: `evaluate(snapshot)` checks one scrape in-process, `toPrometheusRule()`
  renders the same rule as Prometheus alerting YAML. One definition, so a
  deployment gate, a health endpoint and the on-call page cannot drift apart.
  `lasting(...)` is carried into the generated rule and cannot be honoured
  in-process, which the javadoc says rather than pretending.

  `Alerts.recommended()` is five rules. Queue depth alone is deliberately not one
  of them: a queue is a buffer, so a depth threshold fires during every normal
  burst until it is muted. `queueNotDraining()` requires depth *and* zero
  consumers, expressed as `and on(vhost, queue)` in PromQL and as a label join
  in `evaluate`.
- A queue that does not exist is `Optional.empty()`; a 401 is an exception that
  says these are the broker's own users and need the monitoring or administrator
  tag. Reporting bad credentials as "no queue" would send somebody looking in
  entirely the wrong place.

- **Health checks** — `health()` over the twelve `/api/health/checks/` endpoints.
  A failing check answers HTTP 503, which is the check working rather than the
  request failing, so `HealthResult` is a value and not an exception;
  `orThrow()` is there when an exception is what you want. Two of these have no
  metric equivalent: `quorumCritical()`, which decides whether a rolling restart
  may continue, and `certificateExpiration(...)`. `checkAll()` and `failures()`
  deliberately use the node-local checks, because a load balancer probing the
  cluster-wide ones takes every node out of rotation at once.
- **`exportDefinitions()` / `importDefinitions(...)`** — the whole broker's
  configuration in one document, including the runtime parameters that carry
  federation upstreams and shovels. A backup assembled by walking queues,
  exchanges and bindings omits those, and the restored broker looks complete
  while federating nothing. Import is a **merge**: everything in the file is
  applied, nothing absent from it is removed.
- **`connections()`, `channels()`, `consumers()`, `closeConnection(...)`** —
  which client is doing this, and the ability to disconnect one with a reason
  the client is told. `ChannelInfo.isAtPrefetchLimit()` distinguishes a consumer
  that has stopped from one that is slow, which the queue's own counters cannot.
- **Topology writes**: `declareQueue`, `deleteQueue`, `purgeQueue`,
  `declareExchange`, `deleteExchange`, `bindQueue`, `bindExchange`, `unbind`.
  `unbind` takes a `BindingInfo` rather than a routing key because a binding is
  identified by a `properties_key` that arrives already percent-encoded, and
  encoding it again produces a 404 that looks like a binding already removed.
- **Operator policies, vhost and user limits, topic permissions** — the controls
  a tenant cannot override, the ceilings that stop one application exhausting a
  broker, and the third permission surface. A user with **no** topic permissions
  is unrestricted on topic exchanges, so an empty list means nothing is being
  enforced rather than everything being locked down.
- **`whoami()`, `nodes()`, `clusterName()`, `featureFlags()`,
  `deprecatedFeaturesInUse()`, `globalParameters()`** — `whoami()` at startup
  turns a later 403 into a clear message; the feature-flag and deprecated-feature
  lists are upgrade readiness. `GlobalParameterInfo` exists separately from
  `ParameterInfo` because a global parameter's value is not an object:
  `internal_cluster_id` is a string and `cluster_tags` is an array, and every
  broker has both, so a `Map<String, Object>` model fails to parse against any
  real broker.

### Notes

Three defects in the metrics and alerts work were found by testing against a
running broker rather than a hand-written scrape, and are recorded because each
would have shipped as silence rather than as a failure.

- A shipped rule named `rabbitmq_connections_blocked`, which RabbitMQ does not
  emit. It compiled, passed its unit tests, and rendered valid Prometheus YAML,
  and it could never have fired. `PrometheusMetricsIT` now asserts that every
  metric named by a shipped rule exists on a real broker.
- `deadLetters()` was `queue_messages_ready > 0` with no queue filter, so it
  fired at critical for every ordinary queue holding a single message. Its test
  only checked a queue called `x.dlq` and so never saw it. `Alert.where(...)`
  was added, and it applies to the exported expression as well as to the
  in-process check.
- The scrape parser skipped malformed numbers by catching
  `NumberFormatException`, which does not cover `NaN` — `Double.parseDouble`
  accepts it. One `NaN` turned any `sum()` over that family into `NaN`.
