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
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A queue as the broker actually holds it.
 *
 * <p>This is the thing AMQP cannot give you. A passive declare says whether a queue exists; a
 * real declare says whether your arguments match, by refusing when they do not. Neither reports
 * what the arguments <em>are</em>, so a topology check over AMQP alone can say "this differs"
 * and never "this is what it is".
 *
 * <p>Unknown fields are ignored deliberately. RabbitMQ adds fields to this endpoint between
 * minor versions, and a client that failed on an unrecognised one would break on a broker
 * upgrade it had no reason to care about.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class QueueInfo {

    private final String name;
    private final String vhost;
    private final String type;
    private final boolean durable;
    private final boolean autoDelete;
    private final boolean exclusive;
    private final Map<String, Object> arguments;
    private final long messages;
    private final long messagesReady;
    private final long messagesUnacknowledged;
    private final int consumers;
    private final String state;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public QueueInfo(
            @JsonProperty("name") String name,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("type") String type,
            @JsonProperty("durable") boolean durable,
            @JsonProperty("auto_delete") boolean autoDelete,
            @JsonProperty("exclusive") boolean exclusive,
            @JsonProperty("arguments") Map<String, Object> arguments,
            @JsonProperty("messages") Long messages,
            @JsonProperty("messages_ready") Long messagesReady,
            @JsonProperty("messages_unacknowledged") Long messagesUnacknowledged,
            @JsonProperty("consumers") Integer consumers,
            @JsonProperty("state") String state) {
        this.name = name;
        this.vhost = vhost;
        // "classic" when the broker did not say, which is what it means: the queue type
        // argument is absent on a classic queue declared before the type existed.
        this.type = type == null ? "classic" : type;
        this.durable = durable;
        this.autoDelete = autoDelete;
        this.exclusive = exclusive;
        this.arguments = arguments == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        // The counters are absent rather than zero on a queue the broker has not gathered
        // statistics for yet, and null-unboxing them would fail on a queue declared a moment
        // ago -- which is exactly when a test looks.
        this.messages = messages == null ? 0L : messages;
        this.messagesReady = messagesReady == null ? 0L : messagesReady;
        this.messagesUnacknowledged = messagesUnacknowledged == null ? 0L : messagesUnacknowledged;
        this.consumers = consumers == null ? 0 : consumers;
        this.state = state == null ? "unknown" : state;
    }

    /** @return the queue's name */
    public String name() {
        return name;
    }

    /** @return the virtual host it lives in */
    public String vhost() {
        return vhost;
    }

    /** @return {@code classic}, {@code quorum} or {@code stream} */
    public String type() {
        return type;
    }

    /** @return whether it survives a broker restart */
    public boolean durable() {
        return durable;
    }

    /** @return whether the broker removes it when the last consumer goes */
    public boolean autoDelete() {
        return autoDelete;
    }

    /** @return whether it belongs to one connection */
    public boolean exclusive() {
        return exclusive;
    }

    /**
     * The arguments the queue was declared with.
     *
     * <p>The reason this class exists. {@code x-message-ttl}, {@code x-dead-letter-exchange},
     * {@code x-max-length} and the rest, as the broker holds them rather than as anybody
     * believes they were set.
     *
     * @return the arguments, never null
     */
    public Map<String, Object> arguments() {
        return arguments;
    }

    /**
     * @param name an argument's name
     * @return its value, or null when the queue does not carry it
     */
    public Object argument(String name) {
        return arguments.get(Objects.requireNonNull(name, "name"));
    }

    /** @return total messages: ready plus unacknowledged */
    public long messages() {
        return messages;
    }

    /**
     * @return messages waiting for a consumer.
     *     <p>The number a queue-depth alert should use. {@link #messages()} includes deliveries
     *     already handed to a consumer and not yet settled, so it stays high while a consumer
     *     works through a batch and says nothing about whether the queue is draining.
     */
    public long messagesReady() {
        return messagesReady;
    }

    /** @return messages delivered and not yet acknowledged */
    public long messagesUnacknowledged() {
        return messagesUnacknowledged;
    }

    /** @return how many consumers are attached */
    public int consumers() {
        return consumers;
    }

    /** @return {@code running}, {@code idle}, {@code flow}, or whatever the broker reported */
    public String state() {
        return state;
    }

    @Override
    public String toString() {
        return "QueueInfo{" + vhost + "/" + name + " " + type
                + (durable ? " durable" : " transient")
                + ", ready=" + messagesReady + ", consumers=" + consumers
                + (arguments.isEmpty() ? "" : ", args=" + arguments) + "}";
    }
}
