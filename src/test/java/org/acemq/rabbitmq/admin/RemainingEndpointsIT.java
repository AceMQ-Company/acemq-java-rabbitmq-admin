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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.rabbitmq.stream.Address;
import com.rabbitmq.stream.Consumer;
import com.rabbitmq.stream.Environment;
import com.rabbitmq.stream.OffsetSpecification;
import com.rabbitmq.stream.Producer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The endpoints added in 0.2.0: hashed passwords, bulk user deletion, users without
 * permissions, extensions, authentication attempts, queue rebalancing and the stream protocol.
 *
 * <p>Runs on {@code rabbitmq:4-management} by default; {@code -Drabbitmq.image=rabbitmq:3.13-management}
 * runs it on the previous series, which every one of these endpoints also exists on.
 */
@Testcontainers
@DisplayName("the remaining management endpoints, on a real RabbitMQ")
class RemainingEndpointsIT {

    private static final int STREAM_PORT = 5552;

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse(System.getProperty("rabbitmq.image", "rabbitmq:4-management"))
                    .asCompatibleSubstituteFor("rabbitmq"))
            .withPluginsEnabled("rabbitmq_stream", "rabbitmq_stream_management")
            .withExposedPorts(5672, 15672, STREAM_PORT);

    private static String url;

    @BeforeAll
    static void connect() {
        url = "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(15672);
    }

    private static RabbitAdmin admin() {
        return RabbitAdmin.connect(url, "guest", "guest", Duration.ofSeconds(20));
    }

    private static String node(RabbitAdmin admin) {
        return admin.nodes().get(0).name();
    }

    @Nested
    @DisplayName("users")
    class Users {

        @Test
        @DisplayName("a password hashed here is one the broker accepts at login")
        void locallyHashedPasswordLogsIn() {
            try (RabbitAdmin admin = admin()) {
                // Non-ASCII on purpose: the broker hashes the UTF-8 bytes, and a client that
                // used the platform charset would create a user whose password never works.
                admin.createUser("hashed-256", "pässwörd-256", "monitoring");
                admin.createUser("hashed-512", PasswordHash.sha512("pässwörd-512"), "monitoring");
                try {
                    try (RabbitAdmin as = RabbitAdmin.connect(url, "hashed-256", "pässwörd-256")) {
                        assertThat(as.whoami().name()).isEqualTo("hashed-256");
                    }
                    try (RabbitAdmin as = RabbitAdmin.connect(url, "hashed-512", "pässwörd-512")) {
                        assertThat(as.whoami().name()).isEqualTo("hashed-512");
                    }
                    try (RabbitAdmin as = RabbitAdmin.connect(url, "hashed-256", "wrong")) {
                        assertThatThrownBy(as::whoami)
                                .isInstanceOf(AdminException.class)
                                .hasMessageContaining("rejected these credentials");
                    }
                } finally {
                    admin.deleteUsers("hashed-256", "hashed-512");
                }
            }
        }

        @Test
        @DisplayName("the broker stores exactly the hash it was sent")
        void storesTheHashItWasSent() {
            try (RabbitAdmin admin = admin()) {
                PasswordHash hash = PasswordHash.sha256("stored-as-sent");
                admin.createUser("hash-sent", hash);
                try {
                    // Proof the password was not what crossed the wire: the broker would
                    // have salted it itself, and the stored hash would differ from ours.
                    assertThat(admin.exportDefinitions().users())
                            .filteredOn(u -> "hash-sent".equals(u.get("name")))
                            .singleElement()
                            .satisfies(u -> {
                                assertThat(u.get("password_hash")).isEqualTo(hash.hash());
                                assertThat(u.get("hashing_algorithm")).isEqualTo(PasswordHash.SHA256);
                            });
                } finally {
                    admin.deleteUser("hash-sent");
                }
            }
        }

        @Test
        @DisplayName("a user with no permissions is listed until it is granted some")
        void usersWithoutPermissions() {
            try (RabbitAdmin admin = admin()) {
                admin.createUser("orphan", "orphan-password");
                try {
                    assertThat(admin.usersWithoutPermissions()).extracting(UserInfo::name)
                            .contains("orphan")
                            .doesNotContain("guest");

                    admin.grant("orphan", "", "", ".*");

                    assertThat(admin.usersWithoutPermissions()).extracting(UserInfo::name)
                            .doesNotContain("orphan");
                } finally {
                    admin.deleteUser("orphan");
                }
            }
        }

        @Test
        @DisplayName("bulk delete removes every named user and tolerates ones already gone")
        void bulkDelete() {
            try (RabbitAdmin admin = admin()) {
                admin.createUser("bulk-1", "p1");
                admin.createUser("bulk-2", "p2");

                admin.deleteUsers("bulk-1", "bulk-2", "never-existed");

                assertThat(admin.users()).extracting(UserInfo::name)
                        .doesNotContain("bulk-1", "bulk-2");
                // Repeating it is not an error, which is what makes it safe in a cleanup step.
                admin.deleteUsers("bulk-1", "bulk-2");
            }
        }
    }

    @Nested
    @DisplayName("authentication attempts")
    class AuthAttempts {

        @Test
        @DisplayName("a failed login is counted, per protocol and, when tracked, per source")
        void failedLoginsAreCounted() throws Exception {
            var run = BROKER.execInContainer("rabbitmqctl", "eval",
                    "application:set_env(rabbit, track_auth_attempt_source, true).");
            assertThat(run.getExitCode()).as(run.getStderr()).isZero();

            try (RabbitAdmin admin = admin()) {
                String node = node(admin);
                admin.resetAuthAttempts(node);

                try (RabbitAdmin intruder = RabbitAdmin.connect(url, "intruder", "guess")) {
                    assertThatThrownBy(intruder::whoami).isInstanceOf(AdminException.class);
                }

                assertThat(admin.authAttempts(node))
                        .filteredOn(a -> "http".equals(a.protocol()))
                        .singleElement()
                        .satisfies(a -> {
                            assertThat(a.failed()).isGreaterThanOrEqualTo(1);
                            assertThat(a.remoteAddress()).isNull();
                        });
                assertThat(admin.authAttemptsBySource(node))
                        .filteredOn(a -> "intruder".equals(a.username()))
                        .singleElement()
                        .satisfies(a -> {
                            assertThat(a.failed()).isEqualTo(1);
                            assertThat(a.succeeded()).isZero();
                            assertThat(a.remoteAddress()).isNotBlank();
                        });

                admin.resetAuthAttempts(node);

                // The reset clears both views. The admin's own next request is counted again,
                // so "empty" is not the right assertion -- "the intruder is gone" is.
                assertThat(admin.authAttemptsBySource(node))
                        .noneMatch(a -> "intruder".equals(a.username()));
                assertThat(admin.authAttempts(node)).allMatch(a -> a.failed() == 0);
            }
        }

        @Test
        @DisplayName("an unknown node is an empty answer, not an error")
        void unknownNode() {
            try (RabbitAdmin admin = admin()) {
                assertThat(admin.authAttempts("rabbit@nowhere")).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("cluster")
    class Cluster {

        @Test
        @DisplayName("rebalancing is accepted, and on one node changes nothing")
        void rebalance() {
            try (RabbitAdmin admin = admin()) {
                admin.declareQueue("rebalance.q", true, Map.of("x-queue-type", "quorum"));
                try {
                    admin.rebalanceQueues();

                    assertThat(admin.queue("rebalance.q")).isPresent();
                } finally {
                    admin.deleteQueue("rebalance.q");
                }
            }
        }

        @Test
        @DisplayName("extensions name the management plugins that are enabled")
        void extensions() {
            try (RabbitAdmin admin = admin()) {
                assertThat(admin.extensions()).contains("dispatcher.js", "stream.js");
            }
        }
    }

    @Nested
    @DisplayName("streams")
    class Streams {

        @Test
        @DisplayName("connections, publishers and consumers are listed and join up")
        void streamTraffic() throws Exception {
            try (RabbitAdmin admin = admin()) {
                assumeTrue(admin.extensions().contains("stream.js"),
                        "rabbitmq_stream_management is not enabled on this broker");

                String host = BROKER.getHost();
                int port = BROKER.getMappedPort(STREAM_PORT);
                // The broker advertises its container hostname and port 5552, neither of which
                // is reachable from here; every address is resolved to the mapped port instead.
                try (Environment env = Environment.builder().host(host).port(port)
                        .addressResolver(address -> new Address(host, port)).build()) {
                    env.streamCreator().stream("events.stream").create();
                    try {
                        CountDownLatch received = new CountDownLatch(1);
                        Producer producer = env.producerBuilder().stream("events.stream")
                                .name("events-writer").build();
                        producer.send(producer.messageBuilder().addData(new byte[] {1}).build(),
                                status -> { });
                        Consumer consumer = env.consumerBuilder().stream("events.stream")
                                .name("events-reader").offset(OffsetSpecification.first())
                                .messageHandler((context, message) -> received.countDown())
                                .build();
                        assertThat(received.await(30, TimeUnit.SECONDS)).isTrue();

                        List<StreamPublisherInfo> publishers = await(admin::streamPublishers,
                                list -> list.stream().anyMatch(p -> p.connectionName() != null
                                        && p.published() >= 1));
                        StreamPublisherInfo publisher = publishers.stream()
                                .filter(p -> "events-writer".equals(p.reference()))
                                .findFirst().orElseThrow();
                        assertThat(publisher.stream()).isEqualTo("events.stream");
                        assertThat(publisher.vhost()).isEqualTo("/");
                        assertThat(publisher.errored()).isZero();
                        assertThat(admin.streamPublishers("events.stream"))
                                .extracting(StreamPublisherInfo::reference).contains("events-writer");
                        assertThat(admin.streamPublishers("no.such.stream")).isEmpty();

                        StreamConsumerInfo reader = await(admin::streamConsumers,
                                list -> list.stream().anyMatch(c -> c.connectionName() != null))
                                .stream().filter(c -> "events-reader".equals(c.name()))
                                .findFirst().orElseThrow();
                        assertThat(reader.stream()).isEqualTo("events.stream");
                        assertThat(reader.active()).isTrue();

                        // The publisher's connection is one of the stream connections, and
                        // every stream connection there is one.
                        assertThat(admin.streamConnections())
                                .extracting(StreamConnectionInfo::name)
                                .contains(publisher.connectionName(), reader.connectionName());
                        assertThat(admin.streamConnections())
                                .allMatch(c -> c.name().endsWith(":5552"));

                        admin.createVhost("streamless");
                        try {
                            assertThat(admin.forVhost("streamless").streamPublishers()).isEmpty();
                            assertThat(admin.forVhost("streamless").streamConsumers()).isEmpty();
                        } finally {
                            admin.deleteVhost("streamless");
                        }

                        consumer.close();
                        producer.close();
                    } finally {
                        env.deleteStream("events.stream");
                    }
                }
            }
        }

        /** Stream entries appear on the broker's statistics interval, five seconds by default. */
        private <T> T await(Supplier<T> read, Predicate<T> done) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            T value = read.get();
            while (!done.test(value) && System.nanoTime() < deadline) {
                Thread.sleep(500);
                value = read.get();
            }
            assertThat(done.test(value)).as("stream statistics within 30s: " + value).isTrue();
            return value;
        }
    }
}
