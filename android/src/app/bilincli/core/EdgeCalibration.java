package app.bilincli.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kanıta dayalı avantaj kalibrasyonu.
 *
 * Seçilen her bacak için öngörülen avantaj (oynama anındaki adil olasılık × oran − 1) ile
 * kapanıştaki avantaj (maçtan hemen önce Pinnacle'ın adil olasılığı × oran − 1) karşılaştırılır.
 * r = gerçekleşen / öngörülen. r, avantaj tahmininin ne kadarının gerçek çıktığını gösterir;
 * seçim yapılırken olasılık iddaa fiyatına doğru küçültülür:
 *     p' = r·p + (1 − r)/oran   ⇒   p'·oran − 1 = r·(p·oran − 1)
 * Az veriyle r = 1 kabul edilir (30 bacaklık ön bilgi); veri arttıkça ölçüme yaklaşır.
 * r en fazla 1'dir (avantaj hiçbir zaman büyütülmez). Uygulanan çarpan {@link #factor}: r ≥ 0,5
 * iken 1 (dokunulmaz), altında 2r, en az {@link #FLOOR}. Kanıt çok zayıfsa tutarlar ve kupon
 * sayısı çok küçülür ama ölçüm sürsün diye sıfırlanmaz.
 */
public final class EdgeCalibration {
    private EdgeCalibration() {}

    static final int PRIOR = 30;
    public static final double FLOOR = 0.25;
    static final int RECENT = 300;
    static final double DEFAULT_EDGE = 0.06;

    public static double adjust(double prob, double odds, double r) {
        return r * prob + (1 - r) / odds;
    }

    /** Bir pazar (ya da tümü) için ölçüm. */
    public static final class Segment {
        public int n;
        public double predicted, realized;
        /** Ölçülen oran (ön bilgiyle yumuşatılmış, en fazla 1). */
        public double ratio = 1.0;
        /** Uygulanan çarpan: gerçekleşen avantaj öngörülenin yarısından azsa devreye girer. */
        public double factor = 1.0;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", (long) n);
            m.put("predicted", n == 0 ? null : predicted / n);
            m.put("realized", n == 0 ? null : realized / n);
            m.put("ratio", ratio);
            m.put("factor", factor);
            return m;
        }
    }

    /** "*" = tümü, diğer anahtarlar pazar (MS, AU25, KG, CS). */
    public static final class Calib {
        public final Map<String, Segment> segments = new LinkedHashMap<>();

        public Map<String, Double> ratios() {
            Map<String, Double> out = new LinkedHashMap<>();
            for (Map.Entry<String, Segment> e : segments.entrySet()) out.put(e.getKey(), e.getValue().factor);
            return out;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, Segment> e : segments.entrySet()) m.put(e.getKey(), e.getValue().toMap());
            return m;
        }
    }

    /** Bir bacağın ölçümü: {oran, öngörülen olasılık, kapanış olasılığı} ve pazarı. */
    public static final class Obs {
        final String market;
        final double odds, prob, closing;

        public Obs(String market, double odds, double prob, double closing) {
            this.market = market;
            this.odds = odds;
            this.prob = prob;
            this.closing = closing;
        }
    }

    static double clamp(double r) {
        return Math.max(0, Math.min(1.0, r));
    }

    /**
     * Ölçülen oran → uygulanan çarpan. Avantajların bir kısmının kapanışta erimesi normaldir
     * (seçim etkisi); simülasyonda r ≥ 0,5 iken küçültme kârı artırmadı, azalttı. Bu yüzden
     * yalnızca gerçekleşen avantaj öngörülenin yarısının altındaysa küçültülür: çarpan = 2r,
     * en az FLOOR.
     */
    static double factor(double r) {
        return Math.max(FLOOR, Math.min(1.0, 2 * r));
    }

    public static Calib compute(List<Obs> obs) {
        Calib c = new Calib();
        Segment all = new Segment();
        Map<String, Segment> by = new LinkedHashMap<>();
        for (Obs o : obs) {
            double pred = o.prob * o.odds - 1, real = o.closing * o.odds - 1;
            all.n++;
            all.predicted += pred;
            all.realized += real;
            Segment s = by.get(o.market);
            if (s == null) by.put(o.market, s = new Segment());
            s.n++;
            s.predicted += pred;
            s.realized += real;
        }
        double edge = all.n > 0 && all.predicted > 0 ? all.predicted / all.n : DEFAULT_EDGE;
        double w = PRIOR * edge;
        all.ratio = clamp((all.realized + w) / (all.predicted + w));
        all.factor = factor(all.ratio);
        c.segments.put("*", all);
        for (Map.Entry<String, Segment> e : by.entrySet()) {
            Segment s = e.getValue();
            double em = s.predicted > 0 ? s.predicted / s.n : edge, wm = PRIOR * em;
            // pazar ölçümü az olduğunda genel orana yaslanır
            s.ratio = clamp((s.realized + wm * all.ratio) / (s.predicted + wm));
            s.factor = factor(s.ratio);
            c.segments.put(e.getKey(), s);
        }
        return c;
    }

    /** Defterdeki kapanışı ölçülmüş son bacaklardan (oynanmış ya da yalnızca önerilmiş). */
    public static Calib fromLedger(Ledger ledger) {
        List<Obs> obs = new ArrayList<>();
        for (Ledger.Coupon c : ledger.coupons()) { // en yeni önce
            for (Ledger.Leg l : c.legs) {
                if (l.closingFair == null || l.fairProb <= 0 || l.odds <= 1) continue;
                obs.add(new Obs(l.market, l.odds, l.fairProb, l.closingFair));
            }
            if (obs.size() >= RECENT) break;
        }
        return compute(obs);
    }

    /** Pazar için oran (yoksa genel). */
    public static double ratio(Map<String, Double> ratios, String market) {
        if (ratios == null) return 1.0;
        Double r = ratios.get(market);
        if (r == null) r = ratios.get("*");
        return r == null ? 1.0 : r;
    }
}
