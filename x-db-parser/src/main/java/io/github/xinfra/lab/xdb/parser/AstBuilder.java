package io.github.xinfra.lab.xdb.parser;

import io.github.xinfra.lab.xdb.parser.ast.*;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converts an ANTLR parse tree into AST node objects.
 */
public class AstBuilder extends MySQLParserBaseVisitor<Object> {

    // ================================================================
    // Top-level
    // ================================================================

    @Override
    public List<Statement> visitSqlStatements(MySQLParser.SqlStatementsContext ctx) {
        List<Statement> stmts = new ArrayList<>();
        for (MySQLParser.SqlStatementContext sc : ctx.sqlStatement()) {
            stmts.add((Statement) visit(sc));
        }
        return stmts;
    }

    @Override
    public Statement visitSqlStatement(MySQLParser.SqlStatementContext ctx) {
        if (ctx.ddlStatement() != null) return (Statement) visit(ctx.ddlStatement());
        if (ctx.dmlStatement() != null) return (Statement) visit(ctx.dmlStatement());
        return (Statement) visit(ctx.utilityStatement());
    }

    @Override
    public Statement visitDdlStatement(MySQLParser.DdlStatementContext ctx) {
        return (Statement) visit(ctx.getChild(0));
    }

    @Override
    public Statement visitDmlStatement(MySQLParser.DmlStatementContext ctx) {
        return (Statement) visit(ctx.getChild(0));
    }

    @Override
    public Statement visitUtilityStatement(MySQLParser.UtilityStatementContext ctx) {
        return (Statement) visit(ctx.getChild(0));
    }

    // ================================================================
    // DDL
    // ================================================================

    @Override
    public CreateDatabaseStmt visitCreateDatabaseStatement(MySQLParser.CreateDatabaseStatementContext ctx) {
        String name = extractIdentifier(ctx.identifier());
        boolean ifNotExists = ctx.IF() != null;
        String charset = null;
        String collation = null;
        for (MySQLParser.CreateDatabaseOptionContext opt : ctx.createDatabaseOption()) {
            if (opt instanceof MySQLParser.CharsetOptionContext co) {
                charset = extractIdentifier(co.identifier());
            } else if (opt instanceof MySQLParser.CollateOptionContext co) {
                collation = extractIdentifier(co.identifier());
            }
        }
        return new CreateDatabaseStmt(name, ifNotExists, charset, collation);
    }

    @Override
    public DropDatabaseStmt visitDropDatabaseStatement(MySQLParser.DropDatabaseStatementContext ctx) {
        String name = extractIdentifier(ctx.identifier());
        boolean ifExists = ctx.IF() != null;
        return new DropDatabaseStmt(name, ifExists);
    }

    @Override
    public CreateTableStmt visitCreateTableStatement(MySQLParser.CreateTableStatementContext ctx) {
        String tableName = extractTableName(ctx.tableName());
        boolean ifNotExists = ctx.IF() != null;

        List<ColumnDef> columns = new ArrayList<>();
        List<TableConstraint> constraints = new ArrayList<>();
        for (MySQLParser.TableElementContext te : ctx.tableElement()) {
            if (te.columnDefinition() != null) {
                columns.add(visitColumnDefinition(te.columnDefinition()));
            } else if (te.tableConstraint() != null) {
                constraints.add((TableConstraint) visit(te.tableConstraint()));
            }
        }

        Map<String, String> options = new LinkedHashMap<>();
        for (MySQLParser.TableOptionContext to : ctx.tableOption()) {
            if (to instanceof MySQLParser.EngineOptionContext eo) {
                options.put("ENGINE", extractIdentifier(eo.identifier()));
            } else if (to instanceof MySQLParser.TableCharsetOptionContext co) {
                options.put("CHARSET", extractIdentifier(co.identifier()));
            } else if (to instanceof MySQLParser.TableCollateOptionContext co) {
                options.put("COLLATE", extractIdentifier(co.identifier()));
            } else if (to instanceof MySQLParser.TableAutoIncrementOptionContext ao) {
                options.put("AUTO_INCREMENT", ao.INTEGER_LITERAL().getText());
            } else if (to instanceof MySQLParser.TableCommentOptionContext co) {
                options.put("COMMENT", unquoteString(co.STRING_LITERAL().getText()));
            }
        }

        return new CreateTableStmt(tableName, columns, constraints, ifNotExists, options);
    }

    @Override
    public ColumnDef visitColumnDefinition(MySQLParser.ColumnDefinitionContext ctx) {
        String name = extractIdentifier(ctx.columnName().identifier());
        ColumnDef.Builder builder = ColumnDef.builder().name(name);

        // Process data type
        processDataType(ctx.dataType(), builder);

        // Process constraints
        for (MySQLParser.ColumnConstraintContext cc : ctx.columnConstraint()) {
            if (cc instanceof MySQLParser.NotNullConstraintContext) {
                builder.nullable(false);
            } else if (cc instanceof MySQLParser.NullConstraintContext) {
                builder.nullable(true);
            } else if (cc instanceof MySQLParser.DefaultConstraintContext dc) {
                builder.defaultValue(visitDefaultValue(dc.defaultValue()));
            } else if (cc instanceof MySQLParser.AutoIncrementConstraintContext) {
                builder.autoIncrement(true);
            } else if (cc instanceof MySQLParser.PrimaryKeyConstraintContext) {
                builder.primaryKey(true);
            } else if (cc instanceof MySQLParser.CommentConstraintContext cmc) {
                builder.comment(unquoteString(cmc.STRING_LITERAL().getText()));
            } else if (cc instanceof MySQLParser.UnsignedConstraintContext) {
                builder.unsigned(true);
            }
        }

        return builder.build();
    }

