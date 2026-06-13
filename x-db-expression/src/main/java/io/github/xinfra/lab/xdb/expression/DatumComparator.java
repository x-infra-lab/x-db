package io.github.xinfra.lab.xdb.expression;

import java.math.BigDecimal;
import java.util.Arrays;

public final class DatumComparator {
    private DatumComparator() {}

    public static int compare(Datum a, Datum b) {
        if (a.isNull() && b.isNull()) return 0;
        if (a.isNull()) return -1;
        if (b.isNull()) return 1;

        if (a instanceof Datum.IntDatum ai && b instanceof Datum.IntDatum bi)
            return Long.compare(ai.value(), bi.value());
        if (a instanceof Datum.DoubleDatum ad && b instanceof Datum.DoubleDatum bd)
            return Double.compare(ad.value(), bd.value());
        if (a instanceof Datum.DecimalDatum ad && b instanceof Datum.DecimalDatum bd)
            return ad.value().compareTo(bd.value());
        if (a instanceof Datum.StringDatum as && b instanceof Datum.StringDatum bs)
            return as.value().compareTo(bs.value());
        if (a instanceof Datum.DateTimeDatum ad && b instanceof Datum.DateTimeDatum bd)
            return ad.value().compareTo(bd.value());
        if (a instanceof Datum.BytesDatum ab && b instanceof Datum.BytesDatum bb)
            return Arrays.compareUnsigned(ab.value(), bb.value());

        if (isNumeric(a) && isNumeric(b)) {
            if (a instanceof Datum.DecimalDatum || b instanceof Datum.DecimalDatum) {
                return toDecimal(a).compareTo(toDecimal(b));
            }
            if (a instanceof Datum.DoubleDatum || b instanceof Datum.DoubleDatum) {
                return Double.compare(a.toDouble(), b.toDouble());
            }
            return Long.compare(a.toLong(), b.toLong());
        }

        return a.toStringValue().compareTo(b.toStringValue());
    }

    private static boolean isNumeric(Datum d) {
        return d instanceof Datum.IntDatum || d instanceof Datum.DoubleDatum
                || d instanceof Datum.DecimalDatum;
    }

    private static BigDecimal toDecimal(Datum d) {
        if (d instanceof Datum.DecimalDatum dd) return dd.value();
        if (d instanceof Datum.IntDatum di) return BigDecimal.valueOf(di.value());
        if (d instanceof Datum.DoubleDatum dd) return BigDecimal.valueOf(dd.value());
        return new BigDecimal(d.toStringValue());
    }
}
