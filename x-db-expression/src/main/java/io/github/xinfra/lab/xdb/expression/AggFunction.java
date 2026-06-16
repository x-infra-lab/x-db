package io.github.xinfra.lab.xdb.expression;

public interface AggFunction {
    enum Type { COUNT, SUM, AVG, MIN, MAX, GROUP_CONCAT }

    Type type();
    Expression arg();
    boolean distinct();
    void update(Datum value);
    void merge(AggFunction other);
    Datum result();
    AggFunction newInstance();
    DataType returnType();

    default long partialCount() { return 0; }
    default java.math.BigDecimal partialSum() { return java.math.BigDecimal.ZERO; }

    default void restorePartialState(long count, java.math.BigDecimal sum) {}
    default boolean hasPartialValue() { return !result().isNull(); }
}
