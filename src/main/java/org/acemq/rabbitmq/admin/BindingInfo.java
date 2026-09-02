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
 * A binding: the rule joining an exchange to a queue or to another exchange.
 *
 * <p>Invisible to AMQP entirely. There is no way to ask a broker what is bound to what, which
 * is why a topology plan over AMQP reports every binding as a creation and why "nothing is
 * bound for this routing key" is discovered by publishing and being refused.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BindingInfo {

    private final String source;
    private final String vhost;
    private final String destination;
    private final String destinationType;
    private final String routingKey;
    private final Map<String, Object> arguments;
    private final String propertiesKey;

    public BindingInfo(
            @JsonProperty("source") String source,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("destination") String destination,
            @JsonProperty("destination_type") String destinationType,
            @JsonProperty("routing_key") String routingKey,
            @JsonProperty("arguments") Map<String, Object> arguments,
            @JsonProperty("properties_key") String propertiesKey) {
        this.propertiesKey = propertiesKey;
        this.source = source == null ? "" : source;
        this.vhost = vhost;
        this.destination = destination;
        this.destinationType = destinationType == null ? "queue" : destinationType;
        this.routingKey = routingKey == null ? "" : routingKey;
        this.arguments = arguments == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * @return the exchange messages come from. Empty for the implicit binding every queue has
     *     to the default exchange under its own name, which is not a binding anybody made and
     *     cannot be removed
     */
    public String source() {
        return source;
    }

    public String vhost() {
        return vhost;
    }

    /** @return the queue or exchange messages go to */
    public String destination() {
        return destination;
    }

    /** @return {@code queue} or {@code exchange} */
    public String destinationType() {
        return destinationType;
    }

    /** @return the routing key or pattern */
    public String routingKey() {
        return routingKey;
    }

    /** @return the binding arguments, which is where a headers exchange keeps its matching rules */
    public Map<String, Object> arguments() {
        return arguments;
    }

    /** @return whether this is the unremovable default-exchange binding rather than a real one */
    public boolean isDefaultExchangeBinding() {
        return source.isEmpty();
    }

    /**
     * The broker's identifier for this binding, needed to delete it.
     *
     * <p>A binding has no name. Several may join the same pair of objects with different routing
     * keys and arguments, so the delete URL identifies one by this key rather than by anything
     * a caller chose.
     *
     * <p><strong>It arrives already percent-encoded.</strong> A routing key of {@code a.#} has a
     * properties key of {@code a.%23}, and it belongs in the URL exactly as given — encoding it
     * again produces {@code a.%2523}, which matches nothing and 404s. This is why
     * {@link RabbitAdmin#unbind(BindingInfo)} takes the whole object rather than a routing key:
     * reconstructing this string correctly is not something a caller should have to know about.
     *
     * @return the properties key, or null if the broker did not send one
     */
    public String propertiesKey() {
        return propertiesKey;
    }

    @Override
    public String toString() {
        return "BindingInfo{" + (source.isEmpty() ? "(default)" : source)
                + " -> " + destinationType + " " + destination
                + (routingKey.isEmpty() ? "" : " [" + routingKey + "]") + "}";
    }
}
