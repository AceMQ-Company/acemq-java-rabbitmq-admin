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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("password hashing")
class PasswordHashTest {

    /** What RabbitMQ 4.3.6 answered for GET /api/auth/hash_password/secret. */
    private static final String BROKER_HASH_OF_SECRET = "S6Dg/Vye1OsHPMpmOUZRVO6IKjMAkcJ+vNn1Q7OJAukd9010";

    /** And RabbitMQ 3.13.7, with a different salt. */
    private static final String BROKER_313_HASH_OF_SECRET = "F6wTB8L3xW8PCADVMt6I6TDgHNh+5pe9namyo6JnnhiGp7cw";

    @Test
    @DisplayName("a hash the broker computed is verified the way the broker verifies it")
    void agreesWithTheBroker() {
        assertThat(PasswordHash.of(BROKER_HASH_OF_SECRET, PasswordHash.SHA256).matches("secret")).isTrue();
        assertThat(PasswordHash.of(BROKER_313_HASH_OF_SECRET, PasswordHash.SHA256).matches("secret")).isTrue();
        assertThat(PasswordHash.of(BROKER_HASH_OF_SECRET, PasswordHash.SHA256).matches("Secret")).isFalse();
    }

    @Test
    @DisplayName("the same salt and password give the broker's exact bytes")
    void reproducesTheBrokerBytes() {
        byte[] salt = java.util.Arrays.copyOf(Base64.getDecoder().decode(BROKER_HASH_OF_SECRET), 4);

        assertThat(PasswordHash.compute(PasswordHash.SHA256, salt, "secret").hash())
                .isEqualTo(BROKER_HASH_OF_SECRET);
    }

    @Test
    @DisplayName("a four-byte salt and a full digest, fresh every time")
    void shapeAndSalt() {
        PasswordHash a = PasswordHash.sha256("same");
        PasswordHash b = PasswordHash.sha256("same");

        assertThat(Base64.getDecoder().decode(a.hash())).hasSize(4 + 32);
        assertThat(Base64.getDecoder().decode(PasswordHash.sha512("same").hash())).hasSize(4 + 64);
        // Two users with the same password must not have the same hash.
        assertThat(a).isNotEqualTo(b);
        assertThat(a.matches("same")).isTrue();
        assertThat(b.matches("same")).isTrue();
        assertThat(PasswordHash.sha512("same").algorithm()).isEqualTo(PasswordHash.SHA512);
    }

    @Test
    @DisplayName("the password is hashed as UTF-8")
    void utf8() {
        PasswordHash hash = PasswordHash.sha256("pässwörd");

        assertThat(hash.matches("pässwörd")).isTrue();
        assertThat(hash.matches("passwort")).isFalse();
    }

    @Test
    @DisplayName("toString does not print the hash")
    void doesNotLeak() {
        PasswordHash hash = PasswordHash.sha256("secret");

        assertThat(hash.toString()).doesNotContain(hash.hash()).contains(PasswordHash.SHA256);
    }

    @Test
    @DisplayName("refuses an algorithm it cannot compute and a value that is not a hash")
    void refusesNonsense() {
        assertThatThrownBy(() -> PasswordHash.of(BROKER_HASH_OF_SECRET, "rabbit_password_hashing_md5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PasswordHash.SHA256);
        assertThatThrownBy(() -> PasswordHash.of("not base64!", PasswordHash.SHA256))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
        assertThatThrownBy(() -> PasswordHash.of("AAAA", PasswordHash.SHA256))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("salt");
    }
}
