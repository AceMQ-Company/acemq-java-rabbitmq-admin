# Tutorial 4 — Alerts that mean something

**25 minutes.** Build alert rules that are evaluated in your process *and*
exported to Prometheus from one definition — and see why most queue alerts get
muted within a week.

Continue from [tutorial 3](tutorial-queue-depth.md): 150 messages in
`orders.new`, 1 in `orders.dlq`, no consumers.

## Step 1 — The obvious alert, and why it is wrong

```java
import org.acemq.rabbitmq.admin.alerts.*;

PrometheusMetrics metrics = PrometheusMetrics.at("http://localhost:15692");

AlertRule naive = Alert.named("queue-deep")
        .on("rabbitmq_detailed_queue_messages_ready")
        .above(100)
        .because("a queue is deep");

naive.evaluate(metrics.scrapeDetailed())
        .forEach(e -> System.out.println(e.message()));
```

```
[WARNING] queue-deep: rabbitmq_detailed_queue_messages_ready is 150 for queue=orders.new — a queue is deep
```

It fires. It is also an alert you will mute.

A queue is a buffer; having things in it is the job. Any threshold you pick is
crossed during every normal burst. The alert fires, somebody looks, nothing is
wrong, and by the fourth time it is silenced — taking with it the one occasion
it would have mattered.

## Step 2 — Add the condition that makes it real

The case that is *never* normal is a backlog with nothing reading it.

```java
AlertRule real = Alert.named("queue-not-draining")
        .on("rabbitmq_detailed_queue_messages_ready")
        .above(100)
        .whileZero("rabbitmq_detailed_queue_consumers")
        .groupedBy("vhost", "queue")
        .because("messages are waiting and no consumer has taken them");

System.out.println(real.isFiring(metrics.scrapeDetailed()));    // true
```

Now attach a consumer and watch it go quiet:

```bash
docker exec -d rabbit sh -c 'rabbitmqadmin consume --queue orders.new --count 1 --ack-mode reject_requeue_true'
```

Simpler: publish to a queue that *has* a consumer and see that it does not fire.
The rule only fires when depth and zero consumers are true for the **same
queue** — that is what `groupedBy` is for. It becomes `and on(vhost, queue)` in
PromQL and a label join in `evaluate`, and without it the join would be
broker-wide and mean something else.

## Step 3 — Export the same rule

```java
System.out.println(real.toPrometheusRule());
```

```yaml
- alert: QueueNotDraining
  expr: rabbitmq_detailed_queue_messages_ready > 100 and on(vhost, queue) rabbitmq_detailed_queue_consumers == 0
  labels:
    severity: warning
  annotations:
    summary: "messages are waiting and no consumer has taken them"
    description: "rabbitmq_detailed_queue_messages_ready is {{ $value }}, which is above 100 while rabbitmq_detailed_queue_consumers is zero. vhost={{ $labels.vhost }} queue={{ $labels.queue }}"
```

This is the point of the library. The check your deployment gate runs and the
rule that pages at 3am are the same object. They cannot drift, because there is
only one of them.

## Step 4 — `lasting` is honest about what it cannot do

```java
AlertRule sustained = Alert.named("queue-not-draining")
        .on("rabbitmq_detailed_queue_messages_ready")
        .above(100)
        .whileZero("rabbitmq_detailed_queue_consumers")
        .lasting(Duration.ofMinutes(5))
        .groupedBy("vhost", "queue")
        .because("nothing has consumed for five minutes");
```

The exported rule gains `for: 5m`. **`evaluate` cannot honour it** — one scrape
has no history — so in-process it is more eager than the deployed alert.

That difference is documented rather than hidden. Reading one scrape means no
history, no deduplication, no silencing and no routing; this is not a
replacement for Alertmanager. Use `evaluate` for questions with an answer now,
and the exported rule for things that must be true over time.

## Step 5 — The filter that stops a critical alert firing on everything

A dead letter is different from a backlog: one message is already a message
nobody processed, so the threshold is zero.

```java
AlertRule wrong = Alert.named("dead-letters")
        .on("rabbitmq_detailed_queue_messages_ready")
        .aboveZero()
        .severity(Severity.CRITICAL)
        .because("a message was dead-lettered");

wrong.evaluate(metrics.scrapeDetailed())
        .forEach(e -> System.out.println(e.queue().orElse("?")));
```

```
orders.new
orders.dlq
```

`orders.new` is doing its job and this rule just paged somebody at critical
about it. The metric is per-queue, and unfiltered it watches every queue on the
broker.

```java
AlertRule right = Alerts.deadLetters();       // .where("queue", ".*\\.(dlq|dead-letter)")

right.evaluate(metrics.scrapeDetailed())
        .forEach(e -> System.out.println(e.queue().orElse("?")));
```

```
orders.dlq
```

The pattern matches **in full**, not as a substring — Java's `matches()` and
PromQL's `=~` are both anchored — so a queue called `dlq.replayed` does not
match `.*\.dlq`. And the filter reaches the exported expression too:

```java
System.out.println(right.toPrometheusRule().contains("queue=~"));    // true
```

A selector applied only in `evaluate` would mean the deployed alert is the
unfiltered one, which is exactly the drift this is meant to prevent.

## Step 6 — The recommended set

```java
Alerts.AlertSet set = Alerts.recommended();

set.evaluate(metrics.scrapeDetailed()).forEach(e -> System.out.println(e.message()));

System.out.println("critical: " + set.hasCritical(metrics.scrapeDetailed()));
```

Five rules. `evaluate` returns the worst first, so code that prints one line
prints the most serious thing rather than the first-declared thing.

Write the whole file for Prometheus:

```java
Files.writeString(Path.of("acemq-alerts.yml"), set.toPrometheusRules());
```

## Step 7 — Prove your metric names exist

This is the step people skip, and it is the one that matters most.

```java
MetricsSnapshot detailed = metrics.scrapeDetailed();
MetricsSnapshot aggregate = metrics.scrape();

for (AlertRule rule : Alerts.recommended().rules()) {
    String name = rule.metric();
    MetricsSnapshot where = name.startsWith("rabbitmq_detailed_") ? detailed : aggregate;
    if (where.all(name).isEmpty()) {
        System.out.println("NEVER FIRES: " + rule.name() + " -> " + name);
    }
}
```

Try it with a name that does not exist:

```java
AlertRule ghost = Alert.named("publishers-blocked")
        .on("rabbitmq_connections_blocked")     // RabbitMQ does not emit this
        .aboveZero()
        .because("the broker is refusing publishes");

System.out.println(ghost.isFiring(metrics.scrape()));            // false, always
System.out.println(ghost.toPrometheusRule().contains("expr"));   // true — perfectly valid YAML
```

It compiles, it produces a valid Prometheus rule, and it can never fire. It is
silent in exactly the way a working alert is silent, and you find out during the
incident it should have caught.

The real signals for a blocked publisher are
`rabbitmq_alarms_memory_used_watermark` and
`rabbitmq_alarms_free_disk_space_watermark`. This library's own test suite
asserts that every metric in `Alerts.recommended()` exists on a running broker,
for exactly this reason.

## What you have

- A depth alert that will not be muted, because it requires zero consumers
- One rule driving both the in-process check and the Prometheus alert
- A label filter that keeps a critical alert off healthy queues
- A check that your metric names are real

Next: [federating two brokers](tutorial-federation.md).
