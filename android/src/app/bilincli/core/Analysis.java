package app.bilincli.core;

import app.bilincli.core.Ledger.Coupon;
import app.bilincli.core.Ledger.Leg;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Şeffaf ROI analizi: oynanan kuponların kâr/zararı maç sayısına, oran aralığına, lige,
 * pazara ve aya göre; ayrıca kasanın zirveden en büyük düşüşü (drawdown).
 * Çok maçlı kuponlarda lig/pazar K/Z'si maçlara eşit bölünür.
 */
public final class Analysis {
    private Analysis() {}

    private static final class Acc {
        String key;
        int n, won;
        long staked;
        double pl, clvSum;
        int clvN;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("n", (long) n);
            m.put("won", (long) won);
            m.put("staked", staked);
            m.put("pl", Math.round(pl));
            m.put("roi", staked == 0 ? null : pl / staked);
            m.put("clv", clvN == 0 ? null : clvSum / clvN);
            m.put("clvN", (long) clvN);
            return m;
        }
    }

    private static Acc acc(Map<String, Acc> map, String key) {
        Acc a = map.get(key);
        if (a == null) {
            a = new Acc();
            a.key = key;
            map.put(key, a);
        }
        return a;
    }

    static String legsBand(int n) {
        return n >= 4 ? "4+ maç" : n + " maç";
    }

    static String oddsBand(double o) {
        if (o < 2) return "1,00–1,99";
        if (o < 3) return "2,00–2,99";
        if (o < 5) return "3,00–4,99";
        return "5,00+";
    }

    private static List<Object> list(Map<String, Acc> m) {
        List<Object> out = new ArrayList<>();
        for (Acc a : m.values()) out.add(a.toMap());
        return out;
    }

    public static Map<String, Object> build(Ledger ledger) {
        Map<String, Acc> byLegs = new LinkedHashMap<>(), byOdds = new LinkedHashMap<>(), byLeague = new LinkedHashMap<>(),
                byMarket = new LinkedHashMap<>(), byMonth = new LinkedHashMap<>();
        for (String k : new String[] {"1 maç", "2 maç", "3 maç", "4+ maç"}) acc(byLegs, k);
        for (String k : new String[] {"1,00–1,99", "2,00–2,99", "3,00–4,99", "5,00+"}) acc(byOdds, k);
        List<Coupon> coupons = ledger.coupons();
        for (int i = coupons.size() - 1; i >= 0; i--) { // eskiden yeniye
            Coupon c = coupons.get(i);
            if (!c.played || c.result == null || Models.VOID.equals(c.result)) continue;
            double pl = (c.payout == null ? 0 : c.payout) - c.stake;
            boolean won = Models.WON.equals(c.result);
            Acc[] whole = {acc(byLegs, legsBand(c.legs.size())), acc(byOdds, oddsBand(c.totalOdds)),
                acc(byMonth, c.playedAt == null ? c.day.substring(0, 7) : Fmt.dayKey(java.time.Instant.parse(c.playedAt)).substring(0, 7))};
            for (Acc a : whole) {
                a.n++;
                if (won) a.won++;
                a.staked += c.stake;
                a.pl += pl;
            }
            int legs = c.legs.size();
            for (Leg l : c.legs) {
                String league = l.league == null || l.league.isEmpty() ? "Diğer" : l.league;
                for (Acc a : new Acc[] {acc(byLeague, league), acc(byMarket, Models.outcomeLabel(l.market, l.outcome).startsWith("MS")
                        ? "Maç Sonucu" : "2,5 Alt/Üst")}) {
                    a.n++;
                    if (Models.WON.equals(l.result)) a.won++;
                    a.staked += c.stake / legs;
                    a.pl += pl / legs;
                    if (l.clv() != null) {
                        a.clvSum += l.clv();
                        a.clvN++;
                    }
                }
            }
        }
        // Drawdown: yalnızca bahis K/Z'si üzerinden (para yatırma/çekme düşüş sayılmaz).
        // Zirvedeki kasaya göre oran: (zirve K/Z - güncel K/Z) / (o anki net yatırılan + zirve K/Z).
        List<Object[]> events = new ArrayList<>();
        for (Ledger.Tx t : ledger.transactions()) {
            if ("deposit".equals(t.kind) || "withdraw".equals(t.kind)) events.add(new Object[] {t.ts, t.amount, true});
        }
        for (int i = coupons.size() - 1; i >= 0; i--) { // eskiden yeniye (eşit zamanlarda sıra korunur)
            Coupon c = coupons.get(i);
            if (c.played && c.result != null) events.add(new Object[] {c.settledAt, (c.payout == null ? 0 : c.payout) - c.stake, false});
        }
        java.util.Collections.sort(events, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return java.time.Instant.parse((String) a[0]).compareTo(java.time.Instant.parse((String) b[0]));
            }
        });
        long netDeposits = 0, pl = 0, peakPl = 0, maxDdAmount = 0;
        double maxDd = 0, current = 0;
        for (Object[] e : events) {
            if ((Boolean) e[2]) netDeposits += (Long) e[1];
            else pl += (Long) e[1];
            if (pl > peakPl) peakPl = pl;
            long base = netDeposits + peakPl;
            current = base > 0 ? (double) (peakPl - pl) / base : 0;
            // en büyük düşüş yalnızca bahis sonuçlarıyla güncellenir; para çekmek düşüş değildir
            if (!(Boolean) e[2] && current > maxDd) {
                maxDd = current;
                maxDdAmount = peakPl - pl;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byLegs", list(byLegs));
        out.put("byOdds", list(byOdds));
        out.put("byLeague", list(byLeague));
        out.put("byMarket", list(byMarket));
        out.put("byMonth", list(byMonth));
        Map<String, Object> dd = new LinkedHashMap<>();
        dd.put("current", current);
        dd.put("max", maxDd);
        dd.put("maxAmount", maxDdAmount);
        dd.put("pl", pl);
        out.put("drawdown", dd);
        return out;
    }
}
