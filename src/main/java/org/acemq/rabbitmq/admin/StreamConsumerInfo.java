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
 * One subscription on a stream protocol connection.
 *
 * <p>A stream is not drained, so there is no queue depth to read. {@link #offsetLag()} is the
 * equivalent: how far behind the end of the stream this consumer is.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StreamConsumerInfo {

    private final int subscriptionId;
    private final String name;
    private final String stream;
    private final String vhost;
    private final String connectionName;
    private final Boolean active;
    private final long offset;
    private final long offsetLag;
    private final long consumed;
    private final long credits;

    public StreamConsumerInfo(
            @JsonProperty("subscription_id") Integer subscriptionId,
            @JsonProperty("properties") Object properties,
            @JsonProperty("queue") Map<String, Object> queue,
            @JsonProperty("connection_details") Object connection,
            @JsonProperty("active") Boolean active,
            @JsonProperty("offset") Long offset,
            @JsonProperty("offset_lag") Long offsetLag,
            @JsonProperty("consumed") Long consumed,
            @JsonProperty("credits") Long credits) {
        this.subscriptionId = subscriptionId == null ? 0 : subscriptionId;
        this.name = StreamPublisherInfo.field(properties, "name");
        this.stream = queue == null ? null : String.valueOf(queue.get("name"));
        this.vhost = queue == null ? null : String.valueOf(queue.get("vhost"));
        this.connectionName = StreamPublisherInfo.field(connection, "name");
        this.active = active;
        this.offset = offset == null ? 0 : offset;
        this.offsetLag = offsetLag == null ? 0 : offsetLag;
        this.consumed = consumed == null ? 0 : consumed;
        this.credits = credits == null ? 0 : credits;
    }

    /** @return the id, unique only within its connection */
    public int subscriptionId() {
        return subscriptionId;
    }

    /**
     * @return the consumer's name, under which the broker stores its offset; null for a consumer
     *     that tracks no offset and so starts over wherever it is told to on every restart
     */
    public String name() {
        return name;
    }

    public String stream() {
        return stream;
    }

    public String vhost() {
        return vhost;
    }

    /** @return the connection it is on; null until the broker's first statistics interval */
    public String connectionName() {
        return connectionName;
    }

    /**
     * @return whether it is receiving. With single active consumer, the others in the group are
     *     attached and waiting
     */
    public boolean active() {
        return active == null || active;
    }

    public long offset() {
        return offset;
    }

    /** @return how many messages it is behind the end of the stream */
    public long offsetLag() {
        return offsetLag;
    }

    public long consumed() {
        return consumed;
    }

    /** @return chunks the client is ready for. A zero that stays zero is a client that
     *     stopped asking */
    public long credits() {
        return credits;
    }

    @Override
    public String toString() {
        return "StreamConsumerInfo{" + (name == null ? "#" + subscriptionId : name)
                + " on " + stream + " lag=" + offsetLag + (active() ? "" : " (inactive)") + "}";
    }
}
