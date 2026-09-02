# Tutorial 3 — Queue depth without a consumer

**15 minutes.** Read how many messages are waiting in each queue, from the
metrics endpoint, in one request for the whole broker.

You need the Prometheus plugin from the [tutorials page](tutorials.md):

```bash
docker exec rabbit rabbitmq-plugins enable rabbitmq_prometheus
```

## Step 1 — Some messages to count

```bash
docker exec rabbit rabbitmqadmin declare queue --name orders.new --durable true
docker exec rabbit rabbitmqadmin declare queue --name orders.dlq --durable true

docker exec rabbit sh -c \
  'for i in $(seq 1 150); do rabbitmqadmin publish message -k orders.new -m "order-$i" >/dev/null; done'
docker exec rabbit rabbitmqadmin publish message -k orders.dlq -m failed
```

150 in one queue, 1 in the other, and no consumer on either.

## Step 2 — Scrape

```java
import org.acemq.rabbitmq.admin.metrics.*;

PrometheusMetrics metrics = PrometheusMetrics.at("http://localhost:15692");

MetricsSnapshot broker = metrics.scrape();
System.out.println(broker.metric("rabbitmq_queue_messages_ready").orElseThrow().asLong());
```

```
151
```

Note the port: **15692**, not 15672. Different plugin, different port, and no
credentials — `PrometheusMetrics.at` takes no username or password because the
endpoint does not ask for one.

151 is correct and useless. Which queue is deep?

## Step 3 — The aggregate endpoint cannot tell you

```java
System.out.println(broker.all("rabbitmq_queue_messages_ready").size());   // 1
System.out.println(broker.queues());                                     // []
```

One sample, no queue label. `/metrics` is a broker-wide total by design. A
dashboard built on it shows a backlog without saying where it is.

## Step 4 — The detailed endpoint

```java
MetricsSnapshot detailed = metrics.scrapeDetailed();

System.out.println(detailed.queues());
System.out.println(detailed.forQueue("orders.new")
        .metric("rabbitmq_detailed_queue_messages_ready")
        .orElseThrow().asLong());
```

```
[orders.new, orders.dlq]
150
```

Two things changed. The samples now carry `vhost` and `queue` labels, and the
metric names gained a **`rabbitmq_detailed_`** prefix:

```
rabbitmq_detailed_queue_messages_ready{vhost="/",queue="orders.new"} 150
```

Get that prefix wrong and nothing errors. `all("rabbitmq_queue_messages_ready")`
on a detailed scrape returns an empty list, which looks exactly like an empty
broker.

## Step 5 — Every queue at once

```java
for (String queue : detailed.queues()) {
    long ready = detailed.forQueue(queue)
            .metric("rabbitmq_detailed_queue_messages_ready")
            .map(MetricSample::asLong)
            .orElse(0L);
    long consumers = detailed.forQueue(queue)
            .metric("rabbitmq_detailed_queue_consumers")
            .map(MetricSample::asLong)
            .orElse(0L);

    System.out.printf("%-14s %6d ready  %d consumers%n", queue, ready, consumers);
}
```

```
orders.new       150 ready  0 consumers
orders.dlq         1 ready  0 consumers
```

One HTTP request for the whole broker. Compare with `admin.queues()` from
[tutorial 1](tutorial-first-look.md), which returns far more data per queue and
is the wrong tool at a thousand queues.

## Step 6 — Why the detailed scrape is filtered

`scrapeDetailed()` asks for three metric families, not everything. The
unfiltered form makes the broker enumerate every object it has, which on a large
estate is the most expensive request anybody makes of it.

Ask for exactly what you need:

```java
MetricsSnapshot m = metrics.scrapeDetailed("queue_coarse_metrics");
System.out.println(m.samples().size());
```

Passing an empty array is refused rather than quietly meaning "all".

## Step 7 — Composing a query

Each of these returns a new snapshot, so they chain:

```java
double waitingInDefaultVhost = metrics.scrapeDetailed()
        .withLabel("vhost", "/")
        .sum("rabbitmq_detailed_queue_messages_ready");

System.out.println(waitingInDefaultVhost);       // 151.0
```

```java
List<String> deep = metrics.scrapeDetailed()
        .where(s -> s.name().endsWith("queue_messages_ready") && s.value() > 100)
        .queues();

System.out.println(deep);                        // [orders.new]
```

## Step 8 — Do not guess metric names

A name the broker does not emit produces no error at all — an empty list, and
everything downstream behaves as though the broker were idle.

```bash
docker exec rabbit sh -c "curl -s localhost:15692/metrics | grep '^rabbitmq_' | sed 's/[ {].*//' | sort -u"
```

That is the authoritative list for your broker and version. There is, for
example, no `rabbitmq_connections_blocked` — an obvious name for a real thing
that simply does not exist. The next tutorial shows what happens when a rule is
built on a name like that, and how to stop it.

## What you have

- Messages per queue, for the whole broker, in one request
- The `rabbitmq_detailed_` prefix, and that getting it wrong is silent
- Why the detailed scrape is always filtered

Next: [alerts that mean something](tutorial-alerts.md).
