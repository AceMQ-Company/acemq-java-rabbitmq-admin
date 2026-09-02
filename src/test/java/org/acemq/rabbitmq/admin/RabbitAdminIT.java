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
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Against a real broker, because every interesting thing here is the broker's behaviour.
 *
 * <p>The unit tests cover encoding and parsing. What they cannot cover is whether RabbitMQ
 * reports a queue's arguments the way this client reads them, which is the entire reason the
 * library exists.
 */
@Testcontainers
@DisplayName("the management API on a real RabbitMQ")
class RabbitAdminIT {

    @Container
    private static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4-management"));

    private static String url;

    @BeforeAll
    static void declareSomethingToLookAt() throws IOException, InterruptedException {
        url = "http://" + BROKER.getHost() + ":" + BROKER.getMappedPort(15672);

        // Declared through rabbitmqadmin rather than through this client, deliberately: a test
        // that both creates and reads through the same code proves the two halves agree with
        // each other and nothing about whether either matches the broker.
        //
        // rabbitmqadmin v2, which ships in RabbitMQ 4, takes --name rather than name=. The
        // older form is accepted by nothing and fails with "unexpected argument", which a
        // test that ignored the exit code would report as a missing queue.
        declare("--name", "orders.new", "--durable", "true",
                "--arguments", "{\"x-message-ttl\":60000,\"x-dead-letter-exchange\":\"dlx\"}");
        declare("--name", "plain.queue", "--durable", "true");
    }

    /** Declares a queue, and fails the run rather than the assertion if it did not work. */
    private static void declare(String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 3];
        command[0] = "rabbitmqadmin";
        command[1] = "declare";
        command[2] = "queue";
        System.arraycopy(arguments, 0, command, 3, arguments.length);

        var result = BROKER.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("could not declare the fixture queue: "
                    + result.getStderr() + result.getStdout());
        }
    }

    private static RabbitAdmin admin() {
        return RabbitAdmin.connect(url, BROKER.getAdminUsername(), BROKER.getAdminPassword());
    }

    @Test
    @Timeout(120)
    @DisplayName("reports the arguments a queue was actually declared with")
    void readsRealArguments() {
        try (RabbitAdmin admin = admin()) {
            QueueInfo queue = admin.queue("orders.new").orElseThrow();

            // The thing AMQP cannot do. A passive declare says the queue exists; a real declare
            // says whether your arguments match, by refusing. Neither reports what they are.
            assertThat(queue.name()).isEqualTo("orders.new");
            assertThat(queue.durable()).isTrue();
            assertThat(queue.argument("x-message-ttl")).isEqualTo(60000);
            assertThat(queue.argument("x-dead-letter-exchange")).isEqualTo("dlx");
            // And the one the broker added, which nobody asked for.
            assertThat(queue.argument("x-queue-type")).isEqualTo("classic");
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("a queue with no arguments reports none, rather than failing")
    void readsAQueueWithoutArguments() {
        try (RabbitAdmin admin = admin()) {
            QueueInfo queue = admin.queue("plain.queue").orElseThrow();

            // Not empty: RabbitMQ 4 records x-queue-type itself, even on a queue declared
            // without arguments. Worth knowing before comparing a queue's arguments against a
            // topology -- an equality check would report drift on every classic queue.
            assertThat(queue.arguments()).containsOnlyKeys("x-queue-type");
            assertThat(queue.type()).isEqualTo("classic");
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("a queue that does not exist is absent, not an error")
    void missingQueueIsEmpty() {
        try (RabbitAdmin admin = admin()) {
            Optional<QueueInfo> queue = admin.queue("nothing-declared-this");

            // "Is it there?" is the question most callers are asking, and an exception is a
            // poor way to answer no.
            assertThat(queue).isEmpty();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("the default virtual host is reachable, which means the slash was encoded")
    void defaultVhostIsEncodedCorrectly() {
        try (RabbitAdmin admin = admin()) {
            // If "/" reached the URL unencoded this would 404 and look exactly like a missing
            // queue. It is the failure this client is most likely to have.
            assertThat(admin.vhost()).isEqualTo("/");
            assertThat(admin.queues()).extracting(QueueInfo::name)
                    .contains("orders.new", "plain.queue");
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("wrong credentials say so, rather than reporting an empty broker")
    void badCredentialsAreNotAnEmptyBroker() {
        try (RabbitAdmin admin = RabbitAdmin.connect(url, "guest", "definitely-not-the-password")) {
            assertThatThrownBy(admin::queues)
                    .isInstanceOf(AdminException.class)
                    .hasMessageContaining("rejected these credentials")
                    // The distinction that saves an hour: these are the broker's users, not the
                    // AMQP connection's, and they need a tag.
                    .hasMessageContaining("monitoring or administrator tag");
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("reports the broker's version, which is the start-up check")
    void reportsTheVersion() {
        try (RabbitAdmin admin = admin()) {
            assertThat(admin.version()).startsWith("4.");
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("a virtual host that does not exist has no queues rather than throwing")
    void unknownVhostIsEmpty() {
        try (RabbitAdmin admin = admin().forVhost("no-such-vhost")) {
            assertThat(admin.queues()).isEmpty();
        }
    }
}
