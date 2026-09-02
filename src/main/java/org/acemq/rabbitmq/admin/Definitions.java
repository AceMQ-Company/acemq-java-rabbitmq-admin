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
import java.util.List;
import java.util.Map;

/**
 * Everything the broker is configured to be, as one document.
 *
 * <pre>{@code
 * Definitions backup = admin.exportDefinitions();
 * Files.writeString(Path.of("broker.json"), backup.json());
 *
 * // ... on the new broker
 * newAdmin.importDefinitions(Files.readString(Path.of("broker.json")));
 * }</pre>
 *
 * <h2>Why this beats walking the topology</h2>
 *
 * <p>Queues, exchanges and bindings are the obvious things to export, and they are not all of
 * it. Federation upstreams and shovels are <em>runtime parameters</em>, policies are separate
 * again, and users, permissions and topic permissions are separate from all of those. A backup
 * assembled by walking the topology misses every one of them, and the broker restored from it
 * comes back looking complete while federating nothing.
 *
 * <p>This endpoint is the whole thing in one request, which is both a better backup and a
 * cheaper one.
 *
 * <h2>What it does not contain</h2>
 *
 * <p><strong>No messages.</strong> This is configuration. Restoring definitions onto an empty
 * broker gives you every queue, correctly configured, and every one of them empty.
 *
 * <p><strong>Passwords are present but hashed.</strong> Users come back with
 * {@code password_hash}, which is enough to restore them onto another broker and not enough to
 * read anybody's password. It is still a credential: an exported definitions file lets anyone
 * holding it stand up a broker that accepts your users' passwords. Treat the file as a secret.
 */
public final class Definitions {

    private final String json;
    private final Map<String, Object> parsed;

    Definitions(String json, Map<String, Object> parsed) {
        this.json = json;
        this.parsed = parsed;
    }

    /**
     * @return the document exactly as the broker sent it. This is what to write to a file and
     *     what {@link RabbitAdmin#importDefinitions(String)} takes — re-serialising the parsed
     *     form risks changing it in ways the import will notice
     */
    public String json() {
        return json;
    }

    /** @return the whole document, parsed */
    public Map<String, Object> asMap() {
        return parsed;
    }

    /** @return the RabbitMQ version that produced this export */
    public String rabbitVersion() {
        Object version = parsed.get("rabbit_version");
        return version == null ? "" : version.toString();
    }

    /** @return the exported queues, as the broker's own JSON shape */
    public List<Map<String, Object>> queues() {
        return section("queues");
    }

    public List<Map<String, Object>> exchanges() {
        return section("exchanges");
    }

    public List<Map<String, Object>> bindings() {
        return section("bindings");
    }

    public List<Map<String, Object>> vhosts() {
        return section("vhosts");
    }

    /** @return users, each with a {@code password_hash} rather than a password */
    public List<Map<String, Object>> users() {
        return section("users");
    }

    public List<Map<String, Object>> permissions() {
        return section("permissions");
    }

    public List<Map<String, Object>> topicPermissions() {
        return section("topic_permissions");
    }

    public List<Map<String, Object>> policies() {
        return section("policies");
    }

    /** @return the runtime parameters: federation upstreams and shovels live here */
    public List<Map<String, Object>> parameters() {
        return section("parameters");
    }

    public List<Map<String, Object>> globalParameters() {
        return section("global_parameters");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> section(String key) {
        Object value = parsed.get(key);
        if (!(value instanceof List)) {
            // A vhost-scoped export omits sections that are cluster-wide, so an absent section
            // is normal rather than a malformed document.
            return Collections.emptyList();
        }
        return Collections.unmodifiableList((List<Map<String, Object>>) value);
    }

    /** @return a one-line count of what is in here, for a log line or a confirmation prompt */
    public String summary() {
        return "vhosts=" + vhosts().size()
                + " queues=" + queues().size()
                + " exchanges=" + exchanges().size()
                + " bindings=" + bindings().size()
                + " users=" + users().size()
                + " permissions=" + permissions().size()
                + " policies=" + policies().size()
                + " parameters=" + parameters().size();
    }

    @Override
    public String toString() {
        return "Definitions{" + summary() + "}";
    }
}
