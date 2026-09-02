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

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A federation link: an upstream and a policy having actually met.
 *
 * <p>This is the only proof federation is working. An upstream can be defined and a policy can
 * match and there can still be no link — because the upstream is unreachable, or its
 * credentials are wrong, or the exchange it names does not exist over there. None of those
 * produce an error anywhere a normal deployment would look.
 *
 * <p>So the useful check is not "is the upstream declared" but "is there a link, and is it
 * {@code running}". A link stuck in {@code starting} is retrying forever, moving nothing, and
 * reporting itself only here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class FederationLinkInfo {

    private final String upstream;
    private final String vhost;
    private final String type;
    private final String status;
    private final String error;

    public FederationLinkInfo(
            @JsonProperty("upstream") String upstream,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("type") String type,
            @JsonProperty("status") String status,
            @JsonProperty("error") String error) {
        this.upstream = upstream;
        this.vhost = vhost;
        this.type = type == null ? "unknown" : type;
        this.status = status == null ? "unknown" : status;
        this.error = error;
    }

    /** @return the upstream this link is to */
    public String upstream() {
        return upstream;
    }

    public String vhost() {
        return vhost;
    }

    /** @return {@code exchange} or {@code queue}: what is being federated */
    public String type() {
        return type;
    }

    /** @return {@code running}, {@code starting} or an error state */
    public String status() {
        return status;
    }

    /**
     * @return why the link is not running, when the broker said. Usually the answer to a
     *     question somebody has been asking for a while
     */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    /** @return whether messages are actually crossing */
    public boolean isRunning() {
        return "running".equals(status);
    }

    @Override
    public String toString() {
        return "FederationLinkInfo{" + vhost + " " + type + " -> " + upstream + " " + status
                + (error == null ? "" : ": " + error) + "}";
    }
}
