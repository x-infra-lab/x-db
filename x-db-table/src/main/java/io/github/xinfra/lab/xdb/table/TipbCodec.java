package io.github.xinfra.lab.xdb.table;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.BetweenExpr;
import io.github.xinfra.lab.xdb.expression.BinaryOp;
import io.github.xinfra.lab.xdb.expression.CaseWhenExpr;
import io.github.xinfra.lab.xdb.expression.CastExpr;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.Constant;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.FunctionCallExpr;
import io.github.xinfra.lab.xdb.expression.InExpr;
import io.github.xinfra.lab.xdb.expression.LikeExpr;
import io.github.xinfra.lab.xdb.expression.UnaryOp;
import io.github.xinfra.lab.xkv.proto.Tipb;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class TipbCodec {
    private TipbCodec() {}

    // --- Expression → Tipb.Expr ---

    public static Tipb.Expr encodeExpr(Expression expr) {
        return switch (expr) {
            case Constant c -> Tipb.Expr.newBuilder()
                    .setTp(Tipb.ExprType.CONSTANT)
                    .setVal(encodeDatum(c.value()))
                    .setDataType(c.returnType().ordinal())
                    .build();
            case ColumnRef c -> Tipb.Expr.newBuilder()
                    .setTp(Tipb.ExprType.COLUMN_REF)
                    .setTableName(c.tableName() != null ? c.tableName() : "")
                    .setColumnName(c.columnName())
                    .setColumnIndex(c.index())
                    .setDataType(c.returnType().ordinal())
                    .build();
            case BinaryOp b -> Tipb.Expr.newBuilder()
                    .setTp(Tipb.ExprType.BINARY_OP)
                    .setOp(b.op().ordinal())
                    .addChildren(encodeExpr(b.left()))
                    .addChildren(encodeExpr(b.right()))
                    .build();
            case UnaryOp u -> Tipb.Expr.newBuilder()
                    .setTp(Tipb.ExprType.UNARY_OP)
                    .setOp(u.op().ordinal())
                    .addChildren(encodeExpr(u.operand()))
                    .build();
            case LikeExpr l -> Tipb.Expr.newBuilder()
                    .setTp(Tipb.ExprType.LIKE)
                    .setNot(l.not())
                    .addChildren(encodeExpr(l.expr()))
                    .addChildren(encodeExpr(l.pattern()))
                    .build();
            case InExpr i -> {
                var b = Tipb.Expr.newBuilder()
                        .setTp(Tipb.ExprType.IN)
                        .setNot(i.not())
                        .addChildren(encodeExpr(i.expr()));
                for (Expression e : i.list()) b.addChildren(encodeExpr(e));
                yield b.build();
            }
            case BetweenExpr be -> Tipb.Expr.newBuilder()
                    .setTp(Tipb.ExprType.BETWEEN)
                    .setNot(be.not())
                    .addChildren(encodeExpr(be.expr()))
                    .addChildren(encodeExpr(be.low()))
                    .addChildren(encodeExpr(be.high()))
                    .build();
            case CastExpr c -> Tipb.Expr.newBuilder()
                    .setTp(Tipb.ExprType.CAST)
                    .setDataType(c.targetType().ordinal())
                    .addChildren(encodeExpr(c.expr()))
                    .build();
            case CaseWhenExpr cw -> {
                var b = Tipb.Expr.newBuilder().setTp(Tipb.ExprType.CASE_WHEN);
                if (cw.compareExpr() != null) {
                    b.setOp(1);
                    b.addChildren(encodeExpr(cw.compareExpr()));
                }
                for (var wc : cw.whenClauses()) {
                    b.addChildren(encodeExpr(wc.condition()));
                    b.addChildren(encodeExpr(wc.result()));
                }
                if (cw.elseExpr() != null) {
                    b.addChildren(encodeExpr(cw.elseExpr()));
                }
                yield b.build();
            }
            case FunctionCallExpr f -> {
                var b = Tipb.Expr.newBuilder()
                        .setTp(Tipb.ExprType.FUNCTION_CALL)
                        .setFuncName(f.name());
                for (Expression a : f.args()) b.addChildren(encodeExpr(a));
                yield b.build();
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported expression: " + expr.getClass().getSimpleName());
        };
    }

    // --- Datum → Tipb.Datum ---

    public static Tipb.Datum encodeDatum(Datum datum) {
        Tipb.Datum.Builder b = Tipb.Datum.newBuilder();
        switch (datum) {
            case Datum.IntDatum d -> b.setIntVal(d.value());
            case Datum.DoubleDatum d -> b.setDoubleVal(d.value());
            case Datum.DecimalDatum d -> b.setDecimalVal(d.value().toPlainString());
            case Datum.StringDatum d -> b.setStringVal(d.value());
            case Datum.BytesDatum d -> b.setBytesVal(ByteString.copyFrom(d.value()));
            case Datum.DateTimeDatum d -> b.setDatetimeVal(
                    d.value().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            case Datum.NullDatum n -> b.setNullVal(true);
        }
        return b.build();
    }

    // --- Tipb.Datum → Datum ---

    public static Datum decodeDatum(Tipb.Datum proto) {
        return switch (proto.getValueCase()) {
            case INT_VAL -> Datum.of(proto.getIntVal());
            case DOUBLE_VAL -> Datum.of(proto.getDoubleVal());
            case DECIMAL_VAL -> Datum.of(new BigDecimal(proto.getDecimalVal()));
            case STRING_VAL -> Datum.of(proto.getStringVal());
            case BYTES_VAL -> Datum.of(proto.getBytesVal().toByteArray());
            case DATETIME_VAL -> Datum.of(LocalDateTime.parse(proto.getDatetimeVal(),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            case NULL_VAL -> Datum.nil();
            case VALUE_NOT_SET -> Datum.nil();
        };
    }

    // --- CopRequest → DAGRequest bytes ---

    public static byte[] encodeDAGRequest(CopRequestCodec.CopRequest req) {
        Tipb.DAGRequest.Builder b = Tipb.DAGRequest.newBuilder()
                .setTableId(req.tableId());

        for (CopRequestCodec.ColumnDesc col : req.columns()) {
            b.addColumns(Tipb.ColumnInfo.newBuilder()
                    .setColumnId(col.id())
                    .setDataType(col.type().ordinal())
                    .setAutoIncrement(col.autoIncrement())
                    .setOffset(col.offset()));
        }

        for (int idx : req.outputColumnIndices()) {
            b.addOutputColumnIndices(idx);
        }

        if (req.conditions() != null) {
            for (Expression cond : req.conditions()) {
                b.addConditions(encodeExpr(cond));
            }
        }

        if (req.groupByExprs() != null) {
            for (Expression gb : req.groupByExprs()) {
                b.addGroupByExprs(encodeExpr(gb));
            }
        }

        if (req.aggFuncs() != null) {
            for (CopRequestCodec.AggFuncDesc agg : req.aggFuncs()) {
                Tipb.AggFuncDesc.Builder aggB = Tipb.AggFuncDesc.newBuilder()
                        .setAggType(agg.type().ordinal())
                        .setDistinct(agg.distinct());
                if (agg.arg() != null) aggB.setArg(encodeExpr(agg.arg()));
                b.addAggFuncs(aggB);
            }
        }

        b.setTopnLimit(req.topNLimit());
        b.setTopnOffset(req.topNOffset());

        if (req.orderByExprs() != null) {
            for (int i = 0; i < req.orderByExprs().size(); i++) {
                b.addOrderBy(Tipb.ByItem.newBuilder()
                        .setExpr(encodeExpr(req.orderByExprs().get(i)))
                        .setDesc(!req.orderByAsc().get(i)));
            }
        }

        return b.build().toByteArray();
    }

    // --- AnalyzeReq ---

    public static byte[] encodeAnalyzeReq(long tableId,
                                             List<CopRequestCodec.ColumnDesc> columns,
                                             int sampleSize) {
        Tipb.AnalyzeReq.Builder b = Tipb.AnalyzeReq.newBuilder()
                .setTableId(tableId)
                .setSampleSize(sampleSize);

        for (int i = 0; i < columns.size(); i++) {
            CopRequestCodec.ColumnDesc col = columns.get(i);
            b.addColumns(Tipb.ColumnInfo.newBuilder()
                    .setColumnId(col.id())
                    .setDataType(col.type().ordinal())
                    .setAutoIncrement(col.autoIncrement())
                    .setOffset(col.offset()));
            b.addOutputColumnIndices(i);
        }

        return b.build().toByteArray();
    }

    public record AnalyzeColumnResult(
            long columnId, long ndv, long nullCount, long totalCount,
            Datum minValue, Datum maxValue, List<Datum> sampleValues
    ) {}

    public record AnalyzeResult(long rowCount, List<AnalyzeColumnResult> columnResults) {}

    public static AnalyzeResult decodeAnalyzeResult(byte[] data) {
        try {
            Tipb.AnalyzeResult proto = Tipb.AnalyzeResult.parseFrom(data);
            List<AnalyzeColumnResult> results = new ArrayList<>(proto.getColumnResultsCount());
            for (Tipb.AnalyzeColumnResult cr : proto.getColumnResultsList()) {
                List<Datum> samples = new ArrayList<>(cr.getSampleValuesCount());
                for (Tipb.Datum sd : cr.getSampleValuesList()) {
                    samples.add(decodeDatum(sd));
                }
                results.add(new AnalyzeColumnResult(
                        cr.getColumnId(), cr.getNdv(), cr.getNullCount(), cr.getTotalCount(),
                        cr.hasMinValue() ? decodeDatum(cr.getMinValue()) : Datum.nil(),
                        cr.hasMaxValue() ? decodeDatum(cr.getMaxValue()) : Datum.nil(),
                        samples
                ));
            }
            return new AnalyzeResult(proto.getRowCount(), results);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Failed to parse AnalyzeResult", e);
        }
    }

    // --- Tipb.Expr → Expression ---

    public static Expression decodeExpr(Tipb.Expr proto) {
        return switch (proto.getTp()) {
            case CONSTANT -> {
                DataType dt = DataType.values()[proto.getDataType()];
                Datum val = proto.hasVal() ? decodeDatum(proto.getVal()) : Datum.nil();
                yield new Constant(val, dt);
            }
            case COLUMN_REF -> {
                String tableName = proto.getTableName().isEmpty() ? null : proto.getTableName();
                DataType dt = DataType.values()[proto.getDataType()];
                yield new ColumnRef(tableName, proto.getColumnName(), proto.getColumnIndex(), dt);
            }
            case BINARY_OP -> {
                BinaryOp.Op op = BinaryOp.Op.values()[proto.getOp()];
                yield new BinaryOp(decodeExpr(proto.getChildren(0)), op, decodeExpr(proto.getChildren(1)));
            }
            case UNARY_OP -> {
                UnaryOp.Op op = UnaryOp.Op.values()[proto.getOp()];
                yield new UnaryOp(op, decodeExpr(proto.getChildren(0)));
            }
            case LIKE -> new LikeExpr(
                    decodeExpr(proto.getChildren(0)),
                    decodeExpr(proto.getChildren(1)),
                    proto.getNot());
            case IN -> {
                Expression expr = decodeExpr(proto.getChildren(0));
                List<Expression> list = new ArrayList<>(proto.getChildrenCount() - 1);
                for (int i = 1; i < proto.getChildrenCount(); i++) {
                    list.add(decodeExpr(proto.getChildren(i)));
                }
                yield new InExpr(expr, list, proto.getNot());
            }
            case BETWEEN -> new BetweenExpr(
                    decodeExpr(proto.getChildren(0)),
                    decodeExpr(proto.getChildren(1)),
                    decodeExpr(proto.getChildren(2)),
                    proto.getNot());
            case CAST -> {
                DataType targetType = DataType.values()[proto.getDataType()];
                yield new CastExpr(decodeExpr(proto.getChildren(0)), targetType);
            }
            case CASE_WHEN -> {
                int idx = 0;
                Expression compareExpr = null;
                if (proto.getOp() == 1) {
                    compareExpr = decodeExpr(proto.getChildren(idx++));
                }
                List<CaseWhenExpr.WhenClause> clauses = new ArrayList<>();
                int remaining = proto.getChildrenCount() - idx;
                boolean hasElse = remaining % 2 != 0;
                int pairCount = remaining / 2;
                for (int i = 0; i < pairCount; i++) {
                    clauses.add(new CaseWhenExpr.WhenClause(
                            decodeExpr(proto.getChildren(idx++)),
                            decodeExpr(proto.getChildren(idx++))));
                }
                Expression elseExpr = hasElse ? decodeExpr(proto.getChildren(idx)) : null;
                yield new CaseWhenExpr(compareExpr, clauses, elseExpr);
            }
            case FUNCTION_CALL -> {
                List<Expression> args = new ArrayList<>(proto.getChildrenCount());
                for (int i = 0; i < proto.getChildrenCount(); i++) {
                    args.add(decodeExpr(proto.getChildren(i)));
                }
                yield new FunctionCallExpr(proto.getFuncName(), args);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported expression type: " + proto.getTp());
        };
    }

    // --- DAGRequest bytes → CopRequest ---

    public static CopRequestCodec.CopRequest decodeDAGRequest(byte[] data) {
        try {
            Tipb.DAGRequest dag = Tipb.DAGRequest.parseFrom(data);

            long tableId = dag.getTableId();

            List<CopRequestCodec.ColumnDesc> columns = new ArrayList<>(dag.getColumnsCount());
            for (Tipb.ColumnInfo ci : dag.getColumnsList()) {
                columns.add(new CopRequestCodec.ColumnDesc(
                        ci.getColumnId(),
                        DataType.values()[ci.getDataType()],
                        ci.getAutoIncrement(),
                        ci.getOffset()));
            }

            List<Integer> outputColumnIndices = new ArrayList<>(dag.getOutputColumnIndicesCount());
            for (int i = 0; i < dag.getOutputColumnIndicesCount(); i++) {
                outputColumnIndices.add(dag.getOutputColumnIndices(i));
            }

            List<Expression> conditions = new ArrayList<>(dag.getConditionsCount());
            for (Tipb.Expr expr : dag.getConditionsList()) {
                conditions.add(decodeExpr(expr));
            }

            List<Expression> groupByExprs = new ArrayList<>(dag.getGroupByExprsCount());
            for (Tipb.Expr expr : dag.getGroupByExprsList()) {
                groupByExprs.add(decodeExpr(expr));
            }

            List<CopRequestCodec.AggFuncDesc> aggFuncs = new ArrayList<>(dag.getAggFuncsCount());
            for (Tipb.AggFuncDesc afd : dag.getAggFuncsList()) {
                AggFunction.Type type = AggFunction.Type.values()[afd.getAggType()];
                Expression arg = afd.hasArg() ? decodeExpr(afd.getArg()) : null;
                aggFuncs.add(new CopRequestCodec.AggFuncDesc(type, afd.getDistinct(), arg));
            }

            long topNLimit = dag.getTopnLimit();
            long topNOffset = dag.getTopnOffset();

            List<Expression> orderByExprs = new ArrayList<>(dag.getOrderByCount());
            List<Boolean> orderByAsc = new ArrayList<>(dag.getOrderByCount());
            for (Tipb.ByItem bi : dag.getOrderByList()) {
                orderByExprs.add(decodeExpr(bi.getExpr()));
                orderByAsc.add(!bi.getDesc());
            }

            return new CopRequestCodec.CopRequest(
                    tableId, columns, outputColumnIndices, conditions,
                    groupByExprs, aggFuncs, topNLimit, topNOffset,
                    orderByExprs, orderByAsc);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Failed to parse DAGRequest", e);
        }
    }

    // --- CopResponse → SelectResponse bytes ---

    public static byte[] encodeSelectResponse(CopRequestCodec.CopResponse resp) {
        Tipb.SelectResponse.Builder b = Tipb.SelectResponse.newBuilder();

        switch (resp) {
            case CopRequestCodec.CopResponse.FilteredRows fr -> {
                b.setIsAgg(false);
                b.setKvPairData(ByteString.copyFrom(fr.kvPairData()));
            }
            case CopRequestCodec.CopResponse.AggResult ar -> {
                b.setIsAgg(true);
                for (CopRequestCodec.AggGroupResult group : ar.groups()) {
                    Tipb.AggGroupResult.Builder gb = Tipb.AggGroupResult.newBuilder();
                    for (Datum key : group.groupKeys()) {
                        gb.addGroupKeys(encodeDatum(key));
                    }
                    for (CopRequestCodec.PartialAggState state : group.partialStates()) {
                        Tipb.PartialAggState.Builder sb = Tipb.PartialAggState.newBuilder()
                                .setAggType(state.type().ordinal())
                                .setDistinct(state.distinct());
                        for (Datum d : state.state()) {
                            sb.addState(encodeDatum(d));
                        }
                        gb.addPartialStates(sb);
                    }
                    b.addAggGroups(gb);
                }
            }
        }

        return b.build().toByteArray();
    }

    // --- SelectResponse bytes → CopResponse ---

    public static CopRequestCodec.CopResponse decodeResponse(byte[] data) {
        try {
            Tipb.SelectResponse resp = Tipb.SelectResponse.parseFrom(data);

            if (resp.getIsAgg()) {
                List<CopRequestCodec.AggGroupResult> groups = new ArrayList<>(resp.getAggGroupsCount());
                for (Tipb.AggGroupResult protoGroup : resp.getAggGroupsList()) {
                    List<Datum> groupKeys = new ArrayList<>(protoGroup.getGroupKeysCount());
                    for (Tipb.Datum pk : protoGroup.getGroupKeysList()) {
                        groupKeys.add(decodeDatum(pk));
                    }

                    List<CopRequestCodec.PartialAggState> states = new ArrayList<>(protoGroup.getPartialStatesCount());
                    for (Tipb.PartialAggState ps : protoGroup.getPartialStatesList()) {
                        AggFunction.Type aggType = AggFunction.Type.values()[ps.getAggType()];
                        List<Datum> stateData = new ArrayList<>(ps.getStateCount());
                        for (Tipb.Datum sd : ps.getStateList()) {
                            stateData.add(decodeDatum(sd));
                        }
                        states.add(new CopRequestCodec.PartialAggState(aggType, ps.getDistinct(), stateData));
                    }
                    groups.add(new CopRequestCodec.AggGroupResult(groupKeys, states));
                }
                return new CopRequestCodec.CopResponse.AggResult(groups);
            } else {
                byte[] kvData = resp.getKvPairData().toByteArray();
                return new CopRequestCodec.CopResponse.FilteredRows(kvData);
            }
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Failed to parse SelectResponse", e);
        }
    }
}
