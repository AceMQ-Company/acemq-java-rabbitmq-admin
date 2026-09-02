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
 * A runtime parameter: how the broker stores a plugin's configuration.
 *
 * <p>Federation upstreams and dynamic shovels are both parameters, which is why they are
 * declared and removed the same way. Worth knowing for a second reason: a topology export that
 * walks queues, exchanges and bindings misses both entirely, so a broker restored from one
 * comes back without its federation and without its shovels, and looks complete.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ParameterInfo {

    private final String name;
    private final String vhost;
    private final String component;
    private final Map<String, Object> value;

    public ParameterInfo(
            @JsonProperty("name") String name,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("component") String component,
            @JsonProperty("value") Map<String, Object> value) {
        this.name = name;
        this.vhost = vhost;
        this.component = component;
        this.value = value == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    public String name() {
        return name;
    }

    public String vhost() {
        return vhost;
    }

    /** @return which plugin owns it: {@code federation-upstream}, {@code shovel}, and so on */
    public String component() {
        return component;
    }

    /**
     * @return the parameter itself. <strong>May contain credentials</strong> — a federation
     *     upstream's URI and a shovel's source and destination all carry them
     */
    public Map<String, Object> value() {
        return value;
    }

    /** @return a description safe to log: names only, never the value */
    @Override
    public String toString() {
        return "ParameterInfo{" + component + " " + vhost + "/" + name + "}";
    }
}
