package app.bilincli.core;

/**
 * Bağımsız Poisson gol modeli: Maç Sonucu (1, 2) ve 2,5 Üst olasılıklarına en iyi uyan ev ve
 * deplasman gol beklentileri bulunur; Karşılıklı Gol olasılığı buradan hesaplanır.
 *
 * KG için yalnızca pazar eşlemesinde kullanılır (bahis kararı Pinnacle'ın KG fiyatıyla). 2.10'dan
 * itibaren Pinnacle'ın fiyat vermediği tek maç pazarlarının (Alt/Üst 0,5–4,5, ev ve deplasman gol
 * Alt/Üst, handikaplı maç sonucu) adil olasılığı da buradan gelir; karar bu olasılıklardan
 * {@link #MARGIN} kadar düşülerek verilir (Models.fair).
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

    /**
     * Model pazarlarında olasılıktan düşülen güvenlik payı. Bağımsız Poisson modelinin hatası Alt/Üst
     * çizgilerinde birkaç puandır (KG'de ölçülen ortalama 2,8 puan); seçim yanlılığına (yalnızca
     * modelin iddaa'dan iyimser olduğu seçimler öne çıkar) karşı pay bunun üstünde tutuldu.
     */
    public static final double MARGIN = 0.04;

    /** Skor matrisi: m[i][j] = P(ev i gol, deplasman j gol), 0..MAX_GOALS. */
    public static double[][] matrix(double lh, double la) {
        double[] h = poisson(lh), a = poisson(la);
        double[][] m = new double[MAX_GOALS + 1][MAX_GOALS + 1];
        for (int i = 0; i <= MAX_GOALS; i++) for (int j = 0; j <= MAX_GOALS; j++) m[i][j] = h[i] * a[j];
        return m;
    }

    /** Maç Sonucu (1, 2) ve 2,5 Üst adil olasılıklarına uyan skor matrisi. */
    public static double[][] matrix(double p1, double p2, double over) {
        double[] l = fit(p1, p2, over);
        return matrix(l[0], l[1]);
    }

    /** Model pazarı mı: "AU@1.5" (toplam gol), "EVG@0.5", "DEPG@1.5" (takım golü), "HMS@-1" (handikaplı MS), "IYAU@0.5" (ilk yarı; yalnızca eşleme). */
    public static boolean isModelMarket(String market) {
        return market.startsWith("AU@") || market.startsWith("EVG@") || market.startsWith("DEPG@") || market.startsWith("HMS@")
                || market.startsWith("IYAU@");
    }

    /**
     * Ek pazarın model olasılıkları (payı düşülmemiş): Alt/Üst türlerinde {ALT, UST}, handikaplı MS'de
     * {1, X, 2} (çizgi: ev sahibine eklenen gol). Çizgi uygun değilse (Alt/Üst'te x,5 değil, handikapta tam
     * sayı değil) null. İlk yarı Alt/Üst yalnızca pazar eşlemesinde ayırt etmek içindir (gollerin ~%45'i).
     */
    public static java.util.Map<String, Double> market(double[][] m, String market) {
        int at = market.indexOf('@');
        if (m == null || at < 0) return null;
        String fam = market.substring(0, at);
        double line;
        try {
            line = Double.parseDouble(market.substring(at + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        if ("HMS".equals(fam)) {
            if (Math.abs(line - Math.rint(line)) > 1e-9) return null;
            double p1 = 0, px = 0, p2 = 0;
            for (int i = 0; i <= MAX_GOALS; i++) {
                for (int j = 0; j <= MAX_GOALS; j++) {
                    double d = i + line - j;
                    if (d > 0) p1 += m[i][j];
                    else if (d == 0) px += m[i][j];
                    else p2 += m[i][j];
                }
            }
            double s = p1 + px + p2;
            out.put("1", p1 / s);
            out.put("X", px / s);
            out.put("2", p2 / s);
            return out;
        }
        if (Math.abs(line * 2 - Math.rint(line * 2)) > 1e-9 || Math.abs(line - Math.rint(line)) < 1e-9 || line <= 0) return null;
        double under = 0, total = 0;
        if ("IYAU".equals(fam)) {
            double lh = 0, la = 0; // matristen gol beklentileri
            for (int i = 0; i <= MAX_GOALS; i++) for (int j = 0; j <= MAX_GOALS; j++) {
                lh += i * m[i][j];
                la += j * m[i][j];
                total += m[i][j];
            }
            double lam = 0.45 * (lh + la) / total, pk = Math.exp(-lam);
            for (int k = 0; k < line; k++) {
                under += pk;
                pk = pk * lam / (k + 1);
            }
            total = 1;
        } else {
            for (int i = 0; i <= MAX_GOALS; i++) {
                for (int j = 0; j <= MAX_GOALS; j++) {
                    total += m[i][j];
                    int goals = "AU".equals(fam) ? i + j : "EVG".equals(fam) ? i : "DEPG".equals(fam) ? j : -1;
                    if (goals < 0) return null;
                    if (goals < line) under += m[i][j];
                }
            }
        }
        out.put("ALT", under / total);
        out.put("UST", 1 - under / total);
        return out;
    }

    /** Maç Sonucu ve 2,5 Üst adil olasılıklarından tahmini KG Var olasılığı. */
    public static double btts(double p1, double p2, double over) {
        double[] l = fit(p1, p2, over);
        return outcomes(l[0], l[1])[4];
    }
}
