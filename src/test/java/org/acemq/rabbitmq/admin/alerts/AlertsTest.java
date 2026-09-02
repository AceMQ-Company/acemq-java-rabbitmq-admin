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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.acemq.rabbitmq.admin.metrics.MetricsSnapshot;
import org.acemq.rabbitmq.admin.metrics.PrometheusMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("alerts")
class AlertsTest {

    private static MetricsSnapshot scrape(String body) {
        return PrometheusMetrics.parse(body);
    }

    @Nested
    @DisplayName("evaluating")
    class Evaluating {

        @Test
        @DisplayName("fires once per queue, not once for the broker")
        void firesPerLabelCombination() {
            AlertRule rule = Alert.named("deep")
                    .on("rabbitmq_detailed_queue_messages_ready")
                    .above(100)
                    .because("a queue is deep");

            List<AlertEvent> firing = rule.evaluate(scrape(
                    "rabbitmq_detailed_queue_messages_ready{queue=\"a\"} 500\n"
                            + "rabbitmq_detailed_queue_messages_ready{queue=\"b\"} 5\n"
                            + "rabbitmq_detailed_queue_messages_ready{queue=\"c\"} 900\n"));

            // "something is backing up" is not actionable; "a and c are" is.
            assertThat(firing).hasSize(2);
            assertThat(firing).extracting(e -> e.queue().orElseThrow()).containsExactly("a", "c");
        }

        @Test
        @DisplayName("a message says what fired, where, the value, and why it matters")
        void messageIsActionable() {
            AlertRule rule = Alert.named("deep")
                    .on("rabbitmq_detailed_queue_messages_ready")
                    .above(100)
                    .severity(Severity.WARNING)
                    .because("nothing is consuming");

            AlertEvent event = rule.evaluate(
                    scrape("rabbitmq_detailed_queue_messages_ready{queue=\"orders\"} 500\n")).get(0);

            assertThat(event.message())
                    .contains("WARNING")
                    .contains("deep")
                    .contains("orders")
                    .contains("500")
                    .contains("nothing is consuming");
        }

        @Test
        @DisplayName("nothing fires when nothing breaches")
        void quietWhenHealthy() {
            AlertRule rule = Alert.named("deep")
                    .on("rabbitmq_detailed_queue_messages_ready")
                    .above(100)
                    .because("a queue is deep");

            assertThat(rule.isFiring(scrape("rabbitmq_detailed_queue_messages_ready{queue=\"a\"} 5\n")))
                    .isFalse();
        }

        @Test
        @DisplayName("above zero is the right shape for a dead letter")
        void aboveZero() {
            AlertRule rule = Alerts.deadLetters();

            assertThat(rule.isFiring(scrape("rabbitmq_detailed_queue_messages_ready{queue=\"x.dlq\"} 1\n")))
                    .isTrue();
            assertThat(rule.isFiring(scrape("rabbitmq_detailed_queue_messages_ready{queue=\"x.dlq\"} 0\n")))
                    .isFalse();
        }

        @Test
        @DisplayName("a dead-letter alert does not fire for an ordinary queue")
        void deadLettersOnlyMatchesDeadLetterQueues() {
            // Without the queue filter this rule is "any queue holds a message", at critical,
            // for the whole broker. The version of this test that only checked a queue called
            // "x.dlq" passed happily while the rule fired on everything.
            AlertRule rule = Alerts.deadLetters();

            assertThat(rule.isFiring(scrape(
                    "rabbitmq_detailed_queue_messages_ready{queue=\"orders.new\"} 5000\n")))
                    .isFalse();
            // Anchored, not a substring: a queue merely containing "dlq" is not one.
            assertThat(rule.isFiring(scrape(
                    "rabbitmq_detailed_queue_messages_ready{queue=\"dlq.replayed\"} 12\n")))
                    .isFalse();
            assertThat(rule.isFiring(scrape(
                    "rabbitmq_detailed_queue_messages_ready{queue=\"orders.dead-letter\"} 1\n")))
                    .isTrue();
        }

        @Test
        @DisplayName("a backlog with a consumer attached is not an alert")
        void backlogNeedsNoConsumers() {
            AlertRule rule = Alerts.queueNotDraining();

            String deep = "rabbitmq_detailed_queue_messages_ready{vhost=\"/\",queue=\"orders\"} 5000\n";

            // Depth alone is a normal burst. The alert is depth with nothing reading.
            assertThat(rule.isFiring(scrape(deep
                    + "rabbitmq_detailed_queue_consumers{vhost=\"/\",queue=\"orders\"} 3\n")))
                    .isFalse();
            assertThat(rule.isFiring(scrape(deep
                    + "rabbitmq_detailed_queue_consumers{vhost=\"/\",queue=\"orders\"} 0\n")))
                    .isTrue();
            // The consumer count of a different queue must not satisfy the join.
            assertThat(rule.isFiring(scrape(deep
                    + "rabbitmq_detailed_queue_consumers{vhost=\"/\",queue=\"other\"} 0\n")))
                    .isFalse();
        }

        @Test
        @DisplayName("a set reports its worst finding first")
        void criticalFirst() {
            Alerts.AlertSet set = Alerts.recommended();

            List<AlertEvent> firing = set.evaluate(scrape(
                    "rabbitmq_detailed_queue_messages_ready{vhost=\"/\",queue=\"orders\"} 5000\n"
                            + "rabbitmq_detailed_queue_consumers{vhost=\"/\",queue=\"orders\"} 0\n"
                            + "rabbitmq_alarms_memory_used_watermark 1\n"));

            // Whoever prints the first line should get the worst thing, not the
            // first-declared thing.
            assertThat(firing).isNotEmpty();
            assertThat(firing.get(0).rule().severity()).isEqualTo(Severity.CRITICAL);
            assertThat(set.hasCritical(scrape("rabbitmq_alarms_memory_used_watermark 1\n"))).isTrue();
        }

