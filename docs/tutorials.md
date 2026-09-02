# Tutorials

Step by step, in order, each one ending with something that runs.

The [guide](index.html) explains how a thing works and why it is that way. These
are the other shape: start with nothing, finish with something working, and
understand what you typed by the end rather than before the beginning.

| | | | |
|---|---|---|---|
| 1 | [Reading what the broker really holds](tutorial-first-look.html) | Connect, and see the queue arguments AMQP cannot show you | 10 min |
| 2 | [Provisioning a vhost from scratch](tutorial-provisioning.html) | Vhost, user, permissions and a policy, as reviewable code | 20 min |
| 3 | [Queue depth without a consumer](tutorial-queue-depth.html) | The Prometheus endpoint, and messages per queue | 15 min |
| 4 | [Alerts that mean something](tutorial-alerts.html) | Rules evaluated in-process and exported to Prometheus | 25 min |
| 5 | [Federating two brokers](tutorial-federation.html) | Two containers, an upstream, a policy, and proof it works | 30 min |

Each builds on the one before. Nothing is left as an exercise.

## Before you start

Java 11 or newer, Docker, and Maven.

The library is not published yet, so build and install it first:

```bash
git clone https://github.com/AceMQ-Company/acemq-java-rabbitmq-admin
cd acemq-java-rabbitmq-admin
mvn -DskipTests install
```

Then in a scratch project:

```xml
<dependency>
  <groupId>org.acemq</groupId>
  <artifactId>acemq-java-rabbitmq-admin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## The broker

Every tutorial uses this one container. Start it once and leave it running:

```bash
docker run -d --name rabbit \
  -p 5672:5672 -p 15672:15672 -p 15692:15692 \
  rabbitmq:4-management

docker exec rabbit rabbitmq-plugins enable rabbitmq_prometheus
```

Tutorials 3 and 4 need that `rabbitmq_prometheus` line. Tutorial 5 starts a
second container of its own.

The management UI is at <http://localhost:15672>, guest/guest. Worth having open
throughout — everything these tutorials do shows up there, and seeing it appear
is most of the point.

When you are done:

```bash
docker rm -f rabbit
```
