package app.bilincli.core;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Demo modu: sentetik dünyada N günlük simülasyon (Python: `bilincli demo`). */
public final class DemoSim {
    private DemoSim() {}

    public static Map<String, Object> run(Ledger.Storage storage, int days, long bankroll, long seed, Instant today0600) {
        final Demo world = new Demo(seed);
        Instant start = today0600.minusSeconds(days * 86400L);
        world.now = start.minusSeconds(60);
        Ledger ledger = new Ledger(storage, new Ledger.Clock() {
            @Override
            public Instant now() {
                return world.now;
            }
        });
        ledger.deposit(bankroll, "demo başlangıç");
        Daily.Sources src = new Daily.DemoSources(world);
        long baseline = bankroll;
        int coupon = 0, pass = 0, guard = 0;
        for (int d = 0; d <= days; d++) {
            world.now = start.plusSeconds(d * 86400L);
            Daily.Result r = Daily.runDaily(ledger, src, false, true);
            if (r.couponId != null) {
                // Kapanış oranı: demo dünyasında piyasa maça doğru gerçeğe yaklaşır.
                for (Ledger.Leg l : ledger.coupon(r.couponId).legs) {
                    Double t = world.truth(l.sharpRef, l.outcome);
                    if (t != null) ledger.setClosing(r.couponId, l.position, t);
                }
            }
            String h = r.headline();
            if ("KUPON".equals(h)) coupon++;
            else if ("PAS".equals(h)) pass++;
            else if ("KORUMA".equals(h)) guard++;
            if (d == days) break; // bugün: sonucu henüz belli değil
            LocalDate date = world.now.atOffset(Fmt.TR).toLocalDate();
            List<double[]> picks = world.favourites(date);
            long stake = baseline * 2 / 100;
            double odds = 1;
            boolean all = true;
            for (double[] p : picks) {
                odds *= p[0];
                if (p[1] == 0) all = false;
            }
            baseline += all ? Math.round(stake * odds) - stake : -stake;
        }
        Ledger.Stats s = ledger.stats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", (long) days);
        out.put("couponDays", (long) coupon);
        out.put("passDays", (long) pass);
        out.put("guardDays", (long) guard);
        out.put("start", bankroll);
        out.put("end", s.balance);
        out.put("roi", s.roi());
        out.put("won", (long) s.won);
        out.put("played", (long) s.played);
        out.put("expectedWins", s.expectedWins);
        out.put("baselineEnd", baseline);
        return out;
    }
}
