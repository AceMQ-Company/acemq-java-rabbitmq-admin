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
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A limit on a virtual host or a user.
 *
 * <p>The controls that stop one misbehaving application taking a broker down for everybody: a
 * connection leak that would otherwise reach the node's file descriptor limit, or a service
 * declaring queues in a loop.
 *
 * <p>The broker returns these as a map because a vhost can carry several at once — the JSON is
 * {@code {"vhost":"/","value":{"max-connections":100,"max-queues":50}}} rather than one object
 * per limit.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class LimitInfo {

    /** Limits the number of concurrent connections. */
    public static final String MAX_CONNECTIONS = "max-connections";

    /** Limits the number of queues that may exist. */
    public static final String MAX_QUEUES = "max-queues";

    private final String vhost;
    private final String user;
    private final Map<String, Object> value;

    public LimitInfo(
            @JsonProperty("vhost") String vhost,
            @JsonProperty("user") String user,
            @JsonProperty("value") Map<String, Object> value) {
        this.vhost = vhost;
        this.user = user;
        this.value = value == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    /** @return the virtual host this applies to, or null for a user limit */
    public String vhost() {
        return vhost;
    }

    /** @return the user this applies to, or null for a vhost limit */
    public String user() {
        return user;
    }

    /** @return every limit set here, by name */
    public Map<String, Object> value() {
        return value;
    }

    /**
     * @param name a limit name, such as {@link #MAX_CONNECTIONS}
     * @return its value, if this object carries it
     */
    public Optional<Long> limit(String name) {
        Object found = value.get(name);
        return found instanceof Number
                ? Optional.of(((Number) found).longValue())
                : Optional.empty();
    }

    @Override
    public String toString() {
        return "LimitInfo{" + (user == null ? "vhost=" + vhost : "user=" + user) + " " + value + "}";
    }
}
