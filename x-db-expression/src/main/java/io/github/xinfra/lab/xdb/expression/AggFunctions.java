package io.github.xinfra.lab.xdb.expression;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;

public final class AggFunctions {
    private AggFunctions() {}

    public static AggFunction create(AggFunction.Type type, Expression arg, boolean distinct) {
        return switch (type) {
            case COUNT -> new CountAgg(arg, distinct);
            case SUM -> new SumAgg(arg, distinct);
            case AVG -> new AvgAgg(arg, distinct);
            case MIN -> new MinAgg(arg, distinct);
            case MAX -> new MaxAgg(arg, distinct);
            case GROUP_CONCAT -> new GroupConcatAgg(arg, distinct);
        };
    }

    static class CountAgg implements AggFunction {
        private final Expression arg;
        private final boolean distinct;
        private long count;
        private final Set<String> seen;

        CountAgg(Expression arg, boolean distinct) {
            this.arg = arg; this.distinct = distinct;
            this.seen = distinct ? new HashSet<>() : null;
        }

        @Override public Type type() { return Type.COUNT; }
        @Override public Expression arg() { return arg; }
        @Override public boolean distinct() { return distinct; }
        @Override public void update(Datum value) {
            if (value.isNull()) return;
            if (distinct && !seen.add(value.toStringValue())) return;
            count++;
        }
        @Override public void merge(AggFunction other) { count += ((CountAgg) other).count; }
        @Override public Datum result() { return Datum.of(count); }
        @Override public AggFunction newInstance() { return new CountAgg(arg, distinct); }
        @Override public DataType returnType() { return DataType.BIGINT; }
        @Override public long partialCount() { return count; }
        @Override public void restorePartialState(long c, BigDecimal s) { this.count = c; }
    }

    static class SumAgg implements AggFunction {
        private final Expression arg;
        private final boolean distinct;
        private BigDecimal sum = BigDecimal.ZERO;
        private boolean hasValue;
        private final Set<String> seen;

        SumAgg(Expression arg, boolean distinct) {
            this.arg = arg; this.distinct = distinct;
            this.seen = distinct ? new HashSet<>() : null;
        }

        @Override public Type type() { return Type.SUM; }
        @Override public Expression arg() { return arg; }
        @Override public boolean distinct() { return distinct; }
        @Override public void update(Datum value) {
            if (value.isNull()) return;
            if (distinct && !seen.add(value.toStringValue())) return;
            hasValue = true;
            if (value instanceof Datum.IntDatum i) sum = sum.add(BigDecimal.valueOf(i.value()));
            else if (value instanceof Datum.DecimalDatum d) sum = sum.add(d.value());
            else sum = sum.add(BigDecimal.valueOf(value.toDouble()));
        }
        @Override public void merge(AggFunction other) {
            SumAgg o = (SumAgg) other;
            if (o.hasValue) { hasValue = true; sum = sum.add(o.sum); }
        }
        @Override public Datum result() { return hasValue ? Datum.of(sum) : Datum.nil(); }
        @Override public AggFunction newInstance() { return new SumAgg(arg, distinct); }
        @Override public DataType returnType() { return DataType.DECIMAL; }
        @Override public BigDecimal partialSum() { return sum; }
        @Override public void restorePartialState(long c, BigDecimal s) {
            if (s != null) { this.hasValue = true; this.sum = s; }
        }
    }

    static class AvgAgg implements AggFunction {
        private final Expression arg;
        private final boolean distinct;
        private BigDecimal sum = BigDecimal.ZERO;
        private long count;
        private final Set<String> seen;

        AvgAgg(Expression arg, boolean distinct) {
            this.arg = arg; this.distinct = distinct;
            this.seen = distinct ? new HashSet<>() : null;
        }

