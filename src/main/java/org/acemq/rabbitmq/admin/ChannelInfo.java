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

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One channel on one connection.
 *
 * <p>Where prefetch and unacknowledged counts live, which makes this the view that explains a
 * consumer that has stopped moving: {@link #messagesUnacknowledged()} equal to
 * {@link #prefetchCount()} is a consumer holding its whole allowance and acknowledging nothing.
 * The broker will send it no more until it does, and from the queue's side that is
 * indistinguishable from a consumer that is merely slow.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ChannelInfo {

    private final String name;
    private final String user;
    private final String vhost;
    private final String node;
    private final Integer number;
    private final String state;
    private final Integer prefetchCount;
    private final Integer consumerCount;
    private final Integer messagesUnacknowledged;
    private final Integer messagesUnconfirmed;
    private final Boolean confirm;
    private final Boolean transactional;
    private final String connectionName;

    @SuppressWarnings("unchecked")
    public ChannelInfo(
            @JsonProperty("name") String name,
            @JsonProperty("user") String user,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("node") String node,
            @JsonProperty("number") Integer number,
            @JsonProperty("state") String state,
            @JsonProperty("prefetch_count") Integer prefetchCount,
            @JsonProperty("consumer_count") Integer consumerCount,
            @JsonProperty("messages_unacknowledged") Integer messagesUnacknowledged,
            @JsonProperty("messages_unconfirmed") Integer messagesUnconfirmed,
            @JsonProperty("confirm") Boolean confirm,
            @JsonProperty("transactional") Boolean transactional,
            @JsonProperty("connection_details") Map<String, Object> connectionDetails) {
        this.name = name;
        this.user = user;
        this.vhost = vhost;
        this.node = node;
        this.number = number;
        this.state = state;
        this.prefetchCount = prefetchCount;
        this.consumerCount = consumerCount;
        this.messagesUnacknowledged = messagesUnacknowledged;
        this.messagesUnconfirmed = messagesUnconfirmed;
        this.confirm = confirm;
        this.transactional = transactional;
        // Nested rather than flat in the API, and the only field of it worth surfacing is the
        // connection's name -- which is what joins a channel back to ConnectionInfo.
        Object owner = connectionDetails == null ? null : connectionDetails.get("name");
        this.connectionName = owner == null ? null : owner.toString();
    }

    public String name() {
        return name;
    }

    public String user() {
        return user;
    }

    public String vhost() {
        return vhost;
    }

    public String node() {
        return node;
    }

    /** @return the channel number within its connection */
    public int number() {
        return number == null ? 0 : number;
    }

    /** @return {@code running} or {@code flow}. {@code flow} is the broker throttling this channel */
    public String state() {
        return state;
    }

    /** @return how many unacknowledged deliveries this channel will accept. Zero means unlimited */
    public int prefetchCount() {
        return prefetchCount == null ? 0 : prefetchCount;
    }

    public int consumerCount() {
        return consumerCount == null ? 0 : consumerCount;
    }

    /** @return deliveries sent and not yet acknowledged */
    public int messagesUnacknowledged() {
        return messagesUnacknowledged == null ? 0 : messagesUnacknowledged;
    }

    /** @return publishes sent and not yet confirmed by the broker */
    public int messagesUnconfirmed() {
        return messagesUnconfirmed == null ? 0 : messagesUnconfirmed;
    }

    /** @return whether publisher confirms are on */
    public boolean confirm() {
        return Boolean.TRUE.equals(confirm);
    }

    public boolean transactional() {
        return Boolean.TRUE.equals(transactional);
    }

    /** @return the name of the connection this channel belongs to */
    public String connectionName() {
        return connectionName;
    }

    /**
     * @return whether this channel is holding its entire prefetch allowance unacknowledged. The
     *     broker will send it nothing further until something is acked, so a consumer in this
     *     state has stopped and looks, from the queue, exactly like one that is slow
     */
    public boolean isAtPrefetchLimit() {
        return prefetchCount() > 0 && messagesUnacknowledged() >= prefetchCount();
    }

    @Override
    public String toString() {
        return "ChannelInfo{" + name + " state=" + state
                + " unacked=" + messagesUnacknowledged() + "/" + prefetchCount() + "}";
    }
}
