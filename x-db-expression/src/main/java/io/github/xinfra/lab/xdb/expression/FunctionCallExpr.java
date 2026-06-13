package io.github.xinfra.lab.xdb.expression;

import java.util.ArrayList;
import java.util.List;

public final class FunctionCallExpr implements Expression {
    private final String name;
    private final List<Expression> args;

    public FunctionCallExpr(String name, List<Expression> args) {
        this.name = name;
        this.args = args;
    }

    public String name() { return name; }
    public List<Expression> args() { return args; }

    @Override
    public Datum eval(EvalContext ctx, Row row) {
        List<Datum> evaluated = new ArrayList<>(args.size());
        for (Expression arg : args) evaluated.add(arg.eval(ctx, row));
        return ScalarFunctions.eval(name, evaluated, ctx);
    }

    @Override
    public DataType returnType() {
        return switch (name.toUpperCase()) {
            case "LENGTH", "CHAR_LENGTH" -> DataType.BIGINT;
            case "ABS", "CEIL", "FLOOR", "ROUND", "MOD" -> DataType.BIGINT;
            case "CONCAT", "UPPER", "LOWER", "TRIM", "SUBSTRING", "REPLACE", "LEFT", "RIGHT" -> DataType.VARCHAR;
            case "NOW", "CURDATE" -> DataType.DATETIME;
            case "YEAR", "MONTH", "DAY" -> DataType.INT;
            case "RAND", "SQRT", "POWER" -> DataType.DOUBLE;
            default -> DataType.VARCHAR;
        };
    }

    @Override
    public String toSQL() {
        StringBuilder sb = new StringBuilder(name).append("(");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(args.get(i).toSQL());
        }
        return sb.append(")").toString();
    }
}
