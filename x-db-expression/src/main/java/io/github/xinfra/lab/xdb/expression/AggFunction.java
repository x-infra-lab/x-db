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
}
