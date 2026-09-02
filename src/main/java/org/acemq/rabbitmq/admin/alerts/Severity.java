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
package org.acemq.rabbitmq.admin.alerts;

/**
 * How much this matters.
 *
 * <p>Three, deliberately. A scale with five levels ends up with everything at the middle one,
 * and the only question an on-call rota actually asks is whether to wake somebody.
 */
public enum Severity {

    /** Wake somebody. Messages are being lost, or will be shortly. */
    CRITICAL,

    /** Look at it today. Something is degrading and has not broken yet. */
    WARNING,

    /** Worth knowing when you are already looking. Never a page. */
    INFO
}
