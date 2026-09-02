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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.acemq.rabbitmq.admin.metrics.MetricsSnapshot;

/**
 * The alerts worth having, ready made.
 *
 * <pre>{@code
 * List<AlertEvent> firing = Alerts.recommended().evaluate(metrics);
 * firing.forEach(event -> log.warn(event.message()));
 *
 * Files.writeString(Path.of("acemq-alerts.yml"), Alerts.recommended().toPrometheusRules());
 * }</pre>
 *
 * <p>Five of them, and the list is short on purpose. A starter pack of thirty alerts is a
 * starter pack of thirty things to silence, and the ones that survive contact with an on-call
 * rota are the ones that are silent until something is genuinely wrong.
 *
 * <p>Every metric named here is asserted against a running broker by {@code PrometheusMetricsIT}.
 * A rule naming a metric that RabbitMQ does not emit is not a compile error and not a test
 * failure — it is an alert that stays silent forever, in Prometheus as well as here, and the way
 * you find out is the incident it did not page for.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><strong>Queue depth.</strong> A queue is a buffer and having things in it is the job.
 * Depth crosses any threshold you pick during every normal burst, so the alert fires, nothing is
 * wrong, and within a week it is muted — taking with it the one time it would have mattered.
 * {@link #queueNotDraining()} watches a queue with a backlog <em>and no consumers</em>, which
 * is the case that is always wrong.
 */
public final class Alerts {

    private Alerts() {
    }

    /**
     * A backlog with nobody reading it.
     *
     * <p>Not a depth alarm. Depth alone is normal; depth with zero consumers means every
     * consumer has gone and nothing is coming back on its own.
     *
     * @return the rule
     */
    public static AlertRule queueNotDraining() {
        return Alert.named("queue-not-draining")
                .on("rabbitmq_detailed_queue_messages_ready")
                .above(100)
                .whileZero("rabbitmq_detailed_queue_consumers")
                .lasting(Duration.ofMinutes(5))
                .severity(Severity.WARNING)
                .groupedBy("vhost", "queue")
                .because("messages are waiting and no consumer has taken them for five minutes."
                        + " Check whether the consuming service is running and connected.");
    }

    /** The queue names {@link #deadLetters()} treats as dead-letter queues. */
    public static final String DEFAULT_DEAD_LETTER_PATTERN = ".*\\.(dlq|dead-letter)";

    /**
     * Anything in a dead-letter queue.
     *
     * <p>Above zero rather than above a threshold: a message reaches a dead-letter queue only
     * after every retry has failed, so one is already a message nobody processed.
     *
     * <p>Restricted to queues named like dead-letter queues. The metric is per-queue and
     * unfiltered it would fire, at critical, for every ordinary queue holding a single
     * message — which is the whole broker, most of the time.
     *
     * @return the rule, matching queues ending in {@code .dlq} or {@code .dead-letter}
     */
    public static AlertRule deadLetters() {
        return deadLetters(DEFAULT_DEAD_LETTER_PATTERN);
    }

    /**
     * @param queuePattern a regular expression matching your dead-letter queue names in full
     * @return the rule, for an estate that names them differently
     */
    public static AlertRule deadLetters(String queuePattern) {
        return Alert.named("dead-letters-present")
                .on("rabbitmq_detailed_queue_messages_ready")
                .where("queue", queuePattern)
                .aboveZero()
                .severity(Severity.CRITICAL)
                .groupedBy("vhost", "queue")
                .because("a message exhausted its retries and was dead-lettered. It will not be"
                        + " processed by anything until somebody replays it.");
    }

    /**
     * A broker refusing publishes because memory is exhausted.
     *
     * <p>A resource alarm blocks publishing connections. The broker keeps running, the consumers
     * keep working, and every publisher stops — which looks like an application problem from
     * every direction except this one.
     *
     * @return the rule
     */
    public static AlertRule memoryAlarm() {
        return Alert.named("memory-alarm")
                .on("rabbitmq_alarms_memory_used_watermark")
                .aboveZero()
                .lasting(Duration.ofMinutes(1))
                .severity(Severity.CRITICAL)
                .because("the broker has hit its memory watermark and is refusing publishes."
                        + " This is broker capacity, not application load.");
    }

    /**
     * A broker refusing publishes because it is out of disk.
     *
     * <p>The alarm {@link #diskAlarmApproaching()} warns about, once it has tripped.
     *
     * @return the rule
     */
    public static AlertRule diskAlarm() {
        return Alert.named("disk-alarm")
                .on("rabbitmq_alarms_free_disk_space_watermark")
                .aboveZero()
                .severity(Severity.CRITICAL)
                .because("the broker has hit its free disk watermark and is refusing publishes."
                        + " Publishing stays blocked until disk is freed.");
    }

    /**
     * A node running out of disk.
     *
     * <p>The one that becomes {@link #publishersBlocked()} shortly, and the difference between
     * the two is how much time somebody has.
     *
     * @return the rule
     */
    public static AlertRule diskAlarmApproaching() {
        return Alert.named("disk-space-low")
                .on("rabbitmq_disk_space_available_bytes")
                .below(2L * 1024 * 1024 * 1024)
                .lasting(Duration.ofMinutes(5))
                .severity(Severity.WARNING)
                .groupedBy("node")
                .because("a node is close to its disk alarm. When it trips, every publisher on"
                        + " this broker is blocked.");
    }

    /** @return the five above, as a set that can be evaluated or exported together */
    public static AlertSet recommended() {
        return AlertSet.of(queueNotDraining(), deadLetters(), memoryAlarm(), diskAlarm(),
                diskAlarmApproaching());
    }

    /** Several rules, evaluated and exported together. */
    public static final class AlertSet {

        private final List<AlertRule> rules;

        private AlertSet(List<AlertRule> rules) {
            this.rules = List.copyOf(rules);
        }

        /**
         * @param rules the rules
         * @return a set of them
         */
        public static AlertSet of(AlertRule... rules) {
            return new AlertSet(List.of(rules));
        }

        /**
         * @param rule another rule
         * @return a set with it added
         */
        public AlertSet and(AlertRule rule) {
            List<AlertRule> combined = new ArrayList<>(rules);
            combined.add(Objects.requireNonNull(rule, "rule"));
            return new AlertSet(combined);
        }

        /**
         * @param metrics a scrape
         * @return every event firing across every rule, most severe first, so a caller printing
         *     the first line prints the worst thing rather than the first-declared thing
         */
        public List<AlertEvent> evaluate(MetricsSnapshot metrics) {
            List<AlertEvent> firing = new ArrayList<>();
            for (AlertRule rule : rules) {
                firing.addAll(rule.evaluate(metrics));
            }
            firing.sort(java.util.Comparator.comparing(event -> event.rule().severity()));
            return firing;
        }

        /**
         * @param metrics a scrape
         * @return whether anything is firing at {@link Severity#CRITICAL}
         */
        public boolean hasCritical(MetricsSnapshot metrics) {
            return evaluate(metrics).stream()
                    .anyMatch(event -> event.rule().severity() == Severity.CRITICAL);
        }

        /**
         * @return a complete Prometheus rules file, ready to load
         */
        public String toPrometheusRules() {
            StringBuilder yaml = new StringBuilder("groups:\n- name: acemq\n  rules:\n");
            for (AlertRule rule : rules) {
                for (String line : rule.toPrometheusRule().split("\n")) {
                    yaml.append("  ").append(line).append('\n');
                }
            }
            return yaml.toString();
        }

        /** @return the rules in this set */
        public List<AlertRule> rules() {
            return rules;
        }
    }
}
