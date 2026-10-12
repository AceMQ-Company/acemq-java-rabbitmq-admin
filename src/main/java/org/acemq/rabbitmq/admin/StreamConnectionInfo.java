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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One connection over RabbitMQ's stream protocol, on port 5552.
 *
 * <p>{@link RabbitAdmin#connections()} lists these too, among every other protocol's, with the
 * AMQP-shaped fields that mean little for a stream client. This list holds only stream
 * connections, and its {@link #name()} is what {@link StreamPublisherInfo#connectionName()} and
 * {@link StreamConsumerInfo#connectionName()} refer to.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StreamConnectionInfo {

    private final String name;
    private final String vhost;
    private final String user;
    private final String node;
    private final String state;
    private final String peerHost;
    private final Integer peerPort;
    private final String clientProvidedName;
    private final Long connectedAt;

    public StreamConnectionInfo(
            @JsonProperty("name") String name,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("user") String user,
            @JsonProperty("node") String node,
            @JsonProperty("state") String state,
            @JsonProperty("peer_host") String peerHost,
            @JsonProperty("peer_port") Integer peerPort,
            @JsonProperty("user_provided_name") String clientProvidedName,
            @JsonProperty("connected_at") Long connectedAt) {
        this.name = name;
        this.vhost = vhost;
        this.user = user;
        this.node = node;
        this.state = state;
        this.peerHost = peerHost;
        this.peerPort = peerPort;
        this.clientProvidedName = clientProvidedName;
        this.connectedAt = connectedAt;
    }

    /** @return the broker's name for it, joining it to the connection names publishers carry */
    public String name() {
        return name;
    }

    public String vhost() {
        return vhost;
    }

    public String user() {
        return user;
    }

    public String node() {
        return node;
    }

    /** @return {@code running}, usually */
    public String state() {
        return state;
    }

    public String peerHost() {
        return peerHost;
    }

    public int peerPort() {
        return peerPort == null ? 0 : peerPort;
    }

    /** @return the name the client gave itself, or null when it gave none */
    public String clientProvidedName() {
        return clientProvidedName;
    }

    /** @return when it connected, in milliseconds since the epoch; 0 when not reported */
    public long connectedAt() {
        return connectedAt == null ? 0 : connectedAt;
    }

    @Override
    public String toString() {
        return "StreamConnectionInfo{" + name + " " + user + "@" + vhost + "}";
    }
}
