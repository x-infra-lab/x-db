package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataTypeTest {

    @Nested
    class Properties {

        @Test
        void integerTypes() {
            assertThat(DataType.TINYINT.isInteger()).isTrue();
            assertThat(DataType.SMALLINT.isInteger()).isTrue();
            assertThat(DataType.INT.isInteger()).isTrue();
            assertThat(DataType.BIGINT.isInteger()).isTrue();
            assertThat(DataType.YEAR.isInteger()).isTrue();
            assertThat(DataType.BOOLEAN.isInteger()).isTrue();
        }

        @Test
        void nonIntegerTypes() {
            assertThat(DataType.DOUBLE.isInteger()).isFalse();
            assertThat(DataType.VARCHAR.isInteger()).isFalse();
            assertThat(DataType.DECIMAL.isInteger()).isFalse();
        }

        @Test
        void numericTypes() {
            assertThat(DataType.TINYINT.isNumeric()).isTrue();
            assertThat(DataType.BIGINT.isNumeric()).isTrue();
            assertThat(DataType.FLOAT.isNumeric()).isTrue();
            assertThat(DataType.DOUBLE.isNumeric()).isTrue();
            assertThat(DataType.DECIMAL.isNumeric()).isTrue();
            assertThat(DataType.BOOLEAN.isNumeric()).isTrue();
        }

        @Test
        void nonNumericTypes() {
            assertThat(DataType.VARCHAR.isNumeric()).isFalse();
            assertThat(DataType.BLOB.isNumeric()).isFalse();
            assertThat(DataType.DATETIME.isNumeric()).isFalse();
        }

        @Test
        void floatTypes() {
            assertThat(DataType.FLOAT.isFloat()).isTrue();
            assertThat(DataType.DOUBLE.isFloat()).isTrue();
            assertThat(DataType.DECIMAL.isFloat()).isFalse();
            assertThat(DataType.INT.isFloat()).isFalse();
        }

        @Test
        void stringTypes() {
            assertThat(DataType.CHAR.isString()).isTrue();
            assertThat(DataType.VARCHAR.isString()).isTrue();
            assertThat(DataType.TEXT.isString()).isTrue();
            assertThat(DataType.BLOB.isString()).isFalse();
        }

        @Test
        void binaryTypes() {
            assertThat(DataType.BINARY.isBinary()).isTrue();
            assertThat(DataType.VARBINARY.isBinary()).isTrue();
            assertThat(DataType.BLOB.isBinary()).isTrue();
            assertThat(DataType.VARCHAR.isBinary()).isFalse();
        }

        @Test
        void temporalTypes() {
            assertThat(DataType.DATE.isTemporal()).isTrue();
            assertThat(DataType.DATETIME.isTemporal()).isTrue();
            assertThat(DataType.TIMESTAMP.isTemporal()).isTrue();
            assertThat(DataType.TIME.isTemporal()).isTrue();
            assertThat(DataType.INT.isTemporal()).isFalse();
        }

        @Test
        void fixedLength() {
            assertThat(DataType.TINYINT.fixedLength()).isEqualTo(1);
            assertThat(DataType.SMALLINT.fixedLength()).isEqualTo(2);
            assertThat(DataType.INT.fixedLength()).isEqualTo(4);
            assertThat(DataType.BIGINT.fixedLength()).isEqualTo(8);
            assertThat(DataType.VARCHAR.fixedLength()).isEqualTo(-1);
            assertThat(DataType.NULL.fixedLength()).isEqualTo(0);
        }
    }

    @Nested
    class FromMySQLType {

        @ParameterizedTest
        @CsvSource({
                "TINYINT, TINYINT",
                "SMALLINT, SMALLINT",
                "INT, INT",
                "INTEGER, INT",
                "MEDIUMINT, INT",
                "BIGINT, BIGINT",
                "FLOAT, FLOAT",
                "DOUBLE, DOUBLE",
                "REAL, DOUBLE",
                "DECIMAL, DECIMAL",
                "NUMERIC, DECIMAL",
                "DEC, DECIMAL",
                "CHAR, CHAR",
                "VARCHAR, VARCHAR",
                "TEXT, TEXT",
                "TINYTEXT, TEXT",
                "MEDIUMTEXT, TEXT",
                "LONGTEXT, TEXT",
                "BINARY, BINARY",
                "VARBINARY, VARBINARY",
                "BLOB, BLOB",
                "TINYBLOB, BLOB",
                "MEDIUMBLOB, BLOB",
                "LONGBLOB, BLOB",
                "DATE, DATE",
                "DATETIME, DATETIME",
                "TIMESTAMP, TIMESTAMP",
                "TIME, TIME",
                "YEAR, YEAR",
                "BOOLEAN, BOOLEAN",
                "BOOL, BOOLEAN",
                "BIT, BOOLEAN"
        })
        void validMappings(String input, String expectedName) {
            DataType expected = DataType.valueOf(expectedName);
            assertThat(DataType.fromMySQLType(input)).isEqualTo(expected);
        }

        @Test
        void caseInsensitive() {
            assertThat(DataType.fromMySQLType("int")).isEqualTo(DataType.INT);
            assertThat(DataType.fromMySQLType("varchar")).isEqualTo(DataType.VARCHAR);
            assertThat(DataType.fromMySQLType("BigInt")).isEqualTo(DataType.BIGINT);
        }

        @Test
        void unknownTypeThrows() {
            assertThatThrownBy(() -> DataType.fromMySQLType("UNKNOWN"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown type");
        }
    }
}
