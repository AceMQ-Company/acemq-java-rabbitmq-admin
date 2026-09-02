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

/**
 * Another broker this one may federate from.
 *
 * <p>An upstream on its own does nothing. It names a broker and how to connect to it; what
 * actually links anything is a <strong>policy</strong> whose definition carries
 * {@code federation-upstream} or {@code federation-upstream-set}. Declaring an upstream and
 * expecting messages to flow is the most common way federation appears not to work, and nothing
 * reports it because nothing is wrong — you described a broker and never asked for anything
 * from it.
 *
 * <h2>Federation is not a shovel</h2>
 *
 * <p>They are easy to confuse and behave differently in the way that matters:
 *
 * <ul>
 *   <li>A <strong>shovel</strong> consumes from the source. A message it moves is <em>gone</em>
 *       from where it was.
 *   <li><strong>Federation</strong> on an exchange copies: the upstream's subscribers still get
 *       the message, and so do yours.
 * </ul>
 *
 * <p>So federation is for fanning a stream of events out to another site, and a shovel is for
 * moving a queue's contents somewhere else — which is what a migration wants.
 *
 * <h2>The URI carries credentials</h2>
 *
 * <p>{@code amqp://user:password@host}. That is how the broker stores it and how the management
 * API returns it, so this object holds a password whether anybody wanted it to or not.
 * {@link #toString()} redacts it. {@link #uri()} does not, because a caller comparing two
 * upstreams needs the real value — so do not log the result.
 */
public final class FederationUpstream {

    private final String name;
    private final String vhost;
    private final String uri;
    private final Map<String, Object> settings;

    FederationUpstream(String name, String vhost, Map<String, Object> value) {
        this.name = name;
        this.vhost = vhost;
        Map<String, Object> copy = value == null ? Collections.emptyMap() : new LinkedHashMap<>(value);
        Object rawUri = copy.get("uri");
        this.uri = rawUri == null ? "" : String.valueOf(rawUri);
        this.settings = Collections.unmodifiableMap(copy);
    }

    /** @return what this upstream is called, and what a policy refers to it by */
    public String name() {
        return name;
    }

    /** @return the virtual host the upstream is defined in */
    public String vhost() {
        return vhost;
    }

    /**
     * @return the connection URI, <strong>including any credentials in it</strong>. Needed to
     *     compare two upstreams; never log the result
     */
    public String uri() {
        return uri;
    }

    /** @return the URI with any password replaced, which is what belongs in a log or a report */
    public String redactedUri() {
        return redact(uri);
    }

    /**
     * @return everything the upstream defines: {@code expires}, {@code message-ttl},
     *     {@code ack-mode}, {@code max-hops}, {@code prefetch-count} and the rest, exactly as
     *     the broker holds them
     */
    public Map<String, Object> settings() {
        return settings;
    }

    /**
     * @return how long the upstream's queue survives with nothing consuming from it, in
     *     milliseconds, or empty when it is unset — which means forever, and is how a
     *     decommissioned downstream leaves a queue growing on somebody else's broker
     */
    public java.util.Optional<Long> expires() {
        Object value = settings.get("expires");
        return value instanceof Number
                ? java.util.Optional.of(((Number) value).longValue())
                : java.util.Optional.empty();
    }

    /**
     * Replaces the password in a URI with {@code ***}.
     *
     * <p>Deliberately conservative: anything between {@code ://} and the {@code @} that
     * contains a colon has its second half removed, and a URI this cannot parse is reported as
     * unprintable rather than printed. A redaction that fails open is not a redaction.
     */
    static String redact(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "";
        }
        int scheme = uri.indexOf("://");
        int at = uri.indexOf('@', scheme < 0 ? 0 : scheme);
        if (scheme < 0 || at < 0) {
            // No credentials in it at all.
            return uri;
        }
        String credentials = uri.substring(scheme + 3, at);
        int colon = credentials.indexOf(':');
        if (colon < 0) {
            // A user with no password.
            return uri;
        }
        return uri.substring(0, scheme + 3) + credentials.substring(0, colon) + ":***" + uri.substring(at);
    }

    @Override
    public String toString() {
        return "FederationUpstream{" + vhost + "/" + name + " -> " + redactedUri() + "}";
    }
}
