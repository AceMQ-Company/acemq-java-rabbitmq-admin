# Health checks

The broker's own answers to "is this node healthy", rather than numbers you
interpret yourself.

```java
Health health = admin.health();

if (!health.isHealthy()) {
    health.failures().forEach(f ->
            System.out.println(f.check() + ": " + f.reason().orElse("")));
}
```

## Why these and not metrics

An [alert](alerts.md) built on the metrics endpoint infers health from gauges.
These endpoints are the broker answering directly, and two of them have **no
metric equivalent at all**:

- `quorumCritical()` knows which quorum queues would lose their majority if one
  more node went away. Nothing in `/metrics` can tell you that, and it is the
  check that decides whether a rolling restart may continue.
- `certificateExpiration(1, "months")` knows when your TLS certificates expire.
  No amount of scraping produces a future date.

## A failing check is an answer, not an error

The endpoints return **HTTP 503** when the thing they check is unhealthy. That
is the check working.

```java
HealthResult alarms = admin.health().localAlarms();

alarms.isOk();                    // false
alarms.reason();                  // "There are alarms in effect on the node"
alarms.details().get("alarms");   // [{node=rabbit@one, resource=memory}]
```

If that 503 were routed through the ordinary reader it would throw, and "is the
broker healthy?" could never be answered with *no* — which is the only answer
that matters. `HealthResult` is a value; `orThrow()` is there when you do want an
exception.

## The checks

| Method | Scope | Answers |
|---|---|---|
| `localAlarms()` | node | Is a resource alarm holding publishers on **this** node |
| `alarms()` | cluster | Is one in effect anywhere |
| `quorumCritical()` | cluster | Would another node loss break a quorum queue |
| `isInService()` | node | Fully started, and not draining for maintenance |
| `readyToServeClients()` | node | Will accept clients |
| `virtualHosts()` | node | Every vhost on this node is running |
| `belowNodeConnectionLimit()` | node | Room for more connections |
| `portListener(5672)` | node | Something is listening there |
| `protocolListener("amqp")` | node | That protocol's listener is up |
| `certificateExpiration(1, "months")` | node | Any certificate expiring soon |

## Choosing one for a load balancer

**Use a node-local check.** `isInService()` or `localAlarms()`.

`alarms()` and `quorumCritical()` are cluster-wide, so every node answers
identically. A load balancer probing those takes the *entire cluster* out of
rotation the moment one node has a memory alarm — turning a degraded cluster
into an unreachable one.

```java
// Readiness probe
boolean ready = admin.health().isInService().isOk()
        && admin.health().localAlarms().isOk();
```

## Deployment gate

`checkAll()` runs the argument-free node checks and `failures()` returns the ones
that failed:

```java
List<HealthResult> failures = admin.health().failures();
if (!failures.isEmpty()) {
    failures.forEach(f -> System.err.println("FAILED " + f.check() + ": "
            + f.reason().orElse("no reason given")));
    System.exit(1);
}
```

Before a rolling restart, the one that matters is:

```java
admin.health().quorumCritical().orThrow();   // stop the rollout rather than continue
```

Proceeding past a failing quorum check is how a cluster loses quorum on queues
that were one node away from it.

## What a passing check still tells you

```java
HealthResult quorum = admin.health().quorumCritical();
quorum.isOk();                          // true
quorum.details().get("reason");         // "single node cluster"
```

Passing because there is nothing to lose is a different kind of healthy from
passing because every quorum queue has a comfortable majority. The broker says
which, and `details()` passes it through unmodelled — the shape differs per
check, and flattening them all into one would fit none of them.
