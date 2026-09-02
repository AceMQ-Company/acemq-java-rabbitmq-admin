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
package org.acemq.rabbitmq.admin.alerts;

import java.time.Instant;
import java.util.Map;

import org.acemq.rabbitmq.admin.metrics.MetricSample;

/**
 * A rule that breached, and the measurement that breached it.
 *
 * <p>One per label combination, so a rule about queue depth produces one of these per deep
 * queue rather than one for the broker. That is the difference between "something is backing
 * up" and "orders.new is backing up", and it is the whole reason the detailed metrics endpoint
 * is worth its cost.
 */
public final class AlertEvent {

    private final AlertRule rule;
    private final MetricSample sample;
    private final Instant observedAt;

    AlertEvent(AlertRule rule, MetricSample sample, Instant observedAt) {
        this.rule = rule;
        this.sample = sample;
        this.observedAt = observedAt;
    }

    /** @return the rule that fired */
    public AlertRule rule() {
        return rule;
    }

    /** @return the measurement that breached it */
    public MetricSample sample() {
        return sample;
    }

    /** @return the value observed */
    public double value() {
        return sample.value();
    }

    /** @return which queue, connection or node this is about */
    public Map<String, String> labels() {
        return sample.labels();
    }

    /** @return the queue, when the metric was about one */
    public java.util.Optional<String> queue() {
        return sample.queue();
    }

    /** @return when the scrape this came from was taken */
    public Instant observedAt() {
        return observedAt;
    }

    /**
     * @return a line fit for a log, a Slack message or a CLI: what fired, where, what the value
     *     was, and why it matters
     */
    public String message() {
        String where = sample.queue().map(q -> " on " + q).orElseGet(() ->
                labels().isEmpty() ? "" : " " + labels());
        return "[" + rule.severity() + "] " + rule.name() + where
                + ": " + rule.metric() + " is " + format(value())
                + ", " + rule.conditionText() + ". " + rule.because();
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    @Override
    public String toString() {
        return message();
    }
}