        @Override public Type type() { return Type.AVG; }
        @Override public Expression arg() { return arg; }
        @Override public boolean distinct() { return distinct; }
        @Override public void update(Datum value) {
            if (value.isNull()) return;
            if (distinct && !seen.add(value.toStringValue())) return;
            count++;
            if (value instanceof Datum.IntDatum i) sum = sum.add(BigDecimal.valueOf(i.value()));
            else if (value instanceof Datum.DecimalDatum d) sum = sum.add(d.value());
            else sum = sum.add(BigDecimal.valueOf(value.toDouble()));
        }
        @Override public void merge(AggFunction other) {
            AvgAgg o = (AvgAgg) other;
            sum = sum.add(o.sum);
            count += o.count;
        }
        @Override public Datum result() {
            return count > 0 ? Datum.of(sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP)) : Datum.nil();
        }
        @Override public AggFunction newInstance() { return new AvgAgg(arg, distinct); }
        @Override public DataType returnType() { return DataType.DECIMAL; }
        @Override public long partialCount() { return count; }
        @Override public BigDecimal partialSum() { return sum; }
        @Override public void restorePartialState(long c, BigDecimal s) {
            this.count = c;
            if (s != null) this.sum = s;
        }
    }

    static class MinAgg implements AggFunction {
        private final Expression arg;
        private final boolean distinct;
        private Datum min = Datum.nil();

        MinAgg(Expression arg, boolean distinct) { this.arg = arg; this.distinct = distinct; }

        @Override public Type type() { return Type.MIN; }
        @Override public Expression arg() { return arg; }
        @Override public boolean distinct() { return distinct; }
        @Override public void update(Datum value) {
            if (value.isNull()) return;
            if (min.isNull() || DatumComparator.compare(value, min) < 0) min = value;
        }
        @Override public void merge(AggFunction other) { update(((MinAgg) other).min); }
        @Override public Datum result() { return min; }
        @Override public AggFunction newInstance() { return new MinAgg(arg, distinct); }
        @Override public DataType returnType() { return arg != null ? arg.returnType() : DataType.NULL; }
    }

    static class MaxAgg implements AggFunction {
        private final Expression arg;
        private final boolean distinct;
        private Datum max = Datum.nil();

        MaxAgg(Expression arg, boolean distinct) { this.arg = arg; this.distinct = distinct; }

        @Override public Type type() { return Type.MAX; }
        @Override public Expression arg() { return arg; }
        @Override public boolean distinct() { return distinct; }
        @Override public void update(Datum value) {
            if (value.isNull()) return;
            if (max.isNull() || DatumComparator.compare(value, max) > 0) max = value;
        }
        @Override public void merge(AggFunction other) { update(((MaxAgg) other).max); }
        @Override public Datum result() { return max; }
        @Override public AggFunction newInstance() { return new MaxAgg(arg, distinct); }
        @Override public DataType returnType() { return arg != null ? arg.returnType() : DataType.NULL; }
    }

    static class GroupConcatAgg implements AggFunction {
        private final Expression arg;
        private final boolean distinct;
        private final StringBuilder sb = new StringBuilder();
        private boolean first = true;
        private final Set<String> seen;

        GroupConcatAgg(Expression arg, boolean distinct) {
            this.arg = arg; this.distinct = distinct;
            this.seen = distinct ? new HashSet<>() : null;
        }

        @Override public Type type() { return Type.GROUP_CONCAT; }
        @Override public Expression arg() { return arg; }
        @Override public boolean distinct() { return distinct; }
        @Override public void update(Datum value) {
            if (value.isNull()) return;
            if (distinct && !seen.add(value.toStringValue())) return;
            if (!first) sb.append(",");
            sb.append(value.toStringValue());
            first = false;
        }
        @Override public void merge(AggFunction other) {
            GroupConcatAgg o = (GroupConcatAgg) other;
            if (!o.first) {
                if (!first) sb.append(",");
                sb.append(o.sb);
                first = false;
            }
        }
        @Override public Datum result() { return first ? Datum.nil() : Datum.of(sb.toString()); }
        @Override public AggFunction newInstance() { return new GroupConcatAgg(arg, distinct); }
        @Override public DataType returnType() { return DataType.VARCHAR; }
    }
}
