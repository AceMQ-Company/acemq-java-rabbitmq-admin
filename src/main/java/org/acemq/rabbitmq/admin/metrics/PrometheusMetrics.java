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
package org.acemq.rabbitmq.admin.metrics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.acemq.rabbitmq.admin.AdminException;

/**
 * Reads RabbitMQ's Prometheus endpoint.
 *
 * <pre>{@code
 * PrometheusMetrics metrics = PrometheusMetrics.at("http://localhost:15692");
 *
 * long waiting = metrics.scrapeDetailed()
 *         .forQueue("orders.new")
 *         .metric("rabbitmq_detailed_queue_messages_ready")
 *         .map(MetricSample::asLong)
 *         .orElse(0L);
 * }</pre>
 *
 * <h2>Two endpoints, and the difference matters</h2>
 *
 * <p><strong>{@code /metrics} is aggregate.</strong> {@code rabbitmq_queue_messages_ready} with
 * no labels: the total across the whole broker. It cannot tell you which queue is deep, and a
 * dashboard built on it will show a backlog without saying where.
 *
 * <p><strong>{@code /metrics/detailed} is per-object</strong>, and only for the metric families
 * you ask for. The names gain a {@code rabbitmq_detailed_} prefix and the labels appear:
 * {@code rabbitmq_detailed_queue_messages_ready{vhost="/",queue="orders.new"}}. This is what
 * "messages per queue" needs, and it is not the default because it costs the broker real work
 * on an estate with many thousands of queues.
 *
 * <p>{@link #scrape()} reads the first; {@link #scrapeDetailed()} reads the second with the
 * families this library's alerts need.
 *
 * <h2>A different port, and no credentials</h2>
 *
 * <p>15692, not 15672, and a different plugin — {@code rabbitmq_prometheus} rather than
 * {@code rabbitmq_management}. It is unauthenticated by default, which is deliberate on
 * RabbitMQ's part and worth knowing: anything that can reach the port can read every queue name
 * on the broker.
 */
public final class PrometheusMetrics {

    /** What RabbitMQ's Prometheus plugin listens on. */
    public static final int DEFAULT_PORT = 15692;

    /**
     * The families {@link #scrapeDetailed()} asks for.
     *
     * <p>Deliberately not everything. The detailed endpoint with no filter asks the broker to
     * enumerate every object it has, which on a large estate is the most expensive request
     * anybody makes of it — and these three carry every number the alerts in this library use.
     */
    private static final String DETAILED_FAMILIES =
            "family=queue_coarse_metrics&family=queue_consumer_count&family=connection_churn_metrics";

    private final URI baseUri;
    private final HttpClient http;
    private final Duration timeout;

    private PrometheusMetrics(URI baseUri, Duration timeout) {
        this.baseUri = baseUri;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                // Same reason as the management client: HttpClient's h2c upgrade and this
                // broker's HTTP stack do not agree.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param url where the Prometheus plugin listens, for example {@code http://localhost:15692}
     * @return a scraper
     */
    public static PrometheusMetrics at(String url) {
        return at(url, Duration.ofSeconds(10));
    }

    /**
     * @param url where the Prometheus plugin listens
     * @param timeout how long a scrape may take
     * @return a scraper
     */
    public static PrometheusMetrics at(String url, Duration timeout) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(timeout, "timeout");
        String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        URI uri = URI.create(trimmed);
        if (uri.getScheme() == null || !uri.getScheme().startsWith("http")) {
            throw new IllegalArgumentException("the Prometheus endpoint is http, and '" + url
                    + "' is not. It is usually the same host on port " + DEFAULT_PORT + ".");
        }
        return new PrometheusMetrics(uri, timeout);
    }

    /** @return the aggregate scrape: broker-wide totals, no per-queue labels */
    public MetricsSnapshot scrape() {
        return parse(fetch("/metrics"));
    }

    /**
     * @return the per-object scrape, limited to the families this library's alerts use. Metric
     *     names carry the {@code rabbitmq_detailed_} prefix
     */
    public MetricsSnapshot scrapeDetailed() {
        return parse(fetch("/metrics/detailed?" + DETAILED_FAMILIES));
    }

    /**
     * @param families the metric families to ask for, without the {@code family=} prefix. Note
     *     that {@code scrapeDetailed()} written with empty parentheses calls {@link
     *     #scrapeDetailed()} above, not this method with an empty array — Java prefers the
     *     more specific overload. This guard therefore catches the case that actually happens
     *     in practice: a caller passing a list of families that turned out to be empty
     * @return a per-object scrape of exactly those
     */
    public MetricsSnapshot scrapeDetailed(String... families) {
        if (families.length == 0) {
            throw new IllegalArgumentException("name at least one family: the detailed endpoint with"
                    + " no filter enumerates every object on the broker, which is the most expensive"
                    + " request anybody makes of it.");
        }
        StringBuilder query = new StringBuilder();
        for (String family : families) {
            query.append(query.length() == 0 ? "" : "&").append("family=").append(family);
        }
        return parse(fetch("/metrics/detailed?" + query));
    }