    private void processDataType(MySQLParser.DataTypeContext dtCtx, ColumnDef.Builder builder) {
        if (dtCtx instanceof MySQLParser.TinyIntTypeContext t) {
            builder.typeName("TINYINT").dataType("TINYINT");
            if (t.INTEGER_LITERAL() != null) builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.SmallIntTypeContext t) {
            builder.typeName("SMALLINT").dataType("SMALLINT");
            if (t.INTEGER_LITERAL() != null) builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.MediumIntTypeContext t) {
            builder.typeName("MEDIUMINT").dataType("INT");
            if (t.INTEGER_LITERAL() != null) builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.IntTypeContext t) {
            builder.typeName("INT").dataType("INT");
            if (t.INTEGER_LITERAL() != null) builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.BigIntTypeContext t) {
            builder.typeName("BIGINT").dataType("BIGINT");
            if (t.INTEGER_LITERAL() != null) builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.FloatTypeContext t) {
            builder.typeName("FLOAT").dataType("FLOAT");
            List<TerminalNode> nums = t.INTEGER_LITERAL();
            if (nums.size() > 0) builder.length(Integer.parseInt(nums.get(0).getText()));
            if (nums.size() > 1) builder.decimals(Integer.parseInt(nums.get(1).getText()));
        } else if (dtCtx instanceof MySQLParser.DoubleTypeContext t) {
            builder.typeName("DOUBLE").dataType("DOUBLE");
            List<TerminalNode> nums = t.INTEGER_LITERAL();
            if (nums.size() > 0) builder.length(Integer.parseInt(nums.get(0).getText()));
            if (nums.size() > 1) builder.decimals(Integer.parseInt(nums.get(1).getText()));
        } else if (dtCtx instanceof MySQLParser.RealTypeContext t) {
            builder.typeName("REAL").dataType("DOUBLE");
            List<TerminalNode> nums = t.INTEGER_LITERAL();
            if (nums.size() > 0) builder.length(Integer.parseInt(nums.get(0).getText()));
            if (nums.size() > 1) builder.decimals(Integer.parseInt(nums.get(1).getText()));
        } else if (dtCtx instanceof MySQLParser.DecimalTypeContext t) {
            builder.typeName("DECIMAL").dataType("DECIMAL");
            List<TerminalNode> nums = t.INTEGER_LITERAL();
            if (nums.size() > 0) builder.length(Integer.parseInt(nums.get(0).getText()));
            if (nums.size() > 1) builder.decimals(Integer.parseInt(nums.get(1).getText()));
        } else if (dtCtx instanceof MySQLParser.CharTypeContext t) {
            builder.typeName("CHAR").dataType("CHAR");
            if (t.INTEGER_LITERAL() != null) builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.VarcharTypeContext t) {
            builder.typeName("VARCHAR").dataType("VARCHAR");
            builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.TextTypeContext) {
            builder.typeName("TEXT").dataType("TEXT");
        } else if (dtCtx instanceof MySQLParser.TinyTextTypeContext) {
            builder.typeName("TINYTEXT").dataType("TEXT");
        } else if (dtCtx instanceof MySQLParser.MediumTextTypeContext) {
            builder.typeName("MEDIUMTEXT").dataType("TEXT");
        } else if (dtCtx instanceof MySQLParser.LongTextTypeContext) {
            builder.typeName("LONGTEXT").dataType("TEXT");
        } else if (dtCtx instanceof MySQLParser.BinaryTypeContext t) {
            builder.typeName("BINARY").dataType("BINARY");
            if (t.INTEGER_LITERAL() != null) builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.VarbinaryTypeContext t) {
            builder.typeName("VARBINARY").dataType("VARBINARY");
            builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.BlobTypeContext) {
            builder.typeName("BLOB").dataType("BLOB");
        } else if (dtCtx instanceof MySQLParser.TinyBlobTypeContext) {
            builder.typeName("TINYBLOB").dataType("BLOB");
        } else if (dtCtx instanceof MySQLParser.MediumBlobTypeContext) {
            builder.typeName("MEDIUMBLOB").dataType("BLOB");
        } else if (dtCtx instanceof MySQLParser.LongBlobTypeContext) {
            builder.typeName("LONGBLOB").dataType("BLOB");
        } else if (dtCtx instanceof MySQLParser.DateTypeContext) {
            builder.typeName("DATE").dataType("DATE");
        } else if (dtCtx instanceof MySQLParser.DatetimeTypeContext t) {
            builder.typeName("DATETIME").dataType("DATETIME");
            if (t.INTEGER_LITERAL() != null) builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.TimestampTypeContext t) {
            builder.typeName("TIMESTAMP").dataType("TIMESTAMP");
            if (t.INTEGER_LITERAL() != null) builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.TimeTypeContext t) {
            builder.typeName("TIME").dataType("TIME");
            if (t.INTEGER_LITERAL() != null) builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.YearTypeContext t) {
            builder.typeName("YEAR").dataType("YEAR");
            if (t.INTEGER_LITERAL() != null) builder.length(Integer.parseInt(t.INTEGER_LITERAL().getText()));
        } else if (dtCtx instanceof MySQLParser.BooleanTypeContext) {
            builder.typeName("BOOLEAN").dataType("BOOLEAN");
        } else if (dtCtx instanceof MySQLParser.SignedTypeContext) {
            builder.typeName("SIGNED").dataType("BIGINT");
        } else if (dtCtx instanceof MySQLParser.UnsignedTypeContext) {
            builder.typeName("UNSIGNED").dataType("BIGINT").unsigned(true);
        }
    }

