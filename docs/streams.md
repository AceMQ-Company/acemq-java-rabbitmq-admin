# Streams

Connections, publishers and consumers over RabbitMQ's stream protocol — port
5552, not AMQP.

```java
for (StreamPublisherInfo p : admin.streamPublishers()) {
    System.out.println(p.reference() + " -> " + p.stream()
            + "  published=" + p.published() + " errored=" + p.errored());
}
```

These need the `rabbitmq_stream_management` plugin. Without it every method
here answers an empty list rather than failing, the same rule as shovels and
federation: a plugin that is not enabled is a fact about the broker. To tell the
two kinds of empty apart:

```java
boolean visible = admin.extensions().contains("stream.js");
```

## The calls

| Method | Endpoint | Scope |
|---|---|---|
| `streamConnections()` | `GET /api/stream/connections` | every vhost |
| `streamPublishers()` | `GET /api/stream/publishers/{vhost}` | this vhost |
| `streamPublishers(stream)` | `GET /api/stream/publishers/{vhost}/{stream}` | one stream |
| `streamConsumers()` | `GET /api/stream/consumers/{vhost}` | this vhost |

There is no per-stream consumer endpoint: `/api/stream/consumers/{vhost}/{stream}`
answers 400 on 3.13 and 4.x alike. Filter on `StreamConsumerInfo.stream()`.

## What to look at

**`errored()` on a publisher.** A stream publisher is confirmed asynchronously,
so a message the broker refused does not fail the send — it fails a callback
the application may never read. This counter is where it shows.

**`offsetLag()` on a consumer.** A stream is not drained, so there is no queue
depth to watch. The lag is the equivalent: how far behind the end of the stream
this consumer is.

**`name()` on a consumer, `reference()` on a publisher.** A consumer without a
name stores no offset and starts wherever it is told to on every restart; a
publisher without a reference gets no deduplication. Both are null here when
the client set none.

## They appear a few seconds late

The broker reports stream connections, publishers and consumers through its
statistics, emitted every five seconds by default. One that appeared within
that interval is not listed yet, and a publisher's `connectionName()` is null
until its first report. A check that runs straight after a deployment should
poll rather than read once.

`connectionName()` joins a publisher or consumer to
`StreamConnectionInfo.name()`. `connections()` lists stream connections too,
among every other protocol's, with AMQP-shaped fields that mean little for a
stream client.
