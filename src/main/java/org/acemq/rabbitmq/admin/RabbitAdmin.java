/*
 * Copyright 2026 AceMQ.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acemq.rabbitmq.admin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * RabbitMQ's HTTP management API.
 *
 * <pre>{@code
 * try (RabbitAdmin admin = RabbitAdmin.connect("http://localhost:15672", "guest", "guest")) {
 *     QueueInfo queue = admin.queue("orders.new").orElseThrow();
 *     System.out.println(queue.argument("x-message-ttl"));
 * }
 * }</pre>
 *
 * <p>Port 15672, not 5672, and HTTP rather than AMQP. This is a different protocol to a
 * different endpoint with different credentials, and it is available only where the
 * {@code rabbitmq_management} plugin is enabled — which is why nothing in the message path may
 * depend on it.
 *
 * <h2>Every call has a timeout</h2>
 *
 * <p>Not configurable away, only adjustable. A management call is usually made while something
 * else is already wrong — during a deployment, or while somebody is diagnosing an incident —
 * and a request that hangs forever at that moment turns a slow broker into a stuck process.
 *
 * <h2>Absent is not an error</h2>
 *
 * <p>A queue that does not exist comes back as {@link Optional#empty()} rather than an
 * exception, because "is it there?" is the question most callers are asking. A 401 or a
 * missing plugin <em>is</em> an exception, and says which of the two it was: those are
 * configuration mistakes, and reporting them as "no queue" would send somebody looking in the
 * wrong place entirely.
 */
public final class RabbitAdmin implements AutoCloseable {

    /** The default virtual host, which is literally a forward slash. */
    public static final String DEFAULT_VHOST = "/";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final URI baseUri;
    private final String authorization;
    private final HttpClient http;
    private final Duration timeout;
    private final String vhost;
    private final ObjectMapper json;

    private RabbitAdmin(URI baseUri, String authorization, Duration timeout, String vhost) {
        this.baseUri = baseUri;
        this.timeout = timeout;
        this.vhost = vhost;
        this.authorization = authorization;
        this.http = HttpClient.newBuilder()
                // HTTP/1.1, explicitly. HttpClient defaults to HTTP/2 and attempts an h2c
                // upgrade over plaintext, which the management API mishandles for a request
                // with a body: every PUT dies with "EOF reached while reading" while every GET
                // succeeds, so reads work and provisioning does not. Reproduced with a bare
                // HttpClient and no library code involved, and fixed by not asking for the
                // upgrade -- there is nothing here that HTTP/2 would make faster anyway.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                // Never follow a redirect. The management API does not issue them, so one
                // arriving means something is in front of the broker -- a proxy, a login page,
                // a captive portal -- and following it would send the credentials there.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.json = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * @param url the management base URL, for example {@code http://localhost:15672}
     * @param user a user with the {@code monitoring} or {@code administrator} tag
     * @param password its password
     * @return a client for the default virtual host
     */
    public static RabbitAdmin connect(String url, String user, String password) {
        return connect(url, user, password, DEFAULT_TIMEOUT);
    }

    /**
     * @param url the management base URL
     * @param user a user with the {@code monitoring} or {@code administrator} tag
     * @param password its password
     * @param timeout how long any one call may take
     * @return a client for the default virtual host
     */
    public static RabbitAdmin connect(String url, String user, String password, Duration timeout) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a timeout must be positive, was " + timeout);
        }