    private String extractDataTypeName(MySQLParser.DataTypeContext dtCtx) {
        // Return a simple type name string for CAST
        if (dtCtx instanceof MySQLParser.TinyIntTypeContext) return "TINYINT";
        if (dtCtx instanceof MySQLParser.SmallIntTypeContext) return "SMALLINT";
        if (dtCtx instanceof MySQLParser.MediumIntTypeContext) return "MEDIUMINT";
        if (dtCtx instanceof MySQLParser.IntTypeContext) return "INT";
        if (dtCtx instanceof MySQLParser.BigIntTypeContext) return "BIGINT";
        if (dtCtx instanceof MySQLParser.FloatTypeContext) return "FLOAT";
        if (dtCtx instanceof MySQLParser.DoubleTypeContext) return "DOUBLE";
        if (dtCtx instanceof MySQLParser.RealTypeContext) return "REAL";
        if (dtCtx instanceof MySQLParser.DecimalTypeContext) return "DECIMAL";
        if (dtCtx instanceof MySQLParser.CharTypeContext) return "CHAR";
        if (dtCtx instanceof MySQLParser.VarcharTypeContext) return "VARCHAR";
        if (dtCtx instanceof MySQLParser.TextTypeContext) return "TEXT";
        if (dtCtx instanceof MySQLParser.TinyTextTypeContext) return "TINYTEXT";
        if (dtCtx instanceof MySQLParser.MediumTextTypeContext) return "MEDIUMTEXT";
        if (dtCtx instanceof MySQLParser.LongTextTypeContext) return "LONGTEXT";
        if (dtCtx instanceof MySQLParser.BinaryTypeContext) return "BINARY";
        if (dtCtx instanceof MySQLParser.VarbinaryTypeContext) return "VARBINARY";
        if (dtCtx instanceof MySQLParser.BlobTypeContext) return "BLOB";
        if (dtCtx instanceof MySQLParser.TinyBlobTypeContext) return "TINYBLOB";
        if (dtCtx instanceof MySQLParser.MediumBlobTypeContext) return "MEDIUMBLOB";
        if (dtCtx instanceof MySQLParser.LongBlobTypeContext) return "LONGBLOB";
        if (dtCtx instanceof MySQLParser.DateTypeContext) return "DATE";
        if (dtCtx instanceof MySQLParser.DatetimeTypeContext) return "DATETIME";
        if (dtCtx instanceof MySQLParser.TimestampTypeContext) return "TIMESTAMP";
        if (dtCtx instanceof MySQLParser.TimeTypeContext) return "TIME";
        if (dtCtx instanceof MySQLParser.YearTypeContext) return "YEAR";
        if (dtCtx instanceof MySQLParser.BooleanTypeContext) return "BOOLEAN";
        if (dtCtx instanceof MySQLParser.SignedTypeContext) return "SIGNED";
        if (dtCtx instanceof MySQLParser.UnsignedTypeContext) return "UNSIGNED";
        return dtCtx.getText().toUpperCase();
    }

    @Override
    public ExprNode visitDefaultValue(MySQLParser.DefaultValueContext ctx) {
        if (ctx.literal() != null) {
            return (ExprNode) visit(ctx.literal());
        }
        return visitExpression(ctx.expression());
    }

    @Override
    public TableConstraint visitPrimaryKeyTableConstraint(MySQLParser.PrimaryKeyTableConstraintContext ctx) {
        String name = ctx.indexName() != null ? extractIdentifier(ctx.indexName().identifier()) : null;
        List<IndexColumn> cols = visitIndexColumnList(ctx.indexColumnList());
        return new TableConstraint(TableConstraint.Type.PRIMARY, name, cols);
    }

    @Override
    public TableConstraint visitUniqueKeyTableConstraint(MySQLParser.UniqueKeyTableConstraintContext ctx) {
        String name = ctx.indexName() != null ? extractIdentifier(ctx.indexName().identifier()) : null;
        List<IndexColumn> cols = visitIndexColumnList(ctx.indexColumnList());
        return new TableConstraint(TableConstraint.Type.UNIQUE, name, cols);
    }

    @Override
    public TableConstraint visitIndexTableConstraint(MySQLParser.IndexTableConstraintContext ctx) {
        String name = ctx.indexName() != null ? extractIdentifier(ctx.indexName().identifier()) : null;
        List<IndexColumn> cols = visitIndexColumnList(ctx.indexColumnList());
        return new TableConstraint(TableConstraint.Type.INDEX, name, cols);
    }

    @Override
    public List<IndexColumn> visitIndexColumnList(MySQLParser.IndexColumnListContext ctx) {
        List<IndexColumn> cols = new ArrayList<>();
        for (MySQLParser.IndexColumnContext ic : ctx.indexColumn()) {
            cols.add(visitIndexColumn(ic));
        }
        return cols;
    }

