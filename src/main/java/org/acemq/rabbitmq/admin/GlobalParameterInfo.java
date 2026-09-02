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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A cluster-wide parameter, which is not scoped to a virtual host.
 *
 * <p>Separate from {@link ParameterInfo} because the value is not the same shape. A vhost
 * parameter's value is always an object — a federation upstream, a shovel definition — while a
 * global parameter's may be anything the broker chose: {@code internal_cluster_id} is a string
 * and {@code cluster_tags} is an array.
 *
 * <p>Modelling this with {@code Map<String, Object>} therefore fails to parse on any broker that
 * has ever started, which is how this class came to exist.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class GlobalParameterInfo {

    private final String name;
    private final Object value;

    public GlobalParameterInfo(
            @JsonProperty("name") String name,
            @JsonProperty("value") Object value) {
        this.name = name;
        this.value = value;
    }

    public String name() {
        return name;
    }

    /** @return the value, which may be a string, a number, a list or an object */
    public Object value() {
        return value;
    }

    /** @return the value when it is a string, such as {@code internal_cluster_id} */
    public Optional<String> asString() {
        return value instanceof String ? Optional.of((String) value) : Optional.empty();
    }

    /** @return the value when it is a list, such as {@code cluster_tags} */
    @SuppressWarnings("unchecked")
    public List<Object> asList() {
        return value instanceof List
                ? Collections.unmodifiableList((List<Object>) value)
                : Collections.emptyList();
    }

    /** @return the value when it is an object */
    @SuppressWarnings("unchecked")
    public Map<String, Object> asMap() {
        return value instanceof Map
                ? Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) value))
                : Collections.emptyMap();
    }

    @Override
    public String toString() {
        return "GlobalParameterInfo{" + name + "=" + value + "}";
    }
}
