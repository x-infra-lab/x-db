package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LikeExprTest {

    private final EvalContext ctx = new EvalContext();
    private final Row emptyRow = new Row(0);

    private Datum evalLike(String value, String pattern, boolean not) {
        return new LikeExpr(Constant.ofString(value), Constant.ofString(pattern), not)
                .eval(ctx, emptyRow);
    }

    @Nested
    class PatternMatching {

        @Test
        void exactMatch() {
            assertThat(evalLike("hello", "hello", false).toLong()).isEqualTo(1);
        }

        @Test
        void noMatch() {
            assertThat(evalLike("hello", "world", false).toLong()).isEqualTo(0);
        }

        @Test
        void percentWildcard() {
            assertThat(evalLike("hello world", "hello%", false).toLong()).isEqualTo(1);
            assertThat(evalLike("hello world", "%world", false).toLong()).isEqualTo(1);
            assertThat(evalLike("hello world", "%lo wo%", false).toLong()).isEqualTo(1);
            assertThat(evalLike("hello world", "%xyz%", false).toLong()).isEqualTo(0);
        }

        @Test
        void underscoreWildcard() {
            assertThat(evalLike("abc", "a_c", false).toLong()).isEqualTo(1);
            assertThat(evalLike("abc", "___", false).toLong()).isEqualTo(1);
            assertThat(evalLike("abcd", "a_c", false).toLong()).isEqualTo(0);
        }

        @Test
        void combinedWildcards() {
            assertThat(evalLike("hello", "h_l%", false).toLong()).isEqualTo(1);
            assertThat(evalLike("hi", "h_l%", false).toLong()).isEqualTo(0);
        }

        @Test
        void emptyPattern() {
            assertThat(evalLike("", "", false).toLong()).isEqualTo(1);
            assertThat(evalLike("hello", "", false).toLong()).isEqualTo(0);
        }

        @Test
        void percentMatchesEmpty() {
            assertThat(evalLike("", "%", false).toLong()).isEqualTo(1);
        }

        @Test
        void caseInsensitive() {
            assertThat(evalLike("Hello", "hello", false).toLong()).isEqualTo(1);
            assertThat(evalLike("HELLO WORLD", "hello%", false).toLong()).isEqualTo(1);
        }

        @Test
        void escapedCharacters() {
            assertThat(evalLike("100%", "100\\%", false).toLong()).isEqualTo(1);
            assertThat(evalLike("100x", "100\\%", false).toLong()).isEqualTo(0);
        }
    }

    @Nested
    class NotLike {

        @Test
        void matchBecomesNoMatch() {
            assertThat(evalLike("hello", "hello", true).toLong()).isEqualTo(0);
        }

        @Test
        void noMatchBecomesMatch() {
            assertThat(evalLike("hello", "world", true).toLong()).isEqualTo(1);
        }
    }

    @Nested
    class NullHandling {

        @Test
        void nullExprReturnsNull() {
            LikeExpr expr = new LikeExpr(Constant.ofNull(), Constant.ofString("hello"), false);
            assertThat(expr.eval(ctx, emptyRow).isNull()).isTrue();
        }

        @Test
        void nullPatternReturnsNull() {
            LikeExpr expr = new LikeExpr(Constant.ofString("hello"), Constant.ofNull(), false);
            assertThat(expr.eval(ctx, emptyRow).isNull()).isTrue();
        }
    }

    @Nested
    class LikeMatchHelper {

        @Test
        void directLikeMatchCalls() {
            assertThat(LikeExpr.likeMatch("test", "te%")).isTrue();
            assertThat(LikeExpr.likeMatch("test", "te_t")).isTrue();
            assertThat(LikeExpr.likeMatch("test", "xyz")).isFalse();
        }
    }

    @Test
    void returnTypeIsBoolean() {
        LikeExpr expr = new LikeExpr(Constant.ofString("a"), Constant.ofString("%"), false);
        assertThat(expr.returnType()).isEqualTo(DataType.BOOLEAN);
    }
}
