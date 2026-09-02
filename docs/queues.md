# Queues, exchanges and bindings

## The question AMQP cannot answer

AMQP gives you two ways to ask about a queue, and neither returns its
configuration.

A **passive declare** tells you whether the queue exists. A **real declare**
tells you whether the arguments you passed match the ones the broker holds — by
succeeding, or by killing your channel with a 406. Neither reports what the
arguments *are*.

That is not a gap in AceMQ for Java; it is the protocol. The consequence is that
a topology mismatch can be detected but not explained:

```java
QueueInfo queue = admin.queue("orders.new").orElseThrow();

queue.arguments();      // {x-message-ttl=60000, x-dead-letter-exchange=dlx, x-queue-type=classic}
queue.argument("x-message-ttl");    // 60000
```

## Comparing against a topology

**RabbitMQ 4 records `x-queue-type` on every queue**, including queues declared
with no arguments at all. So this is wrong:

```java
// Reports drift on every classic queue in the estate.
boolean matches = queue.arguments().equals(expected);
```

Compare the arguments you care about, not the whole map:

```java
boolean matches = expected.entrySet().stream()
        .allMatch(e -> Objects.equals(queue.argument(e.getKey()), e.getValue()));
```

That also has the property you want when RabbitMQ adds another
broker-maintained argument in a future version: a queue you did not change does
not suddenly start reporting drift.

## Counters

| Method | What it is |
|---|---|
| `messagesReady()` | Waiting, not yet delivered. **The depth number.** |
| `messagesUnacknowledged()` | Delivered to a consumer, not yet acked |
| `messages()` | The sum of the two |
| `consumers()` | Consumers currently attached |
| `state()` | `"running"`, `"idle"`, `"flow"` |

`messages()` stays high while a healthy consumer works through a batch, so a
depth check built on it fires during normal operation. Use `messagesReady()`.

For depth across many queues at once, the management API is the wrong tool —
one HTTP request per queue does not scale to an estate. Use the
[metrics endpoint](metrics.md), which returns every queue in one scrape.

## Listing

```java
for (QueueInfo q : admin.queues()) {
    System.out.printf("%-30s %8d %s%n", q.name(), q.messagesReady(), q.type());
}
```

`queues()` is scoped to the current vhost. On a large broker this is a big
response; the metrics endpoint is cheaper if you only want the numbers.

## Exchanges and bindings

```java
ExchangeInfo dlx = admin.exchange("dlx").orElseThrow();
dlx.type();        // "topic"
dlx.internal();    // false

for (BindingInfo b : admin.bindingsForQueue("orders.new")) {
    System.out.println(b.source() + " --[" + b.routingKey() + "]--> " + b.destination());
}
```

**The default exchange has an empty name.** Every queue is implicitly bound to
it by its own name, and those bindings come back from `bindings()` with an empty
`source()`. `isDefaultExchangeBinding()` identifies them, and they are almost
never what you want in a topology export — you did not declare them and you
cannot delete them.

The empty name is also why `exchange("")` is found by scanning the listing
rather than by requesting a path: the URL would collapse to the listing endpoint
and return an array where an object was expected.

## Two encoding details that cost a test failure each

**The default vhost is literally `/`** and must reach the URL as `%2F`.
Unencoded it produces `/api/queues///orders.new`, which 404s — and a 404 is
indistinguishable from a queue that is not there. The library encodes it; the
detail is here because it will bite you if you build URLs yourself.

**`URLEncoder` turns a space into `+`.** Correct for a form body, wrong for a
path: a queue named `dead letters` would be looked up as `dead+letters` and
reported missing. Path segments are percent-encoded here, not form-encoded.

## Errors

| Situation | What you get |
|---|---|
| Queue absent | `Optional.empty()` |
| Bad credentials | `AdminException`, saying the user needs `monitoring` or `administrator` |
| Plugin disabled | `AdminException` — the API answers **406**, not 404 |
| Broker unreachable | `AdminException` wrapping the `IOException` |

The 406 is worth knowing. A disabled `rabbitmq_shovel_management` plugin does
not look like a missing endpoint; it looks like a refusal to negotiate, and
treating it as "not found" would report an empty shovel list on a broker that
has shovels.