    @Override
    public IndexColumn visitIndexColumn(MySQLParser.IndexColumnContext ctx) {
        String name = extractIdentifier(ctx.columnName().identifier());
        Integer length = ctx.INTEGER_LITERAL() != null ? Integer.parseInt(ctx.INTEGER_LITERAL().getText()) : null;
        return new IndexColumn(name, length);
    }

    @Override
    public DropTableStmt visitDropTableStatement(MySQLParser.DropTableStatementContext ctx) {
        String tableName = extractTableName(ctx.tableName());
        boolean ifExists = ctx.IF() != null;
        return new DropTableStmt(tableName, ifExists);
    }

    @Override
    public AlterTableStmt visitAlterTableStatement(MySQLParser.AlterTableStatementContext ctx) {
        String tableName = extractTableName(ctx.tableName());
        List<AlterSpec> specs = new ArrayList<>();
        for (MySQLParser.AlterSpecContext as : ctx.alterSpec()) {
            specs.add((AlterSpec) visit(as));
        }
        return new AlterTableStmt(tableName, specs);
    }

    @Override
    public AlterSpec visitAddColumnSpec(MySQLParser.AddColumnSpecContext ctx) {
        ColumnDef colDef = visitColumnDefinition(ctx.columnDefinition());
        boolean first = ctx.FIRST() != null;
        String afterCol = null;
        if (ctx.AFTER() != null && ctx.columnName() != null) {
            afterCol = extractIdentifier(ctx.columnName().identifier());
        }
        return new AlterSpec.AddColumn(colDef, afterCol, first);
    }

    @Override
    public AlterSpec visitDropColumnSpec(MySQLParser.DropColumnSpecContext ctx) {
        String colName = extractIdentifier(ctx.columnName().identifier());
        return new AlterSpec.DropColumn(colName);
    }

    @Override
    public AlterSpec visitAddIndexSpec(MySQLParser.AddIndexSpecContext ctx) {
        String name = ctx.indexName() != null ? extractIdentifier(ctx.indexName().identifier()) : null;
        List<IndexColumn> cols = visitIndexColumnList(ctx.indexColumnList());
        return new AlterSpec.AddIndex(name, cols);
    }

    @Override
    public AlterSpec visitDropIndexSpec(MySQLParser.DropIndexSpecContext ctx) {
        String name = extractIdentifier(ctx.indexName().identifier());
        return new AlterSpec.DropIndex(name);
    }

    @Override
    public TruncateTableStmt visitTruncateTableStatement(MySQLParser.TruncateTableStatementContext ctx) {
        String tableName = extractTableName(ctx.tableName());
        return new TruncateTableStmt(tableName);
    }

    // ================================================================
    // DML
    // ================================================================

    @Override
    public SelectStmt visitSelectStatement(MySQLParser.SelectStatementContext ctx) {
        SelectStmt.Builder builder = SelectStmt.builder();

        // DISTINCT
        builder.distinct(ctx.DISTINCT() != null);

        // SELECT items
        List<SelectItem> items = new ArrayList<>();
        for (MySQLParser.SelectItemContext si : ctx.selectItem()) {
            items.add((SelectItem) visit(si));
        }
        builder.selectItems(items);

        // FROM
        if (ctx.FROM() != null) {
            List<MySQLParser.TableRefContext> tableRefs = ctx.tableRef();
            if (tableRefs.size() == 1) {
                builder.from(visitTableRefNode(tableRefs.get(0)));
            } else {
                // Multiple table refs => implicit cross join
                TableRef result = visitTableRefNode(tableRefs.get(0));
                for (int i = 1; i < tableRefs.size(); i++) {
                    TableRef right = visitTableRefNode(tableRefs.get(i));
                    result = new TableRef.JoinTableRef(result, right, JoinType.CROSS, null);
                }
                builder.from(result);
            }
        }

        // WHERE
        if (ctx.whereExpr != null) {
            builder.where(visitExpression(ctx.whereExpr));
        }

        // GROUP BY
        if (ctx.GROUP() != null) {
            List<ExprNode> groupBy = new ArrayList<>();
            for (MySQLParser.GroupByItemContext gi : ctx.groupByItem()) {
                groupBy.add(visitExpression(gi.expression()));
            }
            builder.groupBy(groupBy);
        }

        // HAVING
        if (ctx.havingExpr != null) {
            builder.having(visitExpression(ctx.havingExpr));
        }

        // ORDER BY
        if (ctx.ORDER() != null) {
            List<OrderByItem> orderBy = new ArrayList<>();
            for (MySQLParser.OrderByItemContext oi : ctx.orderByItem()) {
                ExprNode expr = visitExpression(oi.expression());
                boolean asc = oi.DESC() == null;
                orderBy.add(new OrderByItem(expr, asc));
            }
            builder.orderBy(orderBy);
        }

        // LIMIT / OFFSET
        if (ctx.limitExpr != null) {
            builder.limit(visitExpression(ctx.limitExpr));
            if (ctx.offsetExpr != null) {
                builder.offset(visitExpression(ctx.offsetExpr));
            }
        } else if (ctx.limitExpr2 != null) {
            // LIMIT offset, count syntax
            builder.limit(visitExpression(ctx.limitExpr2));
            builder.offset(visitExpression(ctx.offsetExpr2));
        }

        // FOR UPDATE
        builder.forUpdate(ctx.FOR() != null);

        return builder.build();
    }

