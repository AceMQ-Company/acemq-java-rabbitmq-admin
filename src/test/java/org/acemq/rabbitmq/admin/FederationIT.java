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

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Federation and shovels, on a broker with the plugins actually enabled.
 *
 * <p>Separate from {@link AdministrationIT} because it needs a differently configured broker,
 * and that difference is the point: on the stock image these endpoints answer 406, and a client
 * that treated it as a failure would make "is anything federated?" unanswerable on the many
 * brokers where the answer is simply no.
 */
@Testcontainers
@DisplayName("federation and shovels")
class FederationIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"))
            .withPluginsEnabled(
                    "rabbitmq_federation", "rabbitmq_federation_management",
                    "rabbitmq_shovel", "rabbitmq_shovel_management");

    private static String url;

    @BeforeAll
    static void setUp() throws IOException, InterruptedException {
        url = "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(15672);
    }

    private static RabbitAdmin admin() {
        return RabbitAdmin.connect(url, BROKER.getAdminUsername(), BROKER.getAdminPassword());
    }

    @Nested
    @DisplayName("upstreams")
    class Upstreams {

        @Test
        @Timeout(180)
        @DisplayName("can be declared, listed and removed")
        void lifecycle() {
            try (RabbitAdmin admin = admin()) {
                admin.putFederationUpstream("other-dc", "amqp://guest:guest@localhost:5672",
                        Map.of("expires", 3_600_000));
                try {
                    FederationUpstream upstream = admin.federationUpstreams().stream()
                            .filter(u -> "other-dc".equals(u.name()))
                            .findFirst()
                            .orElseThrow();

                    assertThat(upstream.vhost()).isEqualTo("/");
                    assertThat(upstream.expires()).contains(3_600_000L);
                    assertThat(upstream.settings()).containsKey("uri");
                } finally {
                    admin.deleteFederationUpstream("other-dc");
                }

                assertThat(admin.federationUpstreams()).extracting(FederationUpstream::name)
                        .doesNotContain("other-dc");
            }
        }

        @Test
        @Timeout(180)
        @DisplayName("the password in the URI never reaches a log line")
        void redactsCredentials() {
            try (RabbitAdmin admin = admin()) {
                admin.putFederationUpstream("secret-dc", "amqp://someone:hunter2@elsewhere:5672", Map.of());
                try {
                    FederationUpstream upstream = admin.federationUpstreams().stream()
                            .filter(u -> "secret-dc".equals(u.name()))
                            .findFirst()
                            .orElseThrow();

                    // The broker stores and returns the password, so this object holds one
                    // whether anybody wanted it to or not.
                    assertThat(upstream.uri()).contains("hunter2");

                    // And neither of these may.
                    assertThat(upstream.redactedUri()).doesNotContain("hunter2").contains("someone:***");
                    assertThat(upstream.toString()).doesNotContain("hunter2");
                } finally {
                    admin.deleteFederationUpstream("secret-dc");
                }
            }
        }

        @Test
        @Timeout(180)
        @DisplayName("an upstream on its own federates nothing, which is the usual surprise")
        void anUpstreamAloneDoesNothing() {
            try (RabbitAdmin admin = admin()) {
                admin.putFederationUpstream("idle-dc", "amqp://guest:guest@localhost:5672", Map.of());
                try {
                    // Declared, valid, and linked to nothing: no policy names it. This is what
                    // "federation is not working" usually is, and nothing reports it because
                    // nothing is wrong.
                    assertThat(admin.federationUpstreams()).extracting(FederationUpstream::name)
                            .contains("idle-dc");
                    assertThat(admin.federationLinks()).isEmpty();
                } finally {
                    admin.deleteFederationUpstream("idle-dc");
                }
            }
        }
    }

    @Nested
    @DisplayName("shovels")
    class Shovels {

        @Test
        @Timeout(180)
        @DisplayName("a dynamic shovel can be declared, reports itself running, and removed")
        void lifecycle() throws InterruptedException {
            try (RabbitAdmin admin = admin()) {
                admin.declareShovel("drain-orders", Map.of(
                        "src-protocol", "amqp091",
                        "src-uri", "amqp://localhost",
                        "src-queue", "orders.old",
                        "dest-protocol", "amqp091",
                        "dest-uri", "amqp://localhost",
                        "dest-queue", "orders.new"));
                try {
                    ShovelInfo shovel = awaitShovel(admin, "drain-orders");

                    assertThat(shovel.type()).isEqualTo("dynamic");
                    assertThat(shovel.isRunning()).isTrue();
                } finally {
                    admin.deleteShovel("drain-orders");
                }

                assertThat(admin.shovels()).extracting(ShovelInfo::name).doesNotContain("drain-orders");
            }
        }

        private ShovelInfo awaitShovel(RabbitAdmin admin, String name) throws InterruptedException {
            // A shovel takes a moment to start, and reports "starting" until it has connected.
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                var found = admin.shovels().stream().filter(s -> name.equals(s.name())).findFirst();
                if (found.isPresent() && found.get().isRunning()) {
                    return found.get();
                }
                Thread.sleep(200);
            }
            throw new AssertionError("the shovel '" + name + "' never reached running");
        }
    }

    @Nested
    @DisplayName("runtime parameters")
    class Parameters {

        @Test
        @Timeout(180)
        @DisplayName("both federation and shovels are stored as parameters, which an export can miss")
        void bothAreParameters() {
            try (RabbitAdmin admin = admin()) {
                admin.putFederationUpstream("dc", "amqp://guest:guest@localhost:5672", Map.of());
                try {
                    // The reason this matters: a topology export that walks queues, exchanges
                    // and bindings misses both entirely, and a broker restored from one comes
                    // back without its federation and looks complete.
                    assertThat(admin.parameters("federation-upstream"))
                            .extracting(ParameterInfo::name).contains("dc");
                    assertThat(admin.parameters("federation-upstream"))
                            .allSatisfy(p -> assertThat(p.component()).isEqualTo("federation-upstream"));
                } finally {
                    admin.deleteFederationUpstream("dc");
                }
            }
        }

        @Test
        @Timeout(180)
        @DisplayName("a parameter's toString does not print its value, because the value has credentials in it")
        void parametersDoNotPrintTheirValue() {
            try (RabbitAdmin admin = admin()) {
                admin.putFederationUpstream("dc", "amqp://someone:hunter2@elsewhere:5672", Map.of());
                try {
                    ParameterInfo parameter = admin.parameters("federation-upstream").stream()
                            .filter(p -> "dc".equals(p.name()))
                            .findFirst()
                            .orElseThrow();

                    assertThat(parameter.value()).containsKey("uri");
                    assertThat(parameter.toString()).doesNotContain("hunter2");
                } finally {
                    admin.deleteFederationUpstream("dc");
                }
            }
        }
    }
}
