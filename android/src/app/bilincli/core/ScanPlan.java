package app.bilincli.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Radar taramalarını günün maç saatlerine göre yerleştirir. Maçlar, saat sırasına göre tarama
 * sayısı kadar eşit gruba bölünür; her tarama grubun ilk maçından {@link #LEAD_S} önce yapılır.
 * Bu saatte Pinnacle fiyatı sabahkinden çok daha olgundur, iddaa ise çoğu zaman henüz
 * güncellenmemiştir. Kredi aynıdır (tarama sayısı değişmez; birbirine çok yakın taramalar
 * birleştirilir). Maç bilgisi yoksa sabit saatler kullanılır.
 */
public final class ScanPlan {
    private ScanPlan() {}

    static final long LEAD_S = 150 * 60;
    static final int EARLIEST_HOUR = 9;
    static final long MIN_GAP_S = 90 * 60;

    /** day günü için tarama saatleri; maç yoksa ya da tarama kapalıysa null. */
    public static List<Instant> times(List<Instant> kickoffs, int scans, LocalDate day) {
        if (scans <= 0 || kickoffs == null) return null;
        List<Instant> ko = new ArrayList<>();
        for (Instant k : kickoffs) if (k.atZone(Fmt.TR).toLocalDate().equals(day)) ko.add(k);
        if (ko.isEmpty()) return null;
        Collections.sort(ko);
        Instant earliest = day.atTime(EARLIEST_HOUR, 0).atZone(Fmt.TR).toInstant();
        List<Instant> out = new ArrayList<>();
        for (int g = 0; g < scans; g++) {
            int idx = g * ko.size() / scans;
            if (g > 0 && idx == (g - 1) * ko.size() / scans) continue; // maç sayısı taramadan az
            ZonedDateTime t = ko.get(idx).minusSeconds(LEAD_S).atZone(Fmt.TR);
            t = t.withMinute(t.getMinute() / 15 * 15).withSecond(0).withNano(0);
            Instant at = t.toInstant().isBefore(earliest) ? earliest : t.toInstant();
            if (!out.isEmpty() && at.isBefore(out.get(out.size() - 1).plusSeconds(MIN_GAP_S))) continue;
            out.add(at);
        }
        return out;
    }

    /** Listedeki now'dan sonraki ilk saat (yoksa null). */
    public static Instant next(List<Instant> times, Instant now) {
        if (times == null) return null;
        for (Instant t : times) if (t.isAfter(now)) return t;
        return null;
    }
}