    @Override
    public SelectItem visitSelectAll(MySQLParser.SelectAllContext ctx) {
        return SelectItem.wildcard();
    }

    @Override
    public SelectItem visitSelectTableAll(MySQLParser.SelectTableAllContext ctx) {
        String tableName = extractTableName(ctx.tableName());
        return SelectItem.tableWildcard(tableName);
    }

    @Override
    public SelectItem visitSelectExpr(MySQLParser.SelectExprContext ctx) {
        ExprNode expr = visitExpression(ctx.expression());
        String alias = ctx.identifier() != null ? extractIdentifier(ctx.identifier()) : null;
        return SelectItem.expression(expr, alias);
    }

    private TableRef visitTableRefNode(MySQLParser.TableRefContext ctx) {
        TableRef base = (TableRef) visit(ctx.simpleTableRef());

        for (MySQLParser.JoinClauseContext jc : ctx.joinClause()) {
            JoinType joinType = JoinType.INNER; // default
            if (jc.joinType() != null) {
                MySQLParser.JoinTypeContext jtCtx = jc.joinType();
                if (jtCtx.LEFT() != null) joinType = JoinType.LEFT;
                else if (jtCtx.RIGHT() != null) joinType = JoinType.RIGHT;
                else if (jtCtx.CROSS() != null) joinType = JoinType.CROSS;
                else joinType = JoinType.INNER;
            }
            TableRef right = visitTableRefNode(jc.tableRef());
            ExprNode condition = jc.expression() != null ? visitExpression(jc.expression()) : null;
            base = new TableRef.JoinTableRef(base, right, joinType, condition);
        }
        return base;
    }

    @Override
    public TableRef visitSimpleTable(MySQLParser.SimpleTableContext ctx) {
        String name = extractTableName(ctx.tableName());
        String alias = ctx.identifier() != null ? extractIdentifier(ctx.identifier()) : null;
        return new TableRef.SimpleTableRef(name, alias);
    }

    @Override
    public TableRef visitSubqueryTable(MySQLParser.SubqueryTableContext ctx) {
        SelectStmt query = visitSelectStatement(ctx.selectStatement());
        String alias = extractIdentifier(ctx.identifier());
        return new TableRef.SubqueryTableRef(query, alias);
    }

    @Override
    public InsertStmt visitInsertStatement(MySQLParser.InsertStatementContext ctx) {
        String tableName = extractTableName(ctx.tableName());

        List<String> columns = new ArrayList<>();
        for (MySQLParser.ColumnNameContext cn : ctx.columnName()) {
            columns.add(extractIdentifier(cn.identifier()));
        }

        List<List<ExprNode>> values = new ArrayList<>();
        for (MySQLParser.ValueRowContext vr : ctx.valueRow()) {
            List<ExprNode> row = new ArrayList<>();
            for (MySQLParser.ExpressionContext ec : vr.expression()) {
                row.add(visitExpression(ec));
            }
            values.add(row);
        }

        return new InsertStmt(tableName, columns, values);
    }

    @Override
    public UpdateStmt visitUpdateStatement(MySQLParser.UpdateStatementContext ctx) {
        TableRef tableRef = visitTableRefNode(ctx.tableRef());
        String tableName = extractTargetTable(tableRef);

        List<Assignment> assignments = new ArrayList<>();
        for (MySQLParser.AssignmentContext ac : ctx.assignment()) {
            String col = extractIdentifier(ac.columnRef().columnName().identifier());
            ExprNode val = visitExpression(ac.expression());
            assignments.add(new Assignment(col, val));
        }

        ExprNode where = ctx.expression() != null ? visitExpression(ctx.expression()) : null;
        TableRef from = (tableRef instanceof TableRef.JoinTableRef) ? tableRef : null;
        return new UpdateStmt(tableName, from, assignments, where);
    }

    @Override
    public DeleteStmt visitDeleteMultiTable(MySQLParser.DeleteMultiTableContext ctx) {
        String tableName = extractTableName(ctx.tableName());
        TableRef from = visitTableRefNode(ctx.tableRef());
        ExprNode where = ctx.expression() != null ? visitExpression(ctx.expression()) : null;
        return new DeleteStmt(tableName, from, where);
    }

    @Override
    public DeleteStmt visitDeleteSingleTable(MySQLParser.DeleteSingleTableContext ctx) {
        String tableName = extractTableName(ctx.tableName());
        ExprNode where = ctx.expression() != null ? visitExpression(ctx.expression()) : null;
        return new DeleteStmt(tableName, where);
    }

    private String extractTargetTable(TableRef ref) {
        if (ref instanceof TableRef.SimpleTableRef simple) {
            return simple.getName();
        }
        if (ref instanceof TableRef.JoinTableRef join) {
            return extractTargetTable(join.getLeft());
        }
        throw new RuntimeException("Cannot determine target table from table reference");
    }

    // ================================================================
    // Utility
    // ================================================================

    @Override
    public AnalyzeTableStmt visitAnalyzeTableStatement(MySQLParser.AnalyzeTableStatementContext ctx) {
        String tableName = extractTableName(ctx.tableName());
        return new AnalyzeTableStmt(tableName);
    }

    @Override
    public UseStmt visitUseStatement(MySQLParser.UseStatementContext ctx) {
        return new UseStmt(extractIdentifier(ctx.identifier()));
    }

