package app.bilincli.core;

import app.bilincli.core.Ledger.Coupon;
import app.bilincli.core.Ledger.Leg;
import app.bilincli.core.Models.ScoreResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maç sonuçlarını çekip açık kuponları sonuçlandırma (Python: bilincli/settlement.py). */
public final class Settlement {
    private Settlement() {}

    public interface ScoreFetcher {
        Map<String, ScoreResult> fetch(Set<String> sportKeys) throws Exception;
    }

    static final long RESULT_DELAY_S = 2 * 3600 + 15 * 60;
    static final long MANUAL_AFTER_S = 3 * 24 * 3600;

    public static String legResult(String market, String outcome, int home, int away) {
        String actual;
        if ("MS".equals(market)) actual = home > away ? "1" : away > home ? "2" : "X";
        else if ("BS".equals(market)) {
            // basketbol maç sonucu uzatmalar dahildir; skor eşitse veri eksiktir, bacak iade sayılır
            if (home == away) return Models.VOID;
            actual = home > away ? "1" : "2";
        }
        else if ("AU25".equals(market)) actual = home + away >= 3 ? "UST" : "ALT";
        else if ("KG".equals(market)) actual = home > 0 && away > 0 ? "VAR" : "YOK";
        else if ("CS".equals(market)) {
            // Çifte Şans: iki sonucu birden kapsar
            boolean won = "1X".equals(outcome) ? home >= away : "12".equals(outcome) ? home != away
                    : "X2".equals(outcome) ? away >= home : false;
            return won ? Models.WON : Models.LOST;
        }
        else throw new IllegalArgumentException("bilinmeyen pazar: " + market);
        return outcome.equals(actual) ? Models.WON : Models.LOST;
    }

    /** Settled kuponlar için kullanıcıya gösterilecek mesajları döndürür. */
    public static List<String> settleOpen(Ledger ledger, ScoreFetcher fetcher, Instant now) throws Exception {
        List<String> messages = new ArrayList<>();
        List<Coupon> coupons = ledger.openCoupons();
        List<Object[]> due = new ArrayList<>();
        for (Coupon c : coupons) {
            for (Leg l : c.legs) {
                if (l.result == null && !Instant.parse(l.kickoff).plusSeconds(RESULT_DELAY_S).isAfter(now)) {
                    due.add(new Object[] {c, l});
                }
            }
        }
        if (due.isEmpty()) return messages;
        Set<String> sports = new LinkedHashSet<>();
        for (Object[] d : due) {
            String sk = ((Leg) d[1]).sportKey;
            if (sk != null) sports.add(sk);
        }
        Map<String, ScoreResult> scores = sports.isEmpty() ? new java.util.HashMap<String, ScoreResult>()
                : fetcher.fetch(sports);
        for (Object[] d : due) {
            Coupon c = (Coupon) d[0];
            Leg l = (Leg) d[1];
            ScoreResult s = scores.get(l.sharpRef);
            if (s != null && s.completed && s.home != null && s.away != null) {
                ledger.setLegResult(c.id, l.position, legResult(l.market, l.outcome, s.home, s.away),
                        s.home + "-" + s.away);
            } else if (!Instant.parse(l.kickoff).plusSeconds(MANUAL_AFTER_S).isAfter(now)) {
                messages.add("#" + c.id + " " + l.home + " - " + l.away
                        + ": sonuç bulunamadı (ertelenmiş olabilir). Kuponda maçın sonucunu elle gir.");
            }
        }
        for (Coupon c : coupons) {
            String outcome = ledger.settleCoupon(c.id);
            if (outcome == null) continue;
            String kind = c.played ? "Oynanan" : "Oynanmayan öneri";
            if (Models.WON.equals(outcome)) {
                String extra = c.played ? ", ödeme " + Fmt.tl(c.payout) : "";
                messages.add(kind + " kupon #" + c.id + " TUTTU (oran " + Fmt.odds(c.totalOdds) + extra + ").");
            } else if (Models.LOST.equals(outcome)) {
                messages.add(kind + " kupon #" + c.id + " yattı.");
            } else {
                messages.add(kind + " kupon #" + c.id + " iade oldu.");
            }
        }
        return messages;
    }
}
