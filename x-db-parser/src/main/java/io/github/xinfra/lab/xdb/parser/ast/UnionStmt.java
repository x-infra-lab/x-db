package io.github.xinfra.lab.xdb.parser.ast;

import java.util.List;

public class UnionStmt implements Statement {
    private final List<SelectStmt> selects;
    private final List<Boolean> unionAll;

    public UnionStmt(List<SelectStmt> selects, List<Boolean> unionAll) {
        this.selects = selects;
        this.unionAll = unionAll;
    }

    public List<SelectStmt> getSelects() { return selects; }
    public List<Boolean> getUnionAll() { return unionAll; }
}
