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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("parsing a Prometheus scrape")
class PrometheusParsingTest {

    @Nested
    @DisplayName("the ordinary shapes")
    class Ordinary {

        @Test
        @DisplayName("a metric with no labels")
        void unlabelled() {
            MetricsSnapshot snapshot = PrometheusMetrics.parse(
                    "# TYPE rabbitmq_connections gauge\n"
                            + "# HELP rabbitmq_connections Connections currently open\n"
                            + "rabbitmq_connections 7\n");

            assertThat(snapshot.metric("rabbitmq_connections")).isPresent();
            assertThat(snapshot.metric("rabbitmq_connections").orElseThrow().asLong()).isEqualTo(7);
            assertThat(snapshot.helpFor("rabbitmq_connections")).contains("Connections currently open");
        }

        @Test
        @DisplayName("a metric with labels, one sample per queue")
        void labelled() {
            MetricsSnapshot snapshot = PrometheusMetrics.parse(
                    "rabbitmq_detailed_queue_messages_ready{vhost=\"/\",queue=\"orders.new\"} 42\n"
                            + "rabbitmq_detailed_queue_messages_ready{vhost=\"/\",queue=\"orders.dlq\"} 3\n");

            assertThat(snapshot.all("rabbitmq_detailed_queue_messages_ready")).hasSize(2);
            assertThat(snapshot.queues()).containsExactly("orders.new", "orders.dlq");
            assertThat(snapshot.forQueue("orders.new")
                    .metric("rabbitmq_detailed_queue_messages_ready").orElseThrow().asLong())
                    .isEqualTo(42);
            // The sum is what "how many are waiting anywhere" means on the detailed endpoint.
            assertThat(snapshot.sum("rabbitmq_detailed_queue_messages_ready")).isEqualTo(45.0);
        }

        @Test
        @DisplayName("comments and blank lines are not samples")
        void ignoresNonSamples() {
            MetricsSnapshot snapshot = PrometheusMetrics.parse(
                    "# HELP a thing\n# TYPE a gauge\n\n   \na 1\n");

            assertThat(snapshot.samples()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("the shapes that break naive parsers")
    class Awkward {

        @Test
        @DisplayName("a label value containing a comma")
        void commaInsideALabel() {
            // A queue may legally be called this, and splitting the label block on commas
            // would produce two broken labels and lose the queue's name.
            MetricsSnapshot snapshot = PrometheusMetrics.parse(
                    "q{vhost=\"/\",queue=\"orders,new\"} 5\n");

            assertThat(snapshot.samples()).hasSize(1);
            assertThat(snapshot.samples().get(0).queue()).contains("orders,new");
        }

        @Test
        @DisplayName("a label value containing an escaped quote and a brace")
        void quotesAndBracesInsideALabel() {
            // Finding the closing brace by indexOf('}') would end the labels early.
            MetricsSnapshot snapshot = PrometheusMetrics.parse(
                    "q{queue=\"od\\\"d}\",vhost=\"/\"} 9\n");

            assertThat(snapshot.samples()).hasSize(1);
            assertThat(snapshot.samples().get(0).queue()).contains("od\"d}");
            assertThat(snapshot.samples().get(0).label("vhost")).contains("/");
        }

        @Test
        @DisplayName("a trailing timestamp is not the value")
        void trailingTimestamp() {
            // Legal in the format. Reading the whole remainder as a number fails here.
            MetricsSnapshot snapshot = PrometheusMetrics.parse("a{q=\"x\"} 12 1699999999000\n");

            assertThat(snapshot.samples().get(0).value()).isEqualTo(12.0);
        }

        @Test
        @DisplayName("NaN and infinities are skipped rather than failing the whole scrape")
        void nonNumericValues() {
            MetricsSnapshot snapshot = PrometheusMetrics.parse(
                    "a 1\nb NaN\nc +Inf\nd -Inf\ne Infinity\nf 4\n");

            // One bad line must not cost every other metric in the response.
            assertThat(snapshot.samples()).hasSize(2);
            assertThat(snapshot.metric("a")).isPresent();
            assertThat(snapshot.metric("f")).isPresent();
        }

        @Test
        @DisplayName("one NaN does not turn a whole sum into NaN")
        void nanDoesNotPoisonASum() {
            // Double.parseDouble("NaN") succeeds, so this is not caught by the malformed-number
            // path and has to be excluded on purpose. Without that, a broker-wide total reads
            // "NaN" because one queue had no value yet.
            MetricsSnapshot snapshot = PrometheusMetrics.parse(
                    "q{queue=\"a\"} 10\nq{queue=\"b\"} NaN\nq{queue=\"c\"} 5\n");

            assertThat(snapshot.sum("q")).isEqualTo(15.0);
        }

        @Test
        @DisplayName("an empty scrape is empty rather than an error")
        void emptyScrape() {
            assertThat(PrometheusMetrics.parse("").isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("connecting")
    class Connecting {

        @Test
        @DisplayName("refuses a URL that is not http")
        void refusesNonHttp() {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> PrometheusMetrics.at("amqp://localhost:5672"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("15692");
        }

        @Test
        @DisplayName("refuses a detailed scrape with no families named")
        void refusesUnboundedDetailedScrape() {
            // The detailed endpoint with no filter enumerates every object on the broker.
            //
            // Note the explicit empty array. Written as scrapeDetailed() this calls the no-arg
            // overload, which is a legitimate scrape of the default families and connects to
            // the broker — so the earlier version of this test passed only while nothing was
            // listening on 15692, and asserted nothing about the guard it names.
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> PrometheusMetrics.at("http://localhost:15692")
                            .scrapeDetailed(new String[0]))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("every object");
        }
    }
}
