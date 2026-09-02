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
 * What a user may do in a virtual host.
 *
 * <p>Three regular expressions, matched against resource names. They are easy to write too
 * broadly: {@code .*} for all three is what most tutorials show and what most services end up
 * with, and it means a service that only publishes to one exchange can also delete every queue
 * in the vhost.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PermissionInfo {

    private final String user;
    private final String vhost;
    private final String configure;
    private final String write;
    private final String read;

    public PermissionInfo(
            @JsonProperty("user") String user,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("configure") String configure,
            @JsonProperty("write") String write,
            @JsonProperty("read") String read) {
        this.user = user;
        this.vhost = vhost;
        this.configure = configure == null ? "" : configure;
        this.write = write == null ? "" : write;
        this.read = read == null ? "" : read;
    }

    public String user() {
        return user;
    }

    public String vhost() {
        return vhost;
    }

    /** @return what the user may declare and delete */
    public String configure() {
        return configure;
    }

    /** @return what the user may publish to */
    public String write() {
        return write;
    }

    /** @return what the user may consume from */
    public String read() {
        return read;
    }

    /**
     * @return whether all three patterns are {@code .*}, which is unrestricted access to the
     *     virtual host. Worth checking for in an audit: it is almost never what anybody meant,
     *     and it is what everybody copies
     */
    public boolean isUnrestricted() {
        return ".*".equals(configure) && ".*".equals(write) && ".*".equals(read);
    }

    @Override
    public String toString() {
        return "PermissionInfo{" + user + "@" + vhost
                + " configure=" + configure + " write=" + write + " read=" + read + "}";
    }
}
