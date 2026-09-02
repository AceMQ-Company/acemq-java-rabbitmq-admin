# acemq-java-rabbitmq-admin

RabbitMQ's **HTTP management API** for Java: users, vhosts, permissions,
policies, federation, shovels — and the queue facts AMQP will not tell you.

> **Status: working, unreleased.** Queues, exchanges, bindings, vhosts, users,
> permissions, policies and shovels are implemented and tested against a real
> broker. Nothing is published anywhere yet. See
> [what it will not do](#what-it-will-not-do) for the boundaries.

```java
try (RabbitAdmin admin = RabbitAdmin.connect("http://localhost:15672", "guest", "guest")) {
    QueueInfo queue = admin.queue("orders.new").orElseThrow();
    queue.argument("x-message-ttl");                  // what the broker actually holds

    admin.bindingsForQueue("orders.new");             // invisible to AMQP entirely
    admin.grant("orders-service", "^orders\\.", "^orders$", "^orders\\.new$");
    admin.putPolicy("ttl", "^orders\\.", Map.of("message-ttl", 60000), 1);
}
```

## Why it is a separate repository

`acemq-java-amqp` speaks AMQP 0-9-1, on port 5672, to any broker that implements
it. This speaks HTTP, on port 15672, to RabbitMQ specifically — a
product-specific interface with a different protocol, a different port, a
different authentication model, and a different failure mode.

**Nothing in the message path may depend on it.** A publisher that cannot publish
because a management endpoint is unreachable would be a worse publisher, and a
library that quietly reached for HTTP to answer a question about a queue would
have made its portability claim untrue. Keeping it in another repository is how
that stays enforced rather than merely intended.

## What it is for

Four things, in the order they are worth building.

### 1. The questions AMQP cannot answer

AMQP has no way to read a queue's arguments back. This is not an oversight in
`acemq-java-amqp`; it is the protocol. The consequence shows up in two places
already:

- **Topology drift.** `TopologyPlanner` detects a mismatched queue by offering a
  declaration and reading the broker's 406 refusal. That works and is a
  reasonable trick, and it can only report *that* something differs plus whatever
  RabbitMQ chose to say. The management API can report what the queue actually
  is.
- **Per-step queue depth.** `Pipeline` counts what it handled — `entered()`,
  `completed()`, `inFlight()` — and cannot tell you how many messages are waiting
  at a given step, because a consumer does not know what it has not been given.

### 2. Provisioning

Users, vhosts, permissions, policies. The things a team does by hand in the
management UI, done as code that can be reviewed, and applied the way
`Topology` is: computed, printed, and only then applied.

### 3. Moving messages between brokers

Shovels and federation are configured through this API and nowhere else. They
are also the mechanism behind blue/green broker migration, which is what
`acemq-infrastructure` is for — this library is the half that talks to the
broker.

### 4. Metrics, and alerts that mean something

`PrometheusMetrics` reads RabbitMQ's Prometheus endpoint — port 15692, the
`rabbitmq_prometheus` plugin, no credentials — and answers the question
`Pipeline` cannot: how many messages are waiting in a given queue, right now.

```java
PrometheusMetrics metrics = PrometheusMetrics.at("http://localhost:15692");

long waiting = metrics.scrapeDetailed()
        .forQueue("orders.new")
        .metric("rabbitmq_detailed_queue_messages_ready")
        .map(MetricSample::asLong)
        .orElse(0L);
```

`/metrics` is aggregate — one broker-wide total, no queue labels — and
`/metrics/detailed` is per-object, for the families you name. Asking for
everything makes the broker enumerate every object it has, so the detailed
scrape is always filtered.

`Alerts` builds rules that are used twice: evaluated here, against one scrape,
and exported as Prometheus alerting-rule YAML.

```java
AlertRule rule = Alert.named("queue-not-draining")
        .on("rabbitmq_detailed_queue_messages_ready")
        .above(100)
        .whileZero("rabbitmq_detailed_queue_consumers")
        .lasting(Duration.ofMinutes(5))
        .groupedBy("vhost", "queue")
        .because("messages are waiting and no consumer has taken them");

List<AlertEvent> firing = rule.evaluate(metrics.scrapeDetailed());
String yaml = rule.toPrometheusRule();
```

One definition, so a deployment gate, a health endpoint and the on-call page
cannot drift apart. `Alerts.recommended()` is five rules, and the list is short
deliberately: a pack of thirty alerts is a pack of thirty things to silence.

Note what is **not** in it. Queue depth alone is not an alert — a queue is a
buffer and having things in it is the job, so a depth threshold fires during
every normal burst until somebody mutes it, taking the one real occurrence with
it. `queueNotDraining()` requires depth *and* zero consumers, which is never
normal.

## What it will not do

- **It will not become a second message path.** No publishing, no consuming.
  RabbitMQ's management API can publish a message; it is a debugging facility,
  it is slow, and using it in an application is a mistake this library will not
  make convenient.
- **It will not pretend to be portable.** Every method here is RabbitMQ's. There
  is no SPI and no second implementation, because there is no second broker with
  this API.
- **It will not be required by anything in `acemq-java-amqp`.** If a feature
  there would need this, the feature is either redesigned or documented as
  absent. That rule is the reason the split exists.

## Six things the broker taught us

Each cost a test failure first, and each is the sort of thing that is obvious
afterwards and invisible before.

**The default vhost is literally `/`** and must reach the URL as `%2F`.
Unencoded it produces `/api/queues///orders.new`, which 404s — and a 404 is
indistinguishable from a queue that is not there.

**`URLEncoder` turns a space into `+`.** Right for a form body, wrong for a path:
a queue named `dead letters` would be looked up as `dead+letters` and reported
missing.

**RabbitMQ 4 records `x-queue-type` on every queue**, including ones declared
with no arguments at all. Comparing a queue's arguments against a topology by
equality therefore reports drift on every classic queue. Worth knowing before
writing that comparison rather than after.

**`HttpClient` defaults to HTTP/2 and the management API mishandles the h2c
upgrade for requests with a body.** Every `GET` succeeded and every `PUT` died
with `EOF reached while reading`, so reads worked and provisioning silently did
not. Reproduced with a bare `HttpClient` and no library code involved. The client
now pins HTTP/1.1.

**`rabbitmq_connections_blocked` does not exist.** A rule was written against it
to catch a broker refusing publishes. It compiled, passed every unit test written
against a hand-made scrape, and rendered valid Prometheus YAML — and it could
never have fired, in either half, because RabbitMQ does not emit that metric. The
real signals are `rabbitmq_alarms_memory_used_watermark` and
`rabbitmq_alarms_free_disk_space_watermark`. `PrometheusMetricsIT` now asserts
that every metric named by a shipped rule exists on a running broker, because a
wrong metric name is not a compile error and not a test failure — it is silence,
which is exactly what a working alert looks like.

**`Double.parseDouble("NaN")` succeeds.** The parser skipped malformed values by
catching `NumberFormatException`, which covers `+Inf` (Java wants `Infinity`) but
not `NaN`. RabbitMQ emits `NaN` for a gauge it cannot yet compute, and a single
one turned every `sum()` over that family into `NaN` — a broker-wide total
quietly becoming "not a number". Non-finite values are now excluded explicitly.

## A policy is not an argument

The distinction that makes a topology comparison right or wrong:

- an **argument** is fixed when the queue is declared and cannot be changed
  without deleting the queue;
- a **policy** is applied afterwards, matches by pattern, and can be edited at
  any time.

A queue governed by a policy shows **nothing** in its arguments. A drift check
reading only arguments would call it plain. There is a test asserting exactly
that, because it is the mistake this library exists to make avoidable.

Two more: where several policies match, the highest priority wins *outright*
rather than merging; and an argument beats a policy, so a policy that appears to
do nothing is often being overridden by an argument nobody remembers setting.

## Requirements

|  | |
|---|---|
| Bytecode target | **Java 11**, matching `acemq-java-amqp` |
| Build toolchain | JDK 17 or newer |
| RabbitMQ | The `rabbitmq_management` plugin enabled |

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
