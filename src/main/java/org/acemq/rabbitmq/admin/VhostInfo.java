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

/** A virtual host: the broker's unit of isolation, and the cheapest boundary it offers. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class VhostInfo {

    private final String name;
    private final String description;
    private final boolean tracing;

    public VhostInfo(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("tracing") boolean tracing) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.tracing = tracing;
    }

    /** @return the name. The default one is literally a forward slash */
    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /**
     * @return whether the firehose tracer is on. It copies every message to an exchange, so it
     *     is a diagnostic rather than a setting, and one left on in production is a broker
     *     doing twice the work for nobody
     */
    public boolean tracing() {
        return tracing;
    }

    @Override
    public String toString() {
        return "VhostInfo{" + name + (tracing ? ", TRACING ON" : "") + "}";
    }
}
