# Reporting a vulnerability

Email **security@acemq.com** with what you found and how to reproduce it. Please do
not open a public issue for anything exploitable.

You should get an acknowledgement within two working days, and an assessment of
whether it is a vulnerability, what is affected, and a rough timeline within a week.
If a fix is warranted, we will tell you when it is released and credit you unless you
would rather we did not.

## What is in scope

Everything in this repository. This is a client for RabbitMQ's HTTP management API,
so it holds administrative credentials and can change a broker's configuration — the
things worth reporting follow from that.

- Anything that renders the management password into a log line, an exception
  message, a stack trace, or a request that was going somewhere else.
- A request sent over plain HTTP when HTTPS was configured, or a TLS connection made
  without the certificate or hostname verification that was asked for.
- A user, permission, policy, federation link or shovel created, changed or deleted
  other than as the caller asked — particularly a permission wider than the one
  requested.
- A password set on a user that is weaker than the one supplied, or one that ends up
  somewhere other than the broker.
- Injection into a path or query through a vhost, user, queue or policy name — a
  vhost is very often `/`, and a name that escapes its segment reaches a different
  resource.
- A response parsed in a way that lets the broker's output execute or overwrite
  something on the client side.

## What is not

- **The library being able to delete a queue or a user.** That is what an
  administrative API client is for. Authorisation is the broker's, through the
  credentials you give it.
- **A password appearing in a URL you constructed yourself.**
- **Vulnerabilities in RabbitMQ or its management plugin** — report those to
  Broadcom.
- Findings from a scanner with no demonstrated impact.

## Supported versions

Pre-1.0, only the latest release. There are no maintenance branches yet, so a fix
means a new patch version.

## What this library does not do for you

It calls an API on your behalf with credentials you supply. It does not decide who
may call it, store those credentials, or stop you granting a permission wider than
you meant. Give it an account with the narrowest permissions that do the job.
