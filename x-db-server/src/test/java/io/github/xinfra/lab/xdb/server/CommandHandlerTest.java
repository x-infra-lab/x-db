package io.github.xinfra.lab.xdb.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CommandHandlerTest {

    // ------------------------------------------------------------------
    //  Reflection helper – escapeString is private static
    // ------------------------------------------------------------------

    private static String callEscapeString(String val) throws Exception {
        Method method = CommandHandler.class.getDeclaredMethod("escapeString", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, val);
    }

    // ------------------------------------------------------------------
    //  escapeString tests (Fix 11 – SQL injection prevention)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("escapeString")
    class EscapeStringTest {

        @Test
        @DisplayName("escapes single quote")
        void escapesSingleQuote() throws Exception {
            assertThat(callEscapeString("it's")).isEqualTo("it\\'s");
        }

        @Test
        @DisplayName("escapes backslash")
        void escapesBackslash() throws Exception {
            assertThat(callEscapeString("path\\to\\file")).isEqualTo("path\\\\to\\\\file");
        }

        @Test
        @DisplayName("escapes null byte")
        void escapesNullByte() throws Exception {
            assertThat(callEscapeString("before\0after")).isEqualTo("before\\0after");
        }

        @Test
        @DisplayName("escapes newline")
        void escapesNewline() throws Exception {
            assertThat(callEscapeString("line1\nline2")).isEqualTo("line1\\nline2");
        }

        @Test
        @DisplayName("escapes carriage return")
        void escapesCarriageReturn() throws Exception {
            assertThat(callEscapeString("line1\rline2")).isEqualTo("line1\\rline2");
        }

        @Test
        @DisplayName("escapes Ctrl-Z (\\032)")
        void escapesCtrlZ() throws Exception {
            assertThat(callEscapeString("before\032after")).isEqualTo("before\\Zafter");
        }

        @Test
        @DisplayName("normal string passes through unchanged")
        void normalStringUnchanged() throws Exception {
            assertThat(callEscapeString("hello world 123")).isEqualTo("hello world 123");
        }

        @Test
        @DisplayName("empty string passes through unchanged")
        void emptyStringUnchanged() throws Exception {
            assertThat(callEscapeString("")).isEqualTo("");
        }

        @Test
        @DisplayName("combined special characters are all escaped")
        void combinedSpecialCharacters() throws Exception {
            // Input contains every escapable character: ' \ \0 \n \r \032
            String input = "O'Reilly\\\0\n\r\032";
            String expected = "O\\'Reilly\\\\\\0\\n\\r\\Z";
            assertThat(callEscapeString(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("string with only special characters")
        void onlySpecialCharacters() throws Exception {
            assertThat(callEscapeString("'\\'")).isEqualTo("\\'\\\\\\'" );
        }

        @Test
        @DisplayName("SQL injection attempt is neutralized")
        void sqlInjectionAttemptNeutralized() throws Exception {
            String malicious = "'; DROP TABLE users; --";
            String escaped = callEscapeString(malicious);
            // The single quote is escaped with a preceding backslash
            assertThat(escaped).isEqualTo("\\'; DROP TABLE users; --");
            // Verify no unescaped single quote exists: remove all \' sequences,
            // then the result should contain no single quotes at all.
            String withoutEscapedQuotes = escaped.replace("\\'", "");
            assertThat(withoutEscapedQuotes).doesNotContain("'");
        }
    }
}