        URI uri = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        if (uri.getScheme() == null || !uri.getScheme().startsWith("http")) {
            throw new IllegalArgumentException("the management API is reached over http or https,"
                    + " and '" + url + "' is neither. This is not the AMQP URL: it is usually the"
                    + " same host on port 15672.");
        }
        String authorization = "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return new RabbitAdmin(uri, authorization, timeout, DEFAULT_VHOST);
    }

    /**
     * @param name a virtual host
     * @return a client reading that virtual host instead of {@code /}, sharing this one's
     *     endpoint, credentials and timeout
     */
    public RabbitAdmin forVhost(String name) {
        return new RabbitAdmin(baseUri, authorization, timeout, Objects.requireNonNull(name, "name"));
    }

    /**
     * Looks a queue up, with the arguments the broker actually holds.
     *
     * @param name the queue's name
     * @return the queue, or empty when there is no such queue in this virtual host
     */
    public Optional<QueueInfo> queue(String name) {
        Objects.requireNonNull(name, "name");
        return get("/api/queues/" + encode(vhost) + "/" + encode(name))
                .map(body -> read(body, QueueInfo.class, "queue " + name));
    }

    /**
     * @return every queue in this virtual host
     */
    public List<QueueInfo> queues() {
        return get("/api/queues/" + encode(vhost))
                .map(body -> readList(body, new TypeReference<List<QueueInfo>>() { }, "queues"))
                .orElseGet(Collections::emptyList);
    }

    /**
     * Checks the broker is reachable and the credentials work.
     *
     * <p>Worth calling at start-up rather than discovering at the first real call, because the
     * three ways this fails — wrong password, plugin absent, wrong port — all produce the same
     * confusion later.
     *
     * @return the broker's reported version
     */
    public String version() {
        String body = get("/api/overview").orElseThrow(() -> new AdminException(
                "the broker returned no overview, which should not be possible on a working"
                        + " management API"));
        Object version = read(body, java.util.Map.class, "overview").get("rabbitmq_version");
        return version == null ? "unknown" : String.valueOf(version);
    }


    // ---- exchanges and bindings ---------------------------------------------------

    /**
     * @param name the exchange's name; the empty string is the default exchange
     * @return the exchange, or empty when there is no such exchange in this virtual host
     */
    public Optional<ExchangeInfo> exchange(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            // The default exchange's name is the empty string, so the path would end in a
            // slash -- and /api/exchanges/%2F/ is the *listing* endpoint, which answers with an
            // array. Reading that as one exchange fails with a parse error that blames the
            // broker. Found from the list instead, which is the only way to address it.
            return exchanges().stream().filter(e -> e.name().isEmpty()).findFirst();
        }
        return get("/api/exchanges/" + encode(vhost) + "/" + encode(name))
                .map(body -> read(body, ExchangeInfo.class, "exchange " + name));
    }

    /** @return every exchange in this virtual host, including the broker's own */
    public List<ExchangeInfo> exchanges() {
        return get("/api/exchanges/" + encode(vhost))
                .map(body -> readList(body, new TypeReference<List<ExchangeInfo>>() { }, "exchanges"))
                .orElseGet(Collections::emptyList);
    }

    /** @return every binding in this virtual host */
    public List<BindingInfo> bindings() {
        return get("/api/bindings/" + encode(vhost))
                .map(body -> readList(body, new TypeReference<List<BindingInfo>>() { }, "bindings"))
                .orElseGet(Collections::emptyList);
    }

    /**
     * What is bound to a queue.
     *
     * <p>The answer AMQP cannot give, and the one that identifies an unroutable message: a
     * publish that nothing receives is a routing key with no binding, and until now the only
     * way to find that out was to publish and be told.
     *
     * @param queue the queue
     * @return its bindings, including the implicit default-exchange one
     */
    public List<BindingInfo> bindingsForQueue(String queue) {
        Objects.requireNonNull(queue, "queue");
        return get("/api/queues/" + encode(vhost) + "/" + encode(queue) + "/bindings")
                .map(body -> readList(body, new TypeReference<List<BindingInfo>>() { }, "bindings"))
                .orElseGet(Collections::emptyList);
    }

    // ---- virtual hosts ------------------------------------------------------------

    /** @return every virtual host on the broker. Not scoped to this client's vhost */
    public List<VhostInfo> vhosts() {
        return get("/api/vhosts")
                .map(body -> readList(body, new TypeReference<List<VhostInfo>>() { }, "vhosts"))
                .orElseGet(Collections::emptyList);
    }

    /**
     * Creates a virtual host, or does nothing when it is already there.
     *
     * @param name what to call it
     */
    public void createVhost(String name) {
        put("/api/vhosts/" + encode(Objects.requireNonNull(name, "name")), "{}");
    }

    /**
     * Deletes a virtual host <strong>and everything in it</strong>.
     *
     * <p>Every queue, every message in them, every exchange, every binding and every
     * permission, with no confirmation and nothing to undo it. There is no soft delete in this
     * API and this method does not invent one.
     *
     * @param name the virtual host to remove
     */
    public void deleteVhost(String name) {
        delete("/api/vhosts/" + encode(Objects.requireNonNull(name, "name")));
    }

    // ---- users and permissions ----------------------------------------------------

    /** @return every user on the broker */
    public List<UserInfo> users() {
        return get("/api/users")
                .map(body -> readList(body, new TypeReference<List<UserInfo>>() { }, "users"))
                .orElseGet(Collections::emptyList);
    }

    /**
     * Creates or updates a user.
     *
     * <p>The password is sent to the broker, which hashes it. It therefore crosses the network
     * in the request body, so this call belongs over HTTPS anywhere the network is not
     * trusted — the management API accepts plain HTTP and will not warn you.
     *
     * @param name the user
     * @param password its password
     * @param tags {@code administrator}, {@code monitoring}, {@code management},
     *     {@code policymaker}, or none for a user that can connect and nothing else
     */
    public void createUser(String name, String password, String... tags) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(password, "password");
        put("/api/users/" + encode(name),
                "{\"password\":" + quote(password) + ",\"tags\":" + quote(String.join(",", tags)) + "}");
    }

    /**
     * @param name the user to remove. Connections it already has are closed by the broker
     */
    public void deleteUser(String name) {
        delete("/api/users/" + encode(Objects.requireNonNull(name, "name")));
    }

    /** @return every permission on the broker, across all virtual hosts */
    public List<PermissionInfo> permissions() {
        return get("/api/permissions")
                .map(body -> readList(body, new TypeReference<List<PermissionInfo>>() { }, "permissions"))
                .orElseGet(Collections::emptyList);
    }

    /**
     * Grants a user access to this client's virtual host.
     *
     * <p>Three regular expressions, matched against resource names. {@code ".*"} for all three
     * is unrestricted access — what most tutorials show, and rarely what anybody means.
     *
     * @param user the user
     * @param configure what it may declare and delete
     * @param write what it may publish to
     * @param read what it may consume from
     */
    public void grant(String user, String configure, String write, String read) {
        Objects.requireNonNull(user, "user");
        put("/api/permissions/" + encode(vhost) + "/" + encode(user),
                "{\"configure\":" + quote(configure) + ",\"write\":" + quote(write)
                        + ",\"read\":" + quote(read) + "}");
    }

    /**
     * @param user the user losing access to this client's virtual host
     */
    public void revoke(String user) {
        delete("/api/permissions/" + encode(vhost) + "/" + encode(Objects.requireNonNull(user, "user")));
    }

    // ---- policies -----------------------------------------------------------------

    /** @return the policies in this virtual host */
    public List<PolicyInfo> policies() {
        return get("/api/policies/" + encode(vhost))
                .map(body -> readList(body, new TypeReference<List<PolicyInfo>>() { }, "policies"))
                .orElseGet(Collections::emptyList);
    }

    /**
     * Creates or replaces a policy.
     *
     * <p>Unlike a queue argument, this can be changed later — which is the reason to prefer a
     * policy for anything operational. Note that where several policies match, the highest
     * priority wins <em>outright</em>; the definitions are not merged.
     *
     * @param name what to call it
     * @param pattern a regular expression matched against names
     * @param definition what to set
     * @param priority higher wins
     */
    public void putPolicy(String name, String pattern, Map<String, Object> definition, int priority) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(definition, "definition");
        try {
            String body = json.writeValueAsString(Map.of(
                    "pattern", pattern, "definition", definition, "priority", priority, "apply-to", "all"));
            put("/api/policies/" + encode(vhost) + "/" + encode(name), body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AdminException("could not encode the policy '" + name + "'", e);
        }
    }

    /** @param name the policy to remove. What it was applying reverts immediately */
    public void deletePolicy(String name) {
        delete("/api/policies/" + encode(vhost) + "/" + encode(Objects.requireNonNull(name, "name")));
    }

    // ---- shovels ------------------------------------------------------------------

    /**
     * @return the shovels the broker is running, or an empty list when the shovel plugin is
     *     not enabled — which is a configuration fact rather than a failure
     */
    public List<ShovelInfo> shovels() {
        return get("/api/shovels")
                .map(body -> readList(body, new TypeReference<List<ShovelInfo>>() { }, "shovels"))
                .orElseGet(Collections::emptyList);
    }


    // ---- federation ---------------------------------------------------------------

    /**
     * @return the upstreams defined in this virtual host, or an empty list when the federation
     *     plugin is not enabled
     */
    public List<FederationUpstream> federationUpstreams() {
        return parameters("federation-upstream").stream()
                .map(p -> new FederationUpstream(p.name(), p.vhost(), p.value()))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Declares an upstream, or replaces one of the same name.
     *
     * <p><strong>This alone federates nothing.</strong> An upstream names a broker; a policy
     * whose definition carries {@code federation-upstream} is what links anything to it. That
     * two-step is the usual reason federation appears not to work, and nothing reports it
     * because nothing is wrong.
     *
     * <pre>{@code
     * admin.putFederationUpstream("other-dc", "amqp://user:pass@other-dc:5672",
     *         Map.of("expires", 3_600_000));
     * admin.putPolicy("federate", "^events\\.", Map.of("federation-upstream", "other-dc"), 1);
     * }</pre>
     *
     * <p>Set {@code expires} unless there is a reason not to. Without it the queue federation
     * creates on the upstream survives forever with nothing consuming from it, and a
     * decommissioned downstream leaves a queue growing on somebody else's broker.
     *
     * @param name what to call it, and what a policy will refer to
     * @param uri where the upstream broker is, credentials included
     * @param settings {@code expires}, {@code message-ttl}, {@code ack-mode},
     *     {@code max-hops}, {@code prefetch-count}; may be empty
     */
    public void putFederationUpstream(String name, String uri, Map<String, Object> settings) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(settings, "settings");

        Map<String, Object> value = new java.util.LinkedHashMap<>(settings);
        value.put("uri", uri);
        putParameter("federation-upstream", name, value);
    }

    /**
     * @param name the upstream to remove. Any link using it stops; a policy still naming it
     *     stays and quietly matches nothing
     */
    public void deleteFederationUpstream(String name) {
        deleteParameter("federation-upstream", Objects.requireNonNull(name, "name"));
    }

    /**
     * The links that actually exist.
     *
     * <p>The only proof federation is working. An upstream can be declared and a policy can
     * match and there can still be no link, because the upstream is unreachable or its
     * credentials are wrong — and none of that surfaces anywhere else.
     *
     * @return the links in this virtual host, or empty when the federation management plugin is
     *     not enabled
     */
    public List<FederationLinkInfo> federationLinks() {
        return get("/api/federation-links/" + encode(vhost))
                .map(body -> readList(body, new TypeReference<List<FederationLinkInfo>>() { }, "federation links"))
                .orElseGet(Collections::emptyList);
    }

    // ---- shovels ------------------------------------------------------------------

    /**
     * Declares a dynamic shovel, or replaces one of the same name.
     *
     * <p><strong>A shovel consumes.</strong> A message it moves is gone from the source, which
     * is what makes it right for draining a queue during a migration and wrong for copying a
     * stream of events — federation does that. Pointing one at a queue somebody is still
     * reading produces two consumers racing, and the shovel usually wins.
     *
     * <pre>{@code
     * admin.declareShovel("drain-orders", Map.of(
     *         "src-protocol", "amqp091", "src-uri", "amqp://old-broker", "src-queue", "orders",
     *         "dest-protocol", "amqp091", "dest-uri", "amqp://new-broker", "dest-queue", "orders"));
     * }</pre>
     *
     * @param name what to call it
     * @param definition the shovel's source and destination
     */
    public void declareShovel(String name, Map<String, Object> definition) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(definition, "definition");
        putParameter("shovel", name, definition);
    }

    /** @param name the shovel to remove. It stops moving messages immediately */
    public void deleteShovel(String name) {
        deleteParameter("shovel", Objects.requireNonNull(name, "name"));
    }

    // ---- runtime parameters -------------------------------------------------------

    /**
     * Runtime parameters of one component.
     *
     * <p>Federation upstreams and dynamic shovels are both stored this way, which is why they
     * are declared and deleted identically and why neither appears in a topology export that
     * only looks at queues and exchanges.
     *
     * @param component {@code federation-upstream}, {@code shovel}, or another plugin's
     * @return the parameters, or empty when the plugin providing that component is absent
     */
    public List<ParameterInfo> parameters(String component) {
        Objects.requireNonNull(component, "component");
        return get("/api/parameters/" + encode(component) + "/" + encode(vhost))
                .map(body -> readList(body, new TypeReference<List<ParameterInfo>>() { }, "parameters"))
                .orElseGet(Collections::emptyList);
    }

    private void putParameter(String component, String name, Map<String, Object> value) {
        try {
            // The broker wraps every parameter in a "value" object. Sending the definition at
            // the top level is accepted with a 201 and stores something the plugin ignores.
            put("/api/parameters/" + encode(component) + "/" + encode(vhost) + "/" + encode(name),
                    json.writeValueAsString(Map.of("value", value)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AdminException("could not encode the " + component + " '" + name + "'", e);
        }
    }

    private void deleteParameter(String component, String name) {
        delete("/api/parameters/" + encode(component) + "/" + encode(vhost) + "/" + encode(name));
    }

    // ---------------------------------------------------------------- health

    /**
     * The broker's own health checks.
     *
     * <pre>{@code
     * if (!admin.health().isHealthy()) {
     *     admin.health().failures().forEach(f -> log.error("{}", f));
     * }
     * }</pre>
     *
     * @return a view over {@code /api/health/checks/}
     */
    public Health health() {
        return new Health(this::healthCheck);
    }

    // ----------------------------------------------------------- definitions

    /**
     * Exports everything the broker is configured to be.
     *
     * <p>Queues, exchanges, bindings, vhosts, users, permissions, topic permissions, policies
     * and runtime parameters, in one request. This is the backup: assembling one by walking the
     * topology misses federation upstreams and shovels, which are parameters, and the restored
     * broker looks complete while federating nothing.
     *
     * <p><strong>No messages.</strong> This is configuration only.
     *
     * @return the definitions, with the broker's own JSON kept verbatim
     */
    public Definitions exportDefinitions() {
        String body = get("/api/definitions")
                .orElseThrow(() -> new AdminException("the broker returned no definitions"));
        return new Definitions(body, readMap(body, "definitions"));
    }

    /**
     * @return the definitions for the current virtual host alone, which omits the cluster-wide
     *     sections — users, permissions and global parameters are not scoped to a vhost
     */
    public Definitions exportVhostDefinitions() {
        String path = "/api/definitions/" + encode(vhost);
        String body = get(path)
                .orElseThrow(() -> new AdminException("no definitions for vhost '" + vhost + "'"));
        return new Definitions(body, readMap(body, "definitions for " + vhost));
    }

    /**
     * Applies a definitions document to this broker.
     *
     * <p><strong>This is a merge, not a replacement.</strong> Everything in the document is
     * created or updated; nothing absent from it is removed. Importing an old backup therefore
     * restores what was lost without deleting what has been added since — which is usually what
     * is wanted, and is not what "restore" sounds like.
     *
     * <p>Users in a definitions file carry password hashes rather than passwords, so importing
     * one recreates accounts that accept the original passwords. Treat the file as a credential.
     *
     * @param json a definitions document, as produced by {@link #exportDefinitions()}
     */
    public void importDefinitions(String json) {
        Objects.requireNonNull(json, "json");
        post("/api/definitions", json);
    }

    // ------------------------------------------------- connections and their traffic

    /** @return every client connection to the broker, across all virtual hosts */
    public List<ConnectionInfo> connections() {
        return get("/api/connections")
                .map(body -> readList(body, new TypeReference<List<ConnectionInfo>>() { }, "connections"))
                .orElse(Collections.emptyList());
    }

    /**
     * @param name the broker's name for the connection, from {@link ConnectionInfo#name()}
     * @return it, or empty if it has since closed
     */
    public Optional<ConnectionInfo> connection(String name) {
        return get("/api/connections/" + encode(Objects.requireNonNull(name, "name")))
                .map(body -> read(body, ConnectionInfo.class, "connection " + name));
    }

    /**
     * Closes a client connection.
     *
     * <p>The client sees a connection closed by the broker, with the reason given. A well-built
     * client will reconnect immediately, which makes this a way to force a reconnection — to
     * move a client off a node before maintenance, for instance — rather than a way to keep
     * something disconnected.
     *
     * @param name from {@link ConnectionInfo#name()}
     * @param reason told to the client, and worth making specific
     */
    public void closeConnection(String name, String reason) {
        deleteWithReason("/api/connections/" + encode(Objects.requireNonNull(name, "name")), reason);
    }

    /** @return every open channel, across all connections */
    public List<ChannelInfo> channels() {
        return get("/api/channels")
                .map(body -> readList(body, new TypeReference<List<ChannelInfo>>() { }, "channels"))
                .orElse(Collections.emptyList());
    }

    /** @return every consumer in the current virtual host, with the queue each is attached to */
    public List<ConsumerInfo> consumers() {
        return get("/api/consumers/" + encode(vhost))
                .map(body -> readList(body, new TypeReference<List<ConsumerInfo>>() { }, "consumers"))
                .orElse(Collections.emptyList());
    }

    // --------------------------------------------------------- topology writes

    /**
     * Declares a queue.
     *
     * <p>Note that AMQP can do this, and usually should: a queue an application depends on is
     * best declared by that application, at startup, so it exists before the first publish.
     * This exists for the cases AMQP cannot serve — provisioning a queue for a team that has
     * not deployed yet, or restoring one from a backup.
     *
     * @param name the queue
     * @param durable whether it survives a broker restart
     * @param arguments queue arguments, or an empty map
     */
    public void declareQueue(String name, boolean durable, Map<String, Object> arguments) {
        Objects.requireNonNull(name, "name");
        put("/api/queues/" + encode(vhost) + "/" + encode(name),
                body(mapOf("durable", durable, "auto_delete", false,
                        "arguments", arguments == null ? Collections.emptyMap() : arguments),
                        "queue " + name));
    }

    /**
     * @param name the queue to delete, with everything in it
     */
    public void deleteQueue(String name) {
        delete("/api/queues/" + encode(vhost) + "/" + encode(Objects.requireNonNull(name, "name")));
    }

    /**
     * Discards every message in a queue, keeping the queue.
     *
     * <p>Irreversible, and there is no confirmation. It is genuinely the right tool for a
     * dead-letter queue full of messages that have been analysed and cannot be replayed — and
     * it is exactly as fast on a queue holding something important.
     *
     * @param name the queue to empty
     */
    public void purgeQueue(String name) {
        delete("/api/queues/" + encode(vhost) + "/"
                + encode(Objects.requireNonNull(name, "name")) + "/contents");
    }

    /**
     * @param name the exchange
     * @param type {@code direct}, {@code topic}, {@code fanout} or {@code headers}
     * @param durable whether it survives a broker restart
     * @param arguments exchange arguments, or an empty map
     */
    public void declareExchange(String name, String type, boolean durable,
            Map<String, Object> arguments) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        put("/api/exchanges/" + encode(vhost) + "/" + encode(name),
                body(mapOf("type", type, "durable", durable, "auto_delete", false,
                        "internal", false,
                        "arguments", arguments == null ? Collections.emptyMap() : arguments),
                        "exchange " + name));
    }

    /** @param name the exchange to delete. Its bindings go with it */
    public void deleteExchange(String name) {
        delete("/api/exchanges/" + encode(vhost) + "/" + encode(Objects.requireNonNull(name, "name")));
    }

    /**
     * Binds a queue to an exchange.
     *
     * @param source the exchange
     * @param queue the queue
     * @param routingKey the routing key or pattern
     * @param arguments binding arguments, which is where a headers exchange keeps its rules
     */
    public void bindQueue(String source, String queue, String routingKey,
            Map<String, Object> arguments) {
        bind(source, "q", queue, routingKey, arguments);
    }

    /**
     * Binds an exchange to another exchange.
     *
     * @param source the upstream exchange
     * @param destination the downstream exchange
     * @param routingKey the routing key or pattern
     * @param arguments binding arguments
     */
    public void bindExchange(String source, String destination, String routingKey,
            Map<String, Object> arguments) {
        bind(source, "e", destination, routingKey, arguments);
    }

    private void bind(String source, String kind, String destination, String routingKey,
            Map<String, Object> arguments) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        post("/api/bindings/" + encode(vhost) + "/e/" + encode(source)
                        + "/" + kind + "/" + encode(destination),
                body(mapOf("routing_key", routingKey == null ? "" : routingKey,
                        "arguments", arguments == null ? Collections.emptyMap() : arguments),
                        "binding " + source + " -> " + destination));
    }

    /**
     * Removes a binding.
     *
     * <p>Takes the whole {@link BindingInfo} rather than a routing key because a binding is
     * identified by its {@linkplain BindingInfo#propertiesKey() properties key}, which arrives
     * already percent-encoded and must not be encoded again.
     *
     * @param binding one returned by {@link #bindings()} or {@link #bindingsForQueue(String)}
     */
    public void unbind(BindingInfo binding) {
        Objects.requireNonNull(binding, "binding");
        if (binding.isDefaultExchangeBinding()) {
            throw new IllegalArgumentException("the default-exchange binding for '"
                    + binding.destination() + "' cannot be removed: the broker maintains it, and"
                    + " it exists for as long as the queue does");
        }
        if (binding.propertiesKey() == null) {
            throw new IllegalArgumentException("this binding has no properties key, so the broker"
                    + " cannot be told which binding to remove. Read it back with bindings() first.");
        }
        String kind = "exchange".equals(binding.destinationType()) ? "e" : "q";
        // propertiesKey is inserted verbatim: the broker sends it already encoded, and
        // encoding it again turns "a.%23" into "a.%2523", which matches no binding.
        delete("/api/bindings/" + encode(binding.vhost()) + "/e/" + encode(binding.source())
                + "/" + kind + "/" + encode(binding.destination())
                + "/" + binding.propertiesKey());
    }

    // ------------------------------------------------------- operator policies

    /**
     * Policies an operator sets and a user cannot override.
     *
     * <p>Same shape as {@link #policies()} and a different purpose: where both apply, the
     * operator policy wins. These are the guard rails — a maximum queue length across a shared
     * broker, say — that survive a team applying its own policy.
     *
     * @return every operator policy in the current virtual host
     */
    public List<PolicyInfo> operatorPolicies() {
        return get("/api/operator-policies/" + encode(vhost))
                .map(body -> readList(body, new TypeReference<List<PolicyInfo>>() { }, "operator policies"))
                .orElse(Collections.emptyList());
    }

    /**
     * @param name the policy name
     * @param pattern a regular expression matched against queue or exchange names
     * @param definition the settings to apply
     * @param priority higher wins among matching operator policies
     */
    public void putOperatorPolicy(String name, String pattern, Map<String, Object> definition,
            int priority) {
        Objects.requireNonNull(name, "name");
        put("/api/operator-policies/" + encode(vhost) + "/" + encode(name),
                body(mapOf("pattern", pattern, "definition", definition,
                        "priority", priority, "apply-to", "queues"),
                        "operator policy " + name));
    }

    /** @param name the operator policy to remove */
    public void deleteOperatorPolicy(String name) {
        delete("/api/operator-policies/" + encode(vhost) + "/"
                + encode(Objects.requireNonNull(name, "name")));
    }

    // ------------------------------------------------------------------ limits

    /** @return the limits set on every virtual host */
    public List<LimitInfo> vhostLimits() {
        return get("/api/vhost-limits")
                .map(body -> readList(body, new TypeReference<List<LimitInfo>>() { }, "vhost limits"))
                .orElse(Collections.emptyList());
    }

    /**
     * @param limit {@link LimitInfo#MAX_CONNECTIONS} or {@link LimitInfo#MAX_QUEUES}
     * @param value the ceiling
     */
    public void setVhostLimit(String limit, long value) {
        Objects.requireNonNull(limit, "limit");
        put("/api/vhost-limits/" + encode(vhost) + "/" + encode(limit),
                "{\"value\":" + value + "}");
    }

    /** @param limit the limit to remove, restoring "no limit" */
    public void clearVhostLimit(String limit) {
        delete("/api/vhost-limits/" + encode(vhost) + "/"
                + encode(Objects.requireNonNull(limit, "limit")));
    }

    /** @return the limits set on every user */
    public List<LimitInfo> userLimits() {
        return get("/api/user-limits")
                .map(body -> readList(body, new TypeReference<List<LimitInfo>>() { }, "user limits"))
                .orElse(Collections.emptyList());
    }

    /**
     * @param user the user
     * @param limit {@code max-connections} or {@code max-channels}
     * @param value the ceiling
     */
    public void setUserLimit(String user, String limit, long value) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(limit, "limit");
        put("/api/user-limits/" + encode(user) + "/" + encode(limit), "{\"value\":" + value + "}");
    }

    /**
     * @param user the user
     * @param limit the limit to remove
     */
    public void clearUserLimit(String user, String limit) {
        delete("/api/user-limits/" + encode(Objects.requireNonNull(user, "user")) + "/"
                + encode(Objects.requireNonNull(limit, "limit")));
    }

    // -------------------------------------------------------- topic permissions

    /**
     * Routing-key authorisation on topic exchanges.
     *
     * <p>Separate from {@link #permissions()}, and easy to miss: a user with no topic
     * permissions is <em>unrestricted</em> on topic exchanges. An empty list here means nothing
     * is being enforced, however carefully the ordinary permissions are set.
     *
     * @return every topic permission on the broker
     */
    public List<TopicPermissionInfo> topicPermissions() {
        return get("/api/topic-permissions")
                .map(body -> readList(body, new TypeReference<List<TopicPermissionInfo>>() { },
                        "topic permissions"))
                .orElse(Collections.emptyList());
    }

    /**
     * @param user the user
     * @param exchange the topic exchange
     * @param write a routing-key pattern this user may publish with
     * @param read a routing-key pattern this user may bind and consume with
     */
    public void grantTopic(String user, String exchange, String write, String read) {
        Objects.requireNonNull(user, "user");
        put("/api/topic-permissions/" + encode(vhost) + "/" + encode(user),
                body(mapOf("exchange", exchange, "write", write, "read", read),
                        "topic permission for " + user));
    }

    /**
     * @param user the user whose topic permissions in this virtual host are removed, which
     *     restores unrestricted access rather than removing it
     */
    public void revokeTopic(String user) {
        delete("/api/topic-permissions/" + encode(vhost) + "/"
                + encode(Objects.requireNonNull(user, "user")));
    }

    // ------------------------------------------------------- cluster and upgrade

    /**
     * @return the user these credentials belong to, and its tags. Worth calling at startup: a
     *     missing tag reported here is far easier to act on than the 403 it becomes later
     */
    public CurrentUser whoami() {
        String body = get("/api/whoami")
                .orElseThrow(() -> new AdminException("the broker did not answer /api/whoami"));
        return read(body, CurrentUser.class, "whoami");
    }

    /** @return every node in the cluster */
    public List<NodeInfo> nodes() {
        return get("/api/nodes")
                .map(body -> readList(body, new TypeReference<List<NodeInfo>>() { }, "nodes"))
                .orElse(Collections.emptyList());
    }

    /** @return the cluster's name */
    public String clusterName() {
        return get("/api/cluster-name")
                .map(body -> String.valueOf(readMap(body, "cluster name").get("name")))
                .orElse("");
    }

    /**
     * @return every feature flag and its state. A flag whose stability is {@code required} and
     *     whose state is not {@code enabled} will block the next major upgrade
     */
    public List<FeatureFlagInfo> featureFlags() {
        return get("/api/feature-flags")
                .map(body -> readList(body, new TypeReference<List<FeatureFlagInfo>>() { }, "feature flags"))
                .orElse(Collections.emptyList());
    }

    /**
     * @return the deprecated features this broker is <em>actually using</em>. The upgrade
     *     readiness list: everything on it stops working at some future version, and an empty
     *     list is the answer you want before upgrading
     */
    public List<DeprecatedFeatureInfo> deprecatedFeaturesInUse() {
        return get("/api/deprecated-features/used")
                .map(body -> readList(body, new TypeReference<List<DeprecatedFeatureInfo>>() { },
                        "deprecated features in use"))
                .orElse(Collections.emptyList());
    }

    /**
     * @return the cluster-wide parameters, which are not scoped to a virtual host. These use
     *     {@link GlobalParameterInfo} rather than {@link ParameterInfo} because their values are
     *     not objects: {@code internal_cluster_id} is a string and {@code cluster_tags} is an
     *     array, and every broker has both
     */
    public List<GlobalParameterInfo> globalParameters() {
        return get("/api/global-parameters")
                .map(body -> readList(body, new TypeReference<List<GlobalParameterInfo>>() { },
                        "global parameters"))
                .orElse(Collections.emptyList());
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private String body(Map<String, Object> value, String what) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AdminException("could not encode the request body for " + what, e);
        }
    }

    private Optional<String> get(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AdminException("could not reach the management API at " + baseUri
                    + ". It listens on 15672 by default, which is not the AMQP port, and it exists"
                    + " only when the rabbitmq_management plugin is enabled.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdminException("interrupted while calling " + path, e);
        }

        switch (response.statusCode()) {
            case 200:
                return Optional.of(response.body());
            case 404:
                // Absent, which is an answer rather than a failure.
                return Optional.empty();
            case 406:
                // What the management API answers for an endpoint whose plugin is not enabled
                // -- /api/shovels without rabbitmq_shovel_management, for instance. A
                // configuration fact about the broker rather than something a caller can fix,
                // and reporting it as a failure would make "are there any shovels?"
                // unanswerable on a broker that simply has none.
                return Optional.empty();
            case 401:
                throw new AdminException("the management API rejected these credentials. They are"
                        + " the broker's own users, not the AMQP connection's, and the user needs the"
                        + " monitoring or administrator tag to read anything.");
            case 403:
                throw new AdminException("these credentials are valid and not permitted to read "
                        + path + ". The user needs access to this virtual host, and the monitoring"
                        + " or administrator tag.");
            default:
                throw new AdminException("the management API answered " + response.statusCode()
                        + " for " + path + ": " + abbreviate(response.body()));
        }
    }


    private void put(String path, String body) {
        send(HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), path);
    }

    private void post(String path, String body) {
        send(HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(), path);
    }

    private void deleteWithReason(String path, String reason) {
        send(HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
                .timeout(timeout)
                .header("Authorization", authorization)
                // The broker passes this to the client as the connection-close reason, so
                // whoever gets disconnected is told why rather than seeing an unexplained drop.
                .header("X-Reason", reason == null ? "closed by acemq-java-rabbitmq-admin" : reason)
                .DELETE()
                .build(), path);
    }

    /**
     * Runs one health check.
     *
     * <p>Separate from {@link #get(String)} because a failing check answers <strong>503</strong>,
     * and that is the check working rather than the request failing. Routed through the ordinary
     * reader, a broker with an alarm in effect would raise an exception instead of reporting
     * that it has an alarm in effect.
     */
    private HealthResult healthCheck(String check) {
        String path = "/api/health/checks/" + check;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
                .timeout(timeout)
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AdminException("could not reach the management API at " + baseUri
                    + " for health check '" + check + "'", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdminException("interrupted while calling " + path, e);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new AdminException("the management API rejected these credentials for health"
                    + " check '" + check + "'. The user needs the monitoring or administrator tag.");
        }
        if (status == 404) {
            throw new AdminException("this broker has no health check called '" + check + "'."
                    + " The checks under /api/health/checks/ vary by RabbitMQ version.");
        }
        if (status != 200 && status != 503) {
            throw new AdminException("the management API answered " + status + " for health check '"
                    + check + "': " + abbreviate(response.body()));
        }

        Map<String, Object> body = readMap(response.body(), "health check " + check);
        Object reason = body.get("reason");
        return new HealthResult(check, status == 200,
                reason == null ? null : reason.toString(), body);
    }

    private Map<String, Object> readMap(String body, String what) {
        return readList(body, new TypeReference<Map<String, Object>>() { }, what);
    }

    private void delete(String path) {
        send(HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
                .timeout(timeout)
                .header("Authorization", authorization)
                .DELETE()
                .build(), path);
    }

    /**
     * Runs a write and checks it worked.
     *
     * <p>A 404 is not swallowed here as it is for a read. Deleting something that is not there
     * is arguably fine; being told a write succeeded when the endpoint does not exist is how a
     * provisioning run reports success and changes nothing.
     */
    private void send(HttpRequest request, String path) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AdminException("could not reach the management API at " + baseUri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdminException("interrupted while calling " + path, e);
        }

        int status = response.statusCode();
        if (status == 200 || status == 201 || status == 204) {
            return;
        }
        if (status == 401 || status == 403) {
            throw new AdminException("the management API refused this write to " + path
                    + ". Reading needs the monitoring tag; changing anything needs administrator,"
                    + " or policymaker for policies.");
        }
        throw new AdminException("the management API answered " + status + " for a write to "
                + path + ": " + abbreviate(response.body()));
    }

    /** Quotes a string as JSON, so a password containing a quote does not produce a broken body. */
    private String quote(String value) {
        try {
            return json.writeValueAsString(value == null ? "" : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AdminException("could not encode a value for the management API", e);
        }
    }

    private <T> T read(String body, Class<T> type, String what) {
        try {
            return json.readValue(body, type);
        } catch (IOException e) {
            throw new AdminException("could not read the management API's answer for " + what
                    + ". This is usually something other than RabbitMQ answering on that port.", e);
        }
    }

    private <T> T readList(String body, TypeReference<T> type, String what) {
        try {
            return json.readValue(body, type);
        } catch (IOException e) {
            throw new AdminException("could not read the management API's answer for " + what, e);
        }
    }

    /**
     * Percent-encodes a path segment.
     *
     * <p>The default virtual host is named {@code /}, so it appears in a URL as {@code %2F} —
     * and {@code URLEncoder} is built for form bodies rather than paths, so it turns a space
     * into {@code +}, which a path reads literally. Both are corrected here because both
     * produce a 404 that looks exactly like a missing queue.
     */
    static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String flattened = body.replace('\n', ' ').trim();
        return flattened.length() <= 200 ? flattened : flattened.substring(0, 200) + "...";
    }

    /** @return the virtual host this client reads */
    public String vhost() {
        return vhost;
    }

    @Override
    public void close() {
        // HttpClient holds a selector and a thread pool it closes when collected; there is
        // nothing to release explicitly on Java 11. AutoCloseable is here so callers can use
        // try-with-resources today and keep working when there is.
    }

    @Override
    public String toString() {
        return "RabbitAdmin{" + baseUri + " vhost=" + vhost + "}";
    }
}
