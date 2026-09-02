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
import java.util.Optional;

/**
 * The answer to one of the broker's own health checks.
 *
 * <p>These are worth preferring over anything inferred from metrics, because the broker is
 * answering the question directly rather than letting you reconstruct it. Two of them —
 * {@link Health#quorumCritical()} and {@link Health#certificateExpiration(int, String)} — cannot
 * be derived from the metrics endpoint at all.
 *
 * <p>A failed check is <strong>not</strong> an exception. The endpoint answers HTTP 503 when the
 * thing it checks is unhealthy, which is the check working correctly; turning that into a thrown
 * exception would make "is the broker healthy?" impossible to answer with "no".
 */
public final class HealthResult {

    private final String check;
    private final boolean ok;
    private final String reason;
    private final Map<String, Object> details;

    HealthResult(String check, boolean ok, String reason, Map<String, Object> details) {
        this.check = check;
        this.ok = ok;
        this.reason = reason;
        this.details = details == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** @return which check this was, as its path segment, for example {@code alarms} */
    public String check() {
        return check;
    }

    /** @return whether the broker reported this check as passing */
    public boolean isOk() {
        return ok;
    }

    /**
     * @return why it failed, when it failed. Empty when the check passed — RabbitMQ sends no
     *     reason for a pass, and inventing one here would be words this library made up
     */
    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    /**
     * @return everything else the check returned, unmodelled. {@code alarms} carries an
     *     {@code alarms} array naming the node and resource; the certificate check carries the
     *     expiring certificates. These differ per check and are passed through rather than
     *     flattened into a shape that fits none of them
     */
    public Map<String, Object> details() {
        return details;
    }

    /**
     * @throws AdminException if this check failed, with the broker's own reason
     */
    public void orThrow() {
        if (!ok) {
            throw new AdminException("health check '" + check + "' failed: "
                    + reason().orElse("no reason given") + " " + details);
        }
    }

    @Override
    public String toString() {
        return "HealthResult{" + check + ": " + (ok ? "ok" : "failed - " + reason) + "}";
    }
}
