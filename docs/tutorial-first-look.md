# Tutorial 1 — Reading what the broker really holds

**10 minutes.** Connect to a broker and read a queue's actual configuration —
the thing AMQP cannot tell you.

Start the broker from the [tutorials page](tutorials.md) if it is not running.

## The problem, first

Declare a queue with a message TTL, using whatever AMQP client you have:

```bash
docker exec rabbit rabbitmqadmin declare queue \
  --name orders.new --durable true \
  --arguments '{"x-message-ttl":60000,"x-dead-letter-exchange":"dlx"}'
```

Now, in AMQP, ask the broker what that queue's TTL is.

You cannot. A passive declare tells you the queue exists. A real declare tells
you whether the arguments *you* passed match, by succeeding or by killing your
channel with a 406. Neither returns the value. If you did not write the
declaration, or you wrote it and something has changed since, the protocol has
no way to tell you what is there.

## Step 1 — Connect

```java
import org.acemq.rabbitmq.admin.RabbitAdmin;

public class FirstLook {
    public static void main(String[] args) {
        try (RabbitAdmin admin = RabbitAdmin.connect(
                "http://localhost:15672", "guest", "guest")) {

            System.out.println("broker " + admin.version());
        }
    }
}
```

```
broker 4.1.0
```

If that fails with an `AdminException` about credentials, the user needs the
`monitoring` tag. `guest` has `administrator` on a fresh container, so it works.

## Step 2 — Ask what the queue actually is

```java
import org.acemq.rabbitmq.admin.QueueInfo;

QueueInfo queue = admin.queue("orders.new")
        .orElseThrow(() -> new IllegalStateException("orders.new is not declared"));

System.out.println("type       " + queue.type());
System.out.println("durable    " + queue.durable());
System.out.println("arguments  " + queue.arguments());
System.out.println("ready      " + queue.messagesReady());
System.out.println("consumers  " + queue.consumers());
```

```
type       classic
durable    true
arguments  {x-dead-letter-exchange=dlx, x-message-ttl=60000, x-queue-type=classic}
ready      0
consumers  0
```

There is the TTL. That is the whole point of the library.

## Step 3 — The argument you did not set

Look again at that output. You passed **two** arguments. Three came back.

`x-queue-type=classic` was added by RabbitMQ itself. Version 4 records it on
every queue, including ones declared with no arguments at all.

This matters the moment you compare a queue against a desired topology:

```java
Map<String, Object> expected = Map.of(
        "x-message-ttl", 60000,
        "x-dead-letter-exchange", "dlx");

// Reports drift on every classic queue in the estate.
System.out.println(queue.arguments().equals(expected));       // false
```

Compare only what you care about:

```java
boolean matches = expected.entrySet().stream()
        .allMatch(e -> Objects.equals(queue.argument(e.getKey()), e.getValue()));

System.out.println(matches);                                  // true
```

That version also survives RabbitMQ adding another broker-maintained argument in
some future release, which the first one would report as drift on a queue nobody
touched.

## Step 4 — Absent is not an error

```java
System.out.println(admin.queue("does.not.exist"));    // Optional.empty
```

A missing queue is `Optional.empty()`. Bad credentials are an `AdminException`.
The distinction is deliberate: reporting a 401 as "no queue" would send you
looking at your topology when the problem is your password.

## Step 5 — Depth, and the number to use

Publish a few messages:

```bash
for i in 1 2 3; do
  docker exec rabbit rabbitmqadmin publish message \
    --routing-key orders.new --payload "order-$i"
done
```

```java
QueueInfo q = admin.queue("orders.new").orElseThrow();

System.out.println("ready   " + q.messagesReady());            // 3
System.out.println("unacked " + q.messagesUnacknowledged());   // 0
System.out.println("total   " + q.messages());                 // 3
```

Use `messagesReady()`. `messages()` includes deliveries already handed to a
consumer, so it stays high while a healthy consumer works through a batch — a
depth check built on it fires during entirely normal operation.

## Step 6 — Every queue at once

```java
for (QueueInfo q : admin.queues()) {
    System.out.printf("%-20s %6d ready  %s%n", q.name(), q.messagesReady(), q.type());
}
```

This is one HTTP request returning every queue in the vhost, which is fine for
tens of queues and wrong for thousands. When you need depth across a large
estate, [tutorial 3](tutorial-queue-depth.md) uses the metrics endpoint, which
is built for exactly that.

## What you have

- A queue's real arguments, which AMQP will not give you
- The knowledge that `x-queue-type` will break a naive topology comparison
- `messagesReady()` versus `messages()`, and why it matters

Next: [provisioning a vhost from scratch](tutorial-provisioning.md).
