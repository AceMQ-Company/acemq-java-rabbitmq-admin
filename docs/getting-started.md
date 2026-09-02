# Getting started

## Install

> **Not published yet.** This library has its own version line starting at
> `0.1.0` and has not had a release. Until it does, build it from source with
> `mvn install`. The coordinates below are what a release will use.

```xml
<repositories>
  <repository>
    <id>acemq</id>
    <url>https://acemq-company.github.io/maven/</url>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>org.acemq</groupId>
    <artifactId>acemq-java-rabbitmq-admin</artifactId>
    <version>0.1.0</version>
  </dependency>
</dependencies>
```

Gradle:

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://acemq-company.github.io/maven/") }
}

dependencies {
    implementation("org.acemq:acemq-java-rabbitmq-admin:0.1.0")
}
```

Java 11 or later. One artifact, and the HTTP client is the JDK's own
`java.net.http` — there is no Apache HttpClient or OkHttp underneath it, so this
does not join a version argument your application is already having.

Its version line is separate from AceMQ for Java's. This tracks RabbitMQ's
management API; that tracks AceMQ's messaging API, and the two move for
different reasons.

## A broker to talk to

The management API is a plugin, and on the `management` images it is already on:

```bash
docker run -d --name rabbit \
  -p 5672:5672 -p 15672:15672 -p 15692:15692 \
  rabbitmq:4-management
```

Port 15672 is the management API. Port 15692 is the Prometheus endpoint, which
is a **different plugin** and is not enabled by default:

```bash
docker exec rabbit rabbitmq-plugins enable rabbitmq_prometheus
```

You only need that second one for [metrics](metrics.md) and
[alerts](alerts.md).

## Connect

```java
import org.acemq.rabbitmq.admin.RabbitAdmin;
import org.acemq.rabbitmq.admin.QueueInfo;

try (RabbitAdmin admin = RabbitAdmin.connect("http://localhost:15672", "guest", "guest")) {
    System.out.println(admin.version());   // "4.1.0"
}
```

`RabbitAdmin` is `AutoCloseable`. It is cheap to create and safe to keep for the
lifetime of an application; it holds one `HttpClient`.

The credentials are the **broker's own users**, not your application's. The user
needs the `monitoring` tag to read and `administrator` to write. A user that can
publish and consume perfectly well may still get a 401 here, and that is not a
misconfiguration — it is the management API being a separate permission surface.

## Read a queue

```java
Optional<QueueInfo> maybe = admin.queue("orders.new");

QueueInfo queue = maybe.orElseThrow(() -> new IllegalStateException("not declared"));
System.out.println(queue.type());                   // "classic" or "quorum"
System.out.println(queue.durable());                // true
System.out.println(queue.arguments());              // {x-message-ttl=60000, ...}
System.out.println(queue.messagesReady());          // 42
System.out.println(queue.consumers());              // 3
```

A queue that does not exist is `Optional.empty()`, not an exception. Bad
credentials *are* an exception — reporting them as "no queue" would send
somebody looking in entirely the wrong place.

`messagesReady()` rather than `messages()` is almost always the number you want.
The total includes deliveries already handed to a consumer, so it stays high
while a consumer works through a batch and tells you nothing about whether the
queue is draining.

## Check your permissions at startup

```java
CurrentUser me = admin.whoami();

System.out.println(me.name() + " " + me.tags());   // guest [administrator]

if (!me.canAdminister()) {
    throw new IllegalStateException(me.name() + " cannot change anything through the"
            + " management API: it needs the administrator tag");
}
```

Worth doing once, at startup. Every permission problem otherwise surfaces later
as a 401 or 403 on some unrelated-looking call, and "this user has no
administrator tag" is a much better thing to report than "could not create
vhost" an hour into a provisioning run.

## Upgrade readiness

```java
admin.deprecatedFeaturesInUse().forEach(f ->
        System.out.println(f.name() + "  " + f.deprecationPhase()));

admin.featureFlags().stream()
        .filter(FeatureFlagInfo::isRequired)
        .filter(f -> !f.isEnabled())
        .forEach(f -> System.out.println("blocks the next upgrade: " + f.name()));
```

Both lists should be empty before a major upgrade. A required feature flag left
disabled will stop the upgrade; a deprecated feature in use will stop working
during it. RabbitMQ 4 already *denies* transient non-exclusive queues, which
means a client declaring one has its connection closed with an internal error
rather than a warning.

## Choosing a vhost

Every call is scoped to one vhost, `/` by default:

```java
RabbitAdmin billing = admin.forVhost("billing");
```

`forVhost` returns a new view over the same connection; it does not mutate the
original. The default vhost is literally named `/`, which the library encodes for
you — see [queues](queues.md) for why that detail has teeth.

## Next

- [Queues](queues.md) — the questions AMQP cannot answer
- [Provisioning](provisioning.md) — vhosts, users, permissions, policies
- [Tutorials](tutorials.md) — step by step, start to finish
