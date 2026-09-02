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
 * Provisioning against a real broker: vhosts, users, permissions, policies, and the topology a
 * broker holds that AMQP cannot report.
 */
@Testcontainers
@DisplayName("administering a real RabbitMQ")
class AdministrationIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    private static String url;

    @BeforeAll
    static void setUp() throws IOException, InterruptedException {
        url = "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(15672);

        exec("rabbitmqadmin", "declare", "exchange", "--name", "orders", "--type", "topic", "--durable", "true");
        exec("rabbitmqadmin", "declare", "queue", "--name", "orders.new", "--durable", "true");
        exec("rabbitmqadmin", "declare", "binding",
                "--source", "orders", "--destination-type", "queue",
                "--destination", "orders.new", "--routing-key", "order.*");
    }

    private static void exec(String... command) throws IOException, InterruptedException {
        var result = BROKER.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("fixture command failed: "
                    + String.join(" ", command) + " -> " + result.getStderr() + result.getStdout());
        }
    }

    private static RabbitAdmin admin() {
        return RabbitAdmin.connect(url, BROKER.getAdminUsername(), BROKER.getAdminPassword());
    }

    @Nested
    @DisplayName("the topology AMQP cannot see")
    class Topology {

        @Test
        @Timeout(120)
        @DisplayName("reports an exchange, which a passive declare cannot do without killing a channel")
        void readsAnExchange() {
            try (RabbitAdmin admin = admin()) {
                ExchangeInfo exchange = admin.exchange("orders").orElseThrow();

                assertThat(exchange.type()).isEqualTo("topic");
                assertThat(exchange.durable()).isTrue();
                assertThat(exchange.internal()).isFalse();
            }
        }

        @Test
        @Timeout(120)
        @DisplayName("reports what is bound to a queue")
        void readsBindings() {
            try (RabbitAdmin admin = admin()) {
                var bindings = admin.bindingsForQueue("orders.new");

                // Two: the one declared, and the implicit default-exchange binding every queue
                // has under its own name, which nobody made and nobody can remove.
                assertThat(bindings).hasSize(2);
                assertThat(bindings).anySatisfy(binding -> {
                    assertThat(binding.source()).isEqualTo("orders");
                    assertThat(binding.routingKey()).isEqualTo("order.*");
                    assertThat(binding.destinationType()).isEqualTo("queue");
                });
                assertThat(bindings).anyMatch(BindingInfo::isDefaultExchangeBinding);
            }
        }

        @Test
        @Timeout(120)
        @DisplayName("the default exchange is a real exchange with an empty name")
        void theDefaultExchangeExists() {
            try (RabbitAdmin admin = admin()) {
                // Not an absence. Publishing to "" is publishing to this, and a listing that
                // skipped it would misrepresent the broker.
                assertThat(admin.exchange("")).isPresent();
                assertThat(admin.exchanges()).extracting(ExchangeInfo::name).contains("", "orders");
            }
        }
    }

    @Nested
    @DisplayName("virtual hosts")
    class Vhosts {

        @Test
        @Timeout(120)
        @DisplayName("can be created, listed and deleted")
        void lifecycle() {
            try (RabbitAdmin admin = admin()) {
                admin.createVhost("payments");
                assertThat(admin.vhosts()).extracting(VhostInfo::name).contains("/", "payments");

                // Creating one that exists is not an error, so provisioning can be re-run.
                admin.createVhost("payments");

                admin.deleteVhost("payments");
                assertThat(admin.vhosts()).extracting(VhostInfo::name).doesNotContain("payments");
            }
        }

        @Test
        @Timeout(120)
        @DisplayName("a client scoped to another vhost reads that one")
        void scopedClient() {
            try (RabbitAdmin admin = admin()) {
                admin.createVhost("scoped");
                try {
                    RabbitAdmin scoped = admin.forVhost("scoped");
                    assertThat(scoped.vhost()).isEqualTo("scoped");
                    // Empty, because the fixtures were declared in "/". Same broker, same
                    // credentials, different world.
                    assertThat(scoped.queues()).isEmpty();
                    assertThat(admin.queues()).isNotEmpty();
                } finally {
                    admin.deleteVhost("scoped");
                }
            }
        }
    }

    @Nested
    @DisplayName("users and permissions")
    class Users {

        @Test
        @Timeout(120)
        @DisplayName("a user can be created with tags, granted narrow access, and removed")
        void lifecycle() {
            try (RabbitAdmin admin = admin()) {
                admin.createUser("orders-service", "s3cret");
                try {
                    assertThat(admin.users()).extracting(UserInfo::name).contains("orders-service");

                    // Narrow on purpose: this service publishes to the orders exchange and reads
                    // its own queue, and can do nothing else.
                    admin.grant("orders-service", "^orders\\\\.", "^orders$", "^orders\\\\.new$");

                    PermissionInfo granted = admin.permissions().stream()
                            .filter(p -> "orders-service".equals(p.user()))
                            .findFirst()
                            .orElseThrow();

                    assertThat(granted.vhost()).isEqualTo("/");
                    assertThat(granted.write()).isEqualTo("^orders$");
                    assertThat(granted.isUnrestricted()).isFalse();
                } finally {
                    admin.deleteUser("orders-service");
                }

                assertThat(admin.users()).extracting(UserInfo::name).doesNotContain("orders-service");
            }
        }

        @Test
        @Timeout(120)
        @DisplayName("the broker's own administrator is reported as one")
        void readsTags() {
            try (RabbitAdmin admin = admin()) {
                UserInfo administrator = admin.users().stream()
                        .filter(u -> u.name().equals(BROKER.getAdminUsername()))
                        .findFirst()
                        .orElseThrow();

                assertThat(administrator.tags()).contains("administrator");
                assertThat(administrator.canManage()).isTrue();
            }
        }

        @Test
        @Timeout(120)
        @DisplayName("wide-open permissions are recognisable, because that is what an audit looks for")
        void detectsUnrestrictedPermissions() {
            try (RabbitAdmin admin = admin()) {
                admin.createUser("wide-open", "s3cret");
                try {
                    admin.grant("wide-open", ".*", ".*", ".*");

                    PermissionInfo granted = admin.permissions().stream()
                            .filter(p -> "wide-open".equals(p.user()))
                            .findFirst()
                            .orElseThrow();

                    // What every tutorial shows and almost nobody means.
                    assertThat(granted.isUnrestricted()).isTrue();
                } finally {
                    admin.deleteUser("wide-open");
                }
            }
        }
    }

    @Nested
    @DisplayName("policies")
    class Policies {

        @Test
        @Timeout(120)
        @DisplayName("a policy can be applied and removed, and reports what it sets")
        void lifecycle() {
            try (RabbitAdmin admin = admin()) {
                admin.putPolicy("ttl", "^orders\\\\.", Map.of("message-ttl", 60000), 1);
                try {
                    PolicyInfo policy = admin.policies().stream()
                            .filter(p -> "ttl".equals(p.name()))
                            .findFirst()
                            .orElseThrow();

                    assertThat(policy.pattern()).isEqualTo("^orders\\\\.");
                    assertThat(policy.priority()).isEqualTo(1);
                    assertThat(policy.definition()).containsEntry("message-ttl", 60000);
                } finally {
                    admin.deletePolicy("ttl");
                }

                assertThat(admin.policies()).extracting(PolicyInfo::name).doesNotContain("ttl");
            }
        }

        @Test
        @Timeout(120)
        @DisplayName("a policy does not appear in the queue's arguments")
        void policiesAreNotArguments() {
            try (RabbitAdmin admin = admin()) {
                admin.putPolicy("ttl", "^orders\\\\.", Map.of("message-ttl", 60000), 1);
                try {
                    QueueInfo queue = admin.queue("orders.new").orElseThrow();

                    // The distinction that makes a topology comparison correct or wrong. An
                    // argument is fixed at declaration; a policy is applied afterwards and is
                    // editable. A queue governed by a policy shows nothing in its arguments,
                    // so a drift check reading only arguments would call this queue plain.
                    assertThat(queue.arguments()).doesNotContainKey("x-message-ttl");
                } finally {
                    admin.deletePolicy("ttl");
                }
            }
        }
    }

    @Nested
    @DisplayName("shovels")
    class Shovels {

        @Test
        @Timeout(120)
        @DisplayName("an absent plugin is an empty list rather than a failure")
        void noPluginIsNotAnError() {
            try (RabbitAdmin admin = admin()) {
                // The shovel plugin is not enabled in this image. That is a configuration fact
                // about the broker, not something for a caller to handle as an exception.
                assertThat(admin.shovels()).isEmpty();
            }
        }
    }
}
