# Connections, channels and consumers

Who is connected, what they are doing, and the ability to disconnect one.

```java
for (ConnectionInfo c : admin.connections()) {
    System.out.printf("%-24s %-10s %s%n",
            c.userProvidedName().orElse(c.peerHost()), c.user(), c.state());
}
```

## Name your connections

A connection the client did not name is an IP and a port:

```
192.168.5.31:52344 -> 10.0.0.2:5672
```

On a broker serving six services in containers, identifying which one is
misbehaving means matching ephemeral ports against container addresses, during
an incident.

Set the name in every application. With the RabbitMQ Java client:

```java
Connection connection = factory.newConnection("orders-service");
```

and it comes back here:

```java
c.userProvidedName();     // Optional[orders-service]
c.clientProperties();     // {product=RabbitMQ, version=5.25.0, platform=Java, ...}
```

This is the single cheapest operational improvement available to a RabbitMQ
application, and it costs one argument.

## Blocked publishers

```java
List<ConnectionInfo> blocked = admin.connections().stream()
        .filter(ConnectionInfo::isBlocked)
        .collect(Collectors.toList());
```

`state()` of `blocked` means a resource alarm is holding this publisher. It is
the per-connection view of what [`health().alarms()`](health.md) reports for the
broker — and from the application's side it looks like a publish that never
returns, with no error and no log line.

## Channels: why a consumer stopped

```java
for (ChannelInfo ch : admin.channels()) {
    if (ch.isAtPrefetchLimit()) {
        System.out.println(ch.connectionName() + " is holding its whole prefetch: "
                + ch.messagesUnacknowledged() + "/" + ch.prefetchCount());
    }
}
```

A channel whose unacknowledged count equals its prefetch has been sent
everything it is allowed to hold. The broker will send it nothing more until
something is acknowledged.

From the queue's side this is indistinguishable from a slow consumer: the depth
grows, the consumer count is non-zero, and `queueNotDraining()` does not fire
because there *is* a consumer. This is the view that separates "working through
a backlog" from "stuck holding ten messages and acknowledging none".

`state()` of `flow` means the broker is throttling the channel — usually a
publisher going faster than the queue can accept.

## Consumers: attached is not the same as active

```java
for (ConsumerInfo c : admin.consumers()) {
    System.out.println(c.queue() + "  " + c.consumerTag()
            + (c.active() ? "" : "  (inactive: " + c.activityStatus() + ")"));
}
```

On a queue with single-active-consumer, several consumers are attached and
exactly one is `active()`. A consumer count of three on such a queue means one
consumer and two standbys — which a count alone cannot tell you, and which
matters when the queue is not draining.

`ackRequired()` of `false` is auto-ack: that consumer loses every unprocessed
message if it dies. Worth auditing for.

## Closing a connection

```java
admin.closeConnection(connection.name(), "evicting for node maintenance");
```

The client is told the reason, so whoever gets disconnected sees an explanation
rather than an unexplained drop.

**This does not keep anything disconnected.** A well-built client reconnects
immediately, which makes this a way to *force a reconnection* — to move clients
off a node before maintenance, or to break a connection stuck in a bad state —
rather than a way to lock somebody out. If you need that, remove the user's
permissions first; the reconnection will then fail.

Note the connection name contains spaces and a `>`, so it needs encoding in the
URL. `closeConnection` does that for you; building the path yourself does not.

## Scope

`connections()` and `channels()` are broker-wide, across every virtual host.
`consumers()` is scoped to the current vhost, because a consumer belongs to a
queue and a queue belongs to a vhost.

```java
admin.forVhost("billing").consumers();
```

Stream protocol connections, on port 5552, have [their own page](streams.md).

## Failed logins

A login that fails never becomes a connection, so nothing above lists it. The
broker counts them per node:

```java
String node = admin.nodes().get(0).name();

for (AuthAttemptInfo a : admin.authAttempts(node)) {
    System.out.println(a.protocol() + ": " + a.failed() + " of " + a.attempts() + " failed");
}
```

Which client is failing needs per-source counters, and those are **off by
default**: every distinct address and username becomes an entry, so a scanner
would grow the table without limit. There is no `rabbitmq.conf` key for it on
3.13 or 4.x; it goes in `advanced.config`:

```erlang
[{rabbit, [{track_auth_attempt_source, true}]}].
```

```java
admin.authAttemptsBySource(node).stream()
        .filter(a -> a.failed() > 0)
        .forEach(a -> System.out.println(a.username() + " from " + a.remoteAddress()));

admin.resetAuthAttempts(node);   // both views, after the client is fixed
```

A rising failure count from one address is far more often a client deployed
with an old password than an attack — and this is the only place either shows
up.
