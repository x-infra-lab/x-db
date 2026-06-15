package io.github.xinfra.lab.xdb.server;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLNativePasswordAuthTest {

    @Test
    void hashPasswordDeterministic() {
        byte[] hash1 = MySQLNativePasswordAuth.hashPassword("secret");
        byte[] hash2 = MySQLNativePasswordAuth.hashPassword("secret");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(20);
    }

    @Test
    void differentPasswordsDifferentHashes() {
        byte[] hash1 = MySQLNativePasswordAuth.hashPassword("password1");
        byte[] hash2 = MySQLNativePasswordAuth.hashPassword("password2");
        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void verifyCorrectPassword() {
        String password = "test-password-123";
        byte[] scramble = randomScramble();
        byte[] storedHash = MySQLNativePasswordAuth.hashPassword(password);
        byte[] authResponse = MySQLNativePasswordAuth.computeAuthResponse(password, scramble);

        assertThat(MySQLNativePasswordAuth.verify(scramble, authResponse, storedHash)).isTrue();
    }

    @Test
    void verifyWrongPassword() {
        byte[] scramble = randomScramble();
        byte[] storedHash = MySQLNativePasswordAuth.hashPassword("correct-password");
        byte[] authResponse = MySQLNativePasswordAuth.computeAuthResponse("wrong-password", scramble);

        assertThat(MySQLNativePasswordAuth.verify(scramble, authResponse, storedHash)).isFalse();
    }

    @Test
    void verifyWithDifferentScrambles() {
        String password = "my-password";
        byte[] scramble1 = randomScramble();
        byte[] scramble2 = randomScramble();
        byte[] storedHash = MySQLNativePasswordAuth.hashPassword(password);

        byte[] response1 = MySQLNativePasswordAuth.computeAuthResponse(password, scramble1);
        byte[] response2 = MySQLNativePasswordAuth.computeAuthResponse(password, scramble2);

        assertThat(response1).isNotEqualTo(response2);
        assertThat(MySQLNativePasswordAuth.verify(scramble1, response1, storedHash)).isTrue();
        assertThat(MySQLNativePasswordAuth.verify(scramble2, response2, storedHash)).isTrue();
        assertThat(MySQLNativePasswordAuth.verify(scramble1, response2, storedHash)).isFalse();
    }

    @Test
    void emptyPasswordHash() {
        byte[] emptyHash = MySQLNativePasswordAuth.hashPassword("");
        assertThat(MySQLNativePasswordAuth.isEmptyPassword(emptyHash)).isTrue();

        byte[] nonEmptyHash = MySQLNativePasswordAuth.hashPassword("notempty");
        assertThat(MySQLNativePasswordAuth.isEmptyPassword(nonEmptyHash)).isFalse();
    }

    @Test
    void verifyRejectsWrongLengthResponse() {
        byte[] scramble = randomScramble();
        byte[] storedHash = MySQLNativePasswordAuth.hashPassword("password");
        byte[] tooShort = new byte[10];

        assertThat(MySQLNativePasswordAuth.verify(scramble, tooShort, storedHash)).isFalse();
    }

    @Test
    void authResponseHasCorrectLength() {
        String password = "testing";
        byte[] scramble = randomScramble();
        byte[] response = MySQLNativePasswordAuth.computeAuthResponse(password, scramble);
        assertThat(response).hasSize(20);
    }

    private byte[] randomScramble() {
        byte[] scramble = new byte[20];
        ThreadLocalRandom.current().nextBytes(scramble);
        for (int i = 0; i < scramble.length; i++) {
            if (scramble[i] == 0) scramble[i] = (byte) (i + 1);
        }
        return scramble;
    }
}
