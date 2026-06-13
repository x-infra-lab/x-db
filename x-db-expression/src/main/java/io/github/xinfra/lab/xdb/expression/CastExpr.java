package io.github.xinfra.lab.xdb.expression;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class CastExpr implements Expression {
    private final Expression expr;
    private final DataType targetType;

    public CastExpr(Expression expr, DataType targetType) {
        this.expr = expr;
        this.targetType = targetType;
    }

    public Expression expr() { return expr; }
    public DataType targetType() { return targetType; }

    @Override
    public Datum eval(EvalContext ctx, Row row) {
        Datum v = expr.eval(ctx, row);
        if (v.isNull()) return Datum.nil();
        return TypeCoercion.coerce(v, targetType);
    }

    @Override public DataType returnType() { return targetType; }
}
