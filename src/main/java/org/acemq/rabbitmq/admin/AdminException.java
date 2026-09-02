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

/**
 * Something went wrong talking to the management API.
 *
 * <p>Unchecked, matching {@code AceMqException} in the messaging library: an administrative
 * call fails for reasons a caller usually cannot handle at the call site, and a checked
 * exception here would be caught and rethrown in every one of them.
 *
 * <p>The message is expected to say <em>which</em> of the four usual causes it was — wrong
 * credentials, the plugin not enabled, the object not there, or the broker refusing — because
 * they look identical from a stack trace and are fixed in completely different places.
 */
public class AdminException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** @param message what happened, and where to look */
    public AdminException(String message) {
        super(message);
    }

    /**
     * @param message what happened, and where to look
     * @param cause the underlying failure
     */
    public AdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
