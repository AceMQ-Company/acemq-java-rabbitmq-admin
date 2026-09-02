# Tutorial 2 — Provisioning a vhost from scratch

**20 minutes.** A vhost, a service account with permissions scoped to its own
queues, and a policy — as code you can review and run twice.

## Step 1 — The vhost

```java
try (RabbitAdmin admin = RabbitAdmin.connect("http://localhost:15672", "guest", "guest")) {
    admin.createVhost("billing");

    admin.vhosts().forEach(v -> System.out.println(v.name()));
}
```

```
/
billing
```

Run it twice. It succeeds both times — `createVhost` on an existing vhost is a
`PUT`, and RabbitMQ treats that as "make it so", not "create it now". That
property is what makes provisioning code safe to re-run, and it is worth
checking for every call you build on.

## Step 2 — A service account

```java
admin.createUser("billing-service", "a-real-password", "monitoring");

admin.users().forEach(u -> System.out.println(u.name() + " " + u.tags()));
```

The tag decides what the **management API** allows, and nothing about AMQP. A
service that only publishes and consumes needs no tag at all. `monitoring` is
here because this one is going to read its own queue depth in
[tutorial 3](tutorial-queue-depth.md).

## Step 3 — Permissions, scoped

Now the part that is usually done wrong. The management UI's default button
grants `.*` on all three, which is every resource in the vhost.

```java
RabbitAdmin billing = admin.forVhost("billing");

billing.grant("billing-service",
        "^billing\\..*",     // configure: may declare these
        "^billing\\..*",     // write:     may publish to these
        "^billing\\..*");    // read:      may consume from these
```

The order is **configure, write, read** — RabbitMQ's own order. They are regular
expressions matched against *resource names*, not against actions, and an empty
string means "nothing", not "everything".

Note there is no vhost argument: the permission is granted in the vhost the
client is pointed at. Calling `grant` on `admin` here — still pointed at `/` —
would permission the default vhost instead, and succeed while doing it.

Check what you actually created:

```java
admin.permissions().stream()
        .filter(p -> p.user().equals("billing-service"))
        .forEach(p -> System.out.println(
                p.vhost() + "  configure=" + p.configure()
                + "  write=" + p.write() + "  read=" + p.read()));
```

And find the ones nobody meant to leave open:

```java
admin.permissions().stream()
        .filter(PermissionInfo::isUnrestricted)
        .forEach(p -> System.out.println("UNRESTRICTED: " + p.user() + " on " + p.vhost()));
```

```
UNRESTRICTED: guest on /
```

That is the one an audit will find. On a real broker, `guest` should not exist.

## Step 4 — A policy

Reusing the `billing` view from the last step — `forVhost` returns a new client
over the same connection and does not modify the original.

```java
billing.putPolicy("billing-ttl", "^billing\\.",
        Map.of("message-ttl", 86_400_000),   // 24 hours
        10);

billing.policies().forEach(p ->
        System.out.println(p.name() + "  " + p.pattern() + "  " + p.definition()));
```

## Step 5 — Why the policy might do nothing

Declare a queue that already carries a TTL as an argument:

```bash
docker exec rabbit rabbitmqadmin --vhost billing declare queue \
  --name billing.invoices --durable true \
  --arguments '{"x-message-ttl":60000}'
```

The policy matches `^billing\.`, so it applies. The queue has a 24-hour TTL from
the policy and a 60-second TTL from its declare.

**The queue argument wins.** Messages expire after 60 seconds.

```java
QueueInfo q = billing.queue("billing.invoices").orElseThrow();
System.out.println(q.argument("x-message-ttl"));      // 60000, not 86400000
```

Note the two spellings: `x-message-ttl` as a queue argument, `message-ttl` in a
policy definition. Same setting, different names, and where both exist the
argument silently overrides the policy. This is the single most common reason a
policy "does not work" — it is working, and something more specific is beating
it.

Only one policy applies to a queue: the matching one with the highest priority.
They do not merge.

## Step 6 — Make it re-runnable

A provisioning run that fails halfway leaves half of itself behind. There is no
transaction. So compute the difference, print it, and only then apply:

```java
List<String> wanted = List.of("billing", "shipping", "analytics");

Set<String> existing = admin.vhosts().stream()
        .map(VhostInfo::name)
        .collect(Collectors.toSet());

List<String> toCreate = wanted.stream()
        .filter(name -> !existing.contains(name))
        .collect(Collectors.toList());

if (toCreate.isEmpty()) {
    System.out.println("nothing to do");
} else {
    System.out.println("will create: " + toCreate);
    toCreate.forEach(admin::createVhost);
}
```

Run it twice: the second run prints `nothing to do`. That is the same
compute-print-apply shape `Topology` uses in AceMQ for Java, and it is what turns
a provisioning script into something you are willing to run against production.

## Step 7 — Clean up

```java
admin.deleteVhost("billing");
admin.deleteUser("billing-service");
```

`deleteVhost` destroys every queue, exchange, binding, policy and message in it,
with no confirmation and no undo. It is exactly as fast on a busy production
vhost as on this one.

## What you have

- A vhost, a scoped service account, and a policy
- The configure/write/read order, and why `.*` is worth auditing for
- Why a policy loses to a queue argument
- Provisioning that is safe to run twice

Next: [queue depth without a consumer](tutorial-queue-depth.md).
