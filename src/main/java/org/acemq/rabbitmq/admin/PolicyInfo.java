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
 * A policy: settings applied to whatever matches a pattern.
 *
 * <p>The thing to understand before using one. A queue argument is fixed at declaration and
 * cannot be changed without deleting the queue; a **policy is applied afterwards and can be
 * changed at any time**. So `x-message-ttl` as an argument is permanent, and the same setting
 * as a policy is editable — which is why operators reach for policies and why a topology
 * comparison that only looks at arguments can be confidently wrong.
 *
 * <p>Two more traps worth knowing:
 *
 * <ul>
 *   <li><strong>Only one policy applies.</strong> Where several match, the highest priority
 *       wins outright — the definitions are not merged. A queue matching a broad policy and a
 *       narrow one gets exactly one of them.
 *   <li><strong>An argument beats a policy.</strong> If a queue was declared with
 *       {@code x-message-ttl} and a policy also sets it, the argument wins, and the policy
 *       appears to do nothing for reasons nothing reports.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PolicyInfo {

    private final String name;
    private final String vhost;
    private final String pattern;
    private final String applyTo;
    private final int priority;
    private final Map<String, Object> definition;

    public PolicyInfo(
            @JsonProperty("name") String name,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("pattern") String pattern,
            @JsonProperty("apply-to") String applyTo,
            @JsonProperty("priority") Integer priority,
            @JsonProperty("definition") Map<String, Object> definition) {
        this.name = name;
        this.vhost = vhost;
        this.pattern = pattern;
        this.applyTo = applyTo == null ? "all" : applyTo;
        this.priority = priority == null ? 0 : priority;
        this.definition = definition == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(definition));
    }

    public String name() {
        return name;
    }

    public String vhost() {
        return vhost;
    }

    /** @return the regular expression matched against queue and exchange names */
    public String pattern() {
        return pattern;
    }

    /** @return {@code queues}, {@code exchanges}, {@code classic_queues}, {@code quorum_queues} or {@code all} */
    public String applyTo() {
        return applyTo;
    }

    /** @return the priority. Where several policies match, the highest wins outright */
    public int priority() {
        return priority;
    }

    /** @return what the policy sets */
    public Map<String, Object> definition() {
        return definition;
    }

    @Override
    public String toString() {
        return "PolicyInfo{" + vhost + "/" + name + " /" + pattern + "/ -> "
                + applyTo + " priority=" + priority + " " + definition + "}";
    }
}
