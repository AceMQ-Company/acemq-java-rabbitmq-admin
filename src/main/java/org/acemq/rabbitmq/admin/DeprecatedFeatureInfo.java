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
 * A deprecated feature, and how much time is left.
 *
 * <p>{@link RabbitAdmin#deprecatedFeaturesInUse()} is the list that matters: features this
 * broker is <em>actually using</em>. Anything on it breaks at some future upgrade, and finding
 * out from this endpoint is considerably better than finding out from a broker that will not
 * start.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class DeprecatedFeatureInfo {

    private final String name;
    private final String description;
    private final String deprecationPhase;
    private final String docUrl;

    public DeprecatedFeatureInfo(
            @JsonProperty("name") String name,
            @JsonProperty("desc") String description,
            @JsonProperty("deprecation_phase") String deprecationPhase,
            @JsonProperty("doc_url") String docUrl) {
        this.name = name;
        this.description = description;
        this.deprecationPhase = deprecationPhase;
        this.docUrl = docUrl;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /**
     * @return how far along the removal is: {@code permitted_by_default},
     *     {@code denied_by_default}, {@code disconnected} or {@code removed}. The order is also
     *     the order of urgency
     */
    public String deprecationPhase() {
        return deprecationPhase;
    }

    public String docUrl() {
        return docUrl;
    }

    /**
     * @return whether this feature is already off unless explicitly re-enabled, which means an
     *     upgrade will break it rather than warn about it
     */
    public boolean isDeniedByDefault() {
        return "denied_by_default".equals(deprecationPhase)
                || "disconnected".equals(deprecationPhase)
                || "removed".equals(deprecationPhase);
    }

    @Override
    public String toString() {
        return "DeprecatedFeatureInfo{" + name + " " + deprecationPhase + "}";
    }
}
