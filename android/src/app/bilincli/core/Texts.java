package app.bilincli.core;

import app.bilincli.core.Ledger.Coupon;
import app.bilincli.core.Ledger.Leg;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bildirim ve özet metinleri (Python: bilincli/render.py metin kısmı). */
public final class Texts {
    private Texts() {}

    @SuppressWarnings("unchecked")
    public static String statsLine(Map<String, Object> stats) {
        List<String> parts = new ArrayList<>();
        parts.add("Bülten " + stats.get("iddaa_mac") + " maç");
        parts.add("eşleşen " + stats.get("eslesen"));
        parts.add("karşılaştırılan " + stats.get("karsilastirilan_secim") + " seçim");
        parts.add("avantajlı " + stats.get("avantajli_secim"));
        Object m = stats.get("marjlar");
        if (m instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                parts.add("iddaa " + e.getKey() + " marjı " + Fmt.pct(((Number) e.getValue()).doubleValue(), false));
            }
        }
        return String.join(" · ", parts);
    }

    public static String legLine(Leg l) {
        double ev = l.fairProb * l.odds - 1;
        return Fmt.localTime(l.kickoff) + " " + l.home + " - " + l.away + (l.bookCode == null || l.bookCode.isEmpty() ? "" : " (kod " + l.bookCode + ")")
                + "\n   → " + Models.outcomeLabel(l.market, l.outcome) + " @ " + Fmt.odds(l.odds)
                + " (adil " + Fmt.odds(1 / l.fairProb) + ", avantaj " + Fmt.pct(ev, true) + ", MBS " + l.mbs + ")";
    }

    public static String couponText(Coupon c) {
        StringBuilder b = new StringBuilder();
        int i = 1;
        for (Leg l : c.legs) b.append(i++).append(") ").append(legLine(l)).append('\n');
        double ev = c.winProb * c.totalOdds - 1;
        b.append("Toplam oran ").append(Fmt.odds(c.totalOdds)).append(" · tutma olasılığı ")
                .append(Fmt.pct(c.winProb, false)).append(" · beklenen değer ").append(Fmt.pct(ev, true));
        long stake = c.played ? c.stake : c.suggestedStake;
        if (stake > 0) {
            b.append('\n').append(c.played ? "Oynanan" : "Önerilen").append(" tutar ").append(Fmt.tl(stake))
                    .append(" → tutarsa ").append(Fmt.tl(Math.round(stake * c.totalOdds)));
        }
        return b.toString();
    }

    /** [başlık, metin] */
    public static String[] notification(Ledger ledger, Daily.Result r) {
        String when = LocalDate.parse(r.day).format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT));
        if (r.error != null) return new String[] {"Veri alınamadı", r.error};
        if (r.blocked != null) return new String[] {"Bugün kupon yok · koruma devrede", r.blocked};
        if (r.decision == null) return null;
        if (r.decision.isPass()) {
            return new String[] {"Bugün pas · " + when, r.decision.reason + "\n" + statsLine(r.decision.stats)};
        }
        Coupon c = ledger.coupon(r.couponId);
        String title = "Günün kuponu · " + c.legs.size() + " maç · oran " + Fmt.odds(c.totalOdds)
                + (c.suggestedStake > 0 ? " · " + Fmt.tl(c.suggestedStake) : "");
        String body = couponText(c) + (r.stakeNote != null ? "\n" + r.stakeNote : "")
                + "\nOranlar gün içinde değişir: oynamadan önce uygulamada \"Oynamadan önce kontrol et\"e bas."
                + " İlk maçtan 90 dk önce otomatik kontrol bildirimi de gelir.";
        return new String[] {title, body};
    }
}
