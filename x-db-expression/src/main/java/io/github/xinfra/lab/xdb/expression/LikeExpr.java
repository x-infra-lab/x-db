package io.github.xinfra.lab.xdb.expression;

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
        boolean matches = matchDirect(v.toStringValue(), p.toStringValue());
        return Datum.of((matches ^ not) ? 1L : 0L);
    }

    static boolean matchDirect(String str, String pat) {
        int si = 0, pi = 0;
        int sLen = str.length(), pLen = pat.length();
        int starPi = -1, starSi = -1;

        while (si < sLen) {
            if (pi < pLen && pat.charAt(pi) == '%') {
                starPi = pi;
                starSi = si;
                pi++;
            } else if (pi < pLen && pat.charAt(pi) == '\\' && pi + 1 < pLen) {
                if (Character.toLowerCase(str.charAt(si)) == Character.toLowerCase(pat.charAt(pi + 1))) {
                    si++;
                    pi += 2;
                } else if (starPi != -1) {
                    pi = starPi + 1;
                    starSi++;
                    si = starSi;
                } else {
                    return false;
                }
            } else if (pi < pLen && (pat.charAt(pi) == '_'
                    || Character.toLowerCase(str.charAt(si)) == Character.toLowerCase(pat.charAt(pi)))) {
                si++;
                pi++;
            } else if (starPi != -1) {
                pi = starPi + 1;
                starSi++;
                si = starSi;
            } else {
                return false;
            }
        }

        while (pi < pLen && pat.charAt(pi) == '%') {
            pi++;
        }

        return pi == pLen;
    }

    static boolean likeMatch(String str, String pattern) {
        return matchDirect(str, pattern);
    }

    @Override public DataType returnType() { return DataType.BOOLEAN; }
}
