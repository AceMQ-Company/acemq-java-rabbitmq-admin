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
package org.acemq.rabbitmq.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("the management client")
class RabbitAdminTest {

    @Nested
    @DisplayName("path encoding")
    class Encoding {

        @Test
        @DisplayName("the default virtual host is a slash, and a slash is not a path separator")
        void encodesTheDefaultVhost() {
            // The single most common reason a management call 404s. The default vhost is
            // literally "/", and leaving it unencoded produces /api/queues///orders.new.
            assertThat(RabbitAdmin.encode("/")).isEqualTo("%2F");
        }

        @Test
        @DisplayName("a space becomes %20 rather than a plus")
        void encodesSpacesForAPath() {
            // URLEncoder is built for form bodies, where a space is "+". In a path a "+" is
            // read literally, so a queue called "dead letters" would be looked up under the
            // name "dead+letters" and reported as missing.
            assertThat(RabbitAdmin.encode("dead letters")).isEqualTo("dead%20letters");
        }

        @Test
        @DisplayName("ordinary names are unchanged")
        void leavesOrdinaryNamesAlone() {
            assertThat(RabbitAdmin.encode("orders.new")).isEqualTo("orders.new");
            assertThat(RabbitAdmin.encode("orders.new.retry.5s")).isEqualTo("orders.new.retry.5s");
        }
    }

    @Nested
    @DisplayName("connecting")
    class Connecting {

        @Test
        @DisplayName("refuses an AMQP URL, which is the wrong endpoint entirely")
        void refusesAnAmqpUrl() {
            // Somebody will pass the broker URL they already have. It is a different protocol
            // on a different port, and failing here is far kinder than a connection timeout.
            assertThatThrownBy(() -> RabbitAdmin.connect("amqp://localhost:5672", "guest", "guest"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("15672");
        }

        @Test
        @DisplayName("refuses a timeout that would never expire")
        void refusesANonPositiveTimeout() {
            assertThatThrownBy(() -> RabbitAdmin.connect(
                    "http://localhost:15672", "guest", "guest", Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("a trailing slash on the base URL does not double up")
        void toleratesATrailingSlash() {
            try (RabbitAdmin admin = RabbitAdmin.connect("http://localhost:15672/", "guest", "guest")) {
                assertThat(admin.toString()).contains("http://localhost:15672");
                assertThat(admin.toString()).doesNotContain("15672/");
            }
        }

        @Test
        @DisplayName("switching virtual host keeps the endpoint and the credentials")
        void forVhostCarriesTheConnection() {
            try (RabbitAdmin admin = RabbitAdmin.connect("http://localhost:15672", "guest", "guest")) {
                RabbitAdmin other = admin.forVhost("payments");

                assertThat(other.vhost()).isEqualTo("payments");
                assertThat(admin.vhost()).isEqualTo("/");
                assertThat(other.toString()).contains("http://localhost:15672");
            }
        }
    }

    @Nested
    @DisplayName("reading a queue")
    class ReadingAQueue {

        @Test
        @DisplayName("a queue the broker has not yet counted does not fail to parse")
        void tolerantOfMissingCounters() {
            // The counters are absent rather than zero on a queue declared a moment ago, which
            // is exactly when a test looks at one.
            QueueInfo queue = new QueueInfo(
                    "orders.new", "/", null, true, false, false, null, null, null, null, null, null);

            assertThat(queue.messages()).isZero();
            assertThat(queue.consumers()).isZero();
            assertThat(queue.arguments()).isEmpty();
            // A queue with no type is a classic queue: the argument predates the field.
            assertThat(queue.type()).isEqualTo("classic");
            assertThat(queue.state()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("arguments are readable individually and as a whole")
        void exposesArguments() {
            QueueInfo queue = new QueueInfo(
                    "orders.new", "/", "classic", true, false, false,
                    java.util.Map.of("x-message-ttl", 60000, "x-dead-letter-exchange", "dlx"),
                    5L, 3L, 2L, 1, "running");

            assertThat(queue.argument("x-message-ttl")).isEqualTo(60000);
            assertThat(queue.arguments()).containsKeys("x-message-ttl", "x-dead-letter-exchange");
            assertThat(queue.messagesReady()).isEqualTo(3L);
            assertThat(queue.toString()).contains("orders.new").contains("x-message-ttl");
        }

        @Test
        @DisplayName("the arguments cannot be modified through the returned map")
        void argumentsAreUnmodifiable() {
            QueueInfo queue = new QueueInfo(
                    "q", "/", "classic", true, false, false,
                    new java.util.HashMap<>(java.util.Map.of("x-max-length", 10)),
                    0L, 0L, 0L, 0, "running");

            assertThatThrownBy(() -> queue.arguments().put("x-max-length", 20))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }
}
