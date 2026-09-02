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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A broker user.
 *
 * <p>No password and no hash. The management API will return the hash, and this class
 * deliberately drops it: nothing here needs it, and a field that holds one ends up in a log
 * line eventually.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class UserInfo {

    private final String name;
    private final List<String> tags;

    public UserInfo(@JsonProperty("name") String name, @JsonProperty("tags") Object tags) {
        this.name = name;
        this.tags = parseTags(tags);
    }

    /**
     * Tags arrive as a list on a modern broker and as a comma-separated string on an older one.
     * Both are accepted, because a client that only handled the newer shape would fail against
     * exactly the brokers most likely to need administering.
     */
    private static List<String> parseTags(Object tags) {
        if (tags == null) {
            return Collections.emptyList();
        }
        if (tags instanceof List) {
            List<?> raw = (List<?>) tags;
            String[] values = new String[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                values[i] = String.valueOf(raw.get(i));
            }
            return Collections.unmodifiableList(Arrays.asList(values));
        }
        String text = String.valueOf(tags).trim();
        if (text.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(Arrays.asList(text.split("\\s*,\\s*")));
    }

    public String name() {
        return name;
    }

    /** @return the tags: {@code administrator}, {@code monitoring}, {@code management}, {@code policymaker} */
    public List<String> tags() {
        return tags;
    }

    /** @return whether this user can read the management API at all */
    public boolean canManage() {
        return tags.contains("administrator") || tags.contains("monitoring") || tags.contains("management");
    }

    @Override
    public String toString() {
        return "UserInfo{" + name + " " + tags + "}";
    }
}
