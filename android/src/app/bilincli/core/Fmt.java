package app.bilincli.core;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Türkçe biçimlendirme yardımcıları. */
public final class Fmt {
    private Fmt() {}

    /** Türkiye 2016'dan beri yıl boyu UTC+3. */
    public static final ZoneOffset TR = ZoneOffset.ofHours(3);

    public static String tl(long kurus) {
        String sign = kurus < 0 ? "-" : "";
        long abs = Math.abs(kurus);
        return sign + group(abs / 100) + "," + String.format(Locale.ROOT, "%02d", abs % 100) + " TL";
    }

    private static String group(long whole) {
        String s = Long.toString(whole);
        StringBuilder b = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            b.append(s.charAt(i));
            int left = n - i - 1;
            if (left > 0 && left % 3 == 0) b.append('.');
        }
        return b.toString();
    }

    public static String num(double x, int digits) {
        String s = String.format(Locale.ROOT, "%,." + digits + "f", x);
        return s.replace(",", "X").replace(".", ",").replace("X", ".");
    }

    public static String odds(double x) {
        return num(x, 2);
    }

    public static String pct(Double x, boolean signed) {
        if (x == null) return "-";
        String sign = signed ? (x > 0 ? "+" : x < 0 ? "−" : "") : (x < 0 ? "−" : "");
        return sign + "%" + num(Math.abs(x) * 100, 1);
    }

    public static String dayKey(Instant t) {
        return t.atOffset(TR).toLocalDate().toString();
    }

    public static String localTime(String iso) {
        return Instant.parse(iso).atOffset(TR).format(DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.ROOT));
    }
}
