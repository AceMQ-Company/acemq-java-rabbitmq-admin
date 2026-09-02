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
 * One node of the cluster.
 *
 * <p>Deliberately thin. This endpoint's contents vary a great deal with the broker's
 * configuration — most of the resource statistics are absent unless the management agent is
 * collecting them — so modelling the numbers here would produce a class whose fields are null on
 * a broker that is working perfectly well. The resource numbers come from the metrics endpoint,
 * which always has them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class NodeInfo {

    private final String name;
    private final String type;
    private final Boolean running;
    private final Boolean beingDrained;

    public NodeInfo(
            @JsonProperty("name") String name,
            @JsonProperty("type") String type,
            @JsonProperty("running") Boolean running,
            @JsonProperty("being_drained") Boolean beingDrained) {
        this.name = name;
        this.type = type;
        this.running = running;
        this.beingDrained = beingDrained;
    }

    /** @return the Erlang node name, for example {@code rabbit@host1} */
    public String name() {
        return name;
    }

    /** @return {@code disc} or {@code ram} */
    public String type() {
        return type;
    }

    public boolean running() {
        return Boolean.TRUE.equals(running);
    }

    /**
     * @return whether the node is in maintenance mode. A node being drained is running and
     *     deliberately not taking work, so treating it as unhealthy would page somebody for a
     *     planned operation
     */
    public boolean beingDrained() {
        return Boolean.TRUE.equals(beingDrained);
    }

    @Override
    public String toString() {
        return "NodeInfo{" + name + " " + (running() ? "running" : "down")
                + (beingDrained() ? " (draining)" : "") + "}";
    }
}