        @Test
        @DisplayName("a healthy broker's own scrape fires nothing")
        void quietOnAHealthyBroker() {
            // Every alarm gauge reads 0 on a healthy broker, and these are the real names.
            assertThat(Alerts.recommended().evaluate(scrape(
                    "rabbitmq_alarms_memory_used_watermark 0\n"
                            + "rabbitmq_alarms_free_disk_space_watermark 0\n"
                            + "rabbitmq_disk_space_available_bytes 1836396748800\n")))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("exporting to Prometheus")
    class Exporting {

        @Test
        @DisplayName("renders a rule the way Prometheus expects one")
        void rendersARule() {
            String yaml = Alert.named("queue-backing-up")
                    .on("rabbitmq_detailed_queue_messages_ready")
                    .above(1000)
                    .lasting(Duration.ofMinutes(5))
                    .severity(Severity.WARNING)
                    .groupedBy("vhost", "queue")
                    .because("a queue is deep and not draining")
                    .toPrometheusRule();

            assertThat(yaml).contains("- alert: QueueBackingUp");
            assertThat(yaml).contains("expr: rabbitmq_detailed_queue_messages_ready > 1000");
            assertThat(yaml).contains("for: 5m");
            assertThat(yaml).contains("severity: warning");
            // The label templating that makes a Prometheus notification say which queue.
            assertThat(yaml).contains("{{ $labels.queue }}");
            assertThat(yaml).contains("{{ $value }}");
        }

        @Test
        @DisplayName("a rule with no duration has no for: clause")
        void noDurationNoForClause() {
            String yaml = Alerts.deadLetters().toPrometheusRule();

            assertThat(yaml).doesNotContain("for:");
            assertThat(yaml).contains("severity: critical");
        }

        @Test
        @DisplayName("a set renders a whole rules file")
        void rendersAFile() {
            String yaml = Alerts.recommended().toPrometheusRules();

            assertThat(yaml).startsWith("groups:\n- name: acemq\n  rules:\n");
            assertThat(yaml).contains("QueueNotDraining").contains("DeadLettersPresent")
                    .contains("MemoryAlarm").contains("DiskAlarm").contains("DiskSpaceLow");
        }

        @Test
        @DisplayName("a label filter reaches the exported expression too")
        void filterIsExported() {
            String yaml = Alerts.deadLetters().toPrometheusRule();

            // If the selector were applied only in evaluate(), the deployed alert would be the
            // unfiltered one that fires on every queue — the drift this class exists to stop.
            assertThat(yaml).contains(
                    "expr: rabbitmq_detailed_queue_messages_ready{queue=~\".*\\\\.(dlq|dead-letter)\"} > 0");
        }

        @Test
        @DisplayName("a companion condition is exported as an and-on join")
        void companionIsExported() {
            String yaml = Alerts.queueNotDraining().toPrometheusRule();

            assertThat(yaml).contains("rabbitmq_detailed_queue_messages_ready > 100"
                    + " and on(vhost, queue) rabbitmq_detailed_queue_consumers == 0");
        }

        @Test
        @DisplayName("the same rule drives both the check and the exported alert")
        void oneDefinitionTwoUses() {
            AlertRule rule = Alerts.memoryAlarm();

            // The reason this class exists: a deployment gate, a health endpoint and the
            // on-call alert otherwise express one intention in three places and drift.
            assertThat(rule.isFiring(scrape("rabbitmq_alarms_memory_used_watermark 1\n"))).isTrue();
            assertThat(rule.toPrometheusRule()).contains("rabbitmq_alarms_memory_used_watermark > 0");
        }
    }

    @Nested
    @DisplayName("building one")
    class Building {

        @Test
        @DisplayName("a reason is required, because an alert without one gets silenced")
        void reasonRequired() {
            assertThatThrownBy(() -> Alert.named("x").on("m").above(1).because(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("silenced");
        }

        @Test
        @DisplayName("a threshold is required")
        void thresholdRequired() {
            assertThatThrownBy(() -> Alert.named("x").on("m").because("why"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no threshold");
        }

        @Test
        @DisplayName("the metric must be named before the threshold, because the expression is built from it")
        void metricBeforeThreshold() {
            assertThatThrownBy(() -> Alert.named("x").above(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("before the threshold");
        }

        @Test
        @DisplayName("a filter applies whichever side of the threshold it is written")
        void filterOrderDoesNotMatter() {
            String before = Alert.named("x").on("m").where("queue", "a.*").above(1)
                    .because("why").toPrometheusRule();
            String after = Alert.named("x").on("m").above(1).where("queue", "a.*")
                    .because("why").toPrometheusRule();

            // The expression used to be assembled inside above(), so a filter written after it
            // was dropped from the exported rule while still applying in evaluate().
            assertThat(before).isEqualTo(after);
            assertThat(after).contains("expr: m{queue=~\"a.*\"} > 1");
        }

        @Test
        @DisplayName("a bad regular expression is refused at build time")
        void badPattern() {
            assertThatThrownBy(() -> Alert.named("x").on("m").where("queue", "*.dlq"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a valid regular expression");
        }

        @Test
        @DisplayName("a companion condition needs the labels that join the two metrics")
        void companionNeedsGroupBy() {
            assertThatThrownBy(() -> Alert.named("x").on("m").above(1)
                    .whileZero("other").because("why"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("groupedBy");
        }
    }
}
