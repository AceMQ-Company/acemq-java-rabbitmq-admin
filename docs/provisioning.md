# Provisioning

Vhosts, users, permissions and policies — the things a team otherwise does by
hand in the management UI, done as code that can be reviewed and repeated.

## Vhosts

```java
admin.createVhost("billing");

for (VhostInfo v : admin.vhosts()) {
    System.out.println(v.name() + "  " + v.description());
}

admin.deleteVhost("billing");
```

`deleteVhost` destroys every queue, exchange, binding, policy and message in it.
There is no confirmation and no undo, and it succeeds just as quickly on a busy
production vhost as on an empty test one.

## Users and permissions

```java
RabbitAdmin billing = admin.forVhost("billing");

billing.createUser("orders-service", "a-real-password", "monitoring");
billing.grant("orders-service", "^orders\\..*", "^orders\\..*", "^orders\\..*");
```

`grant(user, configure, write, read)` takes three regular expressions, and their
order is the order RabbitMQ documents: **configure, write, read**. They are
matched against resource names, not against actions, and an empty string means
"nothing" rather than "everything".

The virtual host is the connection's, not an argument — which is why the grant
above goes through `forVhost("billing")`. Calling `grant` on a client still
pointed at `/` silently permissions the wrong vhost, and it succeeds.

```java
for (PermissionInfo p : admin.permissions()) {
    if (p.isUnrestricted()) {
        System.out.println(p.user() + " has .* on " + p.vhost());
    }
}
```

`isUnrestricted()` is `.*` on all three — the permission the management UI hands
out by default, and the one worth finding before an audit does.

Tags decide what the *management API* allows, separately from what AMQP allows:

| Tag | Can |
|---|---|
| *(none)* | Use AMQP. Nothing here. |
| `monitoring` | Read everything through this library |
| `management` | Read its own vhosts |
| `administrator` | Everything, including users and permissions |

A service account that publishes and consumes needs no tag at all. Give it
`monitoring` only if it reads its own queue depth, and `administrator` only if
it provisions.

## Policies

A policy applies settings to queues or exchanges whose name matches a pattern.

```java
admin.putPolicy(
        "ha-orders",
        "^orders\\.",
        Map.of("ha-mode", "exact", "ha-params", 3),
        10);
```

The arguments are `(name, pattern, definition, priority)`.

### A policy is not a queue argument

This is the distinction that causes the most confusion, and it is worth being
precise about.

**A queue argument** is set at declare time, belongs to the queue, and cannot be
changed without deleting and redeclaring it. `x-message-ttl` passed to a declare
is an argument.

**A policy** is applied by the broker to queues matching a pattern, can be
changed at any time, and takes effect immediately on queues that already exist.
`message-ttl` in a policy definition is a policy.

They spell the same settings differently — `x-message-ttl` as an argument,
`message-ttl` in a policy — and where both are present, **the queue argument
wins**. That is the usual reason a policy appears to have been applied and
changed nothing: `policies()` shows it, the queue matches the pattern, and an
argument set at declare time is quietly overriding it.

```java
for (PolicyInfo p : admin.policies()) {
    System.out.println(p.name() + "  " + p.pattern() + "  " + p.definition());
}
```

Only one policy applies to a given queue: the matching one with the highest
`priority()`. They do not merge. Two policies that each set half of what you
want will not combine into the whole of it.

## Operator policies

A second policy list, with a different purpose: where both apply, **the operator
policy wins**. These are the guard rails a platform team sets and a tenant
cannot override.

```java
admin.putOperatorPolicy("cap-queue-length", "^tenant\\.",
        Map.of("max-length", 100_000), 1);

admin.operatorPolicies().forEach(p -> System.out.println(p.name()));
```

They are a separate list, so an audit that reads only `policies()` does not see
them — and a team wondering why their `max-length` is being ignored is usually
looking at the wrong list.

## Limits

The controls that stop one application taking a broker down for everybody:

```java
admin.setVhostLimit(LimitInfo.MAX_CONNECTIONS, 500);
admin.setVhostLimit(LimitInfo.MAX_QUEUES, 200);

admin.setUserLimit("orders-service", "max-connections", 50);
```

A connection leak that would otherwise reach the node's file descriptor limit
stops at 500 instead, affecting one vhost rather than the broker.

```java
admin.vhostLimits().forEach(l ->
        System.out.println(l.vhost() + " " + l.value()));

admin.clearVhostLimit(LimitInfo.MAX_CONNECTIONS);
```

The broker returns these as a map, because a vhost can carry several at once.

## Topic permissions

A third permission surface, separate from `grant`. Regular permissions control
which **resources** a user may touch; topic permissions control which **routing
keys** they may publish or subscribe to on a topic exchange.

```java
admin.grantTopic("orders-service", "amq.topic", "^orders\\..*", "^orders\\..*");
```

The trap is the default:

```java
admin.topicPermissions();     // []
```

**A user with no topic permissions is unrestricted on topic exchanges.** An
empty list does not mean locked down, it means nothing is being enforced —
however careful the ordinary permissions are. Granting one is what starts
enforcement, and `revokeTopic` restores unrestricted access rather than removing
it.

## Applying a plan rather than a script

The management API has no transaction. A provisioning run that fails halfway
leaves half of itself behind, and running it again must not fail on the objects
that already exist.

Prefer computing the difference, printing it, and only then applying it:

```java
List<String> planned = List.of("billing", "shipping", "analytics");
Set<String> existing = admin.vhosts().stream()
        .map(VhostInfo::name)
        .collect(Collectors.toSet());

List<String> toCreate = planned.stream()
        .filter(name -> !existing.contains(name))
        .collect(Collectors.toList());

toCreate.forEach(System.out::println);      // review, then:
toCreate.forEach(admin::createVhost);
```

This is the same shape as `Topology` in AceMQ for Java: compute, print, apply.
It is also what makes a second run a no-op rather than an error.

## Next

- [Federation](federation.md) — moving messages between brokers
- [Tutorial: provision a vhost from scratch](tutorial-provisioning.md)
