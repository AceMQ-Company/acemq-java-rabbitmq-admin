# Metrics

`PrometheusMetrics` reads RabbitMQ's Prometheus endpoint and answers the
question the management API answers slowly and AMQP cannot answer at all: **how
many messages are waiting in this queue, right now.**

```java
PrometheusMetrics metrics = PrometheusMetrics.at("http://localhost:15692");

long waiting = metrics.scrapeDetailed()
        .forQueue("orders.new")
        .metric("rabbitmq_detailed_queue_messages_ready")
        .map(MetricSample::asLong)
        .orElse(0L);
```

## A different port, a different plugin, no credentials

| | Management API | Prometheus endpoint |
|---|---|---|
| Port | 15672 | **15692** |
| Plugin | `rabbitmq_management` | **`rabbitmq_prometheus`** |
| Credentials | Required | **None** |
| Cost per queue | One request | One scrape for all of them |

```bash
rabbitmq-plugins enable rabbitmq_prometheus
```

It is unauthenticated by default. That is deliberate on RabbitMQ's part —
Prometheus scrapers are usually inside the perimeter — and worth knowing:
anything that can reach that port can read every queue name on the broker.
Do not expose 15692 to anywhere you would not expose the queue names themselves.

## Two endpoints, and the difference matters

**`/metrics` is aggregate.** `rabbitmq_queue_messages_ready` with no labels: the
total across the whole broker. It cannot tell you which queue is deep, and a
dashboard built on it shows a backlog without saying where.

```java
MetricsSnapshot broker = metrics.scrape();
broker.metric("rabbitmq_queue_messages_ready");   // one sample, no queue label
```

**`/metrics/detailed` is per-object**, and only for the metric families you ask
for. The names gain a `rabbitmq_detailed_` prefix and the labels appear:

```
rabbitmq_detailed_queue_messages_ready{vhost="/",queue="orders.new"} 42
```

```java
MetricsSnapshot detailed = metrics.scrapeDetailed();
detailed.queues();                                 // [orders.new, orders.dlq, ...]
detailed.sum("rabbitmq_detailed_queue_messages_ready");   // total across all of them
```

A rule or query written against the aggregate name will **silently match
nothing** on a detailed scrape, and vice versa. The prefix is the whole
difference and there is no error when you get it wrong.

### Why the detailed scrape is always filtered

`scrapeDetailed()` asks for three families. The unfiltered form asks the broker
to enumerate every object it has, which on an estate with thousands of queues is
the most expensive request anybody makes of it.

```java
// Exactly the families you name.
MetricsSnapshot m = metrics.scrapeDetailed("queue_coarse_metrics", "queue_consumer_count");
```

Passing an empty array is refused rather than treated as "everything".

## Querying a snapshot

A `MetricsSnapshot` is an immutable parsed scrape.

| Method | Returns |
|---|---|
| `metric(name)` | The first sample, as `Optional` |
| `all(name)` | Every sample — one per queue on a detailed scrape |
| `sum(name)` | Every value added up |
| `forQueue(name)` | A snapshot narrowed to one queue |
| `withLabel(k, v)` | Narrowed by any label |
| `where(predicate)` | Narrowed by anything |
| `queues()` | Queue names present |
| `helpFor(name)` | The `# HELP` text |

They compose, and each returns a new snapshot:

```java
long unacked = metrics.scrapeDetailed()
        .withLabel("vhost", "billing")
        .forQueue("invoices")
        .metric("rabbitmq_detailed_queue_messages_unacked")
        .map(MetricSample::asLong)
        .orElse(0L);
```

## Parsing a scrape from somewhere else

A scrape does not have to come from here. Prometheus itself, a sidecar, a file
captured during an incident:

```java
MetricsSnapshot snapshot = PrometheusMetrics.parse(Files.readString(path));
```

The parser handles the shapes that break a naive one: a comma or an escaped
quote inside a label value, and a trailing timestamp that is not the value.

Non-finite values are skipped. `+Inf` and `-Inf` are rejected by
`Double.parseDouble` anyway — Java wants `Infinity` — but **`NaN` is accepted by
it**, and RabbitMQ emits `NaN` for a gauge it cannot yet compute. Letting one
through would turn every `sum()` over that family into `NaN`: a broker-wide
total quietly becoming "not a number".

## Which metrics exist

Do not guess. A metric name that RabbitMQ does not emit produces no error
anywhere — `all(name)` returns an empty list, an alert built on it never fires,
and the whole thing looks exactly like a healthy broker.

```bash
curl -s localhost:15692/metrics | grep '^rabbitmq_' | sed 's/[ {].*//' | sort -u
```

The ones this library's [alerts](alerts.md) use:

| Metric | Endpoint |
|---|---|
| `rabbitmq_detailed_queue_messages_ready` | detailed |
| `rabbitmq_detailed_queue_consumers` | detailed |
| `rabbitmq_alarms_memory_used_watermark` | aggregate |
| `rabbitmq_alarms_free_disk_space_watermark` | aggregate |
| `rabbitmq_disk_space_available_bytes` | aggregate |

There is no `rabbitmq_connections_blocked`, despite it being an obvious name for
the thing you want. The two alarm watermark gauges are the real signal for a
broker refusing publishes.

## Next

- [Alerts](alerts.md) — turning these numbers into something that pages
- [Tutorial: queue depth without a consumer](tutorial-queue-depth.md)