    @Override
    public ShowStmt visitShowDatabases(MySQLParser.ShowDatabasesContext ctx) {
        return new ShowStmt(ShowStmt.ShowType.DATABASES, null);
    }

    @Override
    public ShowStmt visitShowTables(MySQLParser.ShowTablesContext ctx) {
        return new ShowStmt(ShowStmt.ShowType.TABLES, null);
    }

    @Override
    public ShowStmt visitShowColumns(MySQLParser.ShowColumnsContext ctx) {
        return new ShowStmt(ShowStmt.ShowType.COLUMNS, extractTableName(ctx.tableName()));
    }

    @Override
    public ShowStmt visitShowCreateTable(MySQLParser.ShowCreateTableContext ctx) {
        return new ShowStmt(ShowStmt.ShowType.CREATE_TABLE, extractTableName(ctx.tableName()));
    }

    @Override
    public ShowStmt visitShowVariables(MySQLParser.ShowVariablesContext ctx) {
        return new ShowStmt(ShowStmt.ShowType.VARIABLES, null);
    }

    @Override
    public ShowStmt visitShowWarnings(MySQLParser.ShowWarningsContext ctx) {
        return new ShowStmt(ShowStmt.ShowType.WARNINGS, null);
    }

    @Override
    public ShowStmt visitShowStatus(MySQLParser.ShowStatusContext ctx) {
        return new ShowStmt(ShowStmt.ShowType.STATUS, null);
    }

    @Override
    public ExplainStmt visitExplainStatement(MySQLParser.ExplainStatementContext ctx) {
        SelectStmt select = visitSelectStatement(ctx.selectStatement());
        return new ExplainStmt(select);
    }

    @Override
    public BeginStmt visitBeginStatement(MySQLParser.BeginStatementContext ctx) {
        // For START TRANSACTION, check transaction mode
        boolean optimistic = false;
        if (ctx.transactionMode() != null) {
            // transactionMode: identifier identifier  => READ ONLY | READ WRITE
            // We don't set optimistic based on READ ONLY/WRITE, leave as false
        }
        return new BeginStmt(optimistic);
    }

    @Override
    public CommitStmt visitCommitStatement(MySQLParser.CommitStatementContext ctx) {
        return new CommitStmt();
    }

    @Override
    public RollbackStmt visitRollbackStatement(MySQLParser.RollbackStatementContext ctx) {
        return new RollbackStmt();
    }

    @Override
    public SetStmt visitSetVariable(MySQLParser.SetVariableContext ctx) {
        SetStmt.Scope scope = SetStmt.Scope.NONE;
        if (ctx.setScope() != null) {
            if (ctx.setScope().SESSION() != null) scope = SetStmt.Scope.SESSION;
            else if (ctx.setScope().GLOBAL() != null) scope = SetStmt.Scope.GLOBAL;
        }
        String variable = extractIdentifier(ctx.identifier());
        ExprNode value = visitExpression(ctx.expression());
        return new SetStmt(variable, value, scope);
    }

    @Override
    public SetStmt visitSetSystemVariable(MySQLParser.SetSystemVariableContext ctx) {
        String variable = extractIdentifier(ctx.identifier());
        ExprNode value = visitExpression(ctx.expression());
        return new SetStmt(variable, value, SetStmt.Scope.SESSION);
    }

    @Override
    public DescribeStmt visitDescribeStatement(MySQLParser.DescribeStatementContext ctx) {
        return new DescribeStmt(extractTableName(ctx.tableName()));
    }

    // ================================================================
    // Expressions
    // ================================================================

    public ExprNode visitExpression(MySQLParser.ExpressionContext ctx) {
        return (ExprNode) visit(ctx);
    }

    @Override
    public ExprNode visitPrimaryExprAlt(MySQLParser.PrimaryExprAltContext ctx) {
        return (ExprNode) visit(ctx.primaryExpression());
    }

    @Override
    public ExprNode visitUnaryExpr(MySQLParser.UnaryExprContext ctx) {
        ExprNode operand = visitExpression(ctx.expression());
        if (ctx.op.getType() == MySQLParser.MINUS) {
            return new UnaryExpr(UnaryExpr.Op.NEG, operand);
        }
        // PLUS is identity
        return operand;
    }

    @Override
    public ExprNode visitNotExpr(MySQLParser.NotExprContext ctx) {
        ExprNode operand = visitExpression(ctx.expression());
        return new UnaryExpr(UnaryExpr.Op.NOT, operand);
    }

    @Override
    public ExprNode visitMulDivExpr(MySQLParser.MulDivExprContext ctx) {
        ExprNode left = visitExpression(ctx.expression(0));
        ExprNode right = visitExpression(ctx.expression(1));
        BinaryExpr.Op op;
        switch (ctx.op.getType()) {
            case MySQLParser.STAR:    op = BinaryExpr.Op.MUL; break;
            case MySQLParser.SLASH:   op = BinaryExpr.Op.DIV; break;
            case MySQLParser.PERCENT: op = BinaryExpr.Op.MOD; break;
            default: throw new ParseException("Unknown operator: " + ctx.op.getText());
        }
        return new BinaryExpr(left, op, right);
    }

    @Override
    public ExprNode visitIntDivExpr(MySQLParser.IntDivExprContext ctx) {
        ExprNode left = visitExpression(ctx.expression(0));
        ExprNode right = visitExpression(ctx.expression(1));
        return new BinaryExpr(left, BinaryExpr.Op.INT_DIV, right);
    }

