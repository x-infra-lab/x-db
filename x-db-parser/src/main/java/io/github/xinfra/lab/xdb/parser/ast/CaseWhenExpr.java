package io.github.xinfra.lab.xdb.parser.ast;

import java.util.List;

public class CaseWhenExpr implements ExprNode {
    private final ExprNode caseExpr;
    private final List<WhenClause> whenClauses;
    private final ExprNode elseExpr;

    public CaseWhenExpr(ExprNode caseExpr, List<WhenClause> whenClauses, ExprNode elseExpr) {
        this.caseExpr = caseExpr;
        this.whenClauses = whenClauses;
        this.elseExpr = elseExpr;
    }

    /** The CASE expression, or null for a searched CASE. */
    public ExprNode getCaseExpr() { return caseExpr; }
    public List<WhenClause> getWhenClauses() { return whenClauses; }
    public ExprNode getElseExpr() { return elseExpr; }
}
