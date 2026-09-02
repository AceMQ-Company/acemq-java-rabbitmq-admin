# AceMQ for RabbitMQ administration

A small Java client for RabbitMQ's HTTP management API, and for the metrics
endpoint beside it. It answers the questions AMQP cannot, provisions the things
AMQP has no concept of, and turns the broker's numbers into alerts that are worth
waking somebody for.

```java
try (RabbitAdmin admin = RabbitAdmin.connect("http://localhost:15672", "guest", "guest")) {
    QueueInfo queue = admin.queue("orders.new").orElseThrow();

    System.out.println(queue.type());            // "classic"
    System.out.println(queue.arguments());       // what the broker actually holds
    System.out.println(queue.messagesReady());   // what is waiting
}
```

## Why this is separate from AceMQ for Java

[AceMQ for Java](https://acemq-company.github.io/acemq-java-amqp/) speaks AMQP.
This library speaks HTTP to a RabbitMQ-specific management API that no other
broker implements. Keeping them apart means the messaging library never acquires
a dependency on a management plugin that may not be installed, and never grows a
feature that only works on RabbitMQ.

Nothing in AceMQ for Java requires this library. If a feature there would need
it, that feature is redesigned or documented as absent — and that rule is the
reason the split exists.

## What it is for

**[Reading real queue state](queues.md).** AMQP has no way to read a queue's
arguments back. A passive declare tells you a queue exists; a real declare tells
you whether *your* arguments match, by refusing. Neither tells you what the
queue actually is. This does.

**[Provisioning](provisioning.md).** Vhosts, users, permissions, policies — the
things a team otherwise does by hand in the management UI, done as code that can
be reviewed.

**[Federation and shovels](federation.md).** Configured through this API and
nowhere else, and the mechanism behind blue/green broker migration.

**[Metrics](metrics.md) and [alerts](alerts.md).** How many messages are waiting
in *this* queue, and rules that are evaluated in-process and exported as
Prometheus alerting YAML from one definition.

## What it will not do

- **It will not become a second message path.** No publishing, no consuming. The
  management API *can* publish a message; it is a debugging facility, it is slow,
  and using it in an application is a mistake this library will not make
  convenient.
- **It will not pretend to be portable.** Every method here is RabbitMQ's. There
  is no SPI and no second implementation, because there is no second broker with
  this API.
- **It will not be required by anything in AceMQ for Java.**

## Where to start

New to it: [Getting started](getting-started.md), then the
[tutorials](tutorials.md).

Looking for a specific method: the [API reference](apidocs/index.html).
