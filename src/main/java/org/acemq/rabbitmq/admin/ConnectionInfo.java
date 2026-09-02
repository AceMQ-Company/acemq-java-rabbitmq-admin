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

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One client connection, as the broker sees it.
 *
 * <p>The answer to "which client is doing this". A connection knows its user, its virtual host,
 * where it came from, and — if the client bothered to set it — what the application calls
 * itself.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ConnectionInfo {

    private final String name;
    private final String user;
    private final String vhost;
    private final String state;
    private final String node;
    private final String protocol;
    private final String peerHost;
    private final Integer peerPort;
    private final Integer channels;
    private final Long connectedAt;
    private final Boolean ssl;
    private final String userProvidedName;
    private final Map<String, Object> clientProperties;

    public ConnectionInfo(
            @JsonProperty("name") String name,
            @JsonProperty("user") String user,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("state") String state,
            @JsonProperty("node") String node,
            @JsonProperty("protocol") String protocol,
            @JsonProperty("peer_host") String peerHost,
            @JsonProperty("peer_port") Integer peerPort,
            @JsonProperty("channels") Integer channels,
            @JsonProperty("connected_at") Long connectedAt,
            @JsonProperty("ssl") Boolean ssl,
            @JsonProperty("user_provided_name") String userProvidedName,
            @JsonProperty("client_properties") Map<String, Object> clientProperties) {
        this.name = name;
        this.user = user;
        this.vhost = vhost;
        this.state = state;
        this.node = node;
        this.protocol = protocol;
        this.peerHost = peerHost;
        this.peerPort = peerPort;
        this.channels = channels;
        this.connectedAt = connectedAt;
        this.ssl = ssl;
        this.userProvidedName = userProvidedName;
        this.clientProperties = clientProperties == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(clientProperties));
    }

    /**
     * @return the broker's name for this connection, of the form
     *     {@code 10.0.0.1:52344 -> 10.0.0.2:5672}. This is the identifier
     *     {@link RabbitAdmin#closeConnection(String, String)} takes, and it contains spaces and
     *     a {@code >} — encode it before putting it in a URL, which that method does for you
     */
    public String name() {
        return name;
    }

    /** @return the broker user this connection authenticated as */
    public String user() {
        return user;
    }

    public String vhost() {
        return vhost;
    }

    /**
     * @return {@code running}, {@code blocked}, {@code blocking} or {@code closed}.
     *     <strong>{@code blocked} means a resource alarm is holding this publisher</strong>, and
     *     is the per-connection view of what {@link Health#alarms()} reports for the broker
     */
    public String state() {
        return state;
    }

    /** @return whether this connection is currently blocked by a resource alarm */
    public boolean isBlocked() {
        return "blocked".equals(state) || "blocking".equals(state);
    }

    public String node() {
        return node;
    }

    /** @return the protocol and version, for example {@code AMQP 0-9-1} */
    public String protocol() {
        return protocol;
    }

    public String peerHost() {
        return peerHost;
    }

    public int peerPort() {
        return peerPort == null ? 0 : peerPort;
    }

    /** @return how many channels are open on it */
    public int channels() {
        return channels == null ? 0 : channels;
    }

    /** @return when it connected, if the broker reported it */
    public Optional<Instant> connectedAt() {
        return Optional.ofNullable(connectedAt).map(Instant::ofEpochMilli);
    }

    public boolean ssl() {
        return Boolean.TRUE.equals(ssl);
    }

    /**
     * @return the name the client gave itself, when it gave one. Worth setting in every
     *     application: without it a connection is an IP and a port, and identifying which of six
     *     services is the one misbehaving means matching ports against container addresses
     */
    public Optional<String> userProvidedName() {
        return Optional.ofNullable(userProvidedName);
    }

    /** @return what the client library reported about itself: product, version, platform */
    public Map<String, Object> clientProperties() {
        return clientProperties;
    }

    @Override
    public String toString() {
        return "ConnectionInfo{" + name + " user=" + user + " vhost=" + vhost
                + " state=" + state + " channels=" + channels() + "}";
    }
}
