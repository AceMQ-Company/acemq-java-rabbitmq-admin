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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Who the broker thinks you are.
 *
 * <p>Worth calling once at startup. Every permission failure later is a 401 or a 403 on some
 * unrelated-looking call, and "this user has no administrator tag" is a far better thing to
 * report at startup than "could not create vhost" an hour into a provisioning run.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class CurrentUser {

    private final String name;
    private final List<String> tags;
    private final Boolean internal;

    public CurrentUser(
            @JsonProperty("name") String name,
            @JsonProperty("tags") List<String> tags,
            @JsonProperty("is_internal_user") Boolean internal) {
        this.name = name;
        this.tags = tags == null ? Collections.emptyList() : Collections.unmodifiableList(tags);
        this.internal = internal;
    }

    public String name() {
        return name;
    }

    /** @return the tags this user holds: {@code administrator}, {@code monitoring}, and so on */
    public List<String> tags() {
        return tags;
    }

    /** @return whether the user is in the broker's internal database rather than LDAP or OAuth */
    public boolean isInternal() {
        return Boolean.TRUE.equals(internal);
    }

    /** @return whether this user may change anything through the management API */
    public boolean canAdminister() {
        return tags.contains("administrator");
    }

    /** @return whether this user may read the whole broker through the management API */
    public boolean canMonitor() {
        return tags.contains("administrator") || tags.contains("monitoring");
    }

    @Override
    public String toString() {
        return "CurrentUser{" + name + " " + tags + "}";
    }
}
