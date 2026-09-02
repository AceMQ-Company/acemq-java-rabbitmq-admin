# Tutorial 5 — Federating two brokers

**30 minutes.** Two brokers, an upstream, a policy, and — the part that is
usually missing — proof that messages are actually moving.

## Step 1 — A second broker

```bash
docker network create acemq-fed

docker network connect acemq-fed rabbit

docker run -d --name rabbit2 --network acemq-fed \
  -p 5673:5672 -p 15673:15672 \
  rabbitmq:4-management
```

Enable federation on the **downstream** — the broker that will pull:

```bash
docker exec rabbit2 rabbitmq-plugins enable rabbitmq_federation rabbitmq_federation_management
```

Federation is configured on the side that receives. That is the first thing that
trips people up: there is nothing to configure on the upstream broker at all.

## Step 2 — Something to federate

On the upstream (`rabbit`):

```bash
docker exec rabbit rabbitmqadmin declare exchange --name events --type topic --durable true
```

On the downstream (`rabbit2`), a queue bound to an exchange of the same name:

```bash
docker exec rabbit2 rabbitmqadmin declare exchange --name events --type topic --durable true
docker exec rabbit2 rabbitmqadmin declare queue --name events.local --durable true
docker exec rabbit2 rabbitmqadmin declare binding \
  --source events --destination events.local --destination-type queue --routing-key "#"
```

## Step 3 — The upstream

```java
try (RabbitAdmin down = RabbitAdmin.connect("http://localhost:15673", "guest", "guest")) {

    down.putFederationUpstream("dc1", "amqp://guest:guest@rabbit:5672",
            Map.of("expires", 3_600_000));

    down.federationUpstreams().forEach(u ->
            System.out.println(u.name() + "  " + u.redactedUri()));
}
```

```
dc1  amqp://guest:***@rabbit:5672
```

Note `redactedUri()`. The URI holds a password because the broker stores and
returns it that way, and every printed form here — `redactedUri()`,
`toString()`, `ParameterInfo.toString()` — hides it. A log line containing an
upstream would otherwise put a broker password into your log aggregator, where
it outlives the incident that produced it. `uri()` returns the real value when
you genuinely need to compare two upstreams.

## Step 4 — Nothing is happening

```java
System.out.println(down.federationLinks());       // []
```

Empty. No error anywhere: the upstream exists, the broker is content, and not
one message will move.

**An upstream names a broker. It does not federate anything.** A *policy*
carrying `federation-upstream` is what links something to it, and this
second step is the one that is forgotten.

## Step 5 — The policy

```java
down.putPolicy("federate-events", "^events$",
        Map.of("federation-upstream", "dc1"), 10);
```

The pattern matches the *exchange* name on the downstream. Now:

```java
Thread.sleep(2000);

for (FederationLinkInfo link : down.federationLinks()) {
    System.out.println(link.upstream() + "  " + link.type() + "  " + link.status()
            + link.error().map(e -> "  ERROR: " + e).orElse(""));
}
```

```
dc1  exchange  running
```

## Step 6 — Prove it

Configuration that looks right is not proof. Publish upstream and read
downstream:

```bash
docker exec rabbit rabbitmqadmin publish message \
  --exchange events --routing-key order.created --payload "hello from dc1"

sleep 2

docker exec rabbit2 rabbitmqadmin get queue events.local --ack-mode reject_requeue_true
```

The payload appears on the downstream. It is also **still on the upstream's**
consumers — federation copies, it does not consume.

In code:

```java
long arrived = down.queue("events.local").orElseThrow().messagesReady();
System.out.println(arrived);      // 1
```

## Step 7 — The health check worth having

```java
boolean healthy = !down.federationLinks().isEmpty()
        && down.federationLinks().stream().allMatch(FederationLinkInfo::isRunning);
```

Both halves matter. `allMatch` on an **empty** list is `true`, so a broker that
is federating nothing at all would report healthy — which is precisely the
failure this check exists to catch.

Break it on purpose:

```java
down.putFederationUpstream("dc1", "amqp://guest:wrongpassword@rabbit:5672", Map.of());
Thread.sleep(3000);

down.federationLinks().forEach(l ->
        System.out.println(l.status() + "  " + l.error().orElse("")));
```

The status stops being `running` and the error explains why. Neither
`federationUpstreams()` nor `policies()` would have told you: both succeeded.
`federationLinks()` is the only call that reports the connection itself.

## Step 8 — Back it up properly

Federation upstreams and shovels are stored as **runtime parameters**, not as
queues or exchanges:

```java
down.parameters("federation-upstream").forEach(p ->
        System.out.println(p.component() + "  " + p.name()));
```

A topology export that walks queues, exchanges and bindings misses these
entirely — and a broker restored from that export comes back without its
federation, looking complete. Export `parameters("federation-upstream")`,
`parameters("shovel")` and the policies that reference them alongside the
topology.

## Shovels, for contrast

A shovel **consumes**. The message leaves the source.

```java
down.declareShovel("drain-old", Map.of(
        "src-uri",    "amqp://guest:guest@rabbit:5672",
        "src-queue",  "orders.old",
        "dest-uri",   "amqp://localhost",
        "dest-queue", "orders.new",
        "ack-mode",   "on-confirm"));
```

`on-confirm` acks the source only after the destination confirms, so an
interrupted shovel loses nothing. `no-ack` is faster and will drop messages if
the destination goes away mid-flight.

Choose between the two by asking whether the source should still have the
message afterwards. Draining a queue during a migration: shovel. Copying an
event stream to another site: federation.

## Clean up

```bash
docker rm -f rabbit2
docker network rm acemq-fed
```

## What you have

- Federation configured on the downstream, in two steps
- `federationLinks()` as the only real proof, and why an empty list is a failure
- Passwords redacted in every printed form
- Parameters as the thing a topology backup misses
- When a shovel is right and federation is not
