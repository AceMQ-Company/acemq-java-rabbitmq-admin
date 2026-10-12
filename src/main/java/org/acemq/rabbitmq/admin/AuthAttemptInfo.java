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
 * Login attempts one node has counted, for one protocol and — when the broker tracks sources —
 * one remote address and username.
 *
 * <p>The counters run from node start or from the last
 * {@link RabbitAdmin#resetAuthAttempts(String) reset}, whichever is later. A rising failure
 * count from one address is a misconfigured client far more often than an attack, and this is
 * the only place either shows up: a failed login never becomes a connection.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AuthAttemptInfo {

    private final String protocol;
    private final String remoteAddress;
    private final String username;
    private final long attempts;
    private final long failed;
    private final long succeeded;

    public AuthAttemptInfo(
            @JsonProperty("protocol") String protocol,
            @JsonProperty("remote_address") String remoteAddress,
            @JsonProperty("username") String username,
            @JsonProperty("auth_attempts") Long attempts,
            @JsonProperty("auth_attempts_failed") Long failed,
            @JsonProperty("auth_attempts_succeeded") Long succeeded) {
        this.protocol = protocol;
        this.remoteAddress = remoteAddress;
        this.username = username;
        this.attempts = attempts == null ? 0 : attempts;
        this.failed = failed == null ? 0 : failed;
        this.succeeded = succeeded == null ? 0 : succeeded;
    }

    /** @return {@code amqp091}, {@code http}, {@code stream}, {@code mqtt} and so on */
    public String protocol() {
        return protocol;
    }

    /** @return the client's address, or null in the per-node totals */
    public String remoteAddress() {
        return remoteAddress;
    }

    /** @return the username tried, or null in the per-node totals */
    public String username() {
        return username;
    }

    public long attempts() {
        return attempts;
    }

    public long failed() {
        return failed;
    }

    public long succeeded() {
        return succeeded;
    }

    @Override
    public String toString() {
        return "AuthAttemptInfo{" + protocol
                + (username == null ? "" : " " + username + "@" + remoteAddress)
                + " failed=" + failed + "/" + attempts + "}";
    }
}
