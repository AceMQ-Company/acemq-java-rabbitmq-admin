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

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import org.junit.jupiter.api.AfterAll;
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
 * The management API surface added to close the gaps against {@code /api/index.html}.
 *
 * <p>Every one of these is here because the endpoint's behaviour is not guessable. A health
 * check answers 503 when it fails, which is the check working. A binding is deleted by a
 * properties key that arrives already encoded. A definitions import is a merge rather than a
 * replacement. Reading the documentation gets each of those wrong in a way that compiles.
 */
@Testcontainers
@DisplayName("the rest of the management API, on a real RabbitMQ")
class ManagementGapsIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"))
            // Without this, putFederationUpstream answers 400 with "component
            // federation-upstream not found" -- the parameter component is registered by the
            // plugin, not by the broker.
            .withPluginsEnabled("rabbitmq_federation", "rabbitmq_federation_management");

    private static String url;
    private static Connection amqp;

    @BeforeAll
    @Timeout(120)
    static void connect() throws Exception {
        url = "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(15672);

        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri("amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getAmqpPort());
        amqp = factory.newConnection("gap-probe");
    }

    @AfterAll
    static void disconnect() throws Exception {
        if (amqp != null && amqp.isOpen()) {
            amqp.close();
        }
    }

    private static RabbitAdmin admin() {
        return RabbitAdmin.connect(url, "guest", "guest", Duration.ofSeconds(20));
    }

    @Nested
    @DisplayName("health checks")
    class HealthChecks {

        @Test
        @DisplayName("a healthy broker passes every check")
        void healthy() {
            try (RabbitAdmin admin = admin()) {
                Health health = admin.health();

                assertThat(health.isHealthy()).isTrue();
                assertThat(health.failures()).isEmpty();
                assertThat(health.localAlarms().isOk()).isTrue();
                assertThat(health.isInService().isOk()).isTrue();
                assertThat(health.readyToServeClients().isOk()).isTrue();
                assertThat(health.virtualHosts().isOk()).isTrue();
            }
        }

        @Test
        @DisplayName("quorum-critical passes on a single node, and says why")
        void quorumCritical() {
            try (RabbitAdmin admin = admin()) {
                HealthResult result = admin.health().quorumCritical();

                assertThat(result.isOk()).isTrue();
                // The broker explains a pass here rather than staying silent, and the reason is
                // worth surfacing: "single node cluster" is a different kind of healthy.
                assertThat(result.details()).containsKey("reason");
            }
        }

        @Test
        @DisplayName("a protocol listener check knows amqp is up and that nonsense is not")
        void protocolListener() {
            try (RabbitAdmin admin = admin()) {
                assertThat(admin.health().protocolListener("amqp").isOk()).isTrue();
                assertThat(admin.health().protocolListener("mqtt").isOk()).isFalse();
                assertThat(admin.health().portListener(5672).isOk()).isTrue();
            }
        }

        @Test
        @DisplayName("a failing check is a result, not an exception")
        void failureIsAnAnswer() throws IOException, InterruptedException {
            try (RabbitAdmin admin = admin()) {
                setWatermark("0.0001");
                try {
                    HealthResult result = awaitAlarm(admin);

                    // The endpoint answers 503 here. Routed through the ordinary reader that
                    // would be an exception, and "is the broker healthy?" could never be
                    // answered with "no" -- which is the only answer that matters.
                    assertThat(result.isOk()).isFalse();
                    assertThat(result.reason()).isPresent();
                    assertThat(result.details()).containsKey("alarms");
                    assertThat(admin.health().isHealthy()).isFalse();
                    assertThat(admin.health().failures()).isNotEmpty();

                    assertThatThrownBy(result::orThrow)
                            .isInstanceOf(AdminException.class)
                            .hasMessageContaining("alarms");
                } finally {
                    setWatermark("0.4");
                }
            }
        }

        @Test
        @DisplayName("certificate expiry is a check even with no certificates")
        void certificateExpiration() {
            try (RabbitAdmin admin = admin()) {
                // Passes on a broker with no TLS listener, because nothing is expiring. The
                // value is on a broker that does have certificates, where this is the only
                // warning available -- no metric reports it.
                assertThat(admin.health().certificateExpiration(1, "months").isOk()).isTrue();
            }
        }

        private HealthResult awaitAlarm(RabbitAdmin admin) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            HealthResult result = admin.health().localAlarms();
            while (result.isOk() && System.nanoTime() < deadline) {
                Thread.sleep(500);
                result = admin.health().localAlarms();
            }
            return result;
        }

        private void setWatermark(String fraction) throws IOException, InterruptedException {
            var run = BROKER.execInContainer("rabbitmqctl", "set_vm_memory_high_watermark", fraction);
            if (run.getExitCode() != 0) {
                throw new IllegalStateException("could not set the watermark: " + run.getStderr());
            }
        }
    }

    @Nested
    @DisplayName("definitions")
    class DefinitionsExport {

        @Test
        @DisplayName("one document carries the things a topology walk misses")
        void exportsEverything() {
            try (RabbitAdmin admin = admin()) {
                admin.declareQueue("defs.q", true, Map.of("x-message-ttl", 60000));
                admin.putPolicy("defs-policy", "^defs\\.", Map.of("message-ttl", 30000), 1);
                admin.putFederationUpstream("defs-upstream", "amqp://elsewhere.invalid:5672", Map.of());

                Definitions definitions = admin.exportDefinitions();

                assertThat(definitions.rabbitVersion()).isNotEmpty();
                assertThat(definitions.queues()).anyMatch(q -> "defs.q".equals(q.get("name")));
                assertThat(definitions.policies()).anyMatch(p -> "defs-policy".equals(p.get("name")));
                // The whole point: a federation upstream is a runtime parameter, so an export
                // that walked queues, exchanges and bindings would not contain it and the
                // restored broker would federate nothing while looking complete.
                assertThat(definitions.parameters())
                        .anyMatch(p -> "defs-upstream".equals(p.get("name")));
                assertThat(definitions.users()).isNotEmpty();
                assertThat(definitions.summary()).contains("queues=");

                admin.deleteFederationUpstream("defs-upstream");
                admin.deletePolicy("defs-policy");
                admin.deleteQueue("defs.q");
            }
        }

        @Test
        @DisplayName("passwords are exported as hashes, never in the clear")
        void passwordsAreHashed() {
            try (RabbitAdmin admin = admin()) {
                admin.createUser("defs-user", "a-very-secret-password", "monitoring");
                try {
                    Definitions definitions = admin.exportDefinitions();

                    assertThat(definitions.json()).doesNotContain("a-very-secret-password");
                    assertThat(definitions.users())
                            .filteredOn(u -> "defs-user".equals(u.get("name")))
                            .singleElement()
                            .satisfies(user -> {
                                assertThat(user).containsKey("password_hash");
                                assertThat(user).doesNotContainKey("password");
                            });
                } finally {
                    admin.deleteUser("defs-user");
                }
            }
        }

        @Test
        @DisplayName("importing restores what was deleted and keeps what was added")
        void importIsAMerge() {
            try (RabbitAdmin admin = admin()) {
                admin.declareQueue("defs.restore-me", true, Map.of());
                Definitions backup = admin.exportDefinitions();

                admin.deleteQueue("defs.restore-me");
                admin.declareQueue("defs.added-later", true, Map.of());
                assertThat(admin.queue("defs.restore-me")).isEmpty();

                admin.importDefinitions(backup.json());

                // Restored...
                assertThat(admin.queue("defs.restore-me")).isPresent();
                // ...and the newer queue survives, because an import merges rather than
                // replacing. Calling this "restore" would set the wrong expectation.
                assertThat(admin.queue("defs.added-later")).isPresent();

                admin.deleteQueue("defs.restore-me");
                admin.deleteQueue("defs.added-later");
            }
        }
    }

    @Nested
    @DisplayName("connections, channels and consumers")
    class Connections {

        @Test
        @DisplayName("a connection reports its user, name and channel count")
        void listsConnections() throws Exception {
            try (RabbitAdmin admin = admin(); Channel channel = amqp.createChannel()) {
                channel.queueDeclare("conn.q", true, false, false, null);

                ConnectionInfo probe = awaitConnection(admin, "gap-probe");

                assertThat(probe.user()).isEqualTo("guest");
                assertThat(probe.vhost()).isEqualTo("/");
                assertThat(probe.state()).isEqualTo("running");
                assertThat(probe.isBlocked()).isFalse();
                assertThat(probe.channels()).isPositive();
                assertThat(probe.connectedAt()).isPresent();
                assertThat(probe.clientProperties()).containsKey("product");
                // The name is an address pair containing spaces and a ">", which is why every
                // call taking it has to encode it.
                assertThat(probe.name()).contains("->");
            }
        }

        @Test
        @DisplayName("a channel reports prefetch and unacknowledged counts")
        void listsChannels() throws Exception {
            try (RabbitAdmin admin = admin(); Channel channel = amqp.createChannel()) {
                channel.basicQos(7);
                channel.queueDeclare("chan.q", true, false, false, null);

                List<ChannelInfo> channels = awaitChannels(admin);

                assertThat(channels).isNotEmpty();
                assertThat(channels).anyMatch(c -> c.prefetchCount() == 7);
                assertThat(channels).allSatisfy(c -> assertThat(c.connectionName()).isNotNull());
            }
        }

        @Test
        @DisplayName("a consumer names the queue it is attached to")
        void listsConsumers() throws Exception {
            try (RabbitAdmin admin = admin(); Channel channel = amqp.createChannel()) {
                channel.queueDeclare("cons.q", true, false, false, null);
                channel.basicConsume("cons.q", false, "gap-consumer", new DefaultConsumer(channel) { });

                List<ConsumerInfo> consumers = awaitConsumers(admin);

                ConsumerInfo consumer = consumers.stream()
                        .filter(c -> "gap-consumer".equals(c.consumerTag()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("the consumer is not listed"));

                assertThat(consumer.queue()).isEqualTo("cons.q");
                assertThat(consumer.ackRequired()).isTrue();
                assertThat(consumer.active()).isTrue();
                assertThat(consumer.channelName()).isNotNull();
            }
        }

        @Test
        @DisplayName("closing a connection actually disconnects the client")
        void closesAConnection() throws Exception {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setUri("amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getAmqpPort());
            factory.setAutomaticRecoveryEnabled(false);

            // Not try-with-resources: close() on a connection the broker has already closed
            // throws AlreadyClosed, which would fail the test after it had passed.
            Connection doomed = factory.newConnection("to-be-closed");
            try (RabbitAdmin admin = admin()) {

                String name = awaitConnection(admin, "to-be-closed").name();

                admin.closeConnection(name, "closed by ManagementGapsIT");

                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (doomed.isOpen() && System.nanoTime() < deadline) {
                    Thread.sleep(200);
                }
                assertThat(doomed.isOpen()).isFalse();
            }
        }

        /**
         * Waits for one named connection, not merely for the list to be non-empty.
         *
         * <p>Waiting for non-empty returns immediately once any earlier connection is
         * registered, which is before the one this test just opened has appeared -- the test
         * then fails looking for a connection that was simply not there yet.
         */
        private ConnectionInfo awaitConnection(RabbitAdmin admin, String providedName)
                throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                Optional<ConnectionInfo> found = admin.connections().stream()
                        .filter(c -> c.userProvidedName().filter(providedName::equals).isPresent())
                        .findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
                Thread.sleep(300);
            }
            throw new AssertionError("the connection '" + providedName + "' was never listed");
        }

        private List<ChannelInfo> awaitChannels(RabbitAdmin admin) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            List<ChannelInfo> channels = admin.channels();
            while (channels.stream().noneMatch(c -> c.prefetchCount() == 7)
                    && System.nanoTime() < deadline) {
                Thread.sleep(500);
                channels = admin.channels();
            }
            return channels;
        }

        private List<ConsumerInfo> awaitConsumers(RabbitAdmin admin) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            List<ConsumerInfo> consumers = admin.consumers();
            while (consumers.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(500);
                consumers = admin.consumers();
            }
            return consumers;
        }
    }

    @Nested
    @DisplayName("topology writes")
    class TopologyWrites {

        @Test
        @DisplayName("declare, bind, read back, unbind, delete")
        void fullCycle() {
            try (RabbitAdmin admin = admin()) {
                admin.declareExchange("w.x", "topic", true, Map.of());
                admin.declareQueue("w.q", true, Map.of("x-message-ttl", 30000));
                admin.bindQueue("w.x", "w.q", "orders.#", Map.of());

                QueueInfo queue = admin.queue("w.q").orElseThrow();
                assertThat(queue.durable()).isTrue();
                assertThat(queue.argument("x-message-ttl")).isEqualTo(30000);
                assertThat(admin.exchange("w.x").orElseThrow().type()).isEqualTo("topic");

                BindingInfo binding = admin.bindingsForQueue("w.q").stream()
                        .filter(b -> !b.isDefaultExchangeBinding())
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("the binding is not there"));
                assertThat(binding.routingKey()).isEqualTo("orders.#");
                assertThat(binding.propertiesKey()).isNotNull();

                admin.unbind(binding);

                assertThat(admin.bindingsForQueue("w.q"))
                        .allMatch(BindingInfo::isDefaultExchangeBinding);

                admin.deleteQueue("w.q");
                admin.deleteExchange("w.x");
                assertThat(admin.queue("w.q")).isEmpty();
                assertThat(admin.exchange("w.x")).isEmpty();
            }
        }

        @Test
        @DisplayName("a routing key needing encoding survives the round trip to unbind")
        void awkwardRoutingKey() {
            try (RabbitAdmin admin = admin()) {
                admin.declareExchange("w.x2", "topic", true, Map.of());
                admin.declareQueue("w.q2", true, Map.of());
                // "#" becomes %23 in the properties key. Encoding that again gives %2523,
                // which matches no binding and 404s -- the reason unbind takes the object.
                admin.bindQueue("w.x2", "w.q2", "a.#", Map.of());

                BindingInfo binding = admin.bindingsForQueue("w.q2").stream()
                        .filter(b -> !b.isDefaultExchangeBinding())
                        .findFirst()
                        .orElseThrow();
                assertThat(binding.propertiesKey()).isEqualTo("a.%23");

                admin.unbind(binding);
                assertThat(admin.bindingsForQueue("w.q2"))
                        .allMatch(BindingInfo::isDefaultExchangeBinding);

                admin.deleteQueue("w.q2");
                admin.deleteExchange("w.x2");
            }
        }

        @Test
        @DisplayName("purge empties a queue and keeps it")
        void purge() throws Exception {
            try (RabbitAdmin admin = admin(); Channel channel = amqp.createChannel()) {
                admin.declareQueue("w.purge", true, Map.of());
                for (int i = 0; i < 5; i++) {
                    channel.basicPublish("", "w.purge", null, "x".getBytes());
                }
                awaitDepth(admin, "w.purge", 5);

                admin.purgeQueue("w.purge");

                awaitDepth(admin, "w.purge", 0);
                assertThat(admin.queue("w.purge")).isPresent();

                admin.deleteQueue("w.purge");
            }
        }

        @Test
        @DisplayName("the default-exchange binding is refused rather than attempted")
        void defaultBindingCannotBeRemoved() {
            try (RabbitAdmin admin = admin()) {
                admin.declareQueue("w.default", true, Map.of());

                BindingInfo implicit = admin.bindingsForQueue("w.default").stream()
                        .filter(BindingInfo::isDefaultExchangeBinding)
                        .findFirst()
                        .orElseThrow();

                assertThatThrownBy(() -> admin.unbind(implicit))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("cannot be removed");

                admin.deleteQueue("w.default");
            }
        }

        private void awaitDepth(RabbitAdmin admin, String queue, long expected)
                throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            long seen = -1;
            while (System.nanoTime() < deadline) {
                Optional<QueueInfo> info = admin.queue(queue);
                if (info.isPresent()) {
                    seen = info.get().messagesReady();
                    if (seen == expected) {
                        return;
                    }
                }
                Thread.sleep(300);
            }
            throw new AssertionError(queue + " never reached " + expected + "; last saw " + seen);
        }
    }

    @Nested
    @DisplayName("operator policies, limits and topic permissions")
    class Governance {

        @Test
        @DisplayName("an operator policy is separate from a user policy")
        void operatorPolicies() {
            try (RabbitAdmin admin = admin()) {
                admin.putPolicy("user-side", "^gov\\.", Map.of("message-ttl", 60000), 1);
                admin.putOperatorPolicy("operator-side", "^gov\\.", Map.of("max-length", 1000), 1);

                assertThat(admin.operatorPolicies()).anyMatch(p -> "operator-side".equals(p.name()));
                // Two separate lists. An operator policy does not appear among the user
                // policies, so an audit that reads only policies() misses the guard rails.
                assertThat(admin.policies()).noneMatch(p -> "operator-side".equals(p.name()));
                assertThat(admin.policies()).anyMatch(p -> "user-side".equals(p.name()));

                admin.deleteOperatorPolicy("operator-side");
                admin.deletePolicy("user-side");
                assertThat(admin.operatorPolicies()).noneMatch(p -> "operator-side".equals(p.name()));
            }
        }

        @Test
        @DisplayName("a vhost limit is set, read and cleared")
        void vhostLimits() {
            try (RabbitAdmin admin = admin()) {
                admin.setVhostLimit(LimitInfo.MAX_CONNECTIONS, 100);

                LimitInfo limit = admin.vhostLimits().stream()
                        .filter(l -> "/".equals(l.vhost()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no limit on the default vhost"));
                assertThat(limit.limit(LimitInfo.MAX_CONNECTIONS)).contains(100L);

                admin.clearVhostLimit(LimitInfo.MAX_CONNECTIONS);
                assertThat(admin.vhostLimits())
                        .filteredOn(l -> "/".equals(l.vhost()))
                        .allSatisfy(l -> assertThat(l.limit(LimitInfo.MAX_CONNECTIONS)).isEmpty());
            }
        }

        @Test
        @DisplayName("a user limit is scoped to the user, not the vhost")
        void userLimits() {
            try (RabbitAdmin admin = admin()) {
                admin.createUser("gov-user", "password", "monitoring");
                try {
                    admin.setUserLimit("gov-user", "max-connections", 5);

                    assertThat(admin.userLimits())
                            .filteredOn(l -> "gov-user".equals(l.user()))
                            .singleElement()
                            .satisfies(l -> assertThat(l.limit("max-connections")).contains(5L));

                    admin.clearUserLimit("gov-user", "max-connections");
                    assertThat(admin.userLimits()).noneMatch(l -> "gov-user".equals(l.user()));
                } finally {
                    admin.deleteUser("gov-user");
                }
            }
        }

        @Test
        @DisplayName("topic permissions are a second, separate surface")
        void topicPermissions() {
            try (RabbitAdmin admin = admin()) {
                admin.createUser("topic-user", "password", "monitoring");
                try {
                    // Nothing enforced until something is granted: a user with no topic
                    // permissions is unrestricted on topic exchanges.
                    assertThat(admin.topicPermissions()).noneMatch(p -> "topic-user".equals(p.user()));

                    admin.grantTopic("topic-user", "amq.topic", "^orders\\..*", "^orders\\..*");

                    TopicPermissionInfo granted = admin.topicPermissions().stream()
                            .filter(p -> "topic-user".equals(p.user()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(granted.exchange()).isEqualTo("amq.topic");
                    assertThat(granted.write()).isEqualTo("^orders\\..*");

                    // And it really is separate: the ordinary permission list does not mention it.
                    assertThat(admin.permissions()).noneMatch(p -> "topic-user".equals(p.user()));

                    admin.revokeTopic("topic-user");
                    assertThat(admin.topicPermissions()).noneMatch(p -> "topic-user".equals(p.user()));
                } finally {
                    admin.deleteUser("topic-user");
                }
            }
        }
    }

    @Nested
    @DisplayName("cluster and upgrade readiness")
    class ClusterAndUpgrade {

        @Test
        @DisplayName("whoami reports the tags a later 403 would be about")
        void whoami() {
            try (RabbitAdmin admin = admin()) {
                CurrentUser me = admin.whoami();

                assertThat(me.name()).isEqualTo("guest");
                assertThat(me.tags()).contains("administrator");
                assertThat(me.canAdminister()).isTrue();
                assertThat(me.canMonitor()).isTrue();
                assertThat(me.isInternal()).isTrue();
            }
        }

        @Test
        @DisplayName("a monitoring user can read and cannot write, and says so before trying")
        void monitoringUserCannotWrite() {
            try (RabbitAdmin admin = admin()) {
                admin.createUser("readonly", "password", "monitoring");
                admin.grant("readonly", ".*", ".*", ".*");
                try (RabbitAdmin limited = RabbitAdmin.connect(url, "readonly", "password")) {
                    CurrentUser me = limited.whoami();

                    assertThat(me.canMonitor()).isTrue();
                    assertThat(me.canAdminister()).isFalse();

                    // Which is exactly what the write below fails on -- and knowing it at
                    // startup beats discovering it partway through a provisioning run.
                    assertThat(limited.queues()).isNotNull();
                    assertThatThrownBy(() -> limited.createVhost("should-not-happen"))
                            .isInstanceOf(AdminException.class);
                } finally {
                    admin.deleteUser("readonly");
                }
            }
        }

        @Test
        @DisplayName("nodes and cluster name")
        void cluster() {
            try (RabbitAdmin admin = admin()) {
                List<NodeInfo> nodes = admin.nodes();

                assertThat(nodes).hasSize(1);
                assertThat(nodes.get(0).running()).isTrue();
                assertThat(nodes.get(0).beingDrained()).isFalse();
                assertThat(nodes.get(0).name()).startsWith("rabbit@");
                assertThat(admin.clusterName()).startsWith("rabbit@");
            }
        }

        @Test
        @DisplayName("feature flags say what would block an upgrade")
        void featureFlags() {
            try (RabbitAdmin admin = admin()) {
                List<FeatureFlagInfo> flags = admin.featureFlags();

                assertThat(flags).isNotEmpty();
                // On a broker this version has just created, every required flag is already on.
                // A required flag left disabled is what stops the next major upgrade, and this
                // is the only place it is visible beforehand.
                assertThat(flags)
                        .filteredOn(FeatureFlagInfo::isRequired)
                        .allMatch(FeatureFlagInfo::isEnabled);
            }
        }

        @Test
        @DisplayName("a fresh broker uses no deprecated features")
        void deprecatedFeatures() {
            try (RabbitAdmin admin = admin()) {
                assertThat(admin.deprecatedFeaturesInUse()).isEmpty();
            }
        }

        @Test
        @DisplayName("global parameters hold values that are not objects")
        void globalParameters() {
            try (RabbitAdmin admin = admin()) {
                List<GlobalParameterInfo> parameters = admin.globalParameters();

                // Every broker has these two from the moment it starts, and they are the reason
                // this is not ParameterInfo: one value is a string and the other is an array,
                // so a Map<String, Object> model fails to parse against any real broker.
                GlobalParameterInfo clusterId = parameters.stream()
                        .filter(p -> "internal_cluster_id".equals(p.name()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no internal_cluster_id"));
                assertThat(clusterId.asString()).isPresent();
                assertThat(clusterId.asMap()).isEmpty();

                assertThat(parameters)
                        .filteredOn(p -> "cluster_tags".equals(p.name()))
                        .singleElement()
                        .satisfies(tags -> assertThat(tags.asList()).isEmpty());
            }
        }
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> none() {
        return Collections.emptyMap();
    }
}
