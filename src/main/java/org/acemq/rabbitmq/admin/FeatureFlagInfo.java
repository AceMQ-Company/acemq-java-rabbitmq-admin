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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One feature flag, and whether it is enabled.
 *
 * <p>These gate an upgrade. A newer RabbitMQ will refuse to join a cluster, or refuse to start,
 * when a required flag is still disabled — so the question "can we upgrade yet" is answered
 * here and nowhere else.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class FeatureFlagInfo {

    private final String name;
    private final String description;
    private final String state;
    private final String stability;

    public FeatureFlagInfo(
            @JsonProperty("name") String name,
            @JsonProperty("desc") String description,
            @JsonProperty("state") String state,
            @JsonProperty("stability") String stability) {
        this.name = name;
        this.description = description;
        this.state = state;
        this.stability = stability;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** @return {@code enabled}, {@code disabled} or {@code unsupported} */
    public String state() {
        return state;
    }

    /** @return {@code required}, {@code stable} or {@code experimental} */
    public String stability() {
        return stability;
    }

    public boolean isEnabled() {
        return "enabled".equals(state);
    }

    /**
     * @return whether this flag must be enabled before the next major upgrade. A required flag
     *     that is still disabled will stop that upgrade, and this is the only place it is
     *     visible beforehand
     */
    public boolean isRequired() {
        return "required".equals(stability);
    }

    @Override
    public String toString() {
        return "FeatureFlagInfo{" + name + " " + state
                + ("required".equals(stability) ? " (required)" : "") + "}";
    }
}
