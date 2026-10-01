package app.bilincli.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Radar taramalarını günün maç saatlerine göre yerleştirir. Maçlar, saat sırasına göre tarama
 * sayısı kadar eşit gruba bölünür; her tarama grubun ilk maçından {@link #LEAD_S} önce yapılır.
 * Bu saatte Pinnacle fiyatı sabahkinden çok daha olgundur, iddaa ise çoğu zaman henüz
 * güncellenmemiştir. Kredi aynıdır (tarama sayısı değişmez; birbirine çok yakın taramalar
 * birleştirilir). Maç bilgisi yoksa sabit saatler kullanılır.
 *
 * Kadro saati taramaları ({@link #lineupSlots}) ayrıdır: ilk 11'ler maçtan ~60 dk önce açıklanır,
 * Pinnacle hemen tepki verir, iddaa çoğu zaman gecikir. Her maç grubundan {@link #LINEUP_LEAD_S}
 * önce yalnızca o grupta oynayan ligler taranır (her lig günde bir kez daha).
 */
public final class ScanPlan {
    private ScanPlan() {}

    static final long LEAD_S = 150 * 60;
    static final int EARLIEST_HOUR = 9;
    static final long MIN_GAP_S = 90 * 60;
    /** Kadro saati taraması maçtan bu kadar önce. */
    static final long LINEUP_LEAD_S = 45 * 60;
    /** Aynı kadro taramasına giren maçlar: grubun ilk maçından sonraki bu süre içinde başlayanlar. */
    static final long LINEUP_SPAN_S = 30 * 60;

    /** day günü için tarama saatleri; maç yoksa ya da tarama kapalıysa null. */
    public static List<Instant> times(List<Instant> kickoffs, int scans, LocalDate day) {
        if (scans <= 0 || kickoffs == null) return null;
        Instant earliest = day.atTime(EARLIEST_HOUR, 0).atZone(Fmt.TR).toInstant();
        List<Instant> ko = new ArrayList<>();
        for (Instant k : kickoffs) {
            // gece maçları (ör. NBA 02:00): tarama en erken 09:00'da olabildiğinden maçtan sonraya düşerdi
            if (k.atZone(Fmt.TR).toLocalDate().equals(day) && k.isAfter(earliest.plusSeconds(30 * 60))) ko.add(k);
        }
        if (ko.isEmpty()) return null;
        Collections.sort(ko);
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

    /** Kadro saati taraması: zamanı ve o anda taranacak ligler. */
    public static final class Slot {
        public final Instant at;
        public final Set<String> leagues = new LinkedHashSet<>();

        Slot(Instant at) {
            this.at = at;
        }
    }

    /**
     * day günü için kadro saati taramaları. kickoffs ve leagues paralel listelerdir (maç saati, The
     * Odds API lig kodu). Saati 09:00'dan önceye düşen (gece maçları) ve lig kodu bilinmeyen maçlar
     * atlanır.
     */
    public static List<Slot> lineupSlots(List<Instant> kickoffs, List<String> leagues, LocalDate day) {
        List<Slot> out = new ArrayList<>();
        if (kickoffs == null || leagues == null || kickoffs.size() != leagues.size()) return out;
        Instant earliest = day.atTime(EARLIEST_HOUR, 0).atZone(Fmt.TR).toInstant();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < kickoffs.size(); i++) {
            Instant k = kickoffs.get(i);
            if (leagues.get(i) == null || !k.atZone(Fmt.TR).toLocalDate().equals(day)) continue;
            if (k.minusSeconds(LINEUP_LEAD_S).isBefore(earliest)) continue;
            idx.add(i);
        }
        final List<Instant> ko = kickoffs;
        Collections.sort(idx, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return ko.get(a).compareTo(ko.get(b));
            }
        });
        Instant groupStart = null;
        Slot slot = null;
        for (int i : idx) {
            Instant k = kickoffs.get(i);
            if (groupStart == null || k.isAfter(groupStart.plusSeconds(LINEUP_SPAN_S))) {
                groupStart = k;
                ZonedDateTime t = k.minusSeconds(LINEUP_LEAD_S).atZone(Fmt.TR);
                t = t.withMinute(t.getMinute() / 5 * 5).withSecond(0).withNano(0);
                slot = new Slot(t.toInstant());
                out.add(slot);
            }
            slot.leagues.add(leagues.get(i));
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
