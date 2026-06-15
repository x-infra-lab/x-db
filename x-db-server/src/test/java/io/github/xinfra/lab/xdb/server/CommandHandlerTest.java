package io.github.xinfra.lab.xdb.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandHandlerTest {

    @Nested
    @DisplayName("escapeString")
    class EscapeStringTest {

        @Test
        @DisplayName("escapes single quote")
        void escapesSingleQuote() {
            assertThat(CommandHandler.escapeString("it's")).isEqualTo("it\\'s");
        }

        @Test
        @DisplayName("escapes double quote")
        void escapesDoubleQuote() {
            assertThat(CommandHandler.escapeString("say \"hello\"")).isEqualTo("say \\\"hello\\\"");
        }

        @Test
        @DisplayName("escapes backslash")
        void escapesBackslash() {
            assertThat(CommandHandler.escapeString("path\\to\\file")).isEqualTo("path\\\\to\\\\file");
        }

        @Test
        @DisplayName("escapes null byte")
        void escapesNullByte() {
            assertThat(CommandHandler.escapeString("before\0after")).isEqualTo("before\\0after");
        }

        @Test
        @DisplayName("escapes newline")
        void escapesNewline() {
            assertThat(CommandHandler.escapeString("line1\nline2")).isEqualTo("line1\\nline2");
        }

        @Test
        @DisplayName("escapes carriage return")
        void escapesCarriageReturn() {
            assertThat(CommandHandler.escapeString("line1\rline2")).isEqualTo("line1\\rline2");
        }

        @Test
        @DisplayName("escapes Ctrl-Z (\\032)")
        void escapesCtrlZ() {
            assertThat(CommandHandler.escapeString("before\032after")).isEqualTo("before\\Zafter");
        }

        @Test
        @DisplayName("normal string passes through unchanged")
        void normalStringUnchanged() {
            assertThat(CommandHandler.escapeString("hello world 123")).isEqualTo("hello world 123");
        }

        @Test
        @DisplayName("empty string passes through unchanged")
        void emptyStringUnchanged() {
            assertThat(CommandHandler.escapeString("")).isEqualTo("");
        }

        @Test
        @DisplayName("combined special characters are all escaped")
        void combinedSpecialCharacters() {
            String input = "O'Reilly\\\0\n\r\032";
            String expected = "O\\'Reilly\\\\\\0\\n\\r\\Z";
            assertThat(CommandHandler.escapeString(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("string with only special characters")
        void onlySpecialCharacters() {
            assertThat(CommandHandler.escapeString("'\\'")).isEqualTo("\\'\\\\\\'" );
        }

        @Test
        @DisplayName("SQL injection attempt is neutralized")
        void sqlInjectionAttemptNeutralized() {
            String malicious = "'; DROP TABLE users; --";
            String escaped = CommandHandler.escapeString(malicious);
            assertThat(escaped).isEqualTo("\\'; DROP TABLE users; --");
            String withoutEscapedQuotes = escaped.replace("\\'", "");
            assertThat(withoutEscapedQuotes).doesNotContain("'");
        }
    }

    @Nested
    @DisplayName("findParamPositions")
    class FindParamPositionsTest {

        @Test
        void simpleParams() {
            List<Integer> pos = CommandHandler.findParamPositions("SELECT ? FROM t WHERE id = ?");
            assertThat(pos).containsExactly(7, 27);
        }

        @Test
        void noParams() {
            assertThat(CommandHandler.findParamPositions("SELECT 1")).isEmpty();
        }

        @Test
        void questionMarkInsideSingleQuotedString() {
            List<Integer> pos = CommandHandler.findParamPositions("SELECT * FROM t WHERE name = '?' AND id = ?");
            assertThat(pos).hasSize(1);
            assertThat(pos.get(0)).isEqualTo(42);
        }

        @Test
        void questionMarkInsideDoubleQuotedString() {
            List<Integer> pos = CommandHandler.findParamPositions("SELECT * FROM t WHERE name = \"?\" AND id = ?");
            assertThat(pos).hasSize(1);
            assertThat(pos.get(0)).isEqualTo(42);
        }

        @Test
        void questionMarkInsideLineComment() {
            List<Integer> pos = CommandHandler.findParamPositions("SELECT ? -- is this a param?\nFROM t WHERE id = ?");
            assertThat(pos).hasSize(2);
        }

        @Test
        void questionMarkInsideBlockComment() {
            List<Integer> pos = CommandHandler.findParamPositions("SELECT ? /* what about ? */ FROM t WHERE id = ?");
            assertThat(pos).hasSize(2);
        }

        @Test
        void escapedQuoteInString() {
            List<Integer> pos = CommandHandler.findParamPositions("SELECT * FROM t WHERE name = 'it\\'s a ?' AND id = ?");
            assertThat(pos).hasSize(1);
        }
    }

    @Nested
    @DisplayName("substituteParams")
    class SubstituteParamsTest {

        @Test
        void simpleSubstitution() {
            String result = CommandHandler.substituteParams(
                    "SELECT * FROM t WHERE id = ?", new String[]{"42"});
            assertThat(result).isEqualTo("SELECT * FROM t WHERE id = '42'");
        }

        @Test
        void nullParam() {
            String result = CommandHandler.substituteParams(
                    "INSERT INTO t VALUES (?)", new String[]{null});
            assertThat(result).isEqualTo("INSERT INTO t VALUES (NULL)");
        }

        @Test
        void preservesQuestionMarkInsideString() {
            String result = CommandHandler.substituteParams(
                    "SELECT * FROM t WHERE name = '?' AND id = ?", new String[]{"1"});
            assertThat(result).isEqualTo("SELECT * FROM t WHERE name = '?' AND id = '1'");
        }

        @Test
        void preservesQuestionMarkInsideComment() {
            String result = CommandHandler.substituteParams(
                    "SELECT ? /* ? */ FROM t", new String[]{"1"});
            assertThat(result).isEqualTo("SELECT '1' /* ? */ FROM t");
        }

        @Test
        void escapesInjectionInParamValue() {
            String result = CommandHandler.substituteParams(
                    "SELECT * FROM t WHERE name = ?", new String[]{"'; DROP TABLE t; --"});
            assertThat(result).isEqualTo("SELECT * FROM t WHERE name = '\\'; DROP TABLE t; --'");
        }

        @Test
        void multipleParams() {
            String result = CommandHandler.substituteParams(
                    "INSERT INTO t (a, b) VALUES (?, ?)", new String[]{"x", "y"});
            assertThat(result).isEqualTo("INSERT INTO t (a, b) VALUES ('x', 'y')");
        }
    }

    @Nested
    @DisplayName("sanitizeForError")
    class SanitizeForErrorTest {

        @Test
        void normalStringUnchanged() {
            assertThat(CommandHandler.sanitizeForError("mydb")).isEqualTo("mydb");
        }

        @Test
        void nullReturnsEmpty() {
            assertThat(CommandHandler.sanitizeForError(null)).isEqualTo("");
        }

        @Test
        void controlCharsReplaced() {
            assertThat(CommandHandler.sanitizeForError("db\n\r\0name")).isEqualTo("db???name");
        }

        @Test
        void longStringTruncated() {
            String long_ = "a".repeat(100);
            String result = CommandHandler.sanitizeForError(long_);
            assertThat(result).hasSize(67); // 64 + "..."
            assertThat(result).endsWith("...");
        }

        @Test
        void highUnicodeReplaced() {
            assertThat(CommandHandler.sanitizeForError("dbname")).isEqualTo("db?name");
        }
    }
}