    @Override
    public ExprNode visitModExpr(MySQLParser.ModExprContext ctx) {
        ExprNode left = visitExpression(ctx.expression(0));
        ExprNode right = visitExpression(ctx.expression(1));
        return new BinaryExpr(left, BinaryExpr.Op.MOD, right);
    }

    @Override
    public ExprNode visitAddSubExpr(MySQLParser.AddSubExprContext ctx) {
        ExprNode left = visitExpression(ctx.expression(0));
        ExprNode right = visitExpression(ctx.expression(1));
        BinaryExpr.Op op = ctx.op.getType() == MySQLParser.PLUS ? BinaryExpr.Op.ADD : BinaryExpr.Op.SUB;
        return new BinaryExpr(left, op, right);
    }

    @Override
    public ExprNode visitComparisonExpr(MySQLParser.ComparisonExprContext ctx) {
        ExprNode left = visitExpression(ctx.expression(0));
        ExprNode right = visitExpression(ctx.expression(1));
        BinaryExpr.Op op;
        switch (ctx.op.getType()) {
            case MySQLParser.EQ:  op = BinaryExpr.Op.EQ; break;
            case MySQLParser.NEQ: op = BinaryExpr.Op.NE; break;
            case MySQLParser.LT:  op = BinaryExpr.Op.LT; break;
            case MySQLParser.LE:  op = BinaryExpr.Op.LE; break;
            case MySQLParser.GT:  op = BinaryExpr.Op.GT; break;
            case MySQLParser.GE:  op = BinaryExpr.Op.GE; break;
            default: throw new ParseException("Unknown comparison: " + ctx.op.getText());
        }
        return new BinaryExpr(left, op, right);
    }

    @Override
    public ExprNode visitIsNullExpr(MySQLParser.IsNullExprContext ctx) {
        ExprNode operand = visitExpression(ctx.expression());
        if (ctx.NOT() != null) {
            return new UnaryExpr(UnaryExpr.Op.IS_NOT_NULL, operand);
        }
        return new UnaryExpr(UnaryExpr.Op.IS_NULL, operand);
    }

    @Override
    public ExprNode visitLikeExpr(MySQLParser.LikeExprContext ctx) {
        ExprNode expr = visitExpression(ctx.expression(0));
        ExprNode pattern = visitExpression(ctx.expression(1));
        boolean not = ctx.NOT() != null;
        return new LikeExpr(expr, pattern, not);
    }

    @Override
    public ExprNode visitInExpr(MySQLParser.InExprContext ctx) {
        ExprNode expr = visitExpression(ctx.expression());
        boolean not = ctx.NOT() != null;

        if (ctx.selectStatement() != null) {
            // IN (subquery)
            SelectStmt subquery = visitSelectStatement(ctx.selectStatement());
            List<ExprNode> values = new ArrayList<>();
            values.add(new SubqueryExpr(subquery));
            return new InExpr(expr, values, not);
        }

        // IN (expression list)
        List<ExprNode> values = new ArrayList<>();
        for (MySQLParser.ExpressionContext ec : ctx.expressionList().expression()) {
            values.add(visitExpression(ec));
        }
        return new InExpr(expr, values, not);
    }

    @Override
    public ExprNode visitBetweenExpr(MySQLParser.BetweenExprContext ctx) {
        ExprNode expr = visitExpression(ctx.expression(0));
        ExprNode low = visitExpression(ctx.expression(1));
        ExprNode high = visitExpression(ctx.expression(2));
        boolean not = ctx.NOT() != null;
        return new BetweenExpr(expr, low, high, not);
    }

    @Override
    public ExprNode visitExistsExpr(MySQLParser.ExistsExprContext ctx) {
        SelectStmt subquery = visitSelectStatement(ctx.selectStatement());
        return new ExistsExpr(subquery);
    }

    @Override
    public ExprNode visitAndExpr(MySQLParser.AndExprContext ctx) {
        ExprNode left = visitExpression(ctx.expression(0));
        ExprNode right = visitExpression(ctx.expression(1));
        return new BinaryExpr(left, BinaryExpr.Op.AND, right);
    }

    @Override
    public ExprNode visitOrExpr(MySQLParser.OrExprContext ctx) {
        ExprNode left = visitExpression(ctx.expression(0));
        ExprNode right = visitExpression(ctx.expression(1));
        return new BinaryExpr(left, BinaryExpr.Op.OR, right);
    }

    // ================================================================
    // Primary expressions
    // ================================================================

    @Override
    public ExprNode visitLiteralPrimary(MySQLParser.LiteralPrimaryContext ctx) {
        return (ExprNode) visit(ctx.literal());
    }

    @Override
    public ExprNode visitColumnRefPrimary(MySQLParser.ColumnRefPrimaryContext ctx) {
        return visitColumnRef(ctx.columnRef());
    }

    @Override
    public ExprNode visitFunctionCallPrimary(MySQLParser.FunctionCallPrimaryContext ctx) {
        return (ExprNode) visit(ctx.functionCall());
    }

    @Override
    public ExprNode visitSubqueryPrimary(MySQLParser.SubqueryPrimaryContext ctx) {
        SelectStmt query = visitSelectStatement(ctx.selectStatement());
        return new SubqueryExpr(query);
    }

    @Override
    public ExprNode visitParenPrimary(MySQLParser.ParenPrimaryContext ctx) {
        return visitExpression(ctx.expression());
    }

