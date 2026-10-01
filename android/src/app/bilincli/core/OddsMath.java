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

    /** Standart normal dağılım fonksiyonu (erf yaklaşımı, hata < 1,5e-7). */
    public static double normCdf(double z) {
        double x = Math.abs(z) / Math.sqrt(2), t = 1 / (1 + 0.3275911 * x);
        double erf = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return z >= 0 ? 0.5 * (1 + erf) : 0.5 * (1 - erf);
    }

    /** normCdf'in tersi (Acklam yaklaşımı, göreli hata < 1,2e-9). */
    public static double normInv(double p) {
        if (!(p > 0 && p < 1)) throw new IllegalArgumentException("olasılık (0,1) aralığında olmalı: " + p);
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02,
            -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01,
            -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00,
            4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double lo = 0.02425, q, r;
        if (p < lo) {
            q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - lo) {
            q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        q = p - 0.5;
        r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }
}
