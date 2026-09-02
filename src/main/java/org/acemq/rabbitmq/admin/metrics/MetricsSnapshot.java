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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Everything one scrape returned, queryable.
 *
 * <pre>{@code
 * MetricsSnapshot metrics = PrometheusMetrics.at("http://localhost:15692").scrapeDetailed();
 *
 * metrics.forQueue("orders.new")
 *        .metric("rabbitmq_detailed_queue_messages_ready")
 *        .map(MetricSample::asLong)
 *        .orElse(0L);
 * }</pre>
 *
 * <p>A snapshot is a moment, not a stream. Nothing here computes a rate, because a rate needs
 * two scrapes and a clock and this holds one — {@link #takenAt()} is provided so a caller
 * comparing two snapshots can do the arithmetic honestly rather than have it guessed for them.
 */
public final class MetricsSnapshot {

    private final List<MetricSample> samples;
    private final Map<String, String> help;
    private final Instant takenAt;

    MetricsSnapshot(List<MetricSample> samples, Map<String, String> help, Instant takenAt) {
        this.samples = Collections.unmodifiableList(new ArrayList<>(samples));
        this.help = Collections.unmodifiableMap(new LinkedHashMap<>(help));
        this.takenAt = takenAt;
    }

    /** @return every sample in the scrape */
    public List<MetricSample> samples() {
        return samples;
    }

    /** @return when this scrape was taken, for a caller computing a rate across two of them */
    public Instant takenAt() {
        return takenAt;
    }

    /**
     * @param name a metric name
     * @return every sample of it — one per label combination, so one per queue on the detailed
     *     endpoint and exactly one on the aggregate endpoint
     */
    public List<MetricSample> all(String name) {
        Objects.requireNonNull(name, "name");
        List<MetricSample> found = new ArrayList<>();
        for (MetricSample sample : samples) {
            if (sample.name().equals(name)) {
                found.add(sample);
            }
        }
        return found;
    }

    /**
     * @param name a metric name
     * @return the single sample of it, or empty. Use {@link #all(String)} where the metric is
     *     labelled: this returns the first and there is no meaningful first among queues
     */
    public Optional<MetricSample> metric(String name) {
        List<MetricSample> found = all(name);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * @param name a metric name
     * @return the sum across every label combination. What "how many messages are waiting
     *     anywhere" means on the detailed endpoint
     */
    public double sum(String name) {
        double total = 0;
        for (MetricSample sample : all(name)) {
            total += sample.value();
        }
        return total;
    }

    /**
     * Narrows to one queue.
     *
     * @param queue the queue's name
     * @return a snapshot of only the samples labelled with it
     */
    public MetricsSnapshot forQueue(String queue) {
        Objects.requireNonNull(queue, "queue");
        return where(sample -> sample.label("queue").map(queue::equals).orElse(false));
    }

    /**
     * @param label a label name
     * @param value the value it must have
     * @return a snapshot of only the samples carrying it
     */
    public MetricsSnapshot withLabel(String label, String value) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(value, "value");
        return where(sample -> sample.label(label).map(value::equals).orElse(false));
    }

    /**
     * @param predicate what to keep
     * @return a snapshot of the samples matching it, taken at the same moment as this one
     */
    public MetricsSnapshot where(Predicate<MetricSample> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        List<MetricSample> kept = new ArrayList<>();
        for (MetricSample sample : samples) {
            if (predicate.test(sample)) {
                kept.add(sample);
            }
        }
        return new MetricsSnapshot(kept, help, takenAt);
    }

    /** @return the distinct queue names this snapshot has samples for */
    public List<String> queues() {
        List<String> names = new ArrayList<>();
        for (MetricSample sample : samples) {
            sample.label("queue").filter(name -> !names.contains(name)).ifPresent(names::add);
        }
        return names;
    }

    /**
     * @param name a metric name
     * @return the broker's own description of it, from the {@code # HELP} line. Useful when
     *     generating a dashboard, where a panel wants a subtitle somebody did not have to write
     */
    public Optional<String> helpFor(String name) {
        return Optional.ofNullable(help.get(name));
    }

    /** @return whether the scrape returned nothing at all */
    public boolean isEmpty() {
        return samples.isEmpty();
    }

    @Override
    public String toString() {
        return "MetricsSnapshot{" + samples.size() + " samples at " + takenAt + "}";
    }
}
