package io.github.xinfra.lab.xdb.expression;

import java.util.regex.Pattern;

public final class LikeExpr implements Expression {
    private final Expression expr;
    private final Expression pattern;
    private final boolean not;

    public LikeExpr(Expression expr, Expression pattern, boolean not) {
        this.expr = expr;
        this.pattern = pattern;
        this.not = not;
    }

    @Override
    public Datum eval(EvalContext ctx, Row row) {
        Datum v = expr.eval(ctx, row);
        Datum p = pattern.eval(ctx, row);
        if (v.isNull() || p.isNull()) return Datum.nil();
        boolean matches = likeMatch(v.toStringValue(), p.toStringValue());
        return Datum.of((matches ^ not) ? 1L : 0L);
    }

    static boolean likeMatch(String str, String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '%' -> regex.append(".*");
                case '_' -> regex.append(".");
                case '\\' -> {
                    if (i + 1 < pattern.length()) {
                        regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
                    }
                }
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(str).matches();
    }

    @Override public DataType returnType() { return DataType.BOOLEAN; }
}
