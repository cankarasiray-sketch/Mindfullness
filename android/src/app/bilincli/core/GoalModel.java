package app.bilincli.core;

/**
 * Bağımsız Poisson gol modeli: Maç Sonucu (1, 2) ve 2,5 Üst olasılıklarına en iyi uyan ev ve
 * deplasman gol beklentileri bulunur; Karşılıklı Gol olasılığı buradan hesaplanır.
 *
 * Yalnızca pazar eşlemesi (Nesine'de hangi pazar KG, seçenek sırası ne) için kullanılır: birkaç
 * puanlık model hatası eşleme için önemsizdir. Bahis kararı her zaman Pinnacle'ın gerçek KG
 * fiyatıyla verilir, model olasılığıyla asla.
 */
public final class GoalModel {
    private GoalModel() {}

    private static final int MAX_GOALS = 10;

    private static double[] poisson(double lambda) {
        double[] p = new double[MAX_GOALS + 1];
        p[0] = Math.exp(-lambda);
        for (int k = 1; k <= MAX_GOALS; k++) p[k] = p[k - 1] * lambda / k;
        return p;
    }

    /** {ev kazanır, beraberlik, deplasman kazanır, 2,5 üst, karşılıklı gol}. */
    static double[] outcomes(double lh, double la) {
        double[] h = poisson(lh), a = poisson(la);
        double p1 = 0, px = 0, p2 = 0, under = 0;
        for (int i = 0; i <= MAX_GOALS; i++) {
            for (int j = 0; j <= MAX_GOALS; j++) {
                double p = h[i] * a[j];
                if (i > j) p1 += p;
                else if (i == j) px += p;
                else p2 += p;
                if (i + j <= 2) under += p;
            }
        }
        double btts = (1 - h[0]) * (1 - a[0]);
        return new double[] {p1, px, p2, 1 - under, btts};
    }

    private static double error(double lh, double la, double p1, double p2, double over) {
        double[] o = outcomes(lh, la);
        return sq(o[0] - p1) + sq(o[2] - p2) + sq(o[3] - over);
    }

    private static double sq(double x) {
        return x * x;
    }

    /** En iyi uyan {ev gol beklentisi, deplasman gol beklentisi}. */
    static double[] fit(double p1, double p2, double over) {
        double bh = 1.3, ba = 1.1, best = Double.MAX_VALUE;
        for (double lh = 0.1; lh <= 4.0; lh += 0.05) {
            for (double la = 0.1; la <= 4.0; la += 0.05) {
                double e = error(lh, la, p1, p2, over);
                if (e < best) {
                    best = e;
                    bh = lh;
                    ba = la;
                }
            }
        }
        double ch = bh, ca = ba;
        for (double lh = ch - 0.05; lh <= ch + 0.05; lh += 0.005) {
            for (double la = ca - 0.05; la <= ca + 0.05; la += 0.005) {
                if (lh <= 0 || la <= 0) continue;
                double e = error(lh, la, p1, p2, over);
                if (e < best) {
                    best = e;
                    bh = lh;
                    ba = la;
                }
            }
        }
        return new double[] {bh, ba};
    }

    /** Maç Sonucu ve 2,5 Üst adil olasılıklarından tahmini KG Var olasılığı. */
    public static double btts(double p1, double p2, double over) {
        double[] l = fit(p1, p2, over);
        return outcomes(l[0], l[1])[4];
    }
}
