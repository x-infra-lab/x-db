package io.github.xinfra.lab.xdb.table;

import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Expression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class CopRequestCodec {
    private CopRequestCodec() {}

    private static final byte FLAG_SELECTION = 0x01;
    private static final byte FLAG_AGGREGATION = 0x02;
    private static final byte FLAG_TOPN = 0x04;

    private static final byte RESP_KV_PAIRS = 0x00;
    private static final byte RESP_AGG_RESULT = 0x01;

    // --- Records ---

    public record ColumnDesc(long id, DataType type, boolean autoIncrement, int offset) {}

    public record AggFuncDesc(AggFunction.Type type, boolean distinct, Expression arg) {}

    public record CopRequest(
            long tableId,
            List<ColumnDesc> columns,
            List<Integer> outputColumnIndices,
            List<Expression> conditions,
            List<Expression> groupByExprs,
            List<AggFuncDesc> aggFuncs,
            long topNLimit,
            long topNOffset,
            List<Expression> orderByExprs,
            List<Boolean> orderByAsc
    ) {}

    public sealed interface CopResponse {
        record FilteredRows(byte[] kvPairData) implements CopResponse {}
        record AggResult(List<AggGroupResult> groups) implements CopResponse {}
    }

    public record AggGroupResult(
            List<Datum> groupKeys,
            List<PartialAggState> partialStates
    ) {}

    public record PartialAggState(
            AggFunction.Type type,
            boolean distinct,
            List<Datum> state
    ) {}

    // --- Request encode/decode ---

    public static byte[] encodeRequest(CopRequest req) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(bos);

            out.writeLong(req.tableId);

            out.writeInt(req.columns.size());
            for (ColumnDesc col : req.columns) {
                out.writeLong(col.id);
                out.writeByte(col.type.ordinal());
                out.writeBoolean(col.autoIncrement);
                out.writeInt(col.offset);
            }

            out.writeInt(req.outputColumnIndices.size());
            for (int idx : req.outputColumnIndices) {
                out.writeInt(idx);
            }

            byte flags = 0;
            if (req.conditions != null && !req.conditions.isEmpty()) flags |= FLAG_SELECTION;
            if (req.aggFuncs != null && !req.aggFuncs.isEmpty()) flags |= FLAG_AGGREGATION;
            if (req.topNLimit >= 0) flags |= FLAG_TOPN;
            out.writeByte(flags);

            if ((flags & FLAG_SELECTION) != 0) {
                out.writeInt(req.conditions.size());
                for (Expression cond : req.conditions) {
                    ExpressionCodec.writeExpression(out, cond);
                }
            }

            if ((flags & FLAG_AGGREGATION) != 0) {
                out.writeInt(req.groupByExprs != null ? req.groupByExprs.size() : 0);
                if (req.groupByExprs != null) {
                    for (Expression gb : req.groupByExprs) {
                        ExpressionCodec.writeExpression(out, gb);
                    }
                }
                out.writeInt(req.aggFuncs.size());
                for (AggFuncDesc agg : req.aggFuncs) {
                    out.writeByte(agg.type.ordinal());
                    out.writeBoolean(agg.distinct);
                    boolean hasArg = agg.arg != null;
                    out.writeBoolean(hasArg);
                    if (hasArg) ExpressionCodec.writeExpression(out, agg.arg);
                }
            }

            if ((flags & FLAG_TOPN) != 0) {
                out.writeLong(req.topNLimit);
                out.writeLong(req.topNOffset);
                out.writeInt(req.orderByExprs != null ? req.orderByExprs.size() : 0);
                if (req.orderByExprs != null) {
                    for (int i = 0; i < req.orderByExprs.size(); i++) {
                        ExpressionCodec.writeExpression(out, req.orderByExprs.get(i));
                        out.writeBoolean(req.orderByAsc.get(i));
                    }
                }
            }

            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("encode request failed", e);
        }
    }

    public static CopRequest decodeRequest(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));

            long tableId = in.readLong();

            int numCols = in.readInt();
            List<ColumnDesc> columns = new ArrayList<>(numCols);
            for (int i = 0; i < numCols; i++) {
                long id = in.readLong();
                DataType type = DataType.values()[in.readByte()];
                boolean autoInc = in.readBoolean();
                int offset = in.readInt();
                columns.add(new ColumnDesc(id, type, autoInc, offset));
            }

            int numOutputCols = in.readInt();
            List<Integer> outputIndices = new ArrayList<>(numOutputCols);
            for (int i = 0; i < numOutputCols; i++) {
                outputIndices.add(in.readInt());
            }

            byte flags = in.readByte();

            List<Expression> conditions = List.of();
            if ((flags & FLAG_SELECTION) != 0) {
                int numConds = in.readInt();
                conditions = new ArrayList<>(numConds);
                for (int i = 0; i < numConds; i++) {
                    conditions.add(ExpressionCodec.readExpression(in));
                }
            }

            List<Expression> groupByExprs = List.of();
            List<AggFuncDesc> aggFuncs = List.of();
            if ((flags & FLAG_AGGREGATION) != 0) {
                int numGroupBy = in.readInt();
                groupByExprs = new ArrayList<>(numGroupBy);
                for (int i = 0; i < numGroupBy; i++) {
                    groupByExprs.add(ExpressionCodec.readExpression(in));
                }
                int numAggs = in.readInt();
                aggFuncs = new ArrayList<>(numAggs);
                for (int i = 0; i < numAggs; i++) {
                    AggFunction.Type type = AggFunction.Type.values()[in.readByte()];
                    boolean distinct = in.readBoolean();
                    boolean hasArg = in.readBoolean();
                    Expression arg = hasArg ? ExpressionCodec.readExpression(in) : null;
                    aggFuncs.add(new AggFuncDesc(type, distinct, arg));
                }
            }

            long topNLimit = -1;
            long topNOffset = 0;
            List<Expression> orderByExprs = List.of();
            List<Boolean> orderByAsc = List.of();
            if ((flags & FLAG_TOPN) != 0) {
                topNLimit = in.readLong();
                topNOffset = in.readLong();
                int numOrderBy = in.readInt();
                orderByExprs = new ArrayList<>(numOrderBy);
                orderByAsc = new ArrayList<>(numOrderBy);
                for (int i = 0; i < numOrderBy; i++) {
                    orderByExprs.add(ExpressionCodec.readExpression(in));
                    orderByAsc.add(in.readBoolean());
                }
            }

            return new CopRequest(tableId, columns, outputIndices, conditions,
                    groupByExprs, aggFuncs, topNLimit, topNOffset, orderByExprs, orderByAsc);
        } catch (IOException e) {
            throw new IllegalStateException("decode request failed", e);
        }
    }

    // --- Response encode/decode ---

    public static byte[] encodeResponse(CopResponse resp) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(bos);

            switch (resp) {
                case CopResponse.FilteredRows fr -> {
                    out.writeByte(RESP_KV_PAIRS);
                    out.writeInt(fr.kvPairData.length);
                    out.write(fr.kvPairData);
                }
                case CopResponse.AggResult ar -> {
                    out.writeByte(RESP_AGG_RESULT);
                    out.writeInt(ar.groups.size());
                    for (AggGroupResult group : ar.groups) {
                        out.writeInt(group.groupKeys.size());
                        for (Datum key : group.groupKeys) {
                            ExpressionCodec.writeDatum(out, key);
                        }
                        out.writeInt(group.partialStates.size());
                        for (PartialAggState state : group.partialStates) {
                            out.writeByte(state.type.ordinal());
                            out.writeBoolean(state.distinct);
                            out.writeInt(state.state.size());
                            for (Datum d : state.state) {
                                ExpressionCodec.writeDatum(out, d);
                            }
                        }
                    }
                }
            }

            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("encode response failed", e);
        }
    }

    public static CopResponse decodeResponse(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            byte type = in.readByte();

            return switch (type) {
                case RESP_KV_PAIRS -> {
                    int len = in.readInt();
                    byte[] kvData = new byte[len];
                    in.readFully(kvData);
                    yield new CopResponse.FilteredRows(kvData);
                }
                case RESP_AGG_RESULT -> {
                    int numGroups = in.readInt();
                    List<AggGroupResult> groups = new ArrayList<>(numGroups);
                    for (int g = 0; g < numGroups; g++) {
                        int numKeys = in.readInt();
                        List<Datum> groupKeys = new ArrayList<>(numKeys);
                        for (int k = 0; k < numKeys; k++) {
                            groupKeys.add(ExpressionCodec.readDatum(in));
                        }
                        int numAggs = in.readInt();
                        List<PartialAggState> states = new ArrayList<>(numAggs);
                        for (int a = 0; a < numAggs; a++) {
                            AggFunction.Type aggType = AggFunction.Type.values()[in.readByte()];
                            boolean distinct = in.readBoolean();
                            int numState = in.readInt();
                            List<Datum> stateData = new ArrayList<>(numState);
                            for (int s = 0; s < numState; s++) {
                                stateData.add(ExpressionCodec.readDatum(in));
                            }
                            states.add(new PartialAggState(aggType, distinct, stateData));
                        }
                        groups.add(new AggGroupResult(groupKeys, states));
                    }
                    yield new CopResponse.AggResult(groups);
                }
                default -> throw new IllegalArgumentException("Unknown response type: " + type);
            };
        } catch (IOException e) {
            throw new IllegalStateException("decode response failed", e);
        }
    }
}
