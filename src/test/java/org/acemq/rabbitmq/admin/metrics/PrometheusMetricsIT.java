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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.acemq.rabbitmq.admin.alerts.AlertEvent;
import org.acemq.rabbitmq.admin.alerts.AlertRule;
import org.acemq.rabbitmq.admin.alerts.Alerts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Against a real broker, because a metric name is only correct if RabbitMQ emits it.
 *
 * <p>This is the test that matters most for the alerts. A rule naming a metric the broker does
 * not publish compiles, passes every unit test written against a hand-made scrape, renders
 * perfectly valid Prometheus YAML — and never fires. It is silent in exactly the way a working
 * alert is silent, and the difference only shows up during the incident it should have caught.
 * {@link #everyRecommendedMetricExists()} is the guard.
 */
@Testcontainers
@DisplayName("the Prometheus endpoint on a real RabbitMQ")
class PrometheusMetricsIT {

    /** Enough to breach {@link Alerts#queueNotDraining()}, which fires above 100. */
    private static final int BACKLOG = 150;

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"))
            // Not enabled by default, and on a different port from the management plugin.
            .withPluginsEnabled("rabbitmq_prometheus");

    static {
        // addExposedPort rather than withExposedPorts: the latter replaces the container's own
        // set and would drop 5672 and 15672 along with it.
        BROKER.addExposedPort(PrometheusMetrics.DEFAULT_PORT);
    }

    private static PrometheusMetrics metrics;

    @BeforeAll
    @Timeout(120)
    static void fillAQueue() throws IOException, InterruptedException {
        metrics = PrometheusMetrics.at("http://" + BROKER.getHost() + ":"
                + BROKER.getMappedPort(PrometheusMetrics.DEFAULT_PORT));

        run("rabbitmqadmin", "declare", "queue", "--name", "orders.new", "--durable", "true");
        run("rabbitmqadmin", "declare", "queue", "--name", "orders.dlq", "--durable", "true");

        // A backlog with no consumer, which is the shape queueNotDraining() looks for, and one
        // dead letter. Published through rabbitmqadmin rather than this library: a test that
        // both produces and reads the numbers proves only that it agrees with itself.
        //
        // One exec with a loop inside it rather than BACKLOG execs: each execInContainer is a
        // round trip to the daemon, and at 150 of them this fixture cost more than the rest of
        // the suite put together.
        run("sh", "-c", "for i in $(seq 1 " + BACKLOG + "); do"
                + " rabbitmqadmin publish message --routing-key orders.new --payload order-$i"
                + " >/dev/null || exit 1; done");
        run("rabbitmqadmin", "publish", "message", "--routing-key", "orders.dlq", "--payload", "failed");

        // Both, not just the first. RabbitMQ refreshes these counters on an interval, so
        // waiting only for orders.new returned on the first poll — it was already full — while
        // orders.dlq still read zero, and every assertion about it failed.
        awaitQueueDepth("orders.new", BACKLOG);
        awaitQueueDepth("orders.dlq", 1);
    }

    private static void run(String... command) throws IOException, InterruptedException {
        var result = BROKER.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("fixture command failed: " + String.join(" ", command)
                    + "\n" + result.getStderr() + result.getStdout());
        }
    }

    /** The endpoint is computed per request, but the queue's own counters settle a moment later. */
    private static void awaitQueueDepth(String queue, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        long seen = -1;
        while (System.nanoTime() < deadline) {
            seen = metrics.scrapeDetailed()
                    .forQueue(queue)
                    .metric("rabbitmq_detailed_queue_messages_ready")
                    .map(MetricSample::asLong)
                    .orElse(-1L);
            if (seen == expected) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("queue " + queue + " never reached " + expected
                + " messages; last read " + seen);
    }

    @Test
    @DisplayName("every metric the recommended alerts name is one the broker actually emits")
    void everyRecommendedMetricExists() {
        MetricsSnapshot aggregate = metrics.scrape();
        MetricsSnapshot detailed = metrics.scrapeDetailed();

        List<String> missing = new ArrayList<>();
        for (AlertRule rule : Alerts.recommended().rules()) {
            for (String metric : namesUsedBy(rule)) {
                // The detailed endpoint carries the prefixed names; everything else is aggregate.
                MetricsSnapshot where = metric.startsWith("rabbitmq_detailed_") ? detailed : aggregate;
                if (where.all(metric).isEmpty()) {
                    missing.add(rule.name() + " -> " + metric);
                }
            }
        }

        // This assertion caught rabbitmq_connections_blocked, which does not exist: the real
        // signals for a blocked publisher are the two alarm watermark gauges.
        assertThat(missing)
                .withFailMessage("these alert rules name metrics the broker does not emit,"
                        + " so they can never fire: %s", missing)
                .isEmpty();
    }

    private static List<String> namesUsedBy(AlertRule rule) {
        List<String> names = new ArrayList<>();
        names.add(rule.metric());
        if (rule.companionMetric() != null) {
            names.add(rule.companionMetric());
        }
        return names;
    }

    @Test
    @DisplayName("the detailed endpoint gives a message count per queue")
    void messageCountPerQueue() {
        MetricsSnapshot detailed = metrics.scrapeDetailed();

        assertThat(detailed.forQueue("orders.new")
                .metric("rabbitmq_detailed_queue_messages_ready")
                .orElseThrow()
                .asLong())
                .isEqualTo(BACKLOG);
        assertThat(detailed.forQueue("orders.dlq")
                .metric("rabbitmq_detailed_queue_messages_ready")
                .orElseThrow()
                .asLong())
                .isEqualTo(1);
        assertThat(detailed.queues()).contains("orders.new", "orders.dlq");
    }

    @Test
    @DisplayName("the aggregate endpoint cannot say which queue, which is why detailed exists")
    void aggregateHasNoQueueLabel() {
        MetricsSnapshot aggregate = metrics.scrape();

        assertThat(aggregate.metric("rabbitmq_queue_messages_ready")).isPresent();
        // One unlabelled broker-wide total. A dashboard built on this shows a backlog without
        // saying where it is.
        assertThat(aggregate.all("rabbitmq_queue_messages_ready")).hasSize(1);
        assertThat(aggregate.all("rabbitmq_queue_messages_ready").get(0).queue()).isEmpty();
        assertThat(aggregate.all("rabbitmq_detailed_queue_messages_ready")).isEmpty();
    }

    @Test
    @DisplayName("a backlog with no consumers fires the alert, against real numbers")
    void alertsFireAgainstARealScrape() {
        List<AlertEvent> firing = Alerts.queueNotDraining().evaluate(metrics.scrapeDetailed());

        assertThat(firing).hasSize(1);
        assertThat(firing.get(0).queue()).contains("orders.new");
        assertThat(firing.get(0).message()).contains("orders.new").contains(String.valueOf(BACKLOG));
    }

    @Test
    @DisplayName("the dead-letter rule picks out the dlq and leaves the busy queue alone")
    void deadLetterRuleIsNarrow() {
        List<AlertEvent> firing = Alerts.deadLetters().evaluate(metrics.scrapeDetailed());

        // orders.new holds 150 messages and must not appear: unfiltered, this critical alert
        // would name every queue on the broker that is doing its job.
        assertThat(firing).hasSize(1);
        assertThat(firing.get(0).queue()).contains("orders.dlq");
    }

    @Test
    @DisplayName("a healthy broker fires none of the recommended alerts it can evaluate")
    void healthyBrokerIsQuiet() {
        // Both alarm gauges read 0 and there is disk free, so nothing here should fire. The
        // queue rules are excluded because this test's own fixture deliberately breaches them.
        MetricsSnapshot aggregate = metrics.scrape();

        assertThat(Alerts.memoryAlarm().isFiring(aggregate)).isFalse();
        assertThat(Alerts.diskAlarm().isFiring(aggregate)).isFalse();
        assertThat(Alerts.diskAlarmApproaching().isFiring(aggregate)).isFalse();
    }

    @Test
    @DisplayName("help text comes back with the samples")
    void helpTextIsParsed() {
        assertThat(metrics.scrape().helpFor("rabbitmq_alarms_memory_used_watermark"))
                .isPresent()
                .get()
                .asString()
                .contains("memory");
    }
}
