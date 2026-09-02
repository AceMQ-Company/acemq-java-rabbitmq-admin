# Federation and shovels

Both move messages between brokers, and they are not interchangeable.

**A shovel consumes.** A message it moves is *gone* from the source. Right for
draining a queue during a migration; wrong for copying a stream of events, where
it silently steals messages from the consumers already on the source.

**Federation copies.** The upstream keeps its messages. Right for fanning an
event stream out to another site.

Choose by asking whether the source should still have the message afterwards.

## Federation is two steps, and the second is the one that is forgotten

An upstream on its own federates **nothing**. It names a broker. A *policy*
carrying `federation-upstream` is what links anything to it.

```java
// 1. Name the upstream broker.
admin.putFederationUpstream("dc2", "amqp://user:password@dc2.internal:5672",
        Map.of("expires", 3_600_000));

// 2. Link something to it. Without this, nothing is federated.
admin.putPolicy("federate-events", "^events\\.",
        Map.of("federation-upstream", "dc2"), 10);
```

Between those two steps everything looks correct and nothing happens. The
upstream is listed, the broker reports no error, and no message moves — because
nothing is wrong. It is simply not connected to anything yet.

## The only proof it is working

```java
for (FederationLinkInfo link : admin.federationLinks()) {
    System.out.println(link.upstream() + "  " + link.status()
            + link.error().map(e -> "  " + e).orElse(""));
}

boolean healthy = admin.federationLinks().stream().allMatch(FederationLinkInfo::isRunning);
```

An upstream can be declared, and a policy can match, and there can still be no
link — wrong credentials, an unreachable host, a firewall. `federationUpstreams()`
and `policies()` both report success in that case, because both succeeded.
`federationLinks()` is the only call that reports the connection itself.

An empty list is the failure mode to watch for: it means nothing is federating,
and it looks exactly like a broker that was never asked to.

## Credentials

A federation upstream URI contains a password, because the broker stores it that
way and returns it that way. These objects therefore hold a password whether
anybody wanted them to or not.

```java
FederationUpstream up = admin.federationUpstreams().get(0);

up.uri();           // amqp://user:password@dc2.internal:5672
up.redactedUri();   // amqp://user:***@dc2.internal:5672
up.toString();      // redacted
```

`uri()` returns the real thing because comparing two upstreams needs it.
**Every printed form is redacted** — `redactedUri()`, `toString()`, and
`ParameterInfo.toString()`. A log line or an exception message that included an
upstream would otherwise put a broker password into your log aggregator, where
it will outlive the incident that produced it.

## Shovels

```java
admin.declareShovel("drain-orders", Map.of(
        "src-uri",       "amqp://localhost",
        "src-queue",     "orders.old",
        "dest-uri",      "amqp://newbroker.internal",
        "dest-queue",    "orders.new",
        "ack-mode",      "on-confirm"));

for (ShovelInfo s : admin.shovels()) {
    System.out.println(s.name() + "  " + s.state());
}

admin.deleteShovel("drain-orders");
```

`ack-mode` of `on-confirm` is the one to use for a migration: the shovel acks
the source only after the destination has confirmed. `no-ack` is faster and will
lose messages if the destination goes away mid-flight.

`deleteShovel` stops it where it is. Messages already moved stay moved; the rest
stay on the source. That is safe to do at any point, which is what makes a
shovel a reasonable migration tool.

## Both are runtime parameters

Federation upstreams and dynamic shovels are stored as *parameters*, not as
queues or exchanges:

```java
for (ParameterInfo p : admin.parameters("federation-upstream")) {
    System.out.println(p.name() + "  " + p.value());
}
```

This matters for backup. A topology export that walks queues, exchanges and
bindings misses both — and a broker restored from one comes back without its
federation, looking complete. Export `parameters("federation-upstream")` and
`parameters("shovel")` alongside the topology, and the policies that reference
them.

## The plugins

Both need plugins that are not on by default:

```bash
rabbitmq-plugins enable rabbitmq_federation rabbitmq_federation_management
rabbitmq-plugins enable rabbitmq_shovel rabbitmq_shovel_management
```

Without the `_management` half, the API endpoints answer **406** rather than
404. The library raises an `AdminException` saying so, because reporting it as
"no shovels" would be a confident, wrong answer about a broker that has them.
