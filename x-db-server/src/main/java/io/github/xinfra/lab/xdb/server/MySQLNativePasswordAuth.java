package io.github.xinfra.lab.xdb.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Implements MySQL's {@code mysql_native_password} authentication.
 * <p>
 * Protocol:
 * <ul>
 *   <li>Server stores: {@code SHA1(SHA1(password))} (double hash)</li>
 *   <li>Client sends: {@code SHA1(password) XOR SHA1(scramble || SHA1(SHA1(password)))}</li>
 *   <li>Server verification:
 *     <ol>
 *       <li>{@code check = SHA1(scramble || storedDoubleHash)}</li>
 *       <li>{@code recovered = authResponse XOR check} → should be {@code SHA1(password)}</li>
 *       <li>Verify: {@code SHA1(recovered) == storedDoubleHash}</li>
 *     </ol>
 *   </li>
 * </ul>
 */
public final class MySQLNativePasswordAuth {

    private static final byte[] EMPTY_PASSWORD_DOUBLE_HASH;

    static {
        EMPTY_PASSWORD_DOUBLE_HASH = hashPassword("");
    }

    private MySQLNativePasswordAuth() {}

    /**
     * Compute SHA1(SHA1(password)) — the value stored server-side.
     */
    public static byte[] hashPassword(String password) {
        byte[] stage1 = sha1(password.getBytes(StandardCharsets.UTF_8));
        return sha1(stage1);
    }

    /**
     * Verify a client's auth response against the stored double hash.
     *
     * @param scramble         the 20-byte random challenge sent during handshake
     * @param authResponse     the client's 20-byte auth token
     * @param storedDoubleHash SHA1(SHA1(password)) from server config
     * @return true if the password matches
     */
    public static boolean verify(byte[] scramble, byte[] authResponse, byte[] storedDoubleHash) {
        if (authResponse.length != 20 || storedDoubleHash.length != 20) {
            return false;
        }

        byte[] check = sha1Concat(scramble, storedDoubleHash);

        byte[] recovered = xor(authResponse, check);

        byte[] recoveredDouble = sha1(recovered);

        return MessageDigest.isEqual(recoveredDouble, storedDoubleHash);
    }

    /**
     * Check whether the stored hash represents an empty (zero-length) password.
     */
    public static boolean isEmptyPassword(byte[] storedDoubleHash) {
        return MessageDigest.isEqual(storedDoubleHash, EMPTY_PASSWORD_DOUBLE_HASH);
    }

    /**
     * Simulate a client computing the auth response (used in tests).
     */
    static byte[] computeAuthResponse(String password, byte[] scramble) {
        byte[] stage1 = sha1(password.getBytes(StandardCharsets.UTF_8));
        byte[] stage2 = sha1(stage1);
        byte[] check = sha1Concat(scramble, stage2);
        return xor(stage1, check);
    }

    private static byte[] sha1(byte[] data) {
        return digest().digest(data);
    }

    private static byte[] sha1Concat(byte[] a, byte[] b) {
        MessageDigest md = digest();
        md.update(a);
        md.update(b);
        return md.digest();
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] result = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (byte) (a[i] ^ b[i]);
        }
        return result;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-1 not available", e);
        }
    }
}
