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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * A password hashed the way RabbitMQ's internal user database stores it.
 *
 * <pre>{@code
 * admin.createUser("orders-service", PasswordHash.sha256(secret), "monitoring");
 * }</pre>
 *
 * <p>The format is {@code base64(salt + digest(salt + utf8(password)))} with a random four-byte
 * salt — what {@code rabbit_password_hashing_sha256} and {@code rabbit_password_hashing_sha512}
 * compute, and what {@code password_hash} in a user or in a definitions file holds. Computed
 * here, the password never leaves this process: the broker is sent the hash and the name of the
 * algorithm and can verify a login against it, but cannot recover the password from it.
 *
 * <p>The management API also has {@code GET /api/auth/hash_password/{password}}, which this
 * class deliberately does not call. It puts the password in a URL path, which is the one part
 * of a request that access logs, proxies and browser histories all record.
 *
 * <p>{@link #toString()} does not print the hash. A hash is not the password, and it is still
 * enough to stand up a broker the password works against.
 */
public final class PasswordHash {

    /** RabbitMQ's default since 3.6. */
    public static final String SHA256 = "rabbit_password_hashing_sha256";

    /** Accepted by every broker that accepts {@link #SHA256}. */
    public static final String SHA512 = "rabbit_password_hashing_sha512";

    private static final int SALT_LENGTH = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String hash;
    private final String algorithm;

    private PasswordHash(String hash, String algorithm) {
        this.hash = hash;
        this.algorithm = algorithm;
    }

    /**
     * @param password the password to hash, which is not kept
     * @return its salted SHA-256 hash, with a fresh salt
     */
    public static PasswordHash sha256(String password) {
        return compute(SHA256, salt(), password);
    }

    /**
     * @param password the password to hash, which is not kept
     * @return its salted SHA-512 hash, with a fresh salt
     */
    public static PasswordHash sha512(String password) {
        return compute(SHA512, salt(), password);
    }

    /**
     * A hash computed elsewhere — read from a secrets store, or out of a definitions export.
     *
     * @param hash the base64 {@code password_hash}
     * @param algorithm {@link #SHA256} or {@link #SHA512}
     * @return it, checked to be base64 that is longer than a salt
     */
    public static PasswordHash of(String hash, String algorithm) {
        Objects.requireNonNull(hash, "hash");
        digestFor(Objects.requireNonNull(algorithm, "algorithm"));
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(hash);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("a password hash is base64, and this is not", e);
        }
        if (raw.length <= SALT_LENGTH) {
            throw new IllegalArgumentException("a password hash is a " + SALT_LENGTH
                    + "-byte salt followed by a digest, and this is only " + raw.length + " bytes");
        }
        return new PasswordHash(hash, algorithm);
    }

    /** Package-private so a test can fix the salt and compare against a known value. */
    static PasswordHash compute(String algorithm, byte[] salt, String password) {
        Objects.requireNonNull(password, "password");
        MessageDigest digest = digestFor(algorithm);
        digest.update(salt);
        digest.update(password.getBytes(StandardCharsets.UTF_8));
        byte[] hashed = digest.digest();
        byte[] out = Arrays.copyOf(salt, salt.length + hashed.length);
        System.arraycopy(hashed, 0, out, salt.length, hashed.length);
        return new PasswordHash(Base64.getEncoder().encodeToString(out), algorithm);
    }

    /**
     * @param password a candidate
     * @return whether the broker would accept it for this hash
     */
    public boolean matches(String password) {
        byte[] salt = Arrays.copyOf(Base64.getDecoder().decode(hash), SALT_LENGTH);
        return MessageDigest.isEqual(
                compute(algorithm, salt, password).hash.getBytes(StandardCharsets.US_ASCII),
                hash.getBytes(StandardCharsets.US_ASCII));
    }

    /** @return the base64 value sent as {@code password_hash} */
    public String hash() {
        return hash;
    }

    /** @return the value sent as {@code hashing_algorithm} */
    public String algorithm() {
        return algorithm;
    }

    private static byte[] salt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return salt;
    }

    private static MessageDigest digestFor(String algorithm) {
        String name;
        if (SHA256.equals(algorithm)) {
            name = "SHA-256";
        } else if (SHA512.equals(algorithm)) {
            name = "SHA-512";
        } else {
            // rabbit_password_hashing_md5 exists and is not offered: it is there for users
            // created before 3.6, and nothing new should be hashed with it.
            throw new IllegalArgumentException("'" + algorithm + "' is not a hashing algorithm"
                    + " this library computes; use " + SHA256 + " or " + SHA512);
        }
        try {
            return MessageDigest.getInstance(name);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(name + " is required of every Java platform", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PasswordHash
                && hash.equals(((PasswordHash) other).hash)
                && algorithm.equals(((PasswordHash) other).algorithm);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash, algorithm);
    }

    @Override
    public String toString() {
        return "PasswordHash{" + algorithm + "}";
    }
}
