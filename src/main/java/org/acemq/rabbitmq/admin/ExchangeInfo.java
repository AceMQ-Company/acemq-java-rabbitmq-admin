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
 * An exchange as the broker holds it.
 *
 * <p>Worth having for the same reason as {@link QueueInfo} and one more: AMQP cannot inspect an
 * exchange at all. A passive declare fails the channel when it is absent, and a real declare
 * refuses when the type differs — so a topology planner over AMQP alone reports every exchange
 * as a creation, whether or not it is already there. That limitation is documented in
 * {@code TopologyPlanner}; this is what removes it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ExchangeInfo {

    private final String name;
    private final String vhost;
    private final String type;
    private final boolean durable;
    private final boolean autoDelete;
    private final boolean internal;
    private final Map<String, Object> arguments;

    public ExchangeInfo(
            @JsonProperty("name") String name,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("type") String type,
            @JsonProperty("durable") boolean durable,
            @JsonProperty("auto_delete") boolean autoDelete,
            @JsonProperty("internal") boolean internal,
            @JsonProperty("arguments") Map<String, Object> arguments) {
        this.name = name;
        this.vhost = vhost;
        this.type = type;
        this.durable = durable;
        this.autoDelete = autoDelete;
        this.internal = internal;
        this.arguments = arguments == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * @return the exchange's name. The default exchange is the empty string, which is a real
     *     exchange rather than an absence, and appears in listings as one
     */
    public String name() {
        return name;
    }

    public String vhost() {
        return vhost;
    }

    /** @return {@code direct}, {@code topic}, {@code fanout} or {@code headers} */
    public String type() {
        return type;
    }

    public boolean durable() {
        return durable;
    }

    public boolean autoDelete() {
        return autoDelete;
    }

    /**
     * @return whether it is internal, meaning a publisher cannot publish to it directly. The
     *     retry ladder's exchange is one: messages reach it by dead-lettering, never by being
     *     sent
     */
    public boolean internal() {
        return internal;
    }

    public Map<String, Object> arguments() {
        return arguments;
    }

    @Override
    public String toString() {
        return "ExchangeInfo{" + vhost + "/" + (name.isEmpty() ? "(default)" : name)
                + " " + type + (durable ? " durable" : " transient") + "}";
    }
}
