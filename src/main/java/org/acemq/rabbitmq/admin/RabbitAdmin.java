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
                .orElseGet(java.util.Collections::emptyList);
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
