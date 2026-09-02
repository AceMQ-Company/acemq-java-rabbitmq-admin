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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One measurement: a metric name, its labels, and a number.
 *
 * <p>The unit of everything in a Prometheus scrape. {@code rabbitmq_detailed_queue_messages_ready}
 * with {@code {vhost="/", queue="orders.new"}} and the value {@code 42} is one sample; the same
 * metric for another queue is a different sample with the same name.
 */
public final class MetricSample {

    private final String name;
    private final Map<String, String> labels;
    private final double value;

    public MetricSample(String name, Map<String, String> labels, double value) {
        this.name = Objects.requireNonNull(name, "name");
        this.labels = labels == null || labels.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        this.value = value;
    }

    /** @return the metric name, without labels */
    public String name() {
        return name;
    }

    /** @return the labels, in the order the broker wrote them */
    public Map<String, String> labels() {
        return labels;
    }

    /**
     * @param name a label
     * @return its value, or empty when this sample does not carry it. Absent rather than null,
     *     because the aggregate endpoint publishes the same metric names with no labels at all
     *     and code written against the detailed endpoint would otherwise fail on the wrong one
     */
    public java.util.Optional<String> label(String name) {
        return java.util.Optional.ofNullable(labels.get(name));
    }

    /** @return the queue this sample is about, when it is about one */
    public java.util.Optional<String> queue() {
        return label("queue");
    }

    /** @return the value */
    public double value() {
        return value;
    }

    /** @return the value as a whole number, which is what a count or a depth always is */
    public long asLong() {
        return (long) value;
    }

    @Override
    public String toString() {
        return name + (labels.isEmpty() ? "" : labels) + " " + value;
    }
}
