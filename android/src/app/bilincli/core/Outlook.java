package app.bilincli.core;

import app.bilincli.core.Ledger.Coupon;
import app.bilincli.core.Ledger.Leg;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Aylık beklenti: bu ay oynanan kuponların gerçekleşen kâr/zararı, modelin beklediği kâr/zarar
 * ve açık kuponlarla ay sonu için kötü / olası / iyi senaryo (Monte Carlo, %10 / %50 / %90).
 */
public final class Outlook {
    private Outlook() {}

    static String monthKey(Instant t) {
        return t.atOffset(Fmt.TR).toLocalDate().toString().substring(0, 7);
    }

    public static Map<String, Object> month(Ledger ledger, Instant now) {
        String key = monthKey(now);
        long realized = 0, openStake = 0;
        double expected = 0;
        int played = 0, won = 0, settled = 0;
        List<double[]> open = new ArrayList<>(); // {stake, kazanırsa ödeme, olasılık}
        for (Coupon c : ledger.coupons()) {
            if (!c.played || c.playedAt == null || !monthKey(Instant.parse(c.playedAt)).equals(key)) continue;
            played++;
            expected += c.stake * (c.winProb * c.totalOdds - 1);
            if (c.result != null) {
                settled++;
                if (Models.WON.equals(c.result)) won++;
                realized += (c.payout == null ? 0 : c.payout) - c.stake;
                continue;
            }
            openStake += c.stake;
            double p = 1, mult = 1;
            for (Leg l : c.legs) {
                if (Models.VOID.equals(l.result)) continue;
                mult *= l.odds;
                if (l.result == null) p *= l.fairProb;
            }
            open.add(new double[] {c.stake, c.stake * mult, p});
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", key);
        out.put("played", (long) played);
        out.put("settled", (long) settled);
        out.put("won", (long) won);
        out.put("realized", realized);
        out.put("expected", Math.round(expected));
        out.put("openStake", openStake);
        if (open.isEmpty()) {
            out.put("p10", realized);
            out.put("p50", realized);
            out.put("p90", realized);
            return out;
        }
        int sims = 4000;
        long[] totals = new long[sims];
        Random rng = new Random(key.hashCode() * 31L + open.size());
        for (int i = 0; i < sims; i++) {
            double t = realized;
            for (double[] o : open) t += rng.nextDouble() < o[2] ? o[1] - o[0] : -o[0];
            totals[i] = Math.round(t);
        }
        Arrays.sort(totals);
        out.put("p10", totals[sims / 10]);
        out.put("p50", totals[sims / 2]);
        out.put("p90", totals[sims * 9 / 10]);
        return out;
    }
}