    @Override
    public ExprNode visitCaseWhenPrimary(MySQLParser.CaseWhenPrimaryContext ctx) {
        ExprNode caseExpr = ctx.caseExpr != null ? visitExpression(ctx.caseExpr) : null;

        List<WhenClause> whenClauses = new ArrayList<>();
        for (int i = 0; i < ctx.whenCondition.size(); i++) {
            ExprNode condition = visitExpression(ctx.whenCondition.get(i));
            ExprNode result = visitExpression(ctx.whenResult.get(i));
            whenClauses.add(new WhenClause(condition, result));
        }

        ExprNode elseExpr = ctx.elseExpr != null ? visitExpression(ctx.elseExpr) : null;
        return new CaseWhenExpr(caseExpr, whenClauses, elseExpr);
    }

    @Override
    public ExprNode visitCastPrimary(MySQLParser.CastPrimaryContext ctx) {
        ExprNode expr = visitExpression(ctx.expression());
        String targetType = extractDataTypeName(ctx.dataType());
        return new CastExpr(expr, targetType);
    }

    // ================================================================
    // Literals
    // ================================================================

    @Override
    public ExprNode visitIntLiteral(MySQLParser.IntLiteralContext ctx) {
        long value = Long.parseLong(ctx.INTEGER_LITERAL().getText());
        return LiteralExpr.intLiteral(value);
    }

    @Override
    public ExprNode visitDecLiteral(MySQLParser.DecLiteralContext ctx) {
        double value = Double.parseDouble(ctx.DECIMAL_LITERAL().getText());
        return LiteralExpr.decimalLiteral(value);
    }

    @Override
    public ExprNode visitStringLiteral(MySQLParser.StringLiteralContext ctx) {
        String raw = ctx.STRING_LITERAL().getText();
        return LiteralExpr.stringLiteral(unquoteString(raw));
    }

    @Override
    public ExprNode visitHexLiteral(MySQLParser.HexLiteralContext ctx) {
        String raw = ctx.HEX_LITERAL().getText();
        return LiteralExpr.hexLiteral(raw);
    }

    @Override
    public ExprNode visitTrueLiteral(MySQLParser.TrueLiteralContext ctx) {
        return LiteralExpr.boolLiteral(true);
    }

    @Override
    public ExprNode visitFalseLiteral(MySQLParser.FalseLiteralContext ctx) {
        return LiteralExpr.boolLiteral(false);
    }

    @Override
    public ExprNode visitNullLiteral(MySQLParser.NullLiteralContext ctx) {
        return LiteralExpr.nullLiteral();
    }

    // ================================================================
    // Column reference
    // ================================================================

    @Override
    public ColumnRefExpr visitColumnRef(MySQLParser.ColumnRefContext ctx) {
        String columnName = extractIdentifier(ctx.columnName().identifier());
        String tableName = null;
        if (ctx.tableName() != null) {
            tableName = extractTableName(ctx.tableName());
        }
        return new ColumnRefExpr(tableName, columnName);
    }

    // ================================================================
    // Function calls
    // ================================================================

    @Override
    public FunctionCallExpr visitCountStarCall(MySQLParser.CountStarCallContext ctx) {
        String name = extractIdentifier(ctx.functionName().identifier());
        return new FunctionCallExpr(name.toUpperCase(), List.of(new StarExpr(null)), false);
    }

    @Override
    public FunctionCallExpr visitRegularFunctionCall(MySQLParser.RegularFunctionCallContext ctx) {
        String name = extractIdentifier(ctx.functionName().identifier());
        boolean distinct = ctx.DISTINCT() != null;
        List<ExprNode> args = new ArrayList<>();
        if (ctx.functionArgs() != null) {
            for (MySQLParser.ExpressionContext ec : ctx.functionArgs().expression()) {
                args.add(visitExpression(ec));
            }
        }
        return new FunctionCallExpr(name.toUpperCase(), args, distinct);
    }

    // ================================================================
    // Helpers
    // ================================================================

    private String extractIdentifier(MySQLParser.IdentifierContext ctx) {
        if (ctx.BACKTICK_IDENTIFIER() != null) {
            String text = ctx.BACKTICK_IDENTIFIER().getText();
            // Strip backticks and unescape ``
            return text.substring(1, text.length() - 1).replace("``", "`");
        }
        // For IDENTIFIER or keyword tokens used as identifiers
        return ctx.getText();
    }

    private String extractTableName(MySQLParser.TableNameContext ctx) {
        String table = extractIdentifier(ctx.table);
        if (ctx.schema != null) {
            return extractIdentifier(ctx.schema) + "." + table;
        }
        return table;
    }

    /**
     * Unquotes a SQL string literal: strips surrounding quotes and unescapes
     * internal escape sequences.
     */
    static String unquoteString(String raw) {
        if (raw == null || raw.length() < 2) return raw;
        char quote = raw.charAt(0);
        String inner = raw.substring(1, raw.length() - 1);
        StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                char next = inner.charAt(i + 1);
                switch (next) {
                    case '\\': sb.append('\\'); break;
                    case '\'': sb.append('\''); break;
                    case '"':  sb.append('"'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case '0':  sb.append('\0'); break;
                    default:   sb.append(next); break;
                }
                i++;
            } else if (c == quote && i + 1 < inner.length() && inner.charAt(i + 1) == quote) {
                sb.append(quote);
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
