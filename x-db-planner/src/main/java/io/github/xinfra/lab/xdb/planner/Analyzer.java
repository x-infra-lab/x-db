package io.github.xinfra.lab.xdb.planner;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.expression.*;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.meta.SchemaState;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.parser.ast.*;
import io.github.xinfra.lab.xdb.planner.logical.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Analyzer {
    private final InfoSchema infoSchema;
    private final String currentDatabase;

    public Analyzer(InfoSchema infoSchema, String currentDatabase) {
        this.infoSchema = infoSchema;
        this.currentDatabase = currentDatabase;
    }

    public LogicalPlan analyze(Statement stmt) {
        if (stmt instanceof SelectStmt sel) return analyzeSelect(sel);
        if (stmt instanceof UnionStmt union) return analyzeUnion(union);
        if (stmt instanceof InsertStmt ins) return analyzeInsert(ins);
        if (stmt instanceof UpdateStmt upd) return analyzeUpdate(upd);
        if (stmt instanceof DeleteStmt del) return analyzeDelete(del);
        if (stmt instanceof ShowStmt show) return analyzeShow(show);
        if (stmt instanceof DescribeStmt desc) return analyzeDescribe(desc);
        if (stmt instanceof ExplainStmt explain) return analyzeExplain(explain);
        throw new UnsupportedOperationException("Cannot analyze: " + stmt.getClass().getSimpleName());
    }

    private LogicalPlan analyzeUnion(UnionStmt stmt) {
        List<LogicalPlan> children = new ArrayList<>();
        for (SelectStmt sel : stmt.getSelects()) {
            children.add(analyzeSelect(sel));
        }

        int columnCount = children.get(0).outputSchema().size();
        for (int i = 1; i < children.size(); i++) {
            if (children.get(i).outputSchema().size() != columnCount) {
                throw XDBException.internal(
                        "Each UNION query must have the same number of columns");
            }
        }

        List<ColumnInfo> outputCols = new ArrayList<>(children.get(0).outputSchema());

        boolean all = true;
        for (Boolean isAll : stmt.getUnionAll()) {
            if (!isAll) { all = false; break; }
        }

        return new LogicalUnion(children, all, outputCols);
    }

    private LogicalPlan analyzeSelect(SelectStmt stmt) {
        LogicalPlan plan;

        if (stmt.getFrom() == null) {
            plan = new LogicalDual();
        } else {
            plan = analyzeTableRef(stmt.getFrom());
        }

        if (stmt.getWhere() != null) {
            Expression whereExpr = resolveExpr(stmt.getWhere(), plan);
            plan = new LogicalSelection(plan, Collections.singletonList(whereExpr));
        }

        boolean hasAgg = hasAggregation(stmt);
        if (hasAgg || (stmt.getGroupBy() != null && !stmt.getGroupBy().isEmpty())) {
            plan = analyzeAggregation(stmt, plan);
        }

        if (stmt.getHaving() != null) {
            Expression havingExpr = resolveExpr(stmt.getHaving(), plan);
            plan = new LogicalSelection(plan, Collections.singletonList(havingExpr));
        }

        List<Expression> projExprs = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        List<ColumnInfo> outputCols = new ArrayList<>();
        analyzeSelectItems(stmt, plan, projExprs, aliases, outputCols);
        plan = new LogicalProjection(plan, projExprs, aliases, outputCols);

        if (stmt.getOrderBy() != null && !stmt.getOrderBy().isEmpty()) {
            List<Expression> orderExprs = new ArrayList<>();
            List<Boolean> ascending = new ArrayList<>();
            for (OrderByItem item : stmt.getOrderBy()) {
                orderExprs.add(resolveExpr(item.getExpression(), plan));
                ascending.add(item.isAsc());
            }
            plan = new LogicalSort(plan, orderExprs, ascending);
        }

        if (stmt.getLimit() != null) {
            long limitVal = evalLongLiteral(stmt.getLimit());
            long offsetVal = stmt.getOffset() != null ? evalLongLiteral(stmt.getOffset()) : 0;
            plan = new LogicalLimit(plan, limitVal, offsetVal);
        }

        return plan;
    }

    private LogicalPlan analyzeTableRef(TableRef ref) {
        if (ref instanceof TableRef.SimpleTableRef simple) {
            TableInfo table = resolveTable(simple.getName());
            return new LogicalTableScan(table, simple.getAlias());
        }
        if (ref instanceof TableRef.JoinTableRef join) {
            LogicalPlan left = analyzeTableRef(join.getLeft());
            LogicalPlan right = analyzeTableRef(join.getRight());
            io.github.xinfra.lab.xdb.planner.logical.JoinType joinType = convertJoinType(join.getJoinType());
            Expression condition = join.getCondition() != null
                    ? resolveExpr(join.getCondition(), mergeSchemas(left, right))
                    : null;
            return new LogicalJoin(left, right, joinType, condition);
        }
        if (ref instanceof TableRef.SubqueryTableRef sub) {
            return analyzeSelect(sub.getQuery());
        }
        throw new UnsupportedOperationException("Unknown table ref: " + ref.getClass().getSimpleName());
    }

    private LogicalPlan analyzeAggregation(SelectStmt stmt, LogicalPlan child) {
        List<Expression> groupByExprs = new ArrayList<>();
        if (stmt.getGroupBy() != null) {
            for (ExprNode expr : stmt.getGroupBy()) {
                groupByExprs.add(resolveExpr(expr, child));
            }
        }

        List<AggFunction> aggFunctions = new ArrayList<>();
        List<ColumnInfo> outputCols = new ArrayList<>();

        for (Expression gbExpr : groupByExprs) {
            ColumnInfo col = new ColumnInfo();
            col.setName(gbExpr.toSQL());
            col.setType(gbExpr.returnType());
            outputCols.add(col);
        }

        collectAggFunctions(stmt, child, aggFunctions, outputCols);

        return new LogicalAggregation(child, groupByExprs, aggFunctions, outputCols);
    }

    private void collectAggFunctions(SelectStmt stmt, LogicalPlan child,
                                     List<AggFunction> aggFunctions, List<ColumnInfo> outputCols) {
        for (SelectItem item : stmt.getSelectItems()) {
            ExprNode expr = item.getExpression();
            if (expr instanceof io.github.xinfra.lab.xdb.parser.ast.FunctionCallExpr fnExpr) {
                AggFunction.Type aggType = getAggType(fnExpr.getName());
                if (aggType != null) {
                    Expression arg;
                    if (fnExpr.getArgs().isEmpty() || fnExpr.getArgs().get(0) instanceof StarExpr) {
                        arg = null;
                    } else {
                        arg = resolveExpr(fnExpr.getArgs().get(0), child);
                    }
                    AggFunction agg = AggFunctions.create(aggType, arg, fnExpr.isDistinct());
                    aggFunctions.add(agg);
                    ColumnInfo col = new ColumnInfo();
                    col.setName(item.getAlias() != null ? item.getAlias() : aggCanonicalName(fnExpr));
                    col.setType(agg.returnType());
                    outputCols.add(col);
                }
            }
        }
    }

    private String aggCanonicalName(io.github.xinfra.lab.xdb.parser.ast.FunctionCallExpr fn) {
        String name = fn.getName().toUpperCase();
        if (fn.getArgs().isEmpty()) {
            return name + "(*)";
        }
        StringBuilder sb = new StringBuilder(name).append("(");
        if (fn.isDistinct()) sb.append("DISTINCT ");
        for (int i = 0; i < fn.getArgs().size(); i++) {
            if (i > 0) sb.append(", ");
            ExprNode arg = fn.getArgs().get(i);
            if (arg instanceof ColumnRefExpr ref) {
                sb.append(ref.getColumnName());
            } else if (arg instanceof LiteralExpr lit) {
                sb.append(lit.getValue());
            } else {
                sb.append("*");
            }
        }
        return sb.append(")").toString();
    }

    private LogicalPlan analyzeInsert(InsertStmt stmt) {
        TableInfo table = resolveTable(stmt.getTableName());
        List<ColumnInfo> targetCols;
        if (stmt.getColumns() != null && !stmt.getColumns().isEmpty()) {
            targetCols = new ArrayList<>();
            for (String colName : stmt.getColumns()) {
                ColumnInfo col = table.getColumn(colName);
                if (col == null) throw XDBException.badField(colName);
                targetCols.add(col);
            }
        } else {
            targetCols = table.getPublicColumns();
        }

        List<List<Expression>> rows = new ArrayList<>();
        for (List<ExprNode> valueRow : stmt.getValues()) {
            List<Expression> row = new ArrayList<>();
            for (ExprNode val : valueRow) {
                row.add(resolveExpr(val, null));
            }
            rows.add(row);
        }

        return new LogicalInsert(table, targetCols, rows);
    }

    private LogicalPlan analyzeUpdate(UpdateStmt stmt) {
        TableInfo table = resolveTable(stmt.getTableName());
        LogicalPlan scan;
        if (stmt.getFrom() != null) {
            scan = analyzeTableRef(stmt.getFrom());
        } else {
            scan = new LogicalTableScan(table, null);
        }

        if (stmt.getWhere() != null) {
            Expression whereExpr = resolveExpr(stmt.getWhere(), scan);
            scan = new LogicalSelection(scan, Collections.singletonList(whereExpr));
        }

        List<ColumnInfo> updateCols = new ArrayList<>();
        List<Expression> updateVals = new ArrayList<>();
        for (Assignment assign : stmt.getAssignments()) {
            ColumnInfo col = table.getColumn(assign.getColumn());
            if (col == null) throw XDBException.badField(assign.getColumn());
            updateCols.add(col);
            updateVals.add(resolveExpr(assign.getValue(), scan));
        }

        return new LogicalUpdate(scan, table, updateCols, updateVals);
    }

    private LogicalPlan analyzeDelete(DeleteStmt stmt) {
        TableInfo table = resolveTable(stmt.getTableName());
        LogicalPlan scan;
        if (stmt.getFrom() != null) {
            scan = analyzeTableRef(stmt.getFrom());
        } else {
            scan = new LogicalTableScan(table, null);
        }

        if (stmt.getWhere() != null) {
            Expression whereExpr = resolveExpr(stmt.getWhere(), scan);
            scan = new LogicalSelection(scan, Collections.singletonList(whereExpr));
        }

        return new LogicalDelete(scan, table);
    }

    private LogicalPlan analyzeShow(ShowStmt stmt) {
        LogicalShowStmt.ShowType showType = switch (stmt.getType()) {
            case DATABASES -> LogicalShowStmt.ShowType.DATABASES;
            case TABLES -> LogicalShowStmt.ShowType.TABLES;
            case COLUMNS -> LogicalShowStmt.ShowType.COLUMNS;
            case CREATE_TABLE -> LogicalShowStmt.ShowType.CREATE_TABLE;
            case VARIABLES -> LogicalShowStmt.ShowType.VARIABLES;
            case WARNINGS -> LogicalShowStmt.ShowType.WARNINGS;
            case STATUS -> LogicalShowStmt.ShowType.STATUS;
        };
        return new LogicalShowStmt(showType, currentDatabase, stmt.getTableName());
    }

    private LogicalPlan analyzeDescribe(DescribeStmt stmt) {
        return new LogicalShowStmt(LogicalShowStmt.ShowType.COLUMNS, currentDatabase, stmt.getTableName());
    }

    private LogicalPlan analyzeExplain(ExplainStmt stmt) {
        return analyze(stmt.getStatement());
    }

    private Expression resolveExpr(ExprNode node, LogicalPlan scope) {
        if (node == null) return Constant.ofNull();

        if (node instanceof LiteralExpr lit) {
            return resolveLiteral(lit);
        }
        if (node instanceof ColumnRefExpr ref) {
            return resolveColumnRef(ref, scope);
        }
        if (node instanceof BinaryExpr bin) {
            Expression left = resolveExpr(bin.getLeft(), scope);
            Expression right = resolveExpr(bin.getRight(), scope);
            return new BinaryOp(left, convertBinaryOp(bin.getOp()), right);
        }
        if (node instanceof UnaryExpr un) {
            Expression operand = resolveExpr(un.getOperand(), scope);
            return new UnaryOp(convertUnaryOp(un.getOp()), operand);
        }
        if (node instanceof io.github.xinfra.lab.xdb.parser.ast.FunctionCallExpr fn) {
            AggFunction.Type aggType = getAggType(fn.getName());
            if (aggType != null && scope != null) {
                String canonicalName = aggCanonicalName(fn);
                List<ColumnInfo> schema = scope.outputSchema();
                for (int i = 0; i < schema.size(); i++) {
                    if (schema.get(i).getName().equalsIgnoreCase(canonicalName)) {
                        return new ColumnRef(null, schema.get(i).getName(), i, schema.get(i).getType());
                    }
                }
            }
            List<Expression> args = new ArrayList<>();
            for (ExprNode arg : fn.getArgs()) {
                args.add(resolveExpr(arg, scope));
            }
            return new io.github.xinfra.lab.xdb.expression.FunctionCallExpr(fn.getName(), args);
        }
        if (node instanceof io.github.xinfra.lab.xdb.parser.ast.InExpr in) {
            Expression expr = resolveExpr(in.getExpr(), scope);
            if (in.getValues().size() == 1 && in.getValues().get(0) instanceof SubqueryExpr sub) {
                LogicalPlan subPlan = analyzeSelect(sub.getQuery());
                return new InSubqueryRef(expr, subPlan, in.isNot());
            }
            List<Expression> values = new ArrayList<>();
            for (ExprNode val : in.getValues()) {
                values.add(resolveExpr(val, scope));
            }
            return new io.github.xinfra.lab.xdb.expression.InExpr(expr, values, in.isNot());
        }
        if (node instanceof io.github.xinfra.lab.xdb.parser.ast.BetweenExpr bet) {
            return new io.github.xinfra.lab.xdb.expression.BetweenExpr(
                    resolveExpr(bet.getExpr(), scope),
                    resolveExpr(bet.getLow(), scope),
                    resolveExpr(bet.getHigh(), scope),
                    bet.isNot());
        }
        if (node instanceof io.github.xinfra.lab.xdb.parser.ast.LikeExpr like) {
            return new io.github.xinfra.lab.xdb.expression.LikeExpr(
                    resolveExpr(like.getExpr(), scope),
                    resolveExpr(like.getPattern(), scope),
                    like.isNot());
        }
        if (node instanceof io.github.xinfra.lab.xdb.parser.ast.CastExpr cast) {
            DataType targetType = DataType.fromMySQLType(cast.getTargetType());
            return new io.github.xinfra.lab.xdb.expression.CastExpr(
                    resolveExpr(cast.getExpr(), scope), targetType);
        }
        if (node instanceof io.github.xinfra.lab.xdb.parser.ast.CaseWhenExpr caseExpr) {
            Expression caseVal = caseExpr.getCaseExpr() != null
                    ? resolveExpr(caseExpr.getCaseExpr(), scope) : null;
            List<io.github.xinfra.lab.xdb.expression.CaseWhenExpr.WhenClause> whens = new ArrayList<>();
            for (WhenClause wc : caseExpr.getWhenClauses()) {
                whens.add(new io.github.xinfra.lab.xdb.expression.CaseWhenExpr.WhenClause(
                        resolveExpr(wc.getCondition(), scope),
                        resolveExpr(wc.getResult(), scope)));
            }
            Expression elseExpr = caseExpr.getElseExpr() != null
                    ? resolveExpr(caseExpr.getElseExpr(), scope) : null;
            return new io.github.xinfra.lab.xdb.expression.CaseWhenExpr(caseVal, whens, elseExpr);
        }
        if (node instanceof SubqueryExpr sub) {
            LogicalPlan subPlan = analyzeSelect(sub.getQuery());
            return new ScalarSubqueryRef(subPlan);
        }
        if (node instanceof ExistsExpr exists) {
            LogicalPlan subPlan = analyzeSelect(exists.getSubquery());
            return new ExistsSubqueryRef(subPlan);
        }
        if (node instanceof StarExpr) {
            return Constant.ofNull();
        }

        throw new UnsupportedOperationException("Unsupported expression: " + node.getClass().getSimpleName());
    }

    private Expression resolveLiteral(LiteralExpr lit) {
        if (lit.getValue() == null) return Constant.ofNull();
        Object val = lit.getValue();
        if (val instanceof Long l) return Constant.ofLong(l);
        if (val instanceof Double d) return Constant.ofDouble(d);
        if (val instanceof String s) return Constant.ofString(s);
        if (val instanceof Boolean b) return b ? Constant.ofTrue() : Constant.ofFalse();
        return Constant.ofString(val.toString());
    }

    private Expression resolveColumnRef(ColumnRefExpr ref, LogicalPlan scope) {
        if (scope == null) {
            return new ColumnRef(ref.getTableName(), ref.getColumnName(), 0, DataType.VARCHAR);
        }
        List<ColumnInfo> schema = scope.outputSchema();
        String qualifiedName = ref.getTableName() != null
                ? ref.getTableName() + "." + ref.getColumnName() : null;
        for (int i = 0; i < schema.size(); i++) {
            ColumnInfo col = schema.get(i);
            if (col.getName().equalsIgnoreCase(ref.getColumnName())
                    || (qualifiedName != null && col.getName().equalsIgnoreCase(qualifiedName))) {
                return new ColumnRef(ref.getTableName(), col.getName(), i, col.getType());
            }
        }
        throw XDBException.badField(ref.getColumnName());
    }

    private void analyzeSelectItems(SelectStmt stmt, LogicalPlan plan,
                                    List<Expression> projExprs, List<String> aliases,
                                    List<ColumnInfo> outputCols) {
        boolean hasAgg = plan instanceof LogicalAggregation;
        int groupByCount = hasAgg ? ((LogicalAggregation) plan).groupByExprs().size() : 0;
        int aggIdx = 0;

        for (SelectItem item : stmt.getSelectItems()) {
            if (item.isWildcard()) {
                List<ColumnInfo> schema = plan.outputSchema();
                for (int i = 0; i < schema.size(); i++) {
                    ColumnInfo col = schema.get(i);
                    projExprs.add(new ColumnRef(null, col.getName(), i, col.getType()));
                    aliases.add(null);
                    outputCols.add(col);
                }
            } else if (hasAgg && item.getExpression() instanceof io.github.xinfra.lab.xdb.parser.ast.FunctionCallExpr fn
                       && getAggType(fn.getName()) != null) {
                int colIdx = groupByCount + aggIdx;
                ColumnInfo aggCol = plan.outputSchema().get(colIdx);
                projExprs.add(new ColumnRef(null, aggCol.getName(), colIdx, aggCol.getType()));
                aliases.add(item.getAlias());
                ColumnInfo col = new ColumnInfo();
                col.setName(item.getAlias() != null ? item.getAlias() : aggCol.getName());
                col.setType(aggCol.getType());
                outputCols.add(col);
                aggIdx++;
            } else {
                Expression expr = resolveExpr(item.getExpression(), plan);
                projExprs.add(expr);
                aliases.add(item.getAlias());
                ColumnInfo col = new ColumnInfo();
                String colName;
                if (item.getAlias() != null) {
                    colName = item.getAlias();
                } else if (expr instanceof ColumnRef cref) {
                    colName = cref.columnName();
                } else {
                    colName = expr.toSQL();
                }
                col.setName(colName);
                col.setType(expr.returnType());
                outputCols.add(col);
            }
        }
    }

    private TableInfo resolveTable(String tableName) {
        if (currentDatabase == null) {
            throw XDBException.noDatabase();
        }
        TableInfo table = infoSchema.getTable(currentDatabase, tableName);
        if (table == null) {
            throw XDBException.tableNotFound(tableName);
        }
        return table;
    }

    private LogicalPlan mergeSchemas(LogicalPlan left, LogicalPlan right) {
        return new LogicalJoin(left, right, io.github.xinfra.lab.xdb.planner.logical.JoinType.CROSS, null);
    }

    private boolean hasAggregation(SelectStmt stmt) {
        if (stmt.getSelectItems() == null) return false;
        for (SelectItem item : stmt.getSelectItems()) {
            ExprNode expr = item.getExpression();
            if (expr instanceof io.github.xinfra.lab.xdb.parser.ast.FunctionCallExpr fn) {
                if (getAggType(fn.getName()) != null) return true;
            }
        }
        return false;
    }

    private AggFunction.Type getAggType(String name) {
        return switch (name.toUpperCase()) {
            case "COUNT" -> AggFunction.Type.COUNT;
            case "SUM" -> AggFunction.Type.SUM;
            case "AVG" -> AggFunction.Type.AVG;
            case "MIN" -> AggFunction.Type.MIN;
            case "MAX" -> AggFunction.Type.MAX;
            case "GROUP_CONCAT" -> AggFunction.Type.GROUP_CONCAT;
            default -> null;
        };
    }

    private long evalLongLiteral(ExprNode node) {
        if (node instanceof LiteralExpr lit && lit.getValue() instanceof Long l) return l;
        if (node instanceof LiteralExpr lit && lit.getValue() instanceof Number n) return n.longValue();
        return 0;
    }

    private io.github.xinfra.lab.xdb.planner.logical.JoinType convertJoinType(
            io.github.xinfra.lab.xdb.parser.ast.JoinType type) {
        return switch (type) {
            case INNER -> io.github.xinfra.lab.xdb.planner.logical.JoinType.INNER;
            case LEFT -> io.github.xinfra.lab.xdb.planner.logical.JoinType.LEFT;
            case RIGHT -> io.github.xinfra.lab.xdb.planner.logical.JoinType.RIGHT;
            case CROSS -> io.github.xinfra.lab.xdb.planner.logical.JoinType.CROSS;
        };
    }

    private BinaryOp.Op convertBinaryOp(io.github.xinfra.lab.xdb.parser.ast.BinaryExpr.Op op) {
        return switch (op) {
            case ADD -> BinaryOp.Op.ADD;
            case SUB -> BinaryOp.Op.SUB;
            case MUL -> BinaryOp.Op.MUL;
            case DIV -> BinaryOp.Op.DIV;
            case MOD -> BinaryOp.Op.MOD;
            case INT_DIV -> BinaryOp.Op.INT_DIV;
            case EQ -> BinaryOp.Op.EQ;
            case NE -> BinaryOp.Op.NE;
            case LT -> BinaryOp.Op.LT;
            case LE -> BinaryOp.Op.LE;
            case GT -> BinaryOp.Op.GT;
            case GE -> BinaryOp.Op.GE;
            case AND -> BinaryOp.Op.AND;
            case OR -> BinaryOp.Op.OR;
        };
    }

    private UnaryOp.Op convertUnaryOp(io.github.xinfra.lab.xdb.parser.ast.UnaryExpr.Op op) {
        return switch (op) {
            case NOT -> UnaryOp.Op.NOT;
            case NEG -> UnaryOp.Op.NEG;
            case IS_NULL -> UnaryOp.Op.IS_NULL;
            case IS_NOT_NULL -> UnaryOp.Op.IS_NOT_NULL;
        };
    }
}
