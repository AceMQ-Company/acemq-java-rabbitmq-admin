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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoublePredicate;
import java.util.regex.Pattern;

import org.acemq.rabbitmq.admin.metrics.MetricSample;
import org.acemq.rabbitmq.admin.metrics.MetricsSnapshot;

/**
 * A condition worth waking somebody for, written once and used twice.
 *
 * <pre>{@code
 * AlertRule backlog = Alert.named("queue-backing-up")
 *         .on("rabbitmq_detailed_queue_messages_ready")
 *         .above(1000)
 *         .lasting(Duration.ofMinutes(5))
 *         .severity(Severity.WARNING)
 *         .because("a queue is deep and not draining")
 *         .build();
 *
 * List<AlertEvent> firing = backlog.evaluate(metrics);   // now, in this process
 * String yaml = backlog.toPrometheusRule();              // and for Prometheus
 * }</pre>
 *
 * <h2>Why both</h2>
 *
 * <p>The same rule evaluates in-process and renders as a Prometheus alerting rule. That is the
 * point of the class: a deployment gate, a health endpoint and the on-call alert otherwise
 * express the same intention in three places, in two languages, and drift apart — and the way
 * you find out is an incident that nothing paged for because the threshold in Prometheus was
 * changed a year ago and the health check was not.
 *
 * <h2>What this is not</h2>
 *
 * <p><strong>Not a replacement for Prometheus and Alertmanager.</strong> Evaluating here reads
 * one scrape, in one process, with no history, no deduplication, no silencing and no routing.
 * {@link Alert#lasting(Duration)} cannot be honoured by a single snapshot at all — it is carried
 * into the generated rule, where a real time-series database can evaluate it, and
 * {@link #evaluate} says so rather than pretending.
 *
 * <p>Use the in-process evaluation for a check with a clear answer now: is this deployment
 * safe, is this broker healthy, should this CLI print red. Use the generated rule for the
 * things that need to be true over time.
 */
public final class AlertRule {

    private final String name;
    private final String metric;
    private final DoublePredicate condition;
    private final String conditionText;
    private final String promQlCondition;
    private final Duration lasting;
    private final Severity severity;
    private final String because;
    private final List<String> groupBy;
    private final Map<String, Pattern> labelMatchers;
    private final String companionMetric;

    AlertRule(
            String name,
            String metric,
            DoublePredicate condition,
            String conditionText,
            String promQlCondition,
            Duration lasting,
            Severity severity,
            String because,
            List<String> groupBy,
            Map<String, String> labelMatchers,
            String companionMetric) {
        this.companionMetric = companionMetric;
        this.name = name;
        this.metric = metric;
        this.condition = condition;
        this.conditionText = conditionText;
        this.promQlCondition = promQlCondition;
        this.lasting = lasting;
        this.severity = severity;
        this.because = because;
        this.groupBy = List.copyOf(groupBy);

        Map<String, Pattern> compiled = new LinkedHashMap<>();
        labelMatchers.forEach((label, pattern) -> compiled.put(label, Pattern.compile(pattern)));
        this.labelMatchers = compiled;
    }

    /**
     * Checks this rule against one scrape.
     *
     * @param metrics a snapshot
     * @return one event per label combination that breached — so one per queue, not one for the
     *     broker. An empty list means nothing is firing
     */
    public List<AlertEvent> evaluate(MetricsSnapshot metrics) {
        Objects.requireNonNull(metrics, "metrics");
        List<AlertEvent> firing = new ArrayList<>();
        for (MetricSample sample : metrics.all(metric)) {
            if (matchesLabels(sample)
                    && condition.test(sample.value())
                    && companionIsZero(sample, metrics)) {
                firing.add(new AlertEvent(this, sample, metrics.takenAt()));
            }
        }
        return firing;
    }

