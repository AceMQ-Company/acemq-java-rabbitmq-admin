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
 * One publisher on a stream protocol connection.
 *
 * <p>{@link #errored()} is the number to watch. A stream publisher is confirmed asynchronously,
 * so a message the broker refused does not fail the send — it fails a callback, which an
 * application can easily never look at.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StreamPublisherInfo {

    private final String reference;
    private final int publisherId;
    private final String stream;
    private final String vhost;
    private final String connectionName;
    private final long published;
    private final long confirmed;
    private final long errored;

    public StreamPublisherInfo(
            @JsonProperty("reference") String reference,
            @JsonProperty("publisher_id") Integer publisherId,
            @JsonProperty("queue") Map<String, Object> queue,
            @JsonProperty("connection_details") Object connection,
            @JsonProperty("published") Long published,
            @JsonProperty("confirmed") Long confirmed,
            @JsonProperty("errored") Long errored) {
        this.reference = reference;
        this.publisherId = publisherId == null ? 0 : publisherId;
        // The stream arrives as a nested queue object, as it does for an AMQP consumer.
        this.stream = queue == null ? null : String.valueOf(queue.get("name"));
        this.vhost = queue == null ? null : String.valueOf(queue.get("vhost"));
        // Empty until the first statistics interval after the publisher appears.
        this.connectionName = field(connection, "name");
        this.published = published == null ? 0 : published;
        this.confirmed = confirmed == null ? 0 : confirmed;
        this.errored = errored == null ? 0 : errored;
    }

    /**
     * @return the publisher's name, which is what the broker deduplicates on; null for a
     *     publisher that gave none, and therefore has no deduplication
     */
    public String reference() {
        return reference;
    }

    /** @return the id, unique only within its connection */
    public int publisherId() {
        return publisherId;
    }

    public String stream() {
        return stream;
    }

    public String vhost() {
        return vhost;
    }

    /**
     * @return the connection it is on, joining it to {@link StreamConnectionInfo#name()}; null
     *     until the broker's first statistics interval after the publisher appeared
     */
    public String connectionName() {
        return connectionName;
    }

    public long published() {
        return published;
    }

    public long confirmed() {
        return confirmed;
    }

    /** @return messages the broker refused, which the sending code may never have noticed */
    public long errored() {
        return errored;
    }

    /**
     * Reads one field of an object the broker may send as an empty array instead. An empty
     * Erlang property list encodes as {@code []}, so 3.13 answers {@code "connection_details":[]}
     * where 4.x answers {@code {}}, and a {@code Map} parameter fails the whole list on 3.13.
     */
    static String field(Object maybeObject, String key) {
        Object value = maybeObject instanceof Map ? ((Map<?, ?>) maybeObject).get(key) : null;
        return value == null ? null : value.toString();
    }

    @Override
    public String toString() {
        return "StreamPublisherInfo{" + (reference == null ? "#" + publisherId : reference)
                + " -> " + stream + " published=" + published + " errored=" + errored + "}";
    }
}
