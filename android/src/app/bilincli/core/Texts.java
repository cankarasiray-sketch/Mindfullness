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
        if (stats.get("dogrulama") != null && !String.valueOf(stats.get("dogrulama")).isEmpty()) {
            parts.add(String.valueOf(stats.get("dogrulama")));
        }
        Object m = stats.get("marjlar");
        if (m instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                parts.add("iddaa " + ("BS".equals(e.getKey()) ? "basket MS" : "BT".equals(e.getKey()) ? "basket A/Ü" : e.getKey()) + " marjı " + Fmt.pct(((Number) e.getValue()).doubleValue(), false));
            }
        }
        String line = String.join(" · ", parts);
        Object near = stats.get("en_yakin");
        return near == null ? line : line + "\nEn yakın seçim: " + near + "\nÖnerilen tutar: " + nearStake(stats);
    }

    /**
     * En yakın seçimin tutarı: avantaj yoksa 0 TL (Kelly sıfır; oynamak zarar beklentisi) ve yine de
     * oynanırsa 100 TL'de beklenen kayıp; oynanır olacağı oran ve o oranda önerilen tutar. "kasa"
     * (kuruş) varsa tutar TL olarak, yoksa kasanın yüzdesi olarak yazılır.
     */
    static String nearStake(Map<String, Object> stats) {
        double ev = stats.get("en_yakin_ev") instanceof Number ? ((Number) stats.get("en_yakin_ev")).doubleValue() : 0;
        Long kasa = stats.get("kasa") instanceof Number ? ((Number) stats.get("kasa")).longValue() : null;
        StringBuilder b = new StringBuilder("0 TL — ");
        if (ev >= 0 && Boolean.FALSE.equals(stats.get("en_yakin_aralikta"))) {
            b.append("oranı ayarlardaki oran aralığının ya da tutma olasılığı şartının dışında, oynanmaz.");
        } else {
            b.append("avantaj yok, oynanmaz");
            if (ev < 0) b.append(" (yine de 100 TL oynanırsa beklenen kayıp ≈ ").append(Fmt.tl(Math.round(-ev * 10000))).append(')');
            b.append('.');
        }
        Object target = stats.get("en_yakin_hedef"), frac = stats.get("en_yakin_hedef_oran");
        if (target instanceof Number && frac instanceof Number && ev < 0) {
            double f = ((Number) frac).doubleValue();
            b.append(" Oran ").append(Fmt.odds(((Number) target).doubleValue())).append(" ya da üstüne çıkarsa (ör. kampanya, oran değişimi) önerilen ");
            if (kasa != null && kasa > 0) {
                b.append(Fmt.tl((long) (kasa * f) / 1000 * 1000)).append(" (kasa payı ").append(Fmt.pct(f, false)).append(").");
            } else {
                b.append("kasa payı ").append(Fmt.pct(f, false)).append('.');
            }
        }
        return b.toString();
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

    /** Düşen oran bildirimi; değer taşıyan en fazla 3 hareket. */
    public static String[] moves(List<Map<String, Object>> moves) {
        if (moves.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Map<String, Object> m : moves) {
            if (n++ == 3) break;
            double from = Json.dbl(m, "from", 0), to = Json.dbl(m, "to", 0);
            b.append(m.get("home")).append(" - ").append(m.get("away")).append(" MS ").append(m.get("outcome"))
                    .append(": Pinnacle ").append(Fmt.odds(1 / from)).append(" → ").append(Fmt.odds(1 / to))
                    .append(", iddaa hâlâ ").append(Fmt.odds(Json.dbl(m, "iddaa", 0)))
                    .append(" (avantaj ").append(Fmt.pct(Json.dbl(m, "ev", 0), true)).append(")\n");
        }
        return new String[] {"Düşen oran: " + moves.size() + " fırsat", b.toString().trim()};
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
        List<Long> ids = r.couponIds.isEmpty() ? java.util.Collections.singletonList(r.couponId) : r.couponIds;
        String title = ids.size() > 1
                ? "Günün kuponları · " + ids.size() + " kupon"
                : "Günün kuponu · " + c.legs.size() + " maç · oran " + Fmt.odds(c.totalOdds)
                        + (c.suggestedStake > 0 ? " · " + Fmt.tl(c.suggestedStake) : "");
        StringBuilder all = new StringBuilder();
        for (Long id : ids) {
            if (all.length() > 0) all.append("\n\n");
            if (ids.size() > 1) all.append("Kupon #").append(id).append('\n');
            all.append(couponText(ledger.coupon(id)));
        }
        String body = all + (r.stakeNote != null ? "\n" + r.stakeNote : "")
                + "\nOranlar gün içinde değişir: oynamadan önce uygulamada \"Oynamadan önce kontrol et\"e bas."
                + " İlk maçtan 90 dk önce otomatik kontrol bildirimi de gelir.";
        return new String[] {title, body};
    }
}