    /**
     * The in-process half of {@code and on(...) other == 0}.
     *
     * @return true when there is no companion condition, or when the companion metric reads zero
     *     for the same object. A missing counterpart is false, matching {@code and on(...)},
     *     which produces nothing when a series is absent on one side
     */
    private boolean companionIsZero(MetricSample sample, MetricsSnapshot metrics) {
        if (companionMetric == null) {
            return true;
        }
        for (MetricSample candidate : metrics.all(companionMetric)) {
            if (joinsWith(sample, candidate)) {
                return candidate.value() == 0;
            }
        }
        return false;
    }

    /** Whether two samples describe the same object, by the labels named in groupedBy. */
    private boolean joinsWith(MetricSample left, MetricSample right) {
        for (String label : groupBy) {
            if (!left.label(label).equals(right.label(label))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Applies the same narrowing here that the generated rule's label selector applies in
     * Prometheus. Skipping it would make the in-process check fire on samples the deployed
     * alert ignores, which is exactly the drift this class exists to prevent.
     */
    private boolean matchesLabels(MetricSample sample) {
        for (Map.Entry<String, Pattern> matcher : labelMatchers.entrySet()) {
            String value = sample.label(matcher.getKey()).orElse(null);
            // A sample without the label cannot satisfy a selector on it, which is also how
            // PromQL's =~ treats an absent label against a pattern that does not match "".
            if (value == null || !matcher.getValue().matcher(value).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param metrics a snapshot
     * @return whether anything breached
     */
    public boolean isFiring(MetricsSnapshot metrics) {
        return !evaluate(metrics).isEmpty();
    }

    /**
     * Renders this rule as Prometheus alerting-rule YAML.
     *
     * <p>The half that {@link #evaluate} cannot do: {@code for:} needs history, and this is
     * where {@link Alert#lasting(Duration)} becomes real.
     *
     * @return a YAML fragment for a {@code groups[].rules[]} list
     */
    public String toPrometheusRule() {
        StringBuilder yaml = new StringBuilder();
        yaml.append("- alert: ").append(toAlertName()).append('\n');
        yaml.append("  expr: ").append(promQlCondition).append('\n');
        if (!lasting.isZero()) {
            yaml.append("  for: ").append(toPromDuration(lasting)).append('\n');
        }
        yaml.append("  labels:\n");
        yaml.append("    severity: ").append(severity.name().toLowerCase(java.util.Locale.ROOT)).append('\n');
        yaml.append("  annotations:\n");
        yaml.append("    summary: ").append(quote(because)).append('\n');
        yaml.append("    description: ").append(quote(describeForPrometheus())).append('\n');
        return yaml.toString();
    }

    private String describeForPrometheus() {
        StringBuilder description = new StringBuilder(metric + " is {{ $value }}, which is " + conditionText + ".");
        for (String label : groupBy) {
            description.append(" ").append(label).append("={{ $labels.").append(label).append(" }}");
        }
        return description.toString();
    }

    /** @return the rule's name in the form Prometheus conventionally uses */
    public String toAlertName() {
        StringBuilder camel = new StringBuilder();
        boolean upper = true;
        for (char c : name.toCharArray()) {
            if (c == '-' || c == '_' || c == ' ') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return camel.toString();
    }

    private static String toPromDuration(Duration duration) {
        long seconds = duration.getSeconds();
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public String name() {
        return name;
    }

    public String metric() {
        return metric;
    }

    /** @return the metric that must read zero alongside {@link #metric()}, or null if there is none */
    public String companionMetric() {
        return companionMetric;
    }

    public Severity severity() {
        return severity;
    }

    /** @return why this matters, in a sentence somebody woken at 3am can act on */
    public String because() {
        return because;
    }

    /** @return how the threshold reads, for a message */
    public String conditionText() {
        return conditionText;
    }

    /** @return how long it must hold. Zero means "now", and only the generated rule can honour it */
    public Duration lasting() {
        return lasting;
    }

    @Override
    public String toString() {
        return "AlertRule{" + name + ": " + metric + " " + conditionText
                + (lasting.isZero() ? "" : " for " + toPromDuration(lasting))
                + " [" + severity + "]}";
    }
}
