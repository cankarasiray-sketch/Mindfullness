package app.bilincli.core;

/** Oran matematiği: marj, marjdan arındırma, beklenen değer, Kelly. */
public final class OddsMath {
    private OddsMath() {}

    private static void check(double[] odds) {
        if (odds.length == 0) throw new IllegalArgumentException("boş oran listesi");
        for (double o : odds) if (!(o > 1.0)) throw new IllegalArgumentException("geçersiz oran: " + o);
    }

    public static double impliedSum(double[] odds) {
        check(odds);
        double s = 0;
        for (double o : odds) s += 1.0 / o;
        return s;
    }

    /** Bahis şirketinin marjı (overround). 0.10 = %10. */
    public static double margin(double[] odds) {
        return impliedSum(odds) - 1.0;
    }

    /**
     * Üs yöntemi: sum((1/o)^k) = 1 olacak k bulunur. Orantılı yönteme göre
     * favori/sürpriz yanlılığını daha iyi düzeltir.
     */
    public static double[] devigPower(double[] odds) {
        check(odds);
        double[] imp = new double[odds.length];
        double total = 0;
        for (int i = 0; i < odds.length; i++) {
            imp[i] = 1.0 / odds[i];
            total += imp[i];
        }
        if (Math.abs(total - 1.0) < 1e-12) return imp;
        double lo = total > 1.0 ? 1.0 : 0.01, hi = total > 1.0 ? 50.0 : 1.0;
        for (int it = 0; it < 200; it++) {
            double mid = (lo + hi) / 2.0, s = 0;
            for (double p : imp) s += Math.pow(p, mid);
            if (s > 1.0) lo = mid;
            else hi = mid;
        }
        double k = (lo + hi) / 2.0, s = 0;
        double[] out = new double[imp.length];
        for (int i = 0; i < imp.length; i++) {
            out[i] = Math.pow(imp[i], k);
            s += out[i];
        }
        for (int i = 0; i < out.length; i++) out[i] /= s;
        return out;
    }

    public static double kellyFraction(double prob, double odds) {
        double b = odds - 1.0;
        if (b <= 0) return 0.0;
        return Math.max(0.0, (prob * odds - 1.0) / b);
    }

    public static double logGrowth(double prob, double odds, double f) {
        if (f <= 0) return 0.0;
        if (f >= 1) return Double.NEGATIVE_INFINITY;
        return prob * Math.log1p(f * (odds - 1.0)) + (1.0 - prob) * Math.log1p(-f);
    }
}
