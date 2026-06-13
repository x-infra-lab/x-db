package io.github.xinfra.lab.xdb.expression;

public interface Expression {
    Datum eval(EvalContext ctx, Row row);
    DataType returnType();
    default String toSQL() { return toString(); }
}
