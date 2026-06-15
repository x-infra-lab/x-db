package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AggFunctionsTest {

    @Nested
    class Count {

        @Test
        void countNonNullValues() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.COUNT, null, false);
            agg.update(Datum.of(1L));
            agg.update(Datum.of(2L));
            agg.update(Datum.of(3L));
            assertThat(agg.result().toLong()).isEqualTo(3);
        }

        @Test
        void countSkipsNulls() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.COUNT, null, false);
            agg.update(Datum.of(1L));
            agg.update(Datum.nil());
            agg.update(Datum.of(3L));
            assertThat(agg.result().toLong()).isEqualTo(2);
        }

        @Test
        void countEmpty() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.COUNT, null, false);
            assertThat(agg.result().toLong()).isEqualTo(0);
        }

        @Test
        void merge() {
            AggFunction agg1 = AggFunctions.create(AggFunction.Type.COUNT, null, false);
            agg1.update(Datum.of(1L));
            agg1.update(Datum.of(2L));

            AggFunction agg2 = AggFunctions.create(AggFunction.Type.COUNT, null, false);
            agg2.update(Datum.of(3L));

            agg1.merge(agg2);
            assertThat(agg1.result().toLong()).isEqualTo(3);
        }

        @Test
        void returnTypeIsBigint() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.COUNT, null, false);
            assertThat(agg.returnType()).isEqualTo(DataType.BIGINT);
        }

        @Test
        void newInstanceCreatesIndependentCopy() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.COUNT, null, false);
            agg.update(Datum.of(1L));
            AggFunction copy = agg.newInstance();
            assertThat(copy.result().toLong()).isEqualTo(0);
        }

        @Test
        void typeAndDistinct() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.COUNT, Constant.ofLong(1), true);
            assertThat(agg.type()).isEqualTo(AggFunction.Type.COUNT);
            assertThat(agg.distinct()).isTrue();
            assertThat(agg.arg()).isNotNull();
        }

        @Test
        void countDistinctSkipsDuplicates() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.COUNT, null, true);
            agg.update(Datum.of(1L));
            agg.update(Datum.of(2L));
            agg.update(Datum.of(1L));
            agg.update(Datum.of(3L));
            agg.update(Datum.of(2L));
            assertThat(agg.result().toLong()).isEqualTo(3);
        }
    }

    @Nested
    class Sum {

        @Test
        void sumIntegers() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.SUM, null, false);
            agg.update(Datum.of(10L));
            agg.update(Datum.of(20L));
            agg.update(Datum.of(30L));
            Datum result = agg.result();
            assertThat(result).isInstanceOf(Datum.DecimalDatum.class);
            assertThat(((Datum.DecimalDatum) result).value()).isEqualByComparingTo(new BigDecimal("60"));
        }

        @Test
        void sumDoubles() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.SUM, null, false);
            agg.update(Datum.of(1.5));
            agg.update(Datum.of(2.5));
            Datum result = agg.result();
            assertThat(((Datum.DecimalDatum) result).value()).isEqualByComparingTo(new BigDecimal("4.0"));
        }

        @Test
        void sumDecimals() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.SUM, null, false);
            agg.update(Datum.of(new BigDecimal("1.11")));
            agg.update(Datum.of(new BigDecimal("2.22")));
            Datum result = agg.result();
            assertThat(((Datum.DecimalDatum) result).value()).isEqualByComparingTo(new BigDecimal("3.33"));
        }

        @Test
        void sumSkipsNulls() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.SUM, null, false);
            agg.update(Datum.of(10L));
            agg.update(Datum.nil());
            agg.update(Datum.of(20L));
            assertThat(((Datum.DecimalDatum) agg.result()).value())
                    .isEqualByComparingTo(new BigDecimal("30"));
        }

        @Test
        void sumEmptyReturnsNull() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.SUM, null, false);
            assertThat(agg.result().isNull()).isTrue();
        }

        @Test
        void merge() {
            AggFunction agg1 = AggFunctions.create(AggFunction.Type.SUM, null, false);
            agg1.update(Datum.of(10L));
            AggFunction agg2 = AggFunctions.create(AggFunction.Type.SUM, null, false);
            agg2.update(Datum.of(20L));
            agg1.merge(agg2);
            assertThat(((Datum.DecimalDatum) agg1.result()).value())
                    .isEqualByComparingTo(new BigDecimal("30"));
        }

        @Test
        void returnTypeIsDecimal() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.SUM, null, false);
            assertThat(agg.returnType()).isEqualTo(DataType.DECIMAL);
        }

        @Test
        void sumDistinctSkipsDuplicates() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.SUM, null, true);
            agg.update(Datum.of(10L));
            agg.update(Datum.of(20L));
            agg.update(Datum.of(10L));
            Datum result = agg.result();
            assertThat(((Datum.DecimalDatum) result).value()).isEqualByComparingTo(new java.math.BigDecimal("30"));
        }
    }

    @Nested
    class Avg {

        @Test
        void avgIntegers() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.AVG, null, false);
            agg.update(Datum.of(10L));
            agg.update(Datum.of(20L));
            agg.update(Datum.of(30L));
            Datum result = agg.result();
            assertThat(((Datum.DecimalDatum) result).value())
                    .isEqualByComparingTo(new BigDecimal("20.0000"));
        }

        @Test
        void avgEmptyReturnsNull() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.AVG, null, false);
            assertThat(agg.result().isNull()).isTrue();
        }

        @Test
        void avgSkipsNulls() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.AVG, null, false);
            agg.update(Datum.of(10L));
            agg.update(Datum.nil());
            agg.update(Datum.of(20L));
            Datum result = agg.result();
            // avg of 10 and 20 = 15
            assertThat(((Datum.DecimalDatum) result).value())
                    .isEqualByComparingTo(new BigDecimal("15.0000"));
        }

        @Test
        void merge() {
            AggFunction agg1 = AggFunctions.create(AggFunction.Type.AVG, null, false);
            agg1.update(Datum.of(10L));
            agg1.update(Datum.of(20L));

            AggFunction agg2 = AggFunctions.create(AggFunction.Type.AVG, null, false);
            agg2.update(Datum.of(30L));

            agg1.merge(agg2);
            // total sum = 60, count = 3, avg = 20
            assertThat(((Datum.DecimalDatum) agg1.result()).value())
                    .isEqualByComparingTo(new BigDecimal("20.0000"));
        }

        @Test
        void returnTypeIsDecimal() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.AVG, null, false);
            assertThat(agg.returnType()).isEqualTo(DataType.DECIMAL);
        }

        @Test
        void avgDistinctSkipsDuplicates() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.AVG, null, true);
            agg.update(Datum.of(10L));
            agg.update(Datum.of(20L));
            agg.update(Datum.of(10L));
            Datum result = agg.result();
            // avg of distinct {10, 20} = 15
            assertThat(((Datum.DecimalDatum) result).value()).isEqualByComparingTo(new java.math.BigDecimal("15.0000"));
        }
    }

    @Nested
    class Min {

        @Test
        void minIntegers() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.MIN, Constant.ofLong(0), false);
            agg.update(Datum.of(30L));
            agg.update(Datum.of(10L));
            agg.update(Datum.of(20L));
            assertThat(agg.result().toLong()).isEqualTo(10);
        }

        @Test
        void minStrings() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.MIN, Constant.ofString(""), false);
            agg.update(Datum.of("cherry"));
            agg.update(Datum.of("apple"));
            agg.update(Datum.of("banana"));
            assertThat(agg.result().toStringValue()).isEqualTo("apple");
        }

        @Test
        void minSkipsNulls() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.MIN, Constant.ofLong(0), false);
            agg.update(Datum.nil());
            agg.update(Datum.of(5L));
            agg.update(Datum.nil());
            assertThat(agg.result().toLong()).isEqualTo(5);
        }

        @Test
        void minEmptyReturnsNull() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.MIN, Constant.ofLong(0), false);
            assertThat(agg.result().isNull()).isTrue();
        }

        @Test
        void merge() {
            AggFunction agg1 = AggFunctions.create(AggFunction.Type.MIN, Constant.ofLong(0), false);
            agg1.update(Datum.of(10L));
            AggFunction agg2 = AggFunctions.create(AggFunction.Type.MIN, Constant.ofLong(0), false);
            agg2.update(Datum.of(5L));
            agg1.merge(agg2);
            assertThat(agg1.result().toLong()).isEqualTo(5);
        }
    }

    @Nested
    class Max {

        @Test
        void maxIntegers() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.MAX, Constant.ofLong(0), false);
            agg.update(Datum.of(10L));
            agg.update(Datum.of(30L));
            agg.update(Datum.of(20L));
            assertThat(agg.result().toLong()).isEqualTo(30);
        }

        @Test
        void maxSkipsNulls() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.MAX, Constant.ofLong(0), false);
            agg.update(Datum.nil());
            agg.update(Datum.of(5L));
            assertThat(agg.result().toLong()).isEqualTo(5);
        }

        @Test
        void maxEmptyReturnsNull() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.MAX, Constant.ofLong(0), false);
            assertThat(agg.result().isNull()).isTrue();
        }

        @Test
        void merge() {
            AggFunction agg1 = AggFunctions.create(AggFunction.Type.MAX, Constant.ofLong(0), false);
            agg1.update(Datum.of(10L));
            AggFunction agg2 = AggFunctions.create(AggFunction.Type.MAX, Constant.ofLong(0), false);
            agg2.update(Datum.of(30L));
            agg1.merge(agg2);
            assertThat(agg1.result().toLong()).isEqualTo(30);
        }
    }

    @Nested
    class GroupConcat {

        @Test
        void concatenatesValues() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, false);
            agg.update(Datum.of("a"));
            agg.update(Datum.of("b"));
            agg.update(Datum.of("c"));
            assertThat(agg.result().toStringValue()).isEqualTo("a,b,c");
        }

        @Test
        void skipsNulls() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, false);
            agg.update(Datum.of("a"));
            agg.update(Datum.nil());
            agg.update(Datum.of("c"));
            assertThat(agg.result().toStringValue()).isEqualTo("a,c");
        }

        @Test
        void emptyReturnsNull() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, false);
            assertThat(agg.result().isNull()).isTrue();
        }

        @Test
        void merge() {
            AggFunction agg1 = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, false);
            agg1.update(Datum.of("a"));
            agg1.update(Datum.of("b"));

            AggFunction agg2 = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, false);
            agg2.update(Datum.of("c"));

            agg1.merge(agg2);
            assertThat(agg1.result().toStringValue()).isEqualTo("a,b,c");
        }

        @Test
        void mergeWithEmpty() {
            AggFunction agg1 = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, false);
            agg1.update(Datum.of("a"));

            AggFunction agg2 = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, false);
            // agg2 is empty

            agg1.merge(agg2);
            assertThat(agg1.result().toStringValue()).isEqualTo("a");
        }

        @Test
        void returnTypeIsVarchar() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, false);
            assertThat(agg.returnType()).isEqualTo(DataType.VARCHAR);
        }

        @Test
        void distinctSkipsDuplicates() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, true);
            agg.update(Datum.of("a"));
            agg.update(Datum.of("b"));
            agg.update(Datum.of("a"));
            agg.update(Datum.of("c"));
            agg.update(Datum.of("b"));
            assertThat(agg.result().toStringValue()).isEqualTo("a,b,c");
        }

        @Test
        void distinctWithNulls() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, true);
            agg.update(Datum.of("x"));
            agg.update(Datum.nil());
            agg.update(Datum.of("x"));
            agg.update(Datum.of("y"));
            assertThat(agg.result().toStringValue()).isEqualTo("x,y");
        }

        @Test
        void distinctAllSameValue() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, true);
            agg.update(Datum.of("dup"));
            agg.update(Datum.of("dup"));
            agg.update(Datum.of("dup"));
            assertThat(agg.result().toStringValue()).isEqualTo("dup");
        }

        @Test
        void nonDistinctKeepsDuplicates() {
            AggFunction agg = AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, false);
            agg.update(Datum.of("a"));
            agg.update(Datum.of("a"));
            agg.update(Datum.of("b"));
            assertThat(agg.result().toStringValue()).isEqualTo("a,a,b");
        }
    }

    @Test
    void createAllTypes() {
        for (AggFunction.Type type : AggFunction.Type.values()) {
            AggFunction agg = AggFunctions.create(type, Constant.ofLong(0), false);
            assertThat(agg).isNotNull();
            assertThat(agg.type()).isEqualTo(type);
        }
    }
}
