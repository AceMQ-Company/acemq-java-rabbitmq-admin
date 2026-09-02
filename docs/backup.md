# Backup and restore

Everything the broker is configured to be, as one document.

```java
Definitions backup = admin.exportDefinitions();
Files.writeString(Path.of("broker.json"), backup.json());

// later, or on another broker
newAdmin.importDefinitions(Files.readString(Path.of("broker.json")));
```

## Why not walk the topology

Queues, exchanges and bindings are the obvious things to export, and they are
not all of it:

| Also configuration | Where it lives |
|---|---|
| Federation upstreams | runtime **parameters** |
| Dynamic shovels | runtime **parameters** |
| Policies | policies |
| Operator policies | operator policies |
| Users and their password hashes | users |
| Permissions | permissions |
| Topic permissions | topic permissions |
| Vhost and user limits | limits |

A backup assembled by walking queues, exchanges and bindings misses every row of
that table. The broker restored from it comes back looking complete while
federating nothing — and nothing reports an error, because nothing is wrong.

`exportDefinitions()` is one request for all of it.

```java
Definitions d = admin.exportDefinitions();

System.out.println(d.summary());
// vhosts=2 queues=14 exchanges=9 bindings=11 users=3 permissions=3 policies=2 parameters=1

d.parameters();        // federation upstreams and shovels are here
d.topicPermissions();  // and these are not in permissions()
```

## What it does not contain

**No messages.** This is configuration. Restoring onto an empty broker gives you
every queue, correctly configured, and every one of them empty. If you need the
messages too, that is a [shovel](federation.md), not a backup.

## Import is a merge, not a replacement

This is the thing to know before relying on it.

```java
admin.importDefinitions(backup.json());
```

Everything in the document is created or updated. **Nothing absent from it is
removed.** Importing yesterday's backup restores what was lost without deleting
what has been added since.

That is usually what you want, and it is not what the word "restore" suggests.
If you need the broker to match the file exactly, you have to delete the extra
objects yourself — the API has no operation for it.

```java
// Restores a deleted queue...
admin.deleteQueue("orders.new");
admin.importDefinitions(backup.json());
assert admin.queue("orders.new").isPresent();

// ...and leaves a newer one alone.
assert admin.queue("added-after-the-backup").isPresent();
```

## Treat the file as a credential

Users are exported with `password_hash` rather than a password:

```java
Definitions d = admin.exportDefinitions();
d.users().get(0).get("password_hash");   // "kI3G+g..."
d.users().get(0).get("password");        // null
```

Nobody's password is readable from it. But the hash is enough to recreate the
account on another broker, and that broker will then accept the original
password. **An exported definitions file lets whoever holds it stand up a broker
your users' credentials work against.** Store it the way you store secrets, not
the way you store logs.

## One vhost at a time

```java
Definitions justBilling = admin.forVhost("billing").exportVhostDefinitions();
```

Useful for moving one tenant between brokers. It omits the cluster-wide
sections — users, permissions and global parameters are not scoped to a vhost —
so it is a topology export rather than a complete backup, and restoring it onto
a fresh broker gives you queues nobody has permission to use.

## A backup worth having

```java
Definitions backup = admin.exportDefinitions();

Path file = Path.of("broker-" + LocalDate.now() + ".json");
Files.writeString(file, backup.json());

// Assert it is not empty before trusting it. An export that ran against the
// wrong broker, or with a monitoring user that cannot see everything, produces
// a valid file describing almost nothing.
if (backup.queues().isEmpty() && backup.vhosts().size() <= 1) {
    throw new IllegalStateException("this backup is suspiciously empty: " + backup.summary());
}
```

Write `backup.json()` rather than re-serialising `asMap()`. The broker's own
bytes are what the import expects, and a round trip through a JSON library can
change number formatting and key order in ways the import notices.
