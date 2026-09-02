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
 * A shovel: the broker moving messages from one place to another, on its own.
 *
 * <p>A shovel consumes from a source and republishes to a destination, and either end may be on
 * a different broker. It is how a migration moves a live queue between clusters without the
 * publishers or consumers knowing, which is what {@code acemq-infrastructure} is being built
 * around.
 *
 * <p><strong>A shovel is not a copy.</strong> It consumes, so a message it moves is gone from
 * the source. Pointing one at a queue somebody is still reading produces two consumers racing,
 * and the shovel usually wins.
 *
 * <p>{@link #state()} is the field to watch. A shovel that cannot reach its destination sits in
 * {@code starting} indefinitely, retrying, moving nothing and reporting no error anywhere else.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ShovelInfo {

    private final String name;
    private final String vhost;
    private final String type;
    private final String state;

    public ShovelInfo(
            @JsonProperty("name") String name,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("type") String type,
            @JsonProperty("state") String state) {
        this.name = name;
        this.vhost = vhost;
        this.type = type == null ? "dynamic" : type;
        this.state = state == null ? "unknown" : state;
    }

    public String name() {
        return name;
    }

    public String vhost() {
        return vhost;
    }

    /** @return {@code dynamic} for one declared through this API, {@code static} for one in the config file */
    public String type() {
        return type;
    }

    /** @return {@code running}, {@code starting} or {@code terminated} */
    public String state() {
        return state;
    }

    /** @return whether it is actually moving messages */
    public boolean isRunning() {
        return "running".equals(state);
    }

    @Override
    public String toString() {
        return "ShovelInfo{" + vhost + "/" + name + " " + type + " " + state + "}";
    }
}
