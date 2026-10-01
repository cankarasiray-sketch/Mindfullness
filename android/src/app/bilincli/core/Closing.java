package app.bilincli.core;

import app.bilincli.core.Ledger.Coupon;
import app.bilincli.core.Ledger.Leg;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Kapanış oranı (CLV) takibi. Maç başlamadan hemen önce Pinnacle'ın adil olasılığı kaydedilir.
 * Seçimler kapanış oranını düzenli olarak yeniyorsa gerçek bir avantaj vardır; yenemiyorsa yoktur.
 * Bu, kâr/zarardan çok daha hızlı (haftalar içinde) güvenilir sinyal verir.
 */
public final class Closing {
    private Closing() {}

    /** Kapanış alınacak pencere: maçtan önceki son bu kadar saniye. */
    public static final long WINDOW_S = 40 * 60;
    /** Kapanış alarmı maçtan bu kadar önce çalar. */
    public static final long LEAD_S = 10 * 60;

    /** Şu an kapanışı alınması gereken bacakların ligleri. */
    public static Set<String> dueLeagues(Ledger ledger, Instant now) {
        Set<String> out = new LinkedHashSet<>();
        for (Coupon c : ledger.openCoupons()) {
            for (Leg l : c.legs) {
                if (needs(l, now) && l.sportKey != null) out.add(l.sportKey);
            }
        }
        return out;
    }

    static boolean needs(Leg l, Instant now) {
        if (l.closingFair != null || l.result != null) return false;
        Instant ko = Instant.parse(l.kickoff);
        return ko.isAfter(now) && !ko.minusSeconds(WINDOW_S).isAfter(now);
    }

    /** Uygun bacaklara kapanış olasılığını yazar; yazılan bacak sayısını döndürür. */
    public static int capture(Ledger ledger, Iterable<SharpEvent> sharp, Instant now) {
        Map<String, SharpEvent> byRef = new LinkedHashMap<>();
        for (SharpEvent s : sharp) byRef.put(s.ref, s);
        int n = 0;
        for (Coupon c : ledger.openCoupons()) {
            for (Leg l : c.legs) {
                if (!needs(l, now)) continue;
                SharpEvent s = byRef.get(l.sharpRef);
                Map<String, Double> fair = s == null ? null : Models.fair(s, l.market);
                if (fair == null) continue;
                Double f = fair.get(l.outcome);
                if (f == null) continue;
                ledger.setClosing(c.id, l.position, f);
                n++;
            }
        }
        return n;
    }

    /** Bir sonraki kapanış alarmı zamanı (yoksa null). */
    public static Instant nextCaptureTime(Ledger ledger, Instant now) {
        if (!dueLeagues(ledger, now).isEmpty()) return now; // kapanış penceresinde, henüz alınmamış
        Instant best = null;
        for (Coupon c : ledger.openCoupons()) {
            for (Leg l : c.legs) {
                if (l.closingFair != null || l.result != null) continue;
                Instant at = Instant.parse(l.kickoff).minusSeconds(LEAD_S);
                if (!at.isAfter(now)) continue;
                if (best == null || at.isBefore(best)) best = at;
            }
        }
        return best;
    }
}
