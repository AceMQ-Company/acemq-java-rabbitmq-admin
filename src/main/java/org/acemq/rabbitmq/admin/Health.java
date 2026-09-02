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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The broker's own health checks.
 *
 * <pre>{@code
 * Health health = admin.health();
 *
 * if (!health.alarms().isOk()) {
 *     log.error("broker is refusing publishes: {}", health.alarms().reason().orElse(""));
 * }
 * }</pre>
 *
 * <h2>Why these rather than metrics</h2>
 *
 * <p>An alert built on the metrics endpoint infers health from numbers. These endpoints are the
 * broker answering the question itself, and two of them have no metric equivalent at all:
 * {@link #quorumCritical()} knows which quorum queues would lose their majority if one more node
 * went away, and {@link #certificateExpiration(int, String)} knows when your TLS certificates
 * expire. No amount of scraping produces either.
 *
 * <h2>Not for a load balancer's readiness probe</h2>
 *
 * <p>{@link #alarms()} and {@link #quorumCritical()} are cluster-wide, so every node answers
 * identically and a load balancer using them takes the whole cluster out at once. For a probe,
 * {@link #isInService()} or {@link #localAlarms()} — both node-local — are the ones that make a
 * single unhealthy node stop receiving traffic while the rest keep serving.
 */
public final class Health {

    private final Function<String, HealthResult> check;

    Health(Function<String, HealthResult> check) {
        this.check = check;
    }

    /**
     * @return whether any resource alarm is in effect <strong>anywhere in the cluster</strong>.
     *     While one is, publishers are blocked
     */
    public HealthResult alarms() {
        return check.apply("alarms");
    }

    /**
     * @return whether a resource alarm is in effect <strong>on this node</strong>. The one to use
     *     for a per-node probe, because {@link #alarms()} fails on every node at once
     */
    public HealthResult localAlarms() {
        return check.apply("local-alarms");
    }

    /**
     * Whether any quorum queue would lose its majority if one more node went down.
     *
     * <p>No metric reports this. It is also the check that decides whether a rolling restart may
     * continue: proceeding while it fails is how a cluster loses quorum on queues that were
     * one node away from it.
     *
     * @return the result
     */
    public HealthResult quorumCritical() {
        return check.apply("node-is-quorum-critical");
    }

    /** @return whether this node is fully started and not being drained for maintenance */
    public HealthResult isInService() {
        return check.apply("is-in-service");
    }

    /** @return whether every protocol listener is up and the node will accept clients */
    public HealthResult readyToServeClients() {
        return check.apply("ready-to-serve-clients");
    }

    /** @return whether every virtual host on this node is running */
    public HealthResult virtualHosts() {
        return check.apply("virtual-hosts");
    }

    /** @return whether the node is below its configured connection limit */
    public HealthResult belowNodeConnectionLimit() {
        return check.apply("below-node-connection-limit");
    }

    /**
     * @param port a TCP port, for example 5672
     * @return whether something is listening on it
     */
    public HealthResult portListener(int port) {
        return check.apply("port-listener/" + port);
    }

    /**
     * @param protocol {@code amqp}, {@code amqps}, {@code mqtt}, {@code stomp}, {@code http}
     * @return whether a listener for it is running
     */
    public HealthResult protocolListener(String protocol) {
        return check.apply("protocol-listener/"
                + RabbitAdmin.encode(Objects.requireNonNull(protocol, "protocol")));
    }

    /**
     * Whether any TLS certificate expires within the given window.
     *
     * <p>The check nobody has until the morning a certificate expires. It has no metric, and it
     * is the only one here that is about the future rather than the present.
     *
     * @param within how many units ahead to look
     * @param unit {@code days}, {@code weeks}, {@code months} or {@code years}
     * @return the result
     */
    public HealthResult certificateExpiration(int within, String unit) {
        return check.apply("certificate-expiration/" + within + "/"
                + RabbitAdmin.encode(Objects.requireNonNull(unit, "unit")));
    }

    /**
     * Runs the checks that need no arguments and apply to any broker.
     *
     * <p>Deliberately excludes {@link #portListener(int)}, {@link #protocolListener(String)} and
     * {@link #certificateExpiration(int, String)}, which need to be told what to look for, and
     * {@link #alarms()}, which duplicates {@link #localAlarms()} across the cluster.
     *
     * @return every result, passing and failing
     */
    public List<HealthResult> checkAll() {
        List<HealthResult> results = new ArrayList<>();
        results.add(localAlarms());
        results.add(quorumCritical());
        results.add(isInService());
        results.add(readyToServeClients());
        results.add(virtualHosts());
        return results;
    }

    /**
     * @return whether every check in {@link #checkAll()} passed
     */
    public boolean isHealthy() {
        return checkAll().stream().allMatch(HealthResult::isOk);
    }

    /**
     * @return the checks in {@link #checkAll()} that failed, which is the list worth printing
     */
    public List<HealthResult> failures() {
        List<HealthResult> failed = new ArrayList<>();
        for (HealthResult result : checkAll()) {
            if (!result.isOk()) {
                failed.add(result);
            }
        }
        return failed;
    }
}