    private String fetch(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(baseUri.resolve(path))
                .timeout(timeout)
                .header("Accept", "text/plain")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new AdminException("the Prometheus endpoint answered " + response.statusCode()
                        + " for " + path + ". It is served by the rabbitmq_prometheus plugin on port "
                        + DEFAULT_PORT + ", which is not the management port and not enabled by default.");
            }
            return response.body();
        } catch (IOException e) {
            throw new AdminException("could not reach the Prometheus endpoint at " + baseUri
                    + ". It listens on " + DEFAULT_PORT + " and needs the rabbitmq_prometheus plugin.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AdminException("interrupted while scraping " + path, e);
        }
    }

    /**
     * Parses the Prometheus text exposition format.
     *
     * <p>Small on purpose. The format is a line per sample, and the only genuinely fiddly parts
     * are that a label value may contain an escaped quote or a comma, and that a line may carry
     * a trailing timestamp that is not the value.
     *
     * <p>Public because a scrape does not have to come from here. Prometheus itself, a
     * sidecar, a file captured during an incident or a federated scrape endpoint all produce
     * this format, and an alert should be evaluable against any of them — the rules in
     * {@link org.acemq.rabbitmq.admin.alerts} care about the samples, not about who fetched
     * them.
     *
     * @param body a scrape in the Prometheus text exposition format
     * @return the samples in it
     */
    public static MetricsSnapshot parse(String body) {
        List<MetricSample> samples = new ArrayList<>();
        Map<String, String> help = new LinkedHashMap<>();

        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                // "# HELP name some words". TYPE and anything else is not needed here.
                String[] parts = trimmed.split("\\s+", 4);
                if (parts.length >= 4 && "HELP".equals(parts[1])) {
                    help.put(parts[2], parts[3]);
                }
                continue;
            }

            int brace = trimmed.indexOf('{');
            String name;
            Map<String, String> labels = new LinkedHashMap<>();
            String remainder;

            if (brace < 0) {
                int space = trimmed.indexOf(' ');
                if (space < 0) {
                    continue;
                }
                name = trimmed.substring(0, space);
                remainder = trimmed.substring(space + 1);
            } else {
                int close = closingBrace(trimmed, brace);
                if (close < 0) {
                    continue;
                }
                name = trimmed.substring(0, brace);
                parseLabels(trimmed.substring(brace + 1, close), labels);
                remainder = trimmed.substring(close + 1).trim();
            }

            // A sample may carry a trailing timestamp. The value is the first token, and
            // reading the whole remainder as a number would fail on every timestamped line.
            int space = remainder.indexOf(' ');
            String value = space < 0 ? remainder : remainder.substring(0, space);
            double parsed;
            try {
                parsed = Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                // The format writes +Inf and -Inf, which Java's parser rejects (it wants
                // "Infinity"). Skipped rather than failing the whole scrape for one line.
                continue;
            }
            if (!Double.isFinite(parsed)) {
                // "NaN" *is* accepted by Double.parseDouble, so this is not covered by the
                // catch above and has to be checked. RabbitMQ emits NaN for a gauge it cannot
                // compute yet, and one of them would otherwise turn every sum() that touches
                // the family into NaN — a broker-wide total silently becoming "not a number"
                // is far worse than one missing sample.
                continue;
            }
            samples.add(new MetricSample(name, labels, parsed));
        }
        return new MetricsSnapshot(samples, help, Instant.now());
    }

    /** Finds the closing brace, ignoring any inside a quoted label value. */
    private static int closingBrace(String line, int open) {
        boolean inQuotes = false;
        for (int i = open + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == '}' && !inQuotes) {
                return i;
            }
        }
        return -1;
    }

    /** Splits {@code a="1",b="2"} into pairs, respecting escapes and commas inside values. */
    private static void parseLabels(String text, Map<String, String> into) {
        int i = 0;
        while (i < text.length()) {
            int equals = text.indexOf('=', i);
            if (equals < 0) {
                return;
            }
            String key = text.substring(i, equals).trim();

            int openQuote = text.indexOf('"', equals);
            if (openQuote < 0) {
                return;
            }
            StringBuilder value = new StringBuilder();
            int j = openQuote + 1;
            while (j < text.length()) {
                char c = text.charAt(j);
                if (c == '\\' && j + 1 < text.length()) {
                    char next = text.charAt(j + 1);
                    // The format escapes exactly these three.
                    value.append(next == 'n' ? '\n' : next);
                    j += 2;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                value.append(c);
                j++;
            }
            into.put(key, value.toString());

            int comma = text.indexOf(',', j);
            if (comma < 0) {
                return;
            }
            i = comma + 1;
        }
    }

    @Override
    public String toString() {
        return "PrometheusMetrics{" + baseUri + "}";
    }
}
