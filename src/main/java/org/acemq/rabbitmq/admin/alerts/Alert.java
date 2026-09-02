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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Builds an {@link AlertRule}.
 *
 * <pre>{@code
 * AlertRule rule = Alert.named("queue-backing-up")
 *         .on("rabbitmq_detailed_queue_messages_ready")
 *         .above(1000)
 *         .lasting(Duration.ofMinutes(5))
 *         .severity(Severity.WARNING)
 *         .groupedBy("vhost", "queue")
 *         .because("a queue is deep and not draining")
 *         .build();
 * }</pre>
 *
 * <p>Reads in the order the thought arrives: what it is called, what it measures, what is too
 * much, for how long, how much it matters, and why. The last one is required — an alert whose
 * reason nobody wrote down is an alert that gets silenced the second time it fires.
 */
public final class Alert {

    private final String name;
    private String metric;
    private java.util.function.DoublePredicate condition;
    private String conditionText;
    private String promQlOperator;
    private Duration lasting = Duration.ZERO;
    private Severity severity = Severity.WARNING;
    private final List<String> groupBy = new ArrayList<>();
    private final Map<String, String> labelMatchers = new LinkedHashMap<>();
    private String companionMetric;

    private Alert(String name) {
        this.name = name;
    }

    /**
     * @param name what to call it, in kebab case
     * @return a builder
     */
    public static Alert named(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("an alert needs a name: it is what appears in the page");
        }
        return new Alert(name);
    }

    /**
     * @param metric the metric to watch. On the detailed endpoint these carry the
     *     {@code rabbitmq_detailed_} prefix, and a rule naming the aggregate metric will
     *     silently match nothing there
     * @return this builder
     */
    public Alert on(String metric) {
        this.metric = Objects.requireNonNull(metric, "metric");
        return this;
    }

    /**
     * @param threshold fires when the value is strictly greater
     * @return this builder
     */
    public Alert above(double threshold) {
        metricOrFail();
        this.condition = value -> value > threshold;
        this.conditionText = "above " + trim(threshold);
        this.promQlOperator = "> " + trim(threshold);
        return this;
    }

    /**
     * @param threshold fires when the value is strictly less
     * @return this builder
     */
    public Alert below(double threshold) {
        metricOrFail();
        this.condition = value -> value < threshold;
        this.conditionText = "below " + trim(threshold);
        this.promQlOperator = "< " + trim(threshold);
        return this;
    }

    /**
     * Fires when the value is anything but zero.
     *
     * <p>The right shape for the alerts that matter most: an unroutable publish, a message in a
     * dead-letter queue, a federation link that is not running. For those, one is as bad as a
     * thousand and a threshold would only delay the page.
     *
     * @return this builder
     */
    public Alert aboveZero() {
        metricOrFail();
        this.condition = value -> value > 0;
        this.conditionText = "above zero";
        this.promQlOperator = "> 0";
        return this;
    }

    /**
     * @return this builder, firing when the value is exactly zero
     */
    public Alert isZero() {
        metricOrFail();
        this.condition = value -> value == 0;
        this.conditionText = "zero";
        this.promQlOperator = "== 0";
        return this;
    }

    /**
     * Narrows the rule to the samples whose label matches.
     *
     * <p>Without this a rule watches every sample the metric produces, which for a per-queue
     * metric means every queue on the broker. That is right for "is any queue not draining" and
     * badly wrong for "is anything in a dead-letter queue": the second one, unfiltered, is a
     * critical alert that fires whenever any ordinary queue holds a single message.
     *
     * <p>The pattern is matched in full, not as a substring, in both halves — Java's {@code
     * matches()} and PromQL's {@code =~} are both anchored, so {@code "orders"} does not match
     * {@code "orders.new"} in either. Use {@code ".*\\.dlq"} for a suffix.
     *
     * @param label the label name, for example {@code queue} or {@code vhost}
     * @param pattern a regular expression the label's value must match in full
     * @return this builder
     */
    public Alert where(String label, String pattern) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(pattern, "pattern");
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            // Better here than at evaluation time, where it would surface as an alert that
            // silently never fires.
            throw new IllegalArgumentException(
                    "alert '" + name + "': '" + pattern + "' is not a valid regular expression", e);
        }
        this.labelMatchers.put(label, pattern);
        return this;
    }

    /**
     * Requires a second metric on the same object to be zero at the same time.
     *
     * <p>The difference between a useful alert and one that gets muted. "This queue is deep" is
     * true during every normal burst; "this queue is deep <em>and no consumer is attached</em>"
     * is never normal.
     *
     * <p>Joined on the labels given to {@link #groupedBy}, in both halves: in PromQL as
     * {@code and on(vhost, queue)}, and here by matching those labels between the two metrics.
     * A sample with no counterpart does not fire, which is how {@code and on(...)} behaves when
     * a series is missing on one side.
     *
     * @param otherMetric the metric that must read zero
     * @return this builder
     */
    public Alert whileZero(String otherMetric) {
        this.companionMetric = Objects.requireNonNull(otherMetric, "otherMetric");
        return this;
    }

    /**
     * How long the condition must hold before it counts.
     *
     * <p>Carried into the generated Prometheus rule as {@code for:}. It cannot be honoured by
     * {@link AlertRule#evaluate}, which sees one scrape and has no history — so a rule with a
     * duration is a rule whose in-process evaluation is deliberately more eager than the
     * deployed one, and that difference is worth knowing before relying on it.
     *
     * @param duration how long
     * @return this builder
     */
    public Alert lasting(Duration duration) {
        this.lasting = Objects.requireNonNull(duration, "duration");
        return this;
    }

    /**
     * @param severity how much it matters
     * @return this builder
     */
    public Alert severity(Severity severity) {
        this.severity = Objects.requireNonNull(severity, "severity");
        return this;
    }

    /**
     * @param labels the labels that identify what fired, for the generated rule's description
     * @return this builder
     */
    public Alert groupedBy(String... labels) {
        this.groupBy.addAll(Arrays.asList(labels));
        return this;
    }

    /**
     * Why this matters, in a sentence somebody woken at three in the morning can act on.
     *
     * <p>Required. An alert with a threshold and no reason is one nobody can triage: the
     * question at that hour is never "is this number high" but "what breaks if I ignore it".
     *
     * @param reason the sentence
     * @return the rule
     */
    public AlertRule because(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("an alert needs a reason. A threshold with no reason"
                    + " cannot be triaged, and is silenced the second time it fires.");
        }
        if (metric == null) {
            throw new IllegalStateException("alert '" + name + "' names no metric: call on(...)");
        }
        if (condition == null) {
            throw new IllegalStateException("alert '" + name + "' has no threshold:"
                    + " call above(...), below(...), aboveZero() or isZero()");
        }
        // Assembled here rather than in above()/below() so that where(...) may be called in any
        // order relative to the threshold. Building it eagerly left the selector out of any rule
        // that named its threshold first.
        String promQl = metric + promQlSelector() + " " + promQlOperator;
        String text = conditionText;
        if (companionMetric != null) {
            if (groupBy.isEmpty()) {
                throw new IllegalStateException("alert '" + name + "': whileZero(" + companionMetric
                        + ") needs groupedBy(...) to say which labels join the two metrics."
                        + " Without them the join is broker-wide and means something else.");
            }
            promQl = promQl + " and on(" + String.join(", ", groupBy) + ") "
                    + companionMetric + promQlSelector() + " == 0";
            text = text + " while " + companionMetric + " is zero";
        }
        return new AlertRule(name, metric, condition, text, promQl, lasting, severity,
                reason, groupBy, labelMatchers, companionMetric);
    }

    /** @return {@code {queue=~"...",vhost=~"..."}}, or empty when the rule watches everything */
    private String promQlSelector() {
        if (labelMatchers.isEmpty()) {
            return "";
        }
        StringBuilder selector = new StringBuilder("{");
        for (Map.Entry<String, String> matcher : labelMatchers.entrySet()) {
            if (selector.length() > 1) {
                selector.append(',');
            }
            selector.append(matcher.getKey()).append("=~\"")
                    .append(matcher.getValue().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return selector.append('}').toString();
    }

    private String metricOrFail() {
        if (metric == null) {
            throw new IllegalStateException("alert '" + name + "': call on(metric) before the threshold,"
                    + " because the generated PromQL expression is built from it");
        }
        return metric;
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
