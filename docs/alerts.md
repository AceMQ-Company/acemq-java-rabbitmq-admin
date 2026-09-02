# Alerts

A rule written once and used twice: evaluated here, against one scrape, and
exported as Prometheus alerting-rule YAML.

```java
AlertRule rule = Alert.named("queue-not-draining")
        .on("rabbitmq_detailed_queue_messages_ready")
        .above(100)
        .whileZero("rabbitmq_detailed_queue_consumers")
        .lasting(Duration.ofMinutes(5))
        .severity(Severity.WARNING)
        .groupedBy("vhost", "queue")
        .because("messages are waiting and no consumer has taken them");

List<AlertEvent> firing = rule.evaluate(metrics.scrapeDetailed());
String yaml = rule.toPrometheusRule();
```

## Why both halves

A deployment gate, a health endpoint and the on-call page otherwise express one
intention in three places, in two languages, and drift apart. The way you find
out is an incident that nothing paged for, because the threshold in Prometheus
was changed a year ago and the health check was not.

One definition cannot drift from itself.

## The recommended set

```java
List<AlertEvent> firing = Alerts.recommended().evaluate(metrics.scrapeDetailed());
firing.forEach(event -> log.warn(event.message()));

if (Alerts.recommended().hasCritical(metrics.scrape())) {
    // fail the deployment, fail the health check
}

Files.writeString(Path.of("acemq-alerts.yml"), Alerts.recommended().toPrometheusRules());
```

Five rules, and the list is short on purpose. A starter pack of thirty alerts is
a starter pack of thirty things to silence, and the ones that survive contact
with an on-call rota are the ones that stay quiet until something is genuinely
wrong.

| Rule | Fires when | Severity |
|---|---|---|
| `queueNotDraining()` | 100+ ready **and zero consumers**, for 5 minutes | WARNING |
| `deadLetters()` | Anything in a dead-letter queue | CRITICAL |
| `memoryAlarm()` | Memory watermark alarm in effect | CRITICAL |
| `diskAlarm()` | Free-disk watermark alarm in effect | CRITICAL |
| `diskAlarmApproaching()` | Under 2 GB free, for 5 minutes | WARNING |

`evaluate` on a set returns the worst finding first, so code that prints one
line prints the most serious thing rather than the first-declared thing.

## Queue depth is deliberately not an alert

A queue is a buffer. Having things in it is the job. Depth crosses any threshold
you pick during every normal burst, so the alert fires, nothing is wrong, and
within a week somebody mutes it — taking with it the one occasion it would have
mattered.

`queueNotDraining()` requires depth **and no consumers**, which is never normal.
That is what `whileZero` is for:

```java
.above(100)
.whileZero("rabbitmq_detailed_queue_consumers")
.groupedBy("vhost", "queue")
```

It becomes `and on(vhost, queue)` in the exported PromQL, and a label join here.
`groupedBy` is required with `whileZero`, because without it the join is
broker-wide and means something else entirely.

## Narrowing to the right objects

A per-queue metric with no filter watches **every queue on the broker**. Right
for "is any queue not draining"; badly wrong for "is anything in a dead-letter
queue", which unfiltered is a critical alert that fires whenever any ordinary
queue holds a single message.

```java
Alert.named("dead-letters-present")
        .on("rabbitmq_detailed_queue_messages_ready")
        .where("queue", ".*\\.(dlq|dead-letter)")
        .aboveZero()
```

The pattern is matched **in full**, not as a substring — Java's `matches()` and
PromQL's `=~` are both anchored, so `orders` does not match `orders.new` in
either. Use `.*\.dlq` for a suffix.

If your estate names them differently:

```java
AlertRule rule = Alerts.deadLetters("^dead\\..*");
```

The filter reaches the exported expression as well as the in-process check. A
selector applied only in `evaluate` would mean the deployed alert is the
unfiltered one — the exact drift this class exists to prevent.

## What `evaluate` cannot do

Reading one scrape, in one process, means no history, no deduplication, no
silencing and no routing.

**`lasting(...)` is not honoured in-process.** It is carried into the generated
rule, where a real time-series database can evaluate it. A rule with a duration
is therefore *more eager* here than the deployed one, and that is documented
rather than hidden.

This is not a replacement for Prometheus and Alertmanager. Use the in-process
evaluation for a question with an answer now — is this deployment safe, is this
broker healthy, should this CLI print red. Use the generated rule for things
that must be true over time.

## Exporting

```java
System.out.println(Alerts.queueNotDraining().toPrometheusRule());
```

```yaml
- alert: QueueNotDraining
  expr: rabbitmq_detailed_queue_messages_ready > 100 and on(vhost, queue) rabbitmq_detailed_queue_consumers == 0
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "messages are waiting and no consumer has taken them for five minutes. Check whether the consuming service is running and connected."
    description: "rabbitmq_detailed_queue_messages_ready is {{ $value }}, which is above 100 while rabbitmq_detailed_queue_consumers is zero. vhost={{ $labels.vhost }} queue={{ $labels.queue }}"
```

`toPrometheusRules()` on a set renders a complete rules file, ready to load.

## A reason is required

`because(...)` is not optional and cannot be empty. The question at three in the
morning is never "is this number high" but "what breaks if I ignore it", and an
alert whose reason nobody wrote down is one that gets silenced the second time
it fires.

## Verify your metric names against a real broker

A rule naming a metric RabbitMQ does not emit compiles, passes unit tests
written against a hand-made scrape, and renders valid YAML. It can never fire —
in either half — and it is silent in exactly the way a working alert is silent.

This library ships a test that asserts every metric named by
`Alerts.recommended()` exists on a running broker. Worth doing for your own
rules too:

```java
MetricsSnapshot detailed = metrics.scrapeDetailed();
if (detailed.all(myRule.metric()).isEmpty()) {
    throw new IllegalStateException(myRule.metric() + " is not emitted by this broker");
}
```

## Next

- [Tutorial: alerts that mean something](tutorial-alerts.md)
- [Metrics](metrics.md) — where the numbers come from
