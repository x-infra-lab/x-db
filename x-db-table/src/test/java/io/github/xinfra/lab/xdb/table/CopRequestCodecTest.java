package io.github.xinfra.lab.xdb.table;

import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.BinaryOp;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.Constant;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Expression;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CopRequestCodecTest {

    // --- Request roundtrip ---

    @Test
    void selectionOnlyRequest() {
        var cols = List.of(
                new CopRequestCodec.ColumnDesc(1, DataType.BIGINT, true, 0),
                new CopRequestCodec.ColumnDesc(2, DataType.VARCHAR, false, 1));
        Expression cond = new BinaryOp(
                new ColumnRef(null, "id", 0, DataType.BIGINT),
                BinaryOp.Op.GT,
                Constant.ofLong(100));

        var req = new CopRequestCodec.CopRequest(
                42, cols, List.of(0, 1), List.of(cond),
                List.of(), List.of(), -1, 0, List.of(), List.of());

        byte[] encoded = CopRequestCodec.encodeRequest(req);
        var decoded = CopRequestCodec.decodeRequest(encoded);

        assertThat(decoded.tableId()).isEqualTo(42);
        assertThat(decoded.columns()).hasSize(2);
        assertThat(decoded.columns().get(0).id()).isEqualTo(1);
        assertThat(decoded.columns().get(0).type()).isEqualTo(DataType.BIGINT);
        assertThat(decoded.columns().get(0).autoIncrement()).isTrue();
        assertThat(decoded.columns().get(1).id()).isEqualTo(2);
        assertThat(decoded.columns().get(1).type()).isEqualTo(DataType.VARCHAR);
        assertThat(decoded.outputColumnIndices()).containsExactly(0, 1);
        assertThat(decoded.conditions()).hasSize(1);
        assertThat(decoded.aggFuncs()).isEmpty();
        assertThat(decoded.topNLimit()).isEqualTo(-1);
    }

    @Test
    void aggOnlyRequest() {
        var cols = List.of(
                new CopRequestCodec.ColumnDesc(1, DataType.BIGINT, true, 0),
                new CopRequestCodec.ColumnDesc(2, DataType.VARCHAR, false, 1));
        var groupBy = List.<Expression>of(
                new ColumnRef(null, "name", 1, DataType.VARCHAR));
        var aggFuncs = List.of(
                new CopRequestCodec.AggFuncDesc(AggFunction.Type.COUNT,
                        false, new ColumnRef(null, "id", 0, DataType.BIGINT)),
                new CopRequestCodec.AggFuncDesc(AggFunction.Type.SUM,
                        true, new ColumnRef(null, "id", 0, DataType.BIGINT)));

        var req = new CopRequestCodec.CopRequest(
                100, cols, List.of(0, 1), List.of(),
                groupBy, aggFuncs, -1, 0, List.of(), List.of());

        byte[] encoded = CopRequestCodec.encodeRequest(req);
        var decoded = CopRequestCodec.decodeRequest(encoded);

        assertThat(decoded.tableId()).isEqualTo(100);
        assertThat(decoded.conditions()).isEmpty();
        assertThat(decoded.groupByExprs()).hasSize(1);
        assertThat(decoded.aggFuncs()).hasSize(2);
        assertThat(decoded.aggFuncs().get(0).type()).isEqualTo(AggFunction.Type.COUNT);
        assertThat(decoded.aggFuncs().get(0).distinct()).isFalse();
        assertThat(decoded.aggFuncs().get(1).type()).isEqualTo(AggFunction.Type.SUM);
        assertThat(decoded.aggFuncs().get(1).distinct()).isTrue();
    }

    @Test
    void selectionAndAggRequest() {
        var cols = List.of(
                new CopRequestCodec.ColumnDesc(1, DataType.BIGINT, false, 0));
        Expression cond = new BinaryOp(
                new ColumnRef(null, "val", 0, DataType.BIGINT),
                BinaryOp.Op.GE, Constant.ofLong(0));
        var aggFuncs = List.of(
                new CopRequestCodec.AggFuncDesc(AggFunction.Type.AVG,
                        false, new ColumnRef(null, "val", 0, DataType.BIGINT)));

        var req = new CopRequestCodec.CopRequest(
                5, cols, List.of(0), List.of(cond),
                List.of(), aggFuncs, -1, 0, List.of(), List.of());

        byte[] encoded = CopRequestCodec.encodeRequest(req);
        var decoded = CopRequestCodec.decodeRequest(encoded);

        assertThat(decoded.conditions()).hasSize(1);
        assertThat(decoded.aggFuncs()).hasSize(1);
        assertThat(decoded.aggFuncs().get(0).type()).isEqualTo(AggFunction.Type.AVG);
    }

    @Test
    void topNRequest() {
        var cols = List.of(
                new CopRequestCodec.ColumnDesc(1, DataType.BIGINT, true, 0),
                new CopRequestCodec.ColumnDesc(2, DataType.VARCHAR, false, 1));
        var orderBy = List.<Expression>of(
                new ColumnRef(null, "id", 0, DataType.BIGINT));

        var req = new CopRequestCodec.CopRequest(
                10, cols, List.of(0, 1), List.of(),
                List.of(), List.of(), 20, 5, orderBy, List.of(true));

        byte[] encoded = CopRequestCodec.encodeRequest(req);
        var decoded = CopRequestCodec.decodeRequest(encoded);

        assertThat(decoded.topNLimit()).isEqualTo(20);
        assertThat(decoded.topNOffset()).isEqualTo(5);
        assertThat(decoded.orderByExprs()).hasSize(1);
        assertThat(decoded.orderByAsc()).containsExactly(true);
    }

    @Test
    void noConditionsNoAggRequest() {
        var cols = List.of(
                new CopRequestCodec.ColumnDesc(1, DataType.BIGINT, false, 0));
        var req = new CopRequestCodec.CopRequest(
                1, cols, List.of(0), List.of(),
                List.of(), List.of(), -1, 0, List.of(), List.of());

        byte[] encoded = CopRequestCodec.encodeRequest(req);
        var decoded = CopRequestCodec.decodeRequest(encoded);

        assertThat(decoded.conditions()).isEmpty();
        assertThat(decoded.aggFuncs()).isEmpty();
        assertThat(decoded.topNLimit()).isEqualTo(-1);
    }

    @Test
    void aggWithNullArgRequest() {
        var cols = List.of(
                new CopRequestCodec.ColumnDesc(1, DataType.BIGINT, false, 0));
        var aggFuncs = List.of(
                new CopRequestCodec.AggFuncDesc(AggFunction.Type.COUNT, false, null));

        var req = new CopRequestCodec.CopRequest(
                1, cols, List.of(0), List.of(),
                List.of(), aggFuncs, -1, 0, List.of(), List.of());

        byte[] encoded = CopRequestCodec.encodeRequest(req);
        var decoded = CopRequestCodec.decodeRequest(encoded);

        assertThat(decoded.aggFuncs().get(0).arg()).isNull();
    }

    // --- Response roundtrip ---

    @Test
    void filteredRowsResponse() {
        byte[] kvData = KvPairDecoder.encode(List.of(
                new KvPairDecoder.KvPair(new byte[]{1, 2}, new byte[]{3, 4}),
                new KvPairDecoder.KvPair(new byte[]{5}, new byte[]{6, 7, 8})));

        var resp = new CopRequestCodec.CopResponse.FilteredRows(kvData);
        byte[] encoded = CopRequestCodec.encodeResponse(resp);
        var decoded = CopRequestCodec.decodeResponse(encoded);

        assertThat(decoded).isInstanceOf(CopRequestCodec.CopResponse.FilteredRows.class);
        var fr = (CopRequestCodec.CopResponse.FilteredRows) decoded;
        var pairs = KvPairDecoder.decode(fr.kvPairData());
        assertThat(pairs).hasSize(2);
        assertThat(pairs.get(0).key()).isEqualTo(new byte[]{1, 2});
    }

    @Test
    void aggResultResponse() {
        var groups = List.of(
                new CopRequestCodec.AggGroupResult(
                        List.of(Datum.of("group1")),
                        List.of(
                                new CopRequestCodec.PartialAggState(
                                        AggFunction.Type.COUNT, false, List.of(Datum.of(10L))),
                                new CopRequestCodec.PartialAggState(
                                        AggFunction.Type.SUM, false,
                                        List.of(Datum.of(new BigDecimal("123.45")))))),
                new CopRequestCodec.AggGroupResult(
                        List.of(Datum.of("group2")),
                        List.of(
                                new CopRequestCodec.PartialAggState(
                                        AggFunction.Type.COUNT, false, List.of(Datum.of(5L))),
                                new CopRequestCodec.PartialAggState(
                                        AggFunction.Type.SUM, false,
                                        List.of(Datum.of(new BigDecimal("67.89")))))));

        var resp = new CopRequestCodec.CopResponse.AggResult(groups);
        byte[] encoded = CopRequestCodec.encodeResponse(resp);
        var decoded = CopRequestCodec.decodeResponse(encoded);

        assertThat(decoded).isInstanceOf(CopRequestCodec.CopResponse.AggResult.class);
        var ar = (CopRequestCodec.CopResponse.AggResult) decoded;
        assertThat(ar.groups()).hasSize(2);

        var g0 = ar.groups().get(0);
        assertThat(g0.groupKeys()).hasSize(1);
        assertThat(g0.groupKeys().get(0).toStringValue()).isEqualTo("group1");
        assertThat(g0.partialStates()).hasSize(2);
        assertThat(g0.partialStates().get(0).type()).isEqualTo(AggFunction.Type.COUNT);
        assertThat(g0.partialStates().get(0).state().get(0).toLong()).isEqualTo(10);
        assertThat(g0.partialStates().get(1).type()).isEqualTo(AggFunction.Type.SUM);
    }

    @Test
    void emptyAggResultResponse() {
        var resp = new CopRequestCodec.CopResponse.AggResult(List.of());
        byte[] encoded = CopRequestCodec.encodeResponse(resp);
        var decoded = CopRequestCodec.decodeResponse(encoded);

        assertThat(decoded).isInstanceOf(CopRequestCodec.CopResponse.AggResult.class);
        assertThat(((CopRequestCodec.CopResponse.AggResult) decoded).groups()).isEmpty();
    }

    @Test
    void aggResultNoGroupKeysResponse() {
        var groups = List.of(
                new CopRequestCodec.AggGroupResult(
                        List.of(),
                        List.of(new CopRequestCodec.PartialAggState(
                                AggFunction.Type.COUNT, false, List.of(Datum.of(42L))))));

        var resp = new CopRequestCodec.CopResponse.AggResult(groups);
        byte[] encoded = CopRequestCodec.encodeResponse(resp);
        var decoded = CopRequestCodec.decodeResponse(encoded);

        var ar = (CopRequestCodec.CopResponse.AggResult) decoded;
        assertThat(ar.groups().get(0).groupKeys()).isEmpty();
        assertThat(ar.groups().get(0).partialStates().get(0).state().get(0).toLong()).isEqualTo(42);
    }

    @Test
    void avgPartialStateResponse() {
        var groups = List.of(
                new CopRequestCodec.AggGroupResult(
                        List.of(),
                        List.of(new CopRequestCodec.PartialAggState(
                                AggFunction.Type.AVG, false,
                                List.of(Datum.of(5L), Datum.of(new BigDecimal("125.50")))))));

        var resp = new CopRequestCodec.CopResponse.AggResult(groups);
        byte[] encoded = CopRequestCodec.encodeResponse(resp);
        var decoded = CopRequestCodec.decodeResponse(encoded);

        var state = ((CopRequestCodec.CopResponse.AggResult) decoded)
                .groups().get(0).partialStates().get(0);
        assertThat(state.type()).isEqualTo(AggFunction.Type.AVG);
        assertThat(state.state().get(0).toLong()).isEqualTo(5);
        assertThat(state.state().get(1)).isInstanceOf(Datum.DecimalDatum.class);
    }
}
