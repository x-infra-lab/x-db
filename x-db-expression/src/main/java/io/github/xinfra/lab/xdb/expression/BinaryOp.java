package io.github.xinfra.lab.xdb.expression;

public final class BinaryOp implements Expression {
    public enum Op {
        ADD, SUB, MUL, DIV, MOD, INT_DIV,
        EQ, NE, LT, LE, GT, GE,
        AND, OR
    }

    private final Expression left;
    private final Expression right;
    private final Op op;

    public BinaryOp(Expression left, Op op, Expression right) {
        this.left = left;
        this.op = op;
        this.right = right;
    }

    public Expression left() { return left; }
    public Expression right() { return right; }
    public Op op() { return op; }

    @Override
    public Datum eval(EvalContext ctx, Row row) {
        Datum lv = left.eval(ctx, row);

        if (op == Op.AND) {
            if (!lv.isNull() && !lv.toBoolean()) return Datum.of(0L);
            Datum rv = right.eval(ctx, row);
            if (!rv.isNull() && !rv.toBoolean()) return Datum.of(0L);
            if (lv.isNull() || rv.isNull()) return Datum.nil();
            return Datum.of(1L);
        }
        if (op == Op.OR) {
            if (!lv.isNull() && lv.toBoolean()) return Datum.of(1L);
            Datum rv = right.eval(ctx, row);
            if (!rv.isNull() && rv.toBoolean()) return Datum.of(1L);
            if (lv.isNull() || rv.isNull()) return Datum.nil();
            return Datum.of(0L);
        }

        Datum rv = right.eval(ctx, row);
        if (lv.isNull() || rv.isNull()) return Datum.nil();

        if (op == Op.EQ || op == Op.NE || op == Op.LT || op == Op.LE
                || op == Op.GT || op == Op.GE) {
            int cmp = DatumComparator.compare(lv, rv);
            boolean result = switch (op) {
                case EQ -> cmp == 0;
                case NE -> cmp != 0;
                case LT -> cmp < 0;
                case LE -> cmp <= 0;
                case GT -> cmp > 0;
                case GE -> cmp >= 0;
                default -> false;
            };
            return Datum.of(result ? 1L : 0L);
        }

        return switch (op) {
            case ADD -> addDatum(lv, rv);
            case SUB -> subDatum(lv, rv);
            case MUL -> mulDatum(lv, rv);
            case DIV -> divDatum(lv, rv);
            case MOD -> modDatum(lv, rv);
            case INT_DIV -> {
                long d = rv.toLong();
                yield d == 0 ? Datum.nil() : Datum.of(lv.toLong() / d);
            }
            default -> throw new UnsupportedOperationException("Unknown op: " + op);
        };
    }

    private Datum addDatum(Datum a, Datum b) {
        if (a instanceof Datum.IntDatum && b instanceof Datum.IntDatum)
            return Datum.of(a.toLong() + b.toLong());
        return Datum.of(a.toDouble() + b.toDouble());
    }

    private Datum subDatum(Datum a, Datum b) {
        if (a instanceof Datum.IntDatum && b instanceof Datum.IntDatum)
            return Datum.of(a.toLong() - b.toLong());
        return Datum.of(a.toDouble() - b.toDouble());
    }

    private Datum mulDatum(Datum a, Datum b) {
        if (a instanceof Datum.IntDatum && b instanceof Datum.IntDatum)
            return Datum.of(a.toLong() * b.toLong());
        return Datum.of(a.toDouble() * b.toDouble());
    }

    private Datum divDatum(Datum a, Datum b) {
        double dv = b.toDouble();
        if (dv == 0.0) return Datum.nil();
        return Datum.of(a.toDouble() / dv);
    }

    private Datum modDatum(Datum a, Datum b) {
        if (a instanceof Datum.IntDatum && b instanceof Datum.IntDatum) {
            long bv = b.toLong();
            if (bv == 0) return Datum.nil();
            return Datum.of(a.toLong() % bv);
        }
        double bv = b.toDouble();
        if (bv == 0.0) return Datum.nil();
        return Datum.of(a.toDouble() % bv);
    }

    @Override
    public DataType returnType() {
        return switch (op) {
            case EQ, NE, LT, LE, GT, GE, AND, OR -> DataType.BOOLEAN;
            case ADD, SUB, MUL, DIV, MOD, INT_DIV -> {
                DataType lt = left.returnType();
                DataType rt = right.returnType();
                if (lt == DataType.DOUBLE || rt == DataType.DOUBLE) yield DataType.DOUBLE;
                if (lt == DataType.DECIMAL || rt == DataType.DECIMAL) yield DataType.DECIMAL;
                yield DataType.BIGINT;
            }
        };
    }

    @Override
    public String toSQL() {
        String opStr = switch (op) {
            case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/";
            case MOD -> "%"; case INT_DIV -> "DIV";
            case EQ -> "="; case NE -> "!="; case LT -> "<"; case LE -> "<=";
            case GT -> ">"; case GE -> ">="; case AND -> "AND"; case OR -> "OR";
        };
        return "(" + left.toSQL() + " " + opStr + " " + right.toSQL() + ")";
    }
}
