package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InExprTest {

    private final EvalContext ctx = new EvalContext();
    private final Row emptyRow = new Row(0);

    @Nested
    class InList {

        @Test
        void valueFound() {
            InExpr expr = new InExpr(
                    Constant.ofLong(2),
                    List.of(Constant.ofLong(1), Constant.ofLong(2), Constant.ofLong(3)),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(1);
        }

        @Test
        void valueNotFound() {
            InExpr expr = new InExpr(
                    Constant.ofLong(5),
                    List.of(Constant.ofLong(1), Constant.ofLong(2), Constant.ofLong(3)),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(0);
        }

        @Test
        void withStrings() {
            InExpr expr = new InExpr(
                    Constant.ofString("b"),
                    List.of(Constant.ofString("a"), Constant.ofString("b"), Constant.ofString("c")),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(1);
        }

        @Test
        void nullExprReturnsNull() {
            InExpr expr = new InExpr(
                    Constant.ofNull(),
                    List.of(Constant.ofLong(1), Constant.ofLong(2)),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).isNull()).isTrue();
        }

        @Test
        void nullInListAndNotFound() {
            InExpr expr = new InExpr(
                    Constant.ofLong(5),
                    List.of(Constant.ofLong(1), Constant.ofNull(), Constant.ofLong(3)),
                    false
            );
            // 5 not found, but list has null => result is null
            assertThat(expr.eval(ctx, emptyRow).isNull()).isTrue();
        }

        @Test
        void nullInListButFound() {
            InExpr expr = new InExpr(
                    Constant.ofLong(1),
                    List.of(Constant.ofLong(1), Constant.ofNull(), Constant.ofLong(3)),
                    false
            );
            // 1 found, null doesn't matter
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(1);
        }
    }

    @Nested
    class NotInList {

        @Test
        void valueFoundInNot() {
            InExpr expr = new InExpr(
                    Constant.ofLong(2),
                    List.of(Constant.ofLong(1), Constant.ofLong(2), Constant.ofLong(3)),
                    true
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(0);
        }

        @Test
        void valueNotFoundInNot() {
            InExpr expr = new InExpr(
                    Constant.ofLong(5),
                    List.of(Constant.ofLong(1), Constant.ofLong(2), Constant.ofLong(3)),
                    true
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(1);
        }
    }

    @Test
    void returnTypeIsBoolean() {
        InExpr expr = new InExpr(
                Constant.ofLong(1),
                List.of(Constant.ofLong(1)),
                false
        );
        assertThat(expr.returnType()).isEqualTo(DataType.BOOLEAN);
    }
}
