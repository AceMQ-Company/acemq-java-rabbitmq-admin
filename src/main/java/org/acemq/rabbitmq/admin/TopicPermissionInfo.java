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
 * Authorisation for routing keys on a topic exchange.
 *
 * <p>A second, separate permission surface. {@link PermissionInfo} controls which
 * <em>resources</em> a user may touch; this controls which <em>routing keys</em> they may
 * publish or subscribe to on a given topic exchange.
 *
 * <p>It is easy to miss, because a user with no topic permissions at all is unrestricted on
 * topic exchanges. Granting one is what starts enforcing anything, so an estate that has never
 * set these has no topic authorisation regardless of how careful its regular permissions are.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class TopicPermissionInfo {

    private final String user;
    private final String vhost;
    private final String exchange;
    private final String write;
    private final String read;

    public TopicPermissionInfo(
            @JsonProperty("user") String user,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("exchange") String exchange,
            @JsonProperty("write") String write,
            @JsonProperty("read") String read) {
        this.user = user;
        this.vhost = vhost;
        this.exchange = exchange;
        this.write = write == null ? "" : write;
        this.read = read == null ? "" : read;
    }

    public String user() {
        return user;
    }

    public String vhost() {
        return vhost;
    }

    /** @return the topic exchange this applies to */
    public String exchange() {
        return exchange;
    }

    /** @return the routing-key pattern this user may publish with */
    public String write() {
        return write;
    }

    /** @return the routing-key pattern this user may bind and consume with */
    public String read() {
        return read;
    }

    @Override
    public String toString() {
        return "TopicPermissionInfo{" + user + " on " + exchange
                + " write=" + write + " read=" + read + "}";
    }
}
