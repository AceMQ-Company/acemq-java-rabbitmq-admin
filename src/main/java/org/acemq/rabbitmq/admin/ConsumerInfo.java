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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One consumer on one queue.
 *
 * <p>The list that answers "is anything actually reading this queue, and is it allowed to?" —
 * which a consumer count alone does not, because a single-active-consumer queue reports several
 * consumers of which exactly one is {@link #active()} and the rest are waiting.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ConsumerInfo {

    private final String consumerTag;
    private final String queue;
    private final String vhost;
    private final Boolean ackRequired;
    private final Boolean exclusive;
    private final Boolean active;
    private final String activityStatus;
    private final Integer prefetchCount;
    private final String channelName;
    private final Map<String, Object> arguments;

    @SuppressWarnings("unchecked")
    public ConsumerInfo(
            @JsonProperty("consumer_tag") String consumerTag,
            @JsonProperty("queue") Map<String, Object> queueDetails,
            @JsonProperty("ack_required") Boolean ackRequired,
            @JsonProperty("exclusive") Boolean exclusive,
            @JsonProperty("active") Boolean active,
            @JsonProperty("activity_status") String activityStatus,
            @JsonProperty("prefetch_count") Integer prefetchCount,
            @JsonProperty("channel_details") Map<String, Object> channelDetails,
            @JsonProperty("arguments") Map<String, Object> arguments) {
        this.consumerTag = consumerTag;
        // Both the queue and the channel arrive as nested objects rather than as names.
        this.queue = queueDetails == null ? null : String.valueOf(queueDetails.get("name"));
        this.vhost = queueDetails == null ? null : String.valueOf(queueDetails.get("vhost"));
        this.ackRequired = ackRequired;
        this.exclusive = exclusive;
        this.active = active;
        this.activityStatus = activityStatus;
        this.prefetchCount = prefetchCount;
        Object channel = channelDetails == null ? null : channelDetails.get("name");
        this.channelName = channel == null ? null : channel.toString();
        this.arguments = arguments == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /** @return the tag the client chose, which is what it uses to cancel */
    public String consumerTag() {
        return consumerTag;
    }

    public String queue() {
        return queue;
    }

    public String vhost() {
        return vhost;
    }

    /**
     * @return whether the consumer must acknowledge. <strong>False means auto-ack</strong>, and
     *     an auto-ack consumer loses every unprocessed message if it dies
     */
    public boolean ackRequired() {
        return Boolean.TRUE.equals(ackRequired);
    }

    public boolean exclusive() {
        return Boolean.TRUE.equals(exclusive);
    }

    /**
     * @return whether this consumer is currently receiving. On a queue with single active
     *     consumer, or a paused quorum queue, a consumer can be attached and not active — which
     *     a consumer count cannot distinguish from one that is working
     */
    public boolean active() {
        return active == null || active;
    }

    /** @return {@code up}, {@code waiting} or {@code suspected_down} */
    public String activityStatus() {
        return activityStatus;
    }

    public int prefetchCount() {
        return prefetchCount == null ? 0 : prefetchCount;
    }

    /** @return the channel this consumer is on, joining it to {@link ChannelInfo#name()} */
    public String channelName() {
        return channelName;
    }

    public Map<String, Object> arguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "ConsumerInfo{" + consumerTag + " on " + queue
                + (active() ? "" : " (inactive)") + "}";
    }
}
