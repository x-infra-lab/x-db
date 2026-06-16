package io.github.xinfra.lab.xdb.planner.cost;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.xinfra.lab.xdb.expression.Datum;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StatsSerializer {
    private StatsSerializer() {}

    private static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper();
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SimpleModule module = new SimpleModule();
        module.addSerializer(Datum.class, new DatumSerializer());
        module.addDeserializer(Datum.class, new DatumDeserializer());
        MAPPER.registerModule(module);
    }

    public static byte[] serialize(TableStatistics stats) {
        try {
            Map<String, Object> root = new HashMap<>();
            root.put("rowCount", stats.getRowCount());
            root.put("dataSize", stats.getDataSize());
            root.put("lastAnalyzeTime", stats.getLastAnalyzeTime());

            Map<String, Object> columns = new HashMap<>();
            if (stats.getColumnStats() != null) {
                for (var entry : stats.getColumnStats().entrySet()) {
                    columns.put(entry.getKey(), serializeColumnStats(entry.getValue()));
                }
            }
            root.put("columnStats", columns);
            return MAPPER.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize table statistics", e);
        }
    }

    public static TableStatistics deserialize(byte[] data) {
        try {
            Map<String, Object> root = MAPPER.readValue(data, new TypeReference<>() {});
            TableStatistics stats = new TableStatistics();
            stats.setRowCount(toLong(root.get("rowCount")));
            stats.setDataSize(toLong(root.get("dataSize")));
            stats.setLastAnalyzeTime(toLong(root.get("lastAnalyzeTime")));

            @SuppressWarnings("unchecked")
            Map<String, Object> columns = (Map<String, Object>) root.get("columnStats");
            if (columns != null) {
                Map<String, ColumnStatistics> columnStats = new HashMap<>();
                for (var entry : columns.entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> colMap = (Map<String, Object>) entry.getValue();
                    columnStats.put(entry.getKey(), deserializeColumnStats(colMap));
                }
                stats.setColumnStats(columnStats);
            }
            return stats;
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize table statistics", e);
        }
    }

    private static Map<String, Object> serializeColumnStats(ColumnStatistics cs) throws IOException {
        Map<String, Object> map = new HashMap<>();
        map.put("ndv", cs.getNdv());
        map.put("nullCount", cs.getNullCount());
        map.put("totalCount", cs.getTotalCount());
        if (cs.getMinValue() != null) map.put("minValue", serializeDatum(cs.getMinValue()));
        if (cs.getMaxValue() != null) map.put("maxValue", serializeDatum(cs.getMaxValue()));

        if (cs.getHistogram() != null) {
            map.put("histogram", serializeHistogram(cs.getHistogram()));
        }
        return map;
    }

    private static ColumnStatistics deserializeColumnStats(Map<String, Object> map) {
        ColumnStatistics cs = new ColumnStatistics();
        cs.setNdv(toLong(map.get("ndv")));
        cs.setNullCount(toLong(map.get("nullCount")));
        cs.setTotalCount(toLong(map.get("totalCount")));

        @SuppressWarnings("unchecked")
        Map<String, Object> minMap = (Map<String, Object>) map.get("minValue");
        if (minMap != null) cs.setMinValue(deserializeDatum(minMap));

        @SuppressWarnings("unchecked")
        Map<String, Object> maxMap = (Map<String, Object>) map.get("maxValue");
        if (maxMap != null) cs.setMaxValue(deserializeDatum(maxMap));

        @SuppressWarnings("unchecked")
        Map<String, Object> histMap = (Map<String, Object>) map.get("histogram");
        if (histMap != null) cs.setHistogram(deserializeHistogram(histMap));

        return cs;
    }

    private static Map<String, Object> serializeHistogram(Histogram h) {
        Map<String, Object> map = new HashMap<>();
        map.put("totalCount", h.getTotalCount());
        map.put("ndv", h.getNdv());
        if (h.getMinValue() != null) map.put("minValue", serializeDatum(h.getMinValue()));

        List<Map<String, Object>> bucketList = new ArrayList<>();
        for (var b : h.getBuckets()) {
            Map<String, Object> bm = new HashMap<>();
            bm.put("upperBound", serializeDatum(b.upperBound()));
            bm.put("count", b.count());
            bm.put("repeats", b.repeats());
            bm.put("ndv", b.ndv());
            bucketList.add(bm);
        }
        map.put("buckets", bucketList);
        return map;
    }

    private static Histogram deserializeHistogram(Map<String, Object> map) {
        long totalCount = toLong(map.get("totalCount"));
        long ndv = toLong(map.get("ndv"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bucketList = (List<Map<String, Object>>) map.get("buckets");
        List<Histogram.Bucket> buckets = new ArrayList<>();
        if (bucketList != null) {
            for (var bm : bucketList) {
                @SuppressWarnings("unchecked")
                Map<String, Object> ubMap = (Map<String, Object>) bm.get("upperBound");
                Datum upperBound = deserializeDatum(ubMap);
                buckets.add(new Histogram.Bucket(
                        upperBound,
                        toLong(bm.get("count")),
                        toLong(bm.get("repeats")),
                        toLong(bm.get("ndv"))
                ));
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> minMap = (Map<String, Object>) map.get("minValue");
        Datum minValue = minMap != null ? deserializeDatum(minMap) : null;

        return new Histogram(buckets, totalCount, ndv, minValue);
    }

    private static Map<String, Object> serializeDatum(Datum datum) {
        Map<String, Object> map = new HashMap<>();
        switch (datum) {
            case Datum.IntDatum d -> { map.put("type", "INT"); map.put("value", d.value()); }
            case Datum.DoubleDatum d -> { map.put("type", "DOUBLE"); map.put("value", d.value()); }
            case Datum.DecimalDatum d -> { map.put("type", "DECIMAL"); map.put("value", d.value().toPlainString()); }
            case Datum.StringDatum d -> { map.put("type", "STRING"); map.put("value", d.value()); }
            case Datum.BytesDatum d -> {
                map.put("type", "BYTES");
                map.put("value", java.util.Base64.getEncoder().encodeToString(d.value()));
            }
            case Datum.DateTimeDatum d -> {
                map.put("type", "DATETIME");
                map.put("value", d.value().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            }
            case Datum.NullDatum n -> map.put("type", "NULL");
        }
        return map;
    }

    private static Datum deserializeDatum(Map<String, Object> map) {
        if (map == null) return Datum.nil();
        String type = (String) map.get("type");
        if (type == null || "NULL".equals(type)) return Datum.nil();

        Object value = map.get("value");
        return switch (type) {
            case "INT" -> Datum.of(toLong(value));
            case "DOUBLE" -> Datum.of(((Number) value).doubleValue());
            case "DECIMAL" -> Datum.of(new BigDecimal((String) value));
            case "STRING" -> Datum.of((String) value);
            case "BYTES" -> Datum.of(java.util.Base64.getDecoder().decode((String) value));
            case "DATETIME" -> Datum.of(LocalDateTime.parse((String) value, DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            default -> Datum.nil();
        };
    }

    private static long toLong(Object obj) {
        if (obj instanceof Number n) return n.longValue();
        if (obj instanceof String s) return Long.parseLong(s);
        return 0L;
    }

    static final class DatumSerializer extends JsonSerializer<Datum> {
        @Override
        public void serialize(Datum value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            switch (value) {
                case Datum.IntDatum d -> { gen.writeStringField("type", "INT"); gen.writeNumberField("value", d.value()); }
                case Datum.DoubleDatum d -> { gen.writeStringField("type", "DOUBLE"); gen.writeNumberField("value", d.value()); }
                case Datum.DecimalDatum d -> { gen.writeStringField("type", "DECIMAL"); gen.writeStringField("value", d.value().toPlainString()); }
                case Datum.StringDatum d -> { gen.writeStringField("type", "STRING"); gen.writeStringField("value", d.value()); }
                case Datum.BytesDatum d -> { gen.writeStringField("type", "BYTES"); gen.writeStringField("value", java.util.Base64.getEncoder().encodeToString(d.value())); }
                case Datum.DateTimeDatum d -> { gen.writeStringField("type", "DATETIME"); gen.writeStringField("value", d.value().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)); }
                case Datum.NullDatum n -> gen.writeStringField("type", "NULL");
            }
            gen.writeEndObject();
        }
    }

    static final class DatumDeserializer extends JsonDeserializer<Datum> {
        @Override
        public Datum deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            ObjectNode node = p.getCodec().readTree(p);
            String type = node.has("type") ? node.get("type").asText() : "NULL";
            return switch (type) {
                case "INT" -> Datum.of(node.get("value").asLong());
                case "DOUBLE" -> Datum.of(node.get("value").asDouble());
                case "DECIMAL" -> Datum.of(new BigDecimal(node.get("value").asText()));
                case "STRING" -> Datum.of(node.get("value").asText());
                case "BYTES" -> Datum.of(java.util.Base64.getDecoder().decode(node.get("value").asText()));
                case "DATETIME" -> Datum.of(LocalDateTime.parse(node.get("value").asText(), DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                default -> Datum.nil();
            };
        }
    }
}
