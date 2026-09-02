# acemq-java-rabbitmq-admin

RabbitMQ's **HTTP management API** for Java: users, vhosts, permissions,
policies, federation, shovels — and the queue facts AMQP will not tell you.

> **Status: scaffolding.** Nothing is implemented yet. This repository exists,
> has a shape, and is deliberately empty of claims. See
> [what it is for](#what-it-is-for) and
> [what it will not do](#what-it-will-not-do).

## Why it is a separate repository

`acemq-java-amqp` speaks AMQP 0-9-1, on port 5672, to any broker that implements
it. This speaks HTTP, on port 15672, to RabbitMQ specifically — a
product-specific interface with a different protocol, a different port, a
different authentication model, and a different failure mode.

**Nothing in the message path may depend on it.** A publisher that cannot publish
because a management endpoint is unreachable would be a worse publisher, and a
library that quietly reached for HTTP to answer a question about a queue would
have made its portability claim untrue. Keeping it in another repository is how
that stays enforced rather than merely intended.

## What it is for

Three things, in the order they are worth building.

### 1. The questions AMQP cannot answer

AMQP has no way to read a queue's arguments back. This is not an oversight in
`acemq-java-amqp`; it is the protocol. The consequence shows up in two places
already:

- **Topology drift.** `TopologyPlanner` detects a mismatched queue by offering a
  declaration and reading the broker's 406 refusal. That works and is a
  reasonable trick, and it can only report *that* something differs plus whatever
  RabbitMQ chose to say. The management API can report what the queue actually
  is.
- **Per-step queue depth.** `Pipeline` counts what it handled — `entered()`,
  `completed()`, `inFlight()` — and cannot tell you how many messages are waiting
  at a given step, because a consumer does not know what it has not been given.

### 2. Provisioning

Users, vhosts, permissions, policies. The things a team does by hand in the
management UI, done as code that can be reviewed, and applied the way
`Topology` is: computed, printed, and only then applied.

### 3. Moving messages between brokers

Shovels and federation are configured through this API and nowhere else. They
are also the mechanism behind blue/green broker migration, which is what
`acemq-infrastructure` is for — this library is the half that talks to the
broker.

## What it will not do

- **It will not become a second message path.** No publishing, no consuming.
  RabbitMQ's management API can publish a message; it is a debugging facility,
  it is slow, and using it in an application is a mistake this library will not
  make convenient.
- **It will not pretend to be portable.** Every method here is RabbitMQ's. There
  is no SPI and no second implementation, because there is no second broker with
  this API.
- **It will not be required by anything in `acemq-java-amqp`.** If a feature
  there would need this, the feature is either redesigned or documented as
  absent. That rule is the reason the split exists.

## Requirements

|  | |
|---|---|
| Bytecode target | **Java 11**, matching `acemq-java-amqp` |
| Build toolchain | JDK 17 or newer |
| RabbitMQ | The `rabbitmq_management` plugin enabled |

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
