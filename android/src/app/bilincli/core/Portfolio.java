package app.bilincli.core;

import app.bilincli.core.Models.Candidate;
import app.bilincli.core.Models.Proposal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ortak Kelly: günün kuponları tek tek değil, birlikte tutarlandırılır.
 *
 * Değerli seçimlerden maç başına bir tane (en çok {@link #POOL}) alınır ve maçlar bağımsız kabul
 * edilir. MBS ve eşikleri sağlayan tüm tekli ve kombine kuponlar aday olur; kupon kümeleri maç
 * paylaşabilir. Tutar vektörü, 2^n sonuç durumu üzerinde tam hesaplanan beklenen log kasa
 * büyümesini en çok artıracak şekilde bulunur. Kesirli Kelly (λ) için tam Kelly problemi sınırlar
 * 1/λ ile genişletilerek çözülür ve sonuç λ ile küçültülür; böylece kupon ve günlük üst sınırlar
 * çözümün içinde kalır. Uygulanabilir olsun diye en büyük tutarlı en fazla N kupon tutulur ve
 * yalnızca onlarla yeniden çözülür.
 */
public final class Portfolio {
    private Portfolio() {}

    static final int POOL = 10;
    private static final double TINY = 1e-5;

    /** Seçilen kupon ve kasa oranı (kesirli Kelly ve tüm üst sınırlar uygulanmış). */
    public static final class Pick {
        public final Proposal proposal;
        public final double fraction;

        Pick(Proposal proposal, double fraction) {
            this.proposal = proposal;
            this.fraction = fraction;
        }

        /** Tek başına Kelly payına göre ölçek (kayıt ve güncel kontrol bu ölçeği korur). */
        public double scale() {
            return proposal.stakeFraction > 0 ? Math.min(1.0, fraction / proposal.stakeFraction) : 0;
        }
    }

    public static final class Result {
        public final List<Pick> picks = new ArrayList<>();
        /** Kesirli tutarlarla günlük beklenen log büyüme. */
        public double growth;
        /** Değerlendirilen seçim ve kupon sayısı. */
        public int legs, bets;
    }

    /** Tek seçimin tam Kelly'de log büyümesi (havuz sıralaması için). */
    static double singleGrowth(Candidate c) {
        double f = OddsMath.kellyFraction(c.prob, c.odds);
        return f <= 0 ? 0 : OddsMath.logGrowth(c.prob, c.odds, Math.min(f, 0.999));
    }

    /**
     * good: eşikleri geçen seçimler. maxBets: en fazla kupon. totalCap: bugün kalan günlük üst
     * sınır (kasa oranı). exclude: bugün zaten kuponda olan maçlar (bağımsızlık için dışarıda).
     */
    public static Result optimize(List<Candidate> good, Settings cfg, int maxBets, double totalCap,
                                  Collection<String> exclude) {
        Result res = new Result();
        double lambda = cfg.kellyMultiplier;
        if (maxBets <= 0 || totalCap <= TINY || lambda <= 0) return res;

        // 1) havuz: maç başına en yüksek büyümeli seçim
        Map<String, Candidate> best = new LinkedHashMap<>();
        for (Candidate c : good) {
            if (exclude != null && exclude.contains(c.book.ref)) continue;
            Candidate cur = best.get(c.book.ref);
            if (cur == null || singleGrowth(c) > singleGrowth(cur)) best.put(c.book.ref, c);
        }
        List<Candidate> pool = new ArrayList<>(best.values());
        Collections.sort(pool, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Double.compare(singleGrowth(b), singleGrowth(a));
            }
        });
        if (pool.size() > POOL) pool = new ArrayList<>(pool.subList(0, POOL));
        int n = pool.size();
        res.legs = n;
        if (n == 0) return res;

        // 2) aday kuponlar: MBS ve eşikleri sağlayan tüm alt kümeler
        List<Proposal> props = new ArrayList<>();
        List<Integer> masks = new ArrayList<>();
        for (int mask = 1; mask < (1 << n); mask++) {
            int k = Integer.bitCount(mask);
            if (k > (cfg.singlesOnly ? 1 : cfg.maxLegs)) continue; // tek maç modu: yalnızca tekli kuponlar
            List<Candidate> legs = new ArrayList<>(k);
            int maxMbs = 0;
            double prob = 1, odds = 1;
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) == 0) continue;
                Candidate c = pool.get(i);
                legs.add(c);
                maxMbs = Math.max(maxMbs, c.mbs());
                prob *= c.prob;
                odds *= c.odds;
            }
            if (maxMbs > k || odds < cfg.minCouponOdds || prob < cfg.minWinProb || prob * odds - 1 < cfg.minCouponEv) continue;
            double f = Engine.stakeFraction(prob, odds, cfg);
            if (f <= 0) continue;
            props.add(new Proposal(legs, odds, prob, f, OddsMath.logGrowth(prob, odds, f)));
            masks.add(mask);
        }
        res.bets = props.size();
        if (props.isEmpty()) return res;

        double[] pi = stateProbs(pool);
        int[] mk = new int[props.size()];
        double[] od = new double[props.size()];
        for (int b = 0; b < mk.length; b++) {
            mk[b] = masks.get(b);
            od[b] = props.get(b).odds;
        }
        // 3) tam Kelly, sınırlar 1/λ ile genişletilmiş
        double upper = cfg.maxStakeFraction / lambda, total = Math.min(totalCap / lambda, 0.95);
        double[] f = solve(n, pi, mk, od, upper, total);

        // 4) en büyük N kupon, yalnızca onlarla yeniden çözüm
        Integer[] order = new Integer[f.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final double[] ff = f;
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(ff[b], ff[a]);
            }
        });
        List<Integer> keep = new ArrayList<>();
        for (Integer i : order) {
            if (keep.size() >= maxBets || ff[i] * lambda < TINY) break;
            keep.add(i);
        }
        if (keep.isEmpty()) return res;
        int[] mk2 = new int[keep.size()];
        double[] od2 = new double[keep.size()];
        for (int j = 0; j < keep.size(); j++) {
            mk2[j] = mk[keep.get(j)];
            od2[j] = od[keep.get(j)];
        }
        double[] f2 = solve(n, pi, mk2, od2, upper, total);

        // 5) kesirli Kelly; hiçbir kupon tek başına Kelly payını aşmaz
        double[] fin = new double[keep.size()];
        for (int j = 0; j < keep.size(); j++) {
            Proposal p = props.get(keep.get(j));
            fin[j] = Math.min(lambda * f2[j], p.stakeFraction);
        }
        List<Integer> idx = new ArrayList<>();
        for (int j = 0; j < fin.length; j++) if (fin[j] >= TINY) idx.add(j);
        final double[] fn = fin;
        Collections.sort(idx, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(fn[b], fn[a]);
            }
        });
        for (int j : idx) res.picks.add(new Pick(props.get(keep.get(j)), fin[j]));
        res.growth = growth(n, pi, mk2, od2, fin);
        return res;
    }

    /** Bağımsız seçimler için 2^n durumun olasılıkları (bit i = i. seçim tuttu). */
    static double[] stateProbs(List<Candidate> pool) {
        int n = pool.size();
        double[] pi = new double[1 << n];
        for (int s = 0; s < pi.length; s++) {
            double p = 1;
            for (int i = 0; i < n; i++) p *= (s & (1 << i)) != 0 ? pool.get(i).prob : 1 - pool.get(i).prob;
            pi[s] = p;
        }
        return pi;
    }

    /** Her durumda kasa çarpanı: 1 - Σf + Σ f_b·o_b·[b tuttu]. */
    static double[] wealth(int n, int[] mk, double[] od, double[] f) {
        int size = 1 << n;
        double[] a = new double[size];
        double spent = 0;
        for (int b = 0; b < f.length; b++) {
            if (f[b] == 0) continue;
            a[mk[b]] += f[b] * od[b];
            spent += f[b];
        }
        // alt küme toplamı: a'[s] = Σ_{m ⊆ s} a[m]
        for (int i = 0; i < n; i++) {
            for (int s = 0; s < size; s++) if ((s & (1 << i)) != 0) a[s] += a[s ^ (1 << i)];
        }
        for (int s = 0; s < size; s++) a[s] += 1 - spent;
        return a;
    }

    static double growth(int n, double[] pi, int[] mk, double[] od, double[] f) {
        double[] w = wealth(n, mk, od, f);
        double g = 0;
        for (int s = 0; s < w.length; s++) {
            if (pi[s] == 0) continue;
            if (w[s] <= 0) return Double.NEGATIVE_INFINITY;
            g += pi[s] * Math.log(w[s]);
        }
        return g;
    }

    static double[] gradient(int n, double[] pi, int[] mk, double[] od, double[] f) {
        double[] w = wealth(n, mk, od, f);
        int size = 1 << n;
        double[] h = new double[size];
        for (int s = 0; s < size; s++) h[s] = pi[s] / w[s];
        // üst küme toplamı: H[m] = Σ_{s ⊇ m} h[s]
        for (int i = 0; i < n; i++) {
            for (int s = 0; s < size; s++) if ((s & (1 << i)) == 0) h[s] += h[s | (1 << i)];
        }
        double[] g = new double[f.length];
        for (int b = 0; b < f.length; b++) g[b] = od[b] * h[mk[b]] - h[0];
        return g;
    }

    /** {0 ≤ f ≤ upper, Σf ≤ total} kümesine izdüşüm. */
    static double[] project(double[] x, double upper, double total) {
        double[] y = new double[x.length];
        double s = 0;
        for (int i = 0; i < x.length; i++) {
            y[i] = Math.min(Math.max(x[i], 0), upper);
            s += y[i];
        }
        if (s <= total) return y;
        double lo = 0, hi = 0;
        for (double v : x) hi = Math.max(hi, v);
        for (int it = 0; it < 100; it++) {
            double tau = (lo + hi) / 2, t = 0;
            for (double v : x) t += Math.min(Math.max(v - tau, 0), upper);
            if (t > total) lo = tau;
            else hi = tau;
        }
        for (int i = 0; i < x.length; i++) y[i] = Math.min(Math.max(x[i] - hi, 0), upper);
        return y;
    }

    /** İzdüşümlü gradyan yükselişi (Armijo adımı); amaç içbükey olduğundan tek tepe vardır. */
    static double[] solve(int n, double[] pi, int[] mk, double[] od, double upper, double total) {
        double[] f = new double[mk.length];
        double g = 0, step = 0.1;
        for (int it = 0; it < 3000; it++) {
            double[] grad = gradient(n, pi, mk, od, f);
            boolean moved = false;
            for (int tries = 0; tries < 60; tries++) {
                double[] x = new double[f.length];
                for (int i = 0; i < f.length; i++) x[i] = f[i] + step * grad[i];
                double[] y = project(x, upper, total);
                double dir = 0, change = 0;
                for (int i = 0; i < f.length; i++) {
                    dir += grad[i] * (y[i] - f[i]);
                    change = Math.max(change, Math.abs(y[i] - f[i]));
                }
                if (change < 1e-12) break;
                double gy = growth(n, pi, mk, od, y);
                if (gy >= g + 1e-4 * dir) {
                    moved = change > 1e-10;
                    f = y;
                    g = gy;
                    step *= 2;
                    break;
                }
                step /= 2;
            }
            if (!moved) break;
        }
        return f;
    }
}
