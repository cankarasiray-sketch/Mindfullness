package app.bilincli.core;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 2.26: altılı defteri, sonuçlar ve öğrenme. Her okumada günün altılıları (son AGF'leri ve kurulan kupon) kaydedilir;
 * bildirilen kupon ayrıca dondurulur. Sonraki günlerde TJK sonuçlarından kazanan atlar okunur, kuponun tutup tutmadığı ve
 * kazancı hesaplanır (ikramiye sayfada yoksa AGF'den tahmin: birim × (1 − kesinti) / kazanan kombinasyonun AGF payı).
 *
 * <p>Öğrenme iki şeydir. (1) AGF düzeltmesi: kazananlar, AGF'nin verdiği olasılıkla ne kadar uyuşuyor? q ∝ p^alpha en
 * çok olabilirlikle bulunur; alpha > 1 kalabalığın favorileri az, sürprizleri çok oynadığı demektir. Yalnızca yeterli
 * veri (en az MIN_LEGS ayak), anlamlı fark (olabilirlik oranı testi, %5) ve son üçte birlik dönemde de (öğrenmede
 * kullanılmadan) iyileştirme varsa kullanılır; azken 1'e doğru büzülür. (2) Bütçe: geçmiş altılılarda kasanın %1, %3
 * ve %6'sı bütçeli kuponlar yeniden kurulup sonuçlarıyla karşılaştırılır; en az MIN_SETTLED sonuçtan sonra getirisi en
 * iyi olan bütçe kullanılır.
 */
public final class AltiliLog {
    private AltiliLog() {}

    static final int KEEP_DAYS = 380, SETTLE_DAYS = 5, ANALYZE_DAYS = 370; // 2.46–2.47: 1 yıllık geriye dönük karne
    static final int MIN_LEGS = 60, MIN_SETTLED = 20, HISTORY = 12; // 2.40: MIN_LEGS 150 → 60 (doğrulanmış veriyle; büzülme payı korur)
    /** 2.48: strateji tablosunun 6/6 olasılığı aralıkları: <%10, %10–15, %15–25, %25–40, ≥%40. */
    static final double[] HIT_EDGES = {0.10, 0.15, 0.25, 0.40};
    /** 2.49: eşik taraması: 6/6 kuralının (6/6 olasılığı ≥ eşik) eşikleri; en iyisi en az MIN_SETTLED kuponla. */
    static final double[] SWEEP = {0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40};
    /** 2.50: taramanın bölgeleri; eşitlikte geniş olan (önce "hepsi"). */
    static final String[] SCOPES = {"hepsi", "yerli", "yabancı"};
    /** 2.51: kupon büyüklüğü taraması (TL; ayardaki tavanla birlikte, onu aşmadan). */
    static final double[] STAKES = {100, 200, 300, 400, 500, 600}; // 2.54: 400, 500 (300 ile 600 arası boştu)
    /** alpha'nın 1'e büzülmesi: kullanılan = 1 + (bulunan − 1) × n / (n + PRIOR_LEGS). */
    static final double PRIOR_LEGS = 200;
    /** Karşılaştırılan bütçeler (2.29: kullanıcının %7'si dahil; bilgi için, bütçe ayardan). */
    static final double[] FRACTIONS = {0.03, 0.07, 0.10};
    /** Kazanan at AGF listesinde yoksa payı (tahmini ikramiye için). */
    static final double MISSING_P = 0.005;

    /**
     * 2.51: kupon büyüklüğü taraması: kurala uyan geçmiş altılılar her büyüklükte (en fazla cap) bugünkü düzeltmeyle en yüksek
     * 6/6 için yeniden kurulur; kasanın (km × kasa: Kelly payı) kupon başına ortalama log büyümesi en yüksek olan seçilir.
     * Hiçbirinde kasa büyümüyorsa "neg" (2.53: kupon tavanda kalır, kasa yetersiz: oynanmaz). {stakes: {rows, best?, neg?, km, kasa}, capUse}.
     */
    private static Map<String, Object> stakes(List<double[]> coupons, List<Map<String, Object>> recs, String scope, double t, double cap,
            double km, double kasa, double unit, double legMin, Map<String, Object> learn) {
        List<Double> caps = new ArrayList<>();
        for (double c : STAKES) if (c < cap - 1e-9) caps.add(c);
        caps.add(cap);
        double kk = Math.max(1e-9, km) * kasa;
        List<Object> rows = new ArrayList<>();
        Double best = null;
        double bestG = 0;
        for (double c : caps) {
            int n = 0, h = 0;
            double cost = 0, ret = 0, e = 0, g = 0;
            boolean ok = true;
            List<double[]> outs = new ArrayList<>(); // 2.52: {bedel, dönüş} (kasa ihtiyacı için)
            for (int i = 0; i < coupons.size(); i++) {
                if (!fits(coupons.get(i), scope, t)) continue;
                Map<String, Object> r = recs.get(i), pc = Json.obj(r.get("plan"));
                List<Object> win = Json.arr(Json.obj(r.get("result")).get("winners")), legs = Json.arr(r.get("legs"));
                double u = Json.dbl(pc, "unit", unit), payout = Json.dbl(Json.obj(r.get("result")), "payout", 0);
                double[] ab = alphaBeta(learn, coupons.get(i)[1] > 0);
                List<Altili.Leg> ls = new ArrayList<>();
                for (Object l : legs) ls.add(Altili.leg(String.valueOf(l), ab[0], ab[1]));
                boolean parsed = ls.size() == 6;
                for (Altili.Leg l : ls) parsed &= !l.horses.isEmpty();
                if (!parsed || !(u > 0)) continue;
                int[] cnt = Altili.bestHit(ls, (long) Math.floor(c / u + 1e-9), legMin);
                long units = 1;
                double hit = 1;
                boolean all = true;
                for (int k = 0; k < 6; k++) {
                    List<Altili.Horse> hs = Altili.byQ(ls.get(k)).subList(0, Math.min(cnt[k], ls.get(k).horses.size()));
                    double sq = 0;
                    boolean in = false;
                    for (Altili.Horse x : hs) {
                        sq += x.q;
                        in |= x.no.equals(String.valueOf(win.get(k)));
                    }
                    units *= hs.size();
                    hit *= sq;
                    all &= in;
                }
                if (coupons.get(i).length > 5 && coupons.get(i)[5] > 0) all = false; // 2.56: devir: kimse tutturamadı
                double cst = units * u, rt = all ? payout : 0;
                outs.add(new double[] {cst, rt});
                n++;
                if (all) h++;
                cost += cst;
                ret += rt;
                e += hit;
                if (cst >= kk) ok = false; // kaybı Kelly payını aşıyor
                else g += Math.log(1 + (rt - cst) / kk);
            }
            Map<String, Object> row = agg(n, h, cost, ret);
            row.put("expected", e);
            row.put("cap", c);
            if (ok && n > 0) row.put("growth", g / n);
            double[] need = kasaNeed(outs); // 2.52: kasa ne kadar olmalı
            if (need != null) {
                row.put("kasaMin", need[0]);
                row.put("kasaNeed", need[0] / Math.max(1e-9, km)); // 2.54: Kelly payıyla oynama kararıyla aynı: kasa bunun üstündeyse büyür
                row.put("kasaKelly", need[1]);
                row.put("kasaRec", need[1] / Math.max(1e-9, km));
            }
            rows.add(row);
            if (ok && n > 0 && g / n > bestG + 1e-12) {
                bestG = g / n;
                best = c;
            }
        }
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("rows", rows);
        st.put("km", km);
        st.put("kasa", kasa);
        if (best != null) st.put("best", best);
        else st.put("neg", true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stakes", st);
        out.put("capUse", best != null ? best : cap); // 2.53: hiçbirinde büyümüyorsa küçültmek çözüm değil: tavanda kalır, oynanmaz
        return out;
    }

    /**
     * 2.52: kasa ne kadar olmalı: geçmiş kuponların {bedel, dönüş} sonuçları tekrarlanırsa kasanın kupon başına log büyümesi
     * h(x) = ort. log(1 + net · x), x = 1/kasa (içbükey, h(0) = 0). {en az kasa (h = 0: altında kasa uzun vadede erir),
     * tam Kelly kasası (h en büyük: kupon bu kasanın Kelly payı)}. Ortalamada kâr yoksa null (hiçbir kasa yetmez).
     */
    static double[] kasaNeed(List<double[]> outs) {
        double mean = 0, maxCost = 0;
        for (double[] o : outs) {
            mean += o[1] - o[0];
            maxCost = Math.max(maxCost, o[0]);
        }
        if (outs.isEmpty() || !(mean > 0) || !(maxCost > 0)) return null;
        double top = (1 - 1e-9) / maxCost, lo = 0, hi = top; // kaybedince kasa sıfıra inmesin: x < 1 / en büyük bedel
        for (int it = 0; it < 80; it++) { // tam Kelly: h'(x) = 0 (azalan)
            double x = (lo + hi) / 2, d = 0;
            for (double[] o : outs) d += (o[1] - o[0]) / (1 + (o[1] - o[0]) * x);
            if (d > 0) lo = x;
            else hi = x;
        }
        double xk = (lo + hi) / 2;
        lo = xk;
        hi = top;
        for (int it = 0; it < 80; it++) { // en az: h(x) = 0, Kelly noktasının ötesinde
            double x = (lo + hi) / 2, v = 0;
            for (double[] o : outs) v += Math.log(1 + (o[1] - o[0]) * x);
            if (v > 0) lo = x;
            else hi = x;
        }
        return new double[] {1 / ((lo + hi) / 2), 1 / xk};
    }

    /** 2.56: kaydın kuponu (önerilen ya da kurulan) okunan kazananları kapsıyor mu (devirde kapsıyorsa doğrulanamaz). */
    private static boolean covers(Map<String, Object> r, List<Object> win) {
        if (win.size() != 6) return true;
        for (String f : new String[] {"plan", "notified"}) {
            Map<String, Object> c = Json.obj(r.get(f));
            if (c != null && hits(c, win)[6]) return true;
        }
        return false;
    }

    /**
     * 2.59: ganyan ile tahmin: doğrulanmış altılılarda (sonuç tablolarından kapanış ganyanı okunmuş) kazanana verilen
     * olasılık AGF (öğrenilen düzeltmeyle), ganyan (1/oran, normalize) ve karışım q ∝ q_AGF^(1−w) · q_ganyan^w ile; w ilk
     * üçte ikide en çok olabilirlikle, ölçüm son üçte birde. Aynı tavanla en yüksek 6/6 kuponu AGF ve karışımla kurulur:
     * son dönemde tutma ve dönüş. {records, verified, w, legs, prob: {agf, ganyan, mix}, fav: {...}, coupons: {n, agf, mix}}.
     */
    private static Map<String, Object> oddsCompare(List<Map<String, Object>> verified, Map<String, Object> learn, double unit, double cap,
            double legMin) {
        List<Map<String, Object>> recs = new ArrayList<>();
        for (Map<String, Object> r : verified) if (Json.arr(Json.obj(r.get("result")).get("odds")).size() == 6) recs.add(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", (long) recs.size());
        out.put("verified", (long) verified.size());
        if (recs.size() < 3) return out;
        int cut = recs.size() * 2 / 3;
        // ayaklar: {q_AGF, q_ganyan (ortak atlarda normalize), kazanan sırası}
        List<double[][]> train = new ArrayList<>(), test = new ArrayList<>();
        List<int[]> trainW = new ArrayList<>(), testW = new ArrayList<>();
        for (int i = 0; i < recs.size(); i++) {
            Map<String, Object> r = recs.get(i), res = Json.obj(r.get("result"));
            List<Object> legs = Json.arr(r.get("legs")), win = Json.arr(res.get("winners")), odds = Json.arr(res.get("odds"));
            double[] ab = alphaBeta(learn, domestic(r));
            for (int k = 0; k < 6; k++) {
                double[][] qq = legQ(String.valueOf(legs.get(k)), Json.obj(odds.get(k)), ab);
                if (qq == null) continue;
                int wi = -1;
                for (int j = 0; j < qq[2].length; j++) if (String.valueOf((int) qq[2][j]).equals(String.valueOf(win.get(k)))) wi = j;
                if (wi < 0) continue;
                (i < cut ? train : test).add(qq);
                (i < cut ? trainW : testW).add(new int[] {wi});
            }
        }
        double bw = 0, bl = Double.NEGATIVE_INFINITY;
        for (int s = 0; s <= 10; s++) {
            double w = s / 10.0, l = 0;
            for (int i = 0; i < train.size(); i++) l += Math.log(mix(train.get(i), w)[trainW.get(i)[0]]);
            if (l > bl + 1e-9) {
                bl = l;
                bw = w;
            }
        }
        out.put("w", bw);
        out.put("legs", (long) test.size());
        double[] lp = new double[3];
        int[] fav = new int[3];
        double[] ws = {0, 1, bw};
        for (int i = 0; i < test.size(); i++) {
            for (int m = 0; m < 3; m++) {
                double[] q = mix(test.get(i), ws[m]);
                lp[m] += Math.log(q[testW.get(i)[0]]);
                int f = 0;
                for (int j = 1; j < q.length; j++) if (q[j] > q[f]) f = j;
                if (f == testW.get(i)[0]) fav[m]++;
            }
        }
        Map<String, Object> prob = new LinkedHashMap<>(), fv = new LinkedHashMap<>();
        String[] names = {"agf", "ganyan", "mix"};
        for (int m = 0; m < 3; m++) {
            if (!test.isEmpty()) {
                prob.put(names[m], Math.exp(lp[m] / test.size())); // kazanana verilen olasılığın geometrik ortalaması
                fv.put(names[m], (double) fav[m] / test.size());
            }
        }
        out.put("prob", prob);
        out.put("fav", fv);
        // son dönemde kupon: aynı tavanla en yüksek 6/6, AGF ile ve karışımla
        long mu = (long) Math.floor(cap / unit + 1e-9);
        int n = 0;
        int[] h = new int[2];
        double[] cost = new double[2], ret = new double[2];
        for (int i = cut; i < recs.size(); i++) {
            Map<String, Object> r = recs.get(i), res = Json.obj(r.get("result"));
            List<Object> legs = Json.arr(r.get("legs")), win = Json.arr(res.get("winners")), odds = Json.arr(res.get("odds"));
            double[] ab = alphaBeta(learn, domestic(r));
            List<Altili.Leg> la = new ArrayList<>(), lm = new ArrayList<>();
            boolean ok = true;
            for (int k = 0; k < 6 && ok; k++) {
                Altili.Leg a = Altili.leg(String.valueOf(legs.get(k)), ab[0], ab[1]), b = Altili.leg(String.valueOf(legs.get(k)), ab[0], ab[1]);
                double[][] qq = legQ(String.valueOf(legs.get(k)), Json.obj(odds.get(k)), ab);
                ok = !a.horses.isEmpty() && qq != null;
                if (!ok) break;
                double[] q = mix(qq, bw);
                for (Altili.Horse x : b.horses) { // ganyanı olmayan at (koşmadı): 0
                    x.q = 0;
                    for (int j = 0; j < qq[2].length; j++) if (String.valueOf((int) qq[2][j]).equals(x.no)) x.q = q[j];
                }
                la.add(a);
                lm.add(b);
            }
            if (!ok) continue;
            n++;
            double payout = Json.dbl(res, "payout", 0);
            for (int m = 0; m < 2; m++) {
                List<Altili.Leg> ls = m == 0 ? la : lm;
                int[] cnt = Altili.bestHit(ls, mu, legMin);
                long units = 1;
                boolean all = true;
                for (int k = 0; k < 6; k++) {
                    List<Altili.Horse> hs = Altili.byQ(ls.get(k)).subList(0, Math.min(cnt[k], ls.get(k).horses.size()));
                    boolean in = false;
                    for (Altili.Horse x : hs) in |= x.no.equals(String.valueOf(win.get(k)));
                    units *= hs.size();
                    all &= in;
                }
                cost[m] += units * unit;
                if (all) {
                    h[m]++;
                    ret[m] += payout;
                }
            }
        }
        Map<String, Object> cp = new LinkedHashMap<>();
        cp.put("n", (long) n);
        cp.put("cap", cap);
        cp.put("agf", agg(n, h[0], cost[0], ret[0]));
        cp.put("mix", agg(n, h[1], cost[1], ret[1]));
        out.put("coupons", cp);
        if (cut < recs.size()) out.put("from", recs.get(cut).get("day"));
        return out;
    }

    /** 2.59: bir ayakta AGF'li ve ganyanlı ortak atlar: {q_AGF, q_ganyan (ikisi de ortak atlarda normalize), at no}; az ise null. */
    private static double[][] legQ(String legText, Map<String, Object> odds, double[] ab) {
        if (odds == null) return null;
        List<Altili.Horse> hs = Altili.leg(legText, ab[0], ab[1]).horses;
        List<double[]> c = new ArrayList<>();
        for (Altili.Horse x : hs) {
            Double o = Json.num(odds, x.no);
            if (o != null && o > 1 && x.q > 0) c.add(new double[] {x.q, 1 / o, Integer.parseInt(x.no)});
        }
        if (c.size() < 2) return null;
        double sa = 0, sg = 0;
        for (double[] x : c) {
            sa += x[0];
            sg += x[1];
        }
        double[][] out = new double[3][c.size()];
        for (int j = 0; j < c.size(); j++) {
            out[0][j] = c.get(j)[0] / sa;
            out[1][j] = c.get(j)[1] / sg;
            out[2][j] = c.get(j)[2];
        }
        return out;
    }

    /** 2.59: karışım q ∝ q_AGF^(1−w) · q_ganyan^w (normalize). */
    private static double[] mix(double[][] qq, double w) {
        int n = qq[0].length;
        double[] q = new double[n];
        double z = 0;
        for (int j = 0; j < n; j++) z += q[j] = Math.pow(qq[0][j], 1 - w) * Math.pow(qq[1][j], w);
        for (int j = 0; j < n; j++) q[j] /= z;
        return q;
    }

    /** 2.58: strateji araması: kupon büyüklükleri (TL) ve 6/6 eşikleri; son dönemde kanıt için en az bu kadar kupon. */
    static final double[] SEARCH_SIZES = {200, 300, 400, 600, 800, 1000, 1500};
    static final double[] SEARCH_T = {0.10, 0.15, 0.20, 0.25};
    static final int TEST_MIN = 15;

    /**
     * 2.58: strateji araması: 6/6 olasılığı (tavandaki kuponla, defterde) en az SEARCH_T[0] olan doğrulanmış altılılar her
     * büyüklükte bugünkü düzeltmeyle en yüksek 6/6 için yeniden kurulur (gerçek ikramiyeyle; devirde kaçtı). Bölge × eşik ×
     * büyüklük: ilk üçte ikide en kârlısı (en az MIN_SETTLED kupon, toplam net kâr; kârlı yoksa en yüksek dönüş) seçilir, son
     * üçte birde denenir: en az TEST_MIN kupon ve dönüş > %100 ise kanıtlandı. Canlı kural tüm veriyle aynı yöntemle seçilir.
     * {train, test, all: {scope, minHit, size, n, hits, …}, sizes: [tüm veride seçilen bölge ve eşikte her büyüklük], proven, from}.
     */
    private static Map<String, Object> strategySearch(List<double[]> coupons, List<Map<String, Object>> recs, Map<String, Object> learn,
            double unit, double legMin) {
        int m = SEARCH_SIZES.length, cut = coupons.size() * 2 / 3;
        long[] mu = new long[m];
        for (int j = 0; j < m; j++) mu[j] = (long) Math.floor(SEARCH_SIZES[j] / unit + 1e-9);
        List<double[]> rows = new ArrayList<>(); // {ph, yerli, test?, j, bedel, dönüş, tuttu, beklenen}
        for (int i = 0; i < coupons.size(); i++) {
            double[] x = coupons.get(i);
            if (x[0] < SEARCH_T[0] - 1e-12) continue;
            Map<String, Object> r = recs.get(i);
            double[] ab = alphaBeta(learn, x[1] > 0);
            List<Altili.Leg> ls = new ArrayList<>();
            boolean ok = true;
            for (Object l : Json.arr(r.get("legs"))) {
                Altili.Leg leg = Altili.leg(String.valueOf(l), ab[0], ab[1]);
                ok &= !leg.horses.isEmpty();
                ls.add(leg);
            }
            if (!ok || ls.size() != 6) continue;
            List<Object> win = Json.arr(Json.obj(r.get("result")).get("winners"));
            double payout = Json.dbl(Json.obj(r.get("result")), "payout", 0);
            boolean dv = x.length > 5 && x[5] > 0;
            int[][] cnt = Altili.bestHitMulti(ls, mu, legMin);
            for (int j = 0; j < m; j++) {
                long units = 1;
                double hit = 1;
                boolean all = !dv;
                for (int k = 0; k < 6; k++) {
                    List<Altili.Horse> hs = Altili.byQ(ls.get(k)).subList(0, Math.min(cnt[j][k], ls.get(k).horses.size()));
                    double sq = 0;
                    boolean in = false;
                    for (Altili.Horse h : hs) {
                        sq += h.q;
                        in |= h.no.equals(String.valueOf(win.get(k)));
                    }
                    units *= hs.size();
                    hit *= sq;
                    all &= in;
                }
                if (units > mu[j]) continue; // ayak sınırıyla sığmadı
                rows.add(new double[] {x[0], x[1], i >= cut ? 1 : 0, j, units * unit, all ? payout : 0, all ? 1 : 0, hit});
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (cut < coupons.size()) out.put("from", recs.get(cut).get("day"));
        Map<String, Object> train = searchPick(rows, 0), all = searchPick(rows, -1);
        if (train != null) {
            out.put("train", train);
            Map<String, Object> test = searchCell(rows, 1, (String) train.get("scope"), Json.dbl(train, "minHit", 0), (int) Json.lng(train, "j", 0));
            out.put("test", test);
            out.put("proven", Json.lng(test, "n", 0) >= TEST_MIN && Json.dbl(test, "roi", 0) > 1);
        } else out.put("proven", false);
        if (all != null) {
            out.put("all", all);
            List<Object> sizes = new ArrayList<>(); // tutma oranı ve dönüş kupon büyüklüğüne göre (tüm veri, seçilen bölge ve eşik)
            for (int j = 0; j < m; j++) sizes.add(searchCell(rows, -1, (String) all.get("scope"), Json.dbl(all, "minHit", 0), j));
            out.put("sizes", sizes);
            List<double[]> outs = new ArrayList<>(); // seçilen büyüklükte kasa ihtiyacı
            for (double[] x : rows) {
                if (x[3] == Json.lng(all, "j", 0) && fits(new double[] {x[0], x[1]}, (String) all.get("scope"), Json.dbl(all, "minHit", 0))) {
                    outs.add(new double[] {x[4], x[5]});
                }
            }
            double[] need = kasaNeed(outs);
            if (need != null) {
                all.put("kasaMin", need[0]);
                all.put("kasaKelly", need[1]);
            }
        }
        return out;
    }

    /** 2.58: aramada bir hücre (bölge, eşik, büyüklük); part 0: ilk dönem, 1: son dönem, −1: hepsi. */
    private static Map<String, Object> searchCell(List<double[]> rows, int part, String scope, double t, int j) {
        int n = 0, h = 0;
        double c = 0, r = 0, e = 0;
        for (double[] x : rows) {
            if ((part >= 0 && x[2] != part) || x[3] != j || !fits(new double[] {x[0], x[1]}, scope, t)) continue;
            n++;
            h += (int) x[6];
            c += x[4];
            r += x[5];
            e += x[7];
        }
        Map<String, Object> m = agg(n, h, c, r);
        m.put("expected", e);
        m.put("scope", scope);
        m.put("minHit", t);
        m.put("size", SEARCH_SIZES[j]);
        m.put("j", (long) j);
        return m;
    }

    /** 2.58: aramada en kârlısı (en az MIN_SETTLED kupon): toplam net kâr; kârlı yoksa en yüksek dönüş. */
    private static Map<String, Object> searchPick(List<double[]> rows, int part) {
        Map<String, Object> best = null, bestR = null;
        double bn = 0, br = -1;
        for (double t : SEARCH_T) {
            for (String sco : SCOPES) {
                for (int j = 0; j < SEARCH_SIZES.length; j++) {
                    Map<String, Object> c = searchCell(rows, part, sco, t, j);
                    if (Json.lng(c, "n", 0) < MIN_SETTLED) continue;
                    double net = Json.dbl(c, "ret", 0) - Json.dbl(c, "cost", 0), roi = Json.dbl(c, "roi", 0);
                    if (net > bn + 1e-9) {
                        bn = net;
                        best = c;
                    }
                    if (roi > br + 1e-12) {
                        br = roi;
                        bestR = c;
                    }
                }
            }
        }
        return best != null ? best : bestR;
    }

    /** 2.57: değer kuponu geriye dönük testinde en az bu kasa (kullanıcının yükseltebileceği; Kelly payı buna göre). */
    static final double VALUE_KASA = 10000;

    /**
     * 2.57: ikramiye modeli: doğrulanmış sonuçlarda log(ikramiye × P / birim) = a + b · log P (P: kazanan kombinasyonun AGF
     * payları çarpımı). b > 0: popüler kombinasyon AGF'nin söylediğinden çok, sürpriz az öder. {n, payA = e^a, payB, mean, use}.
     */
    static Map<String, Object> payoutFit(List<Map<String, Object>> recs, double unit) {
        double sx = 0, sy = 0, sxx = 0, sxy = 0, sr = 0;
        int n = 0;
        for (Map<String, Object> r : recs) {
            Map<String, Object> res = Json.obj(r.get("result"));
            Double ratio = payoutRatio(r, res, unit);
            if (ratio == null || !consistent(ratio)) continue;
            List<Object> w = Json.arr(res.get("winners")), legs = Json.arr(r.get("legs"));
            double lp = 0;
            boolean ok = true;
            for (int k = 0; k < 6 && ok; k++) {
                Double p = agf(String.valueOf(legs.get(k))).get(String.valueOf(w.get(k)));
                ok = p != null && p > 0;
                if (ok) lp += Math.log(p);
            }
            if (!ok) continue;
            double y = Math.log(ratio);
            n++;
            sx += lp;
            sy += y;
            sxx += lp * lp;
            sxy += lp * y;
            sr += ratio;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", (long) n);
        if (n < 2) return m;
        double vx = sxx - sx * sx / n, b = vx > 1e-9 ? (sxy - sx * sy / n) / vx : 0;
        b *= n / (n + 30.0); // az veride eğim 0'a büzülür
        double a = (sy - b * sx) / n;
        m.put("payA", Math.exp(a));
        m.put("payB", b);
        m.put("mean", sr / n);
        m.put("use", n >= MIN_LEGS);
        return m;
    }

    /** 2.57: kayıtların ayak verisi (AGF payları, kazanan) — öğrenme için. */
    private static void legData(Map<String, Object> r, List<LegData> out) {
        List<Object> win = Json.arr(Json.obj(r.get("result")).get("winners")), legs = Json.arr(r.get("legs"));
        for (int k = 0; k < 6 && k < win.size() && k < legs.size(); k++) {
            Map<String, Double> agf = agf(String.valueOf(legs.get(k)));
            String w = String.valueOf(win.get(k));
            if (!agf.containsKey(w) || agf.size() < 2) continue;
            double[] p = new double[agf.size()];
            int wi = 0, j = 0;
            for (Map.Entry<String, Double> e : agf.entrySet()) {
                if (e.getKey().equals(w)) wi = j;
                p[j++] = e.getValue();
            }
            out.add(new LegData(p, wi));
        }
    }

    /** 2.57: yalnızca verilen kayıtlardan öğrenilenler {calib, calibRegion, payout} (geriye dönük testte gelecek sızmasın). */
    private static Map<String, Object> learnFrom(List<Map<String, Object>> recs, Set<Map<String, Object>> devir, double unit) {
        List<LegData> all = new ArrayList<>(), home = new ArrayList<>(), away = new ArrayList<>();
        List<Map<String, Object>> verified = new ArrayList<>();
        for (Map<String, Object> r : recs) {
            if (devir.contains(r)) continue;
            verified.add(r);
            List<LegData> d = new ArrayList<>();
            legData(r, d);
            all.addAll(d);
            (domestic(r) ? home : away).addAll(d);
        }
        Map<String, Object> learn = new LinkedHashMap<>(), region = new LinkedHashMap<>();
        learn.put("calib", calibrate(all));
        region.put("yerli", calibrate(home));
        region.put("yabancı", calibrate(away));
        learn.put("calibRegion", region);
        learn.put("payout", payoutFit(verified, unit));
        return learn;
    }

    /** 2.57: altılı girdileri öğrenilenlerle (AGF ve favori düzeltmesi, ikramiye modeli). */
    public static void applyLearn(Altili.Inputs in, Map<String, Object> learn, boolean home) {
        double[] ab = alphaBeta(learn, home);
        in.alpha = ab[0];
        in.beta = ab[1];
        Map<String, Object> pay = Json.obj(learn.get("payout"));
        if (Json.bool(pay, "use", false)) {
            in.payA = Json.dbl(pay, "payA", 0);
            in.payB = Json.dbl(pay, "payB", 0);
        }
    }

    /**
     * 2.57: değer kuponu geriye dönük: her altılıda öğrenilenlerle değer kuponu kurulur; model dönüşü en az 1 + minEdge
     * olanlar oynanmış sayılır (kat Kelly'den, en az 1); sonuç gerçek ikramiyeyle (devirde kesin kaçtı). {agg, yerli, yabancı}.
     */
    private static Map<String, Object> valueRun(List<Map<String, Object>> recs, Set<Map<String, Object>> devir, Map<String, Object> learn,
            double unit, double kasa, double km, double cap, double legMin, double minEdge) {
        int[] n = new int[3], h = new int[3];
        double[] cost = new double[3], ret = new double[3], ex = new double[3];
        for (Map<String, Object> r : recs) {
            List<String> legs = new ArrayList<>();
            for (Object l : Json.arr(r.get("legs"))) legs.add(String.valueOf(l));
            Altili.Inputs in = new Altili.Inputs();
            in.unit = unit;
            in.kasa = kasa;
            in.km = km;
            in.maxFraction = 0.07; // kupon kasanın en fazla %7'si (varsayılan bütçe payı)
            in.maxCost = cap;
            in.legMin = legMin;
            in.minEdge = minEdge;
            applyLearn(in, learn, domestic(r));
            Map<String, Object> v = Altili.valueCoupon(legs, in);
            if (v == null || !Json.bool(v, "play", false)) continue;
            List<Object> win = Json.arr(Json.obj(r.get("result")).get("winners"));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("horses", v.get("horses"));
            boolean hit = !devir.contains(r) && hits(c, win)[6];
            long kat = Math.max(1, Json.lng(v, "kat", 1));
            double cs = Json.dbl(v, "cost", 0) * kat, rt = hit ? Json.dbl(Json.obj(r.get("result")), "payout", 0) * kat : 0;
            for (int g : new int[] {0, domestic(r) ? 1 : 2}) {
                n[g]++;
                if (hit) h[g]++;
                cost[g] += cs;
                ret[g] += rt;
                ex[g] += Json.dbl(v, "hit", 0);
            }
        }
        Map<String, Object> out = agg(n[0], h[0], cost[0], ret[0]);
        out.put("expected", ex[0]);
        String[] names = {null, "yerli", "yabancı"};
        for (int g = 1; g < 3; g++) {
            Map<String, Object> m = agg(n[g], h[g], cost[g], ret[g]);
            m.put("expected", ex[g]);
            out.put(names[g], m);
        }
        return out;
    }

    /** 2.53: çift kayıtta hangisi sayılır: bildirilen (2), TJK (1). */
    private static int rank(Map<String, Object> r) {
        return (r.get("notified") != null ? 2 : 0) + ("TJK".equals(Json.str(r, "source")) ? 1 : 0);
    }

    /** 2.51: kupon 6/6 kuralına (eşik t, bölge) uyuyor mu. x: {6/6 olasılığı, yerli, …}. */
    private static boolean fits(double[] x, String scope, double t) {
        return x[0] >= t - 1e-12 && ("hepsi".equals(scope) || scope.equals(x[1] > 0 ? "yerli" : "yabancı"));
    }

    /**
     * 2.49–2.55: en iyi 6/6 kuralı {eşik, bölge}, en az MIN_SETTLED kuponla: en kârlısı (toplam net kâr en büyük; 2.55:
     * dönüş oranı değil: kârlı kuponu çok olan kural daha çok kazandırır); hiçbiri kârlı değilse en yüksek dönüş.
     * Eşitlikte düşük eşik, geniş bölge.
     */
    private static Object[] pick(List<double[]> coupons) {
        Object[] best = null, bestR = null;
        double bn = 0, br = -1;
        for (double t : SWEEP) {
            for (String sco : SCOPES) {
                Map<String, Object> cell = rule(coupons, sco, t);
                if (Json.lng(cell, "n", 0) < MIN_SETTLED) continue;
                double net = Json.dbl(cell, "ret", 0) - Json.dbl(cell, "cost", 0), roi = Json.dbl(cell, "roi", 0);
                if (net > bn + 1e-9) {
                    bn = net;
                    best = new Object[] {t, sco};
                }
                if (roi > br + 1e-12) {
                    br = roi;
                    bestR = new Object[] {t, sco};
                }
            }
        }
        return best != null ? best : bestR;
    }

    /** 2.51: altılının bölgesine göre kullanılan AGF düzeltmesi {alpha, beta} (analizden: önce bölgeninki, yoksa genel). */
    public static double[] alphaBeta(Map<String, Object> learn, boolean home) {
        Map<String, Object> calib = Json.obj(learn.get("calib")), regions = Json.obj(learn.get("calibRegion"));
        Map<String, Object> region = regions == null ? null : Json.obj(regions.get(home ? "yerli" : "yabancı"));
        double a = Json.dbl(region != null && Json.bool(region, "use", false) ? region : calib, "alphaUse", 1);
        Map<String, Object> cb = region != null && Json.bool(region, "betaOn", false) ? region : calib;
        return new double[] {a, Json.bool(cb, "betaOn", false) ? Json.dbl(cb, "betaUse", 1) : 1};
    }

    /** 2.49–2.50: 6/6 kuralına (eşik; bölge: hepsi, yerli, yabancı) uyan kuponların sonucu: agg + expected, minHit. */
    private static Map<String, Object> rule(List<double[]> coupons, String scope, double t) {
        int n = 0, h = 0;
        double e = 0, c = 0, ret = 0, v = 0;
        for (double[] x : coupons) {
            if (!fits(x, scope, t)) continue;
            n++;
            e += x[0];
            v += x[0] * (1 - x[0]);
            c += x[2];
            h += (int) x[3];
            ret += x[4];
        }
        Map<String, Object> row = agg(n, h, c, ret);
        row.put("expected", e);
        row.put("var", v); // 2.53: tuttu sayısının varyansı (Σ p(1 − p)): beklenenden sapma denetimi
        row.put("minHit", t);
        return row;
    }

    // ---- kayıt ----------------------------------------------------------------------------------------------

    static List<Object> records(Map<String, Object> store) {
        List<Object> r = Json.arr(store.get("records"));
        if (!(store.get("records") instanceof List)) store.put("records", r);
        return r;
    }

    private static Map<String, Object> find(Map<String, Object> store, String day, String key) {
        for (Object o : records(store)) {
            Map<String, Object> r = Json.obj(o);
            if (day.equals(Json.str(r, "day")) && key.equals(Json.str(r, "key"))) return r;
        }
        return null;
    }

    /** Planın saklanan özeti: ayak ayak atlar, kombinasyon, misli, bedel. */
    static Map<String, Object> coupon(Map<String, Object> plan) {
        Map<String, Object> c = new LinkedHashMap<>();
        List<Object> horses = new ArrayList<>();
        for (Object l : Json.arr(plan.get("legs"))) horses.add(new ArrayList<>(Json.arr(Json.obj(l).get("horses"))));
        c.put("horses", horses);
        c.put("units", Json.lng(plan, "units", 0));
        c.put("kat", Math.max(1, Json.lng(plan, "kat", 1)));
        c.put("cost", Json.dbl(plan, "cost", 0));
        c.put("play", Json.bool(plan, "play", false));
        if (plan.get("mode") != null) c.put("mode", plan.get("mode"));
        if (plan.get("hit") != null) c.put("hit", plan.get("hit")); // 2.43: tahmin edilen 6/6 olasılığı (beklenen ile gerçekleşen)
        if (plan.get("roi") != null) c.put("roi", plan.get("roi")); // 2.45: tahmin edilen dönüş (kâr kuralı)
        return c;
    }

    /**
     * 2.38: ikramiye–AGF tutarlılığı: ikramiye × kazanan kombinasyonun AGF payı (ayak paylarının çarpımı) / birim. AGF
     * altılı bahislerinin atlara dağılımıdır; gerçek sonuçta bu oran ≈ 1 − kesinti (~0,73; devirle büyür, favori
     * kombinasyonlarda biraz küçülür). Yanlış okunmuş kazananlarda çoğunlukla çok küçük ya da çok büyük çıkar. Gerçek
     * ikramiye yoksa null.
     */
    static Double payoutRatio(Map<String, Object> rec, Map<String, Object> res, double unit) {
        if (res == null || Json.bool(res, "estimated", false) || !(Json.dbl(res, "payout", 0) > 0)) return null;
        List<Object> w = Json.arr(res.get("winners")), legs = Json.arr(rec.get("legs"));
        if (w.size() != 6 || legs.size() < 6) return null;
        double pw = 1;
        for (int k = 0; k < 6; k++) {
            Double p = agf(String.valueOf(legs.get(k))).get(String.valueOf(w.get(k)));
            pw *= p == null ? MISSING_P : p;
        }
        return Json.dbl(res, "payout", 0) * pw / (unit > 0 ? unit : 1);
    }

    /** Oran makul aralıkta mı (gerçek sonuçta ~0,73; favori kombinasyonda biraz az, devirle fazla). 2.40: 0,1–10 → 0,2–6. */
    static boolean consistent(Double ratio) {
        return ratio != null && ratio >= 0.2 && ratio <= 6;
    }

    /**
     * Sonucun doğrulaması: "ikramiye" (tutarlı), "devir" (2.56: 6 bilen çıkmadı), "tutarsız", "koşu no" (koşu numarasıyla
     * eşleşme) ya da "doğrulanamadı".
     */
    static String trust(Map<String, Object> rec, Map<String, Object> res, double unit) {
        if (Json.bool(res, "noSix", false)) return "devir"; // 2.56: kimse 6/6 yapamadı: her kupon kesin kaçtı
        if ("koşu no".equals(Json.str(res, "from"))) return "koşu no";
        Double ratio = payoutRatio(rec, res, unit);
        if (ratio == null) return "doğrulanamadı";
        return consistent(ratio) ? "ikramiye" : "tutarsız";
    }

    /** Ayakta favori kazandı mı ve favorinin AGF payı: {1/0, pay}; kazanan AGF listesinde yoksa null. */
    private static double[] favorite(String leg, String winner) {
        Map<String, Double> agf = agf(leg);
        if (!agf.containsKey(winner) || agf.size() < 2) return null;
        String fav = null;
        for (Map.Entry<String, Double> e : agf.entrySet()) if (fav == null || e.getValue() > agf.get(fav)) fav = e.getKey();
        return new double[] {fav.equals(winner) ? 1 : 0, agf.get(fav)};
    }

    /** 2.36: yerli hipodrom mu (TJK şehir numarası 1–10 ya da yerli şehir adı); yabancıda AGF Türk bahisçilerin payı. */
    public static boolean domestic(Map<String, Object> r) {
        long id = Json.lng(r, "cityId", -1);
        if ("TJK".equals(Json.str(r, "source")) && id >= 1 && id < Tjk.KNOWN.length) return true;
        String raw = r.get("city") != null ? Json.str(r, "city") : Json.str(r, "label");
        synchronized (DOMESTIC) { // 2.57: şehir adı başına bir kez (analizde her kayıt defalarca soruluyor)
            Boolean b = DOMESTIC.get(raw);
            if (b != null) return b;
        }
        String c = norm(raw);
        boolean home = false;
        for (int i = 1; i < Tjk.KNOWN.length && !home; i++) {
            String k = norm(Tjk.KNOWN[i]);
            home = c.equals(k) || c.startsWith(k + " ");
        }
        synchronized (DOMESTIC) {
            if (DOMESTIC.size() > 2000) DOMESTIC.clear();
            DOMESTIC.put(raw, home);
        }
        return home;
    }

    private static final Map<String, Boolean> DOMESTIC = new java.util.HashMap<>();

    /** Sonucun kaynağı güvenilir mi: resmî 6'lı sonucu ya da koşu numarasıyla eşleşme (eski kayıtta kaynak yoksa evet). */
    static boolean reliable(Map<String, Object> res) {
        String from = Json.str(res, "from");
        return from == null || "kombinasyon".equals(from) || "koşu no".equals(from);
    }

    /** Grup istatistiği: {ayak, favori kazandı, favorinin AGF toplamı}. */
    private static void count(Map<String, double[]> groups, String key, boolean favWon, double favP) {
        double[] g = groups.get(key);
        if (g == null) groups.put(key, g = new double[3]);
        g[0]++;
        if (favWon) g[1]++;
        g[2] += favP;
    }

    /** 2.37: gerçek yarışta olmayacak kadar az: AGF'nin verdiğinin yarısından az ve 3 standart sapmadan fazla (en az 40 ayak). */
    private static boolean absurd(double n, double win, double agfSum) {
        return n >= 40 && win < 0.5 * agfSum && low(n, win, agfSum);
    }

    /** Favori beklenenden 3 standart sapmadan fazla az kazanıyor mu (en az 40 ayak). */
    private static boolean low(double n, double win, double agfSum) {
        if (n < 40) return false;
        double p = agfSum / n;
        return win < n * p - 3 * Math.sqrt(n * p * (1 - p));
    }

    /**
     * 2.35–2.36: atlarla eşleştirilmiş eski sonuçlar ("koşular") "atlar" diye etiketlenir: koşular atlarla ayırt
     * edilemediğinden kazananlar yanlış olabilir. Silinmez; karnede güvenilir sonuçlarla ayrı gösterilir, öğrenmede
     * kullanılmaz. Değiştiyse true.
     */
    /** 2.47: defterde kaydı olan günler (geriye dönük taramada atlanır; defter bir kez okunur). */
    public static Set<String> days(Map<String, Object> store) {
        Set<String> out = new HashSet<>();
        for (Object o : records(store)) if (Json.str(Json.obj(o), "day") != null) out.add(Json.str(Json.obj(o), "day"));
        return out;
    }

    /** 2.43: o günün defterdeki altılı anahtarları (geriye dönük tahmin olanlara dokunmasın). */
    public static Set<String> keys(Map<String, Object> store, String day) {
        Set<String> out = new HashSet<>();
        for (Object o : records(store)) if (day.equals(Json.str(Json.obj(o), "day"))) out.add(Json.str(Json.obj(o), "key"));
        return out;
    }

    /** 2.43: o günün verilen anahtarlı kayıtlarından sonuçlanan ve ikramiyeyle doğrulanan sayısı: {sonuçlanan, doğrulanan}. */
    public static int[] outcome(Map<String, Object> store, String day, Set<String> keys, double unit) {
        int settled = 0, verified = 0;
        for (Object o : records(store)) {
            Map<String, Object> r = Json.obj(o), res = Json.obj(r.get("result"));
            if (!day.equals(Json.str(r, "day")) || !keys.contains(Json.str(r, "key")) || res == null || Json.arr(res.get("winners")).size() != 6) continue;
            settled++;
            if ("ikramiye".equals(trust(r, res, unit))) verified++;
        }
        return new int[] {settled, verified};
    }

    public static boolean migrate(Map<String, Object> store) {
        if (store == null || Json.lng(store, "v", 1) >= 5) return false;
        if (Json.lng(store, "v", 1) >= 4) { // 2.56: devirli günler (6 bilen çıkmadı) okunabiliyor: ikramiyesiz sonuçlara yeniden deneme
            for (Object o : records(store)) {
                Map<String, Object> r = Json.obj(o), res = Json.obj(r.get("result"));
                if (res != null && (Json.bool(res, "estimated", false) || Json.bool(res, "missing", false))) r.remove("rechecks");
            }
            store.put("v", 5L);
            return true;
        }
        for (Object o : records(store)) {
            Map<String, Object> r = Json.obj(o), res = Json.obj(r.get("result"));
            r.remove("rechecks"); // 2.41: ikramiye okuyucusu düzeldi: doğrulanamayanlara yeniden deneme hakkı
            if (res == null) continue;
            if ("koşular".equals(Json.str(res, "from"))) res.put("from", "atlar");
            List<Object> w = Json.arr(res.get("winners"));
            if (w.size() == 6 && Tjk.raceNumbers(w)) r.remove("result"); // 2.37: koşu numaraları kazanan sanılmıştı: yeniden
        }
        store.put("v", 5L);
        return true;
    }

    /** Okunan altılıları (planlarıyla) deftere yazar; sonuçlanmışlara dokunmaz. Gün: altılının günü, yoksa bugün. */
    public static void record(Map<String, Object> store, String today, List<Object> altilis) {
        record(store, today, altilis, null);
    }

    /**
     * now (2.54): 1. ayağı başlamış altılının kaydı donar (başladıktan sonraki okuma deftere girmez: sonuç bilgisi
     * sızmasın); başladıktan sonra ilk kez okunan "late" işaretlenir, karnede sayılmaz. Geriye dönük kayıtlar ve null: donma yok.
     */
    public static void record(Map<String, Object> store, String today, List<Object> altilis, Instant now) {
        List<Object> recs = records(store);
        for (Object o : altilis) {
            // 2.51: kupon kasaya göre küçültüldüyse defter tavandaki kuponla (planRef): eşik ve taramalar aynı ölçüde
            Map<String, Object> a = Json.obj(o), plan = Json.obj(a == null ? null : a.get("planRef") != null ? a.get("planRef") : a.get("plan"));
            String key = Json.str(a, "key");
            List<Object> legs = Json.arr(a == null ? null : a.get("legs"));
            if (key == null || "manual".equals(key) || legs.size() < 6 || plan == null || plan.get("error") != null) continue;
            String day = Json.str(a, "day") != null ? Json.str(a, "day") : today;
            Map<String, Object> r = find(store, day, key);
            if (r == null) {
                r = new LinkedHashMap<>();
                r.put("day", day);
                r.put("key", key);
                recs.add(r);
            }
            if (r.get("result") != null) continue;
            Instant st = now == null || Json.bool(a, "backtest", false) ? null : startOf(a, day);
            if (st != null && !now.isBefore(st)) {
                if (r.get("plan") != null) continue; // 2.54: başlamış: kayıt donar
                r.put("late", true);
            }
            for (String f : new String[] {"label", "city", "cityId", "no", "source", "races", "backtest"}) if (a.get(f) != null) r.put(f, a.get(f));
            r.put("legs", new ArrayList<>(legs.subList(0, 6)));
            r.put("plan", coupon(plan));
        }
        String oldest = LocalDate.parse(today).minusDays(KEEP_DAYS).toString();
        for (int i = recs.size() - 1; i >= 0; i--) if (String.valueOf(Json.obj(recs.get(i)).get("day")).compareTo(oldest) < 0) recs.remove(i);
    }

    /** 2.54: altılının 1. ayak saati (Türkiye; "start" ya da ilk koşunun saati); bilinmiyorsa null. */
    static Instant startOf(Map<String, Object> a, String day) {
        String t = Json.str(a, "start");
        List<Object> rs = Json.arr(a.get("races"));
        if ((t == null || !t.matches("\\d{2}:\\d{2}")) && !rs.isEmpty()) t = Json.str(Json.obj(rs.get(0)), "time");
        if (t == null || !t.matches("\\d{2}:\\d{2}")) return null;
        try {
            return LocalDate.parse(day).atTime(LocalTime.parse(t)).atOffset(Fmt.TR).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Bildirilen kupon dondurulur (bir kez): "önerilerim ne yaptı" bunun üzerinden. */
    public static void markNotified(Map<String, Object> store, String day, String key, Map<String, Object> plan, Instant now) {
        markNotified(store, day, key, plan, now, false);
    }

    /** replace: yarış öncesi son AGF'yle bildirilen kupon öncekinin yerine geçer (oynanacak olan budur). */
    public static void markNotified(Map<String, Object> store, String day, String key, Map<String, Object> plan, Instant now, boolean replace) {
        Map<String, Object> r = find(store, day, key);
        if (r == null || (r.get("notified") != null && !replace) || plan == null || r.get("result") != null) return;
        Map<String, Object> c = coupon(plan);
        c.put("at", now.toString());
        r.put("notified", c);
    }

    /** Sonucu beklenen günler (bugünden önce, en çok SETTLE_DAYS gün geriye), eskiden yeniye. */
    public static List<String> pendingDays(Map<String, Object> store, String today) {
        Set<String> out = new java.util.TreeSet<>();
        String oldest = LocalDate.parse(today).minusDays(SETTLE_DAYS).toString();
        for (Object o : records(store)) {
            Map<String, Object> r = Json.obj(o);
            String d = Json.str(r, "day");
            if (r.get("result") == null && d != null && d.compareTo(today) < 0 && d.compareTo(oldest) >= 0) out.add(d);
        }
        return new ArrayList<>(out);
    }

    // ---- sonuç ----------------------------------------------------------------------------------------------

    /** Ayak metninden at no → AGF payı (0–1). */
    static Map<String, Double> agf(String leg) {
        synchronized (AGF_CACHE) {
            Map<String, Double> c = AGF_CACHE.get(leg);
            if (c != null) return c;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Altili.Horse h : Altili.leg(leg).horses) out.put(h.no, h.p);
        out = java.util.Collections.unmodifiableMap(out);
        synchronized (AGF_CACHE) {
            AGF_CACHE.put(leg, out);
        }
        return out;
    }

    /** 2.57: ayrıştırılmış AGF (aynı ayak metni analizde defalarca okunur); en çok 30.000 ayak, en eski kullanılan çıkar. */
    private static final Map<String, Map<String, Double>> AGF_CACHE = new LinkedHashMap<String, Map<String, Double>>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Map<String, Double>> e) {
            return size() > 30000;
        }
    };

    static String norm(String s) {
        String t = Normalizer.normalize(String.valueOf(s).toLowerCase(new Locale("tr")), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.replace('ı', 'i').replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    public static Map<String, Object> cityOf(Map<String, Object> rec, List<Object> cities) {
        Long id = rec.get("cityId") == null ? null : Json.lng(rec, "cityId", -1);
        String name = norm(rec.get("city") != null ? Json.str(rec, "city") : Json.str(rec, "label"));
        for (Object o : cities) if (id != null && id == Json.lng(Json.obj(o), "cityId", -2)) return Json.obj(o);
        for (Object o : cities) {
            String n = norm(Json.str(Json.obj(o), "city"));
            if (!n.isEmpty() && (n.equals(name) || name.startsWith(n + " ") || n.startsWith(name + " "))) return Json.obj(o);
        }
        return null;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        Set<String> u = new HashSet<>(a), i = new HashSet<>(a);
        u.addAll(b);
        i.retainAll(b);
        return u.isEmpty() ? 0 : (double) i.size() / u.size();
    }

    /**
     * 2.34: ayak ayak kazananlar (koşmamış ya da bulunamayan ayak null): önce kazanan kombinasyon, sonra altılının koşu
     * numarası (programdan; koşunun atları ayağın atlarıyla örtüşmeli), olmazsa atları en benzer koşu tablosu.
     */
    public static List<Object> legWinners(Map<String, Object> rec, Map<String, Object> city) {
        return legWinners(rec, city, new String[1]);
    }

    /** how[0]: kazananların kaynağı: "kombinasyon" (resmî 6'lı sonucu), "koşu no" ya da "atlar" (en benzer koşu). */
    static List<Object> legWinners(Map<String, Object> rec, Map<String, Object> city, String[] how) {
        List<Object> out = new ArrayList<>(java.util.Collections.nCopies(6, (Object) null));
        if (city == null || Json.arr(rec.get("legs")).size() < 6) return out;
        Object[] combo = combo(rec, city);
        if (combo != null) {
            how[0] = "kombinasyon";
            return new ArrayList<>(Json.arr(combo[0]));
        }
        List<Object> races = Json.arr(city.get("races")), rs = Json.arr(rec.get("races"));
        // 2.35: koşu numaraları (altılıda ve sonuç sayfasında) varsa yalnızca onlarla; atlarla eşleştirme yalnızca tek ve
        // açık bir aday varsa (ABD'de atlar her koşuda 1, 2, 3… diye numaralı: koşular atlarla ayırt edilemiyor, yanlış
        // koşunun kazananı alınıyordu — karnede favori %15 kazanmış görünüyordu)
        boolean numbered = rs.size() >= 6, pageNumbered = false;
        for (int k = 0; k < 6 && numbered; k++) numbered = Json.lng(Json.obj(rs.get(k)), "no", 0) > 0;
        for (Object o : races) pageNumbered |= Json.obj(o).get("no") != null;
        Set<Integer> used = new HashSet<>();
        how[0] = numbered && pageNumbered ? "koşu no" : "atlar";
        for (int k = 0; k < 6; k++) {
            Set<String> leg = agf(String.valueOf(Json.arr(rec.get("legs")).get(k))).keySet();
            int pick = -1;
            if (numbered && pageNumbered) {
                long no = Json.lng(Json.obj(rs.get(k)), "no", 0);
                for (int i = 0; i < races.size() && pick < 0; i++) {
                    Map<String, Object> race = Json.obj(races.get(i));
                    if (Json.lng(race, "no", -1) == no && !used.contains(i) && contained(leg, race) >= 0.6) pick = i;
                }
            } else {
                double best = 0, second = 0;
                for (int i = 0; i < races.size(); i++) {
                    if (used.contains(i) || Json.obj(races.get(i)).get("winner") == null) continue;
                    double j = jaccard(runners(Json.obj(races.get(i))), leg);
                    if (j > best + 1e-12) {
                        second = best;
                        best = j;
                        pick = i;
                    } else if (j > second) second = j;
                }
                if (best < 0.6 || second > best - 0.1) pick = -1; // eşleşme yok ya da belirsiz: yanlış kazanan yerine boş
            }
            if (pick < 0 || Json.obj(races.get(pick)).get("winner") == null) continue;
            used.add(pick);
            out.set(k, String.valueOf(Json.obj(races.get(pick)).get("winner")));
        }
        return out;
    }

    private static Set<String> runners(Map<String, Object> race) {
        Set<String> out = new LinkedHashSet<>();
        for (Object x : Json.arr(race.get("runners"))) out.add(String.valueOf(x));
        return out;
    }

    /** Ayağın atlarının koşuda olan payı (AGF %0'lık atları yazmayabilir; koşunun bütün atları ayakta olmayabilir). */
    private static double contained(Set<String> leg, Map<String, Object> race) {
        Set<String> in = new HashSet<>(leg);
        in.retainAll(runners(race));
        return leg.isEmpty() ? 0 : (double) in.size() / leg.size();
    }

    /** Sayfadaki kazanan kombinasyon (ayaklarla tutarlıysa): {nos, payout, "kombinasyon"} ya da null. */
    private static Object[] combo(Map<String, Object> rec, Map<String, Object> city) {
        List<Map<String, Double>> legs = new ArrayList<>();
        for (Object l : Json.arr(rec.get("legs"))) legs.add(agf(String.valueOf(l)));
        List<Object> combos = Json.arr(city.get("combos"));
        List<Object> order = new ArrayList<>();
        int no = (int) Json.lng(rec, "no", 0);
        if (no >= 1 && no <= combos.size()) order.add(combos.get(no - 1));
        order.addAll(combos);
        Object[] first = null, best = null;
        double bestDev = Double.MAX_VALUE;
        for (Object o : order) {
            List<Object> nos = Json.arr(Json.obj(o).get("nos"));
            if (nos.size() != 6 || Tjk.raceNumbers(nos)) continue; // 2.37: koşu numaraları kazanan değil
            boolean ok = true;
            for (int k = 0; k < 6 && ok; k++) ok = legs.get(k).containsKey(String.valueOf(nos.get(k)));
            if (!ok) continue;
            Object[] c = {nos, Json.num(Json.obj(o), "payout"), "kombinasyon", Json.bool(Json.obj(o), "noSix", false)}; // 2.56: devir
            if (first == null) first = c;
            // 2.38: sayfada birden çok 6'lı sonucu varsa ikramiyesi bu altılının AGF'siyle tutarlı olan (1. ile 2. altılı karışmasın)
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("winners", nos);
            res.put("payout", c[1]);
            Double ratio = payoutRatio(rec, res, 1);
            if (consistent(ratio) && Math.abs(Math.log(ratio / 0.73)) < bestDev) {
                bestDev = Math.abs(Math.log(ratio / 0.73));
                best = c;
            }
        }
        return best != null ? best : first;
    }

    /** Altılının kazananları: önce sayfadaki kazanan kombinasyon (ayaklarla tutarlıysa), yoksa koşu tablolarından. */
    static Object[] winners(Map<String, Object> rec, Map<String, Object> city) {
        Object[] c = combo(rec, city);
        if (c != null) return c;
        String[] how = new String[1];
        List<Object> lw = legWinners(rec, city, how);
        return lw.contains(null) ? null : new Object[] {lw, null, how[0]};
    }

    /**
     * Bir günün sonuçlarını işler; sonuçlanan altılı sayısı. Kazananlar bulunamazsa ve gün SETTLE_DAYS'ten eskiyse kayıt
     * "sonuç yok" olarak kapatılır.
     */
    public static int settle(Map<String, Object> store, String day, Map<String, Object> results, double unit, double takeout, double tax,
            String today, Instant now) {
        List<Object> cities = Json.arr(results == null ? null : results.get("cities"));
        boolean old = ChronoUnit.DAYS.between(LocalDate.parse(day), LocalDate.parse(today)) >= SETTLE_DAYS;
        int n = 0;
        for (Object o : records(store)) {
            Map<String, Object> rec = Json.obj(o);
            if (!day.equals(Json.str(rec, "day")) || rec.get("result") != null) continue;
            Map<String, Object> city = cityOf(rec, cities);
            Object[] w = city == null ? null : winners(rec, city);
            Map<String, Object> res;
            if (w == null) {
                if (!old) continue;
                res = new LinkedHashMap<>();
                res.put("missing", true);
                res.put("at", now.toString());
            } else res = result(rec, w, unit, takeout, tax, now);
            rec.put("result", res);
            putOdds(rec, city); // 2.59
            n++;
        }
        return n;
    }

    /** Kazananlardan sonuç kaydı: {winners, agf, payout (yoksa AGF'den tahmini), estimated, from, at}. */
    private static Map<String, Object> result(Map<String, Object> rec, Object[] w, double unit, double takeout, double tax, Instant now) {
        @SuppressWarnings("unchecked")
        List<Object> nos = (List<Object>) w[0];
        double pw = 1;
        List<Object> legs = Json.arr(rec.get("legs"));
        for (int k = 0; k < 6; k++) {
            Double p = agf(String.valueOf(legs.get(k))).get(String.valueOf(nos.get(k)));
            pw *= p == null ? MISSING_P : p;
        }
        Double payout = (Double) w[1];
        boolean noSix = w.length > 3 && Boolean.TRUE.equals(w[3]); // 2.56: devir: ikramiye yok, tahmin de edilmez
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("winners", nos);
        res.put("agf", pw);
        res.put("payout", payout != null ? payout : noSix ? 0.0 : unit * (1 - takeout) * (1 - tax) / pw);
        res.put("estimated", payout == null && !noSix);
        if (noSix) res.put("noSix", true);
        res.put("from", w[2]);
        res.put("at", now.toString());
        return res;
    }

    // ---- 2.39: geçmişi yeniden doğrulama --------------------------------------------------------------------

    /** Bir kayıt en çok bu kadar kez yeniden doğrulanır (sonuç sayfası yine okunamazsa boşuna istek yapılmasın). */
    static final int RECHECK_MAX = 2;

    /**
     * Yeniden doğrulanacak mı: sonucu doğrulanmamış (tutarsız, ikramiyesiz), bulunamamış ya da hiç sonuçlanmamış (önceki
     * sürümlerin sildiği eski sonuçlar dahil); deneme hakkı var.
     */
    private static boolean needsRecheck(Map<String, Object> rec, double unit) {
        if (Json.lng(rec, "rechecks", 0) >= RECHECK_MAX) return false;
        Map<String, Object> res = Json.obj(rec.get("result"));
        if (res == null) return true;
        if (Json.arr(res.get("winners")).size() != 6) return true; // bulunamamış
        String t = trust(rec, res, unit);
        return !"ikramiye".equals(t) && !"devir".equals(t); // 2.40: yalnızca ikramiyeyle doğrulanan tamam (2.56: ve devir)
    }

    /** 2.39: yeniden doğrulanacak günler (analiz penceresinde), eskiden yeniye. */
    public static List<String> recheckDays(Map<String, Object> store, String today, double unit) {
        Set<String> out = new java.util.TreeSet<>();
        String from = LocalDate.parse(today).minusDays(ANALYZE_DAYS).toString();
        for (Object o : records(store)) {
            Map<String, Object> r = Json.obj(o);
            String d = Json.str(r, "day");
            if (d != null && d.compareTo(today) < 0 && d.compareTo(from) >= 0 && needsRecheck(r, unit)) out.add(d);
        }
        return new ArrayList<>(out);
    }

    /** O günün yeniden doğrulanacak altılılarının TJK şehir numaraları; TJK dışı (şehir adıyla eşleşen) varsa null: hepsi okunur. */
    public static Set<Integer> recheckCities(Map<String, Object> store, String day, double unit) {
        Set<Integer> out = new HashSet<>();
        for (Object o : records(store)) {
            Map<String, Object> r = Json.obj(o);
            if (!day.equals(Json.str(r, "day")) || !needsRecheck(r, unit)) continue;
            if (!"TJK".equals(Json.str(r, "source")) || r.get("cityId") == null) return null;
            out.add((int) Json.lng(r, "cityId", 0));
        }
        return out;
    }

    /**
     * 2.39: geçmiş sonucu yeniden doğrular (o günün sonuç sayfası yeni okuyucuyla): yeni sonuç doğrulanıyorsa (ikramiyeyle
     * tutarlı ya da koşu numaralı) eskisinin yerine geçer, eskisinin kazananları "previous" olarak saklanır; bulunamamış
     * olana bulunan yazılır. Doğrulanan sayısı.
     */
    public static int recheck(Map<String, Object> store, String day, Map<String, Object> results, double unit, double takeout, double tax,
            Instant now) {
        List<Object> cities = Json.arr(results == null ? null : results.get("cities"));
        int n = 0;
        for (Object o : records(store)) {
            Map<String, Object> rec = Json.obj(o);
            if (!day.equals(Json.str(rec, "day")) || !needsRecheck(rec, unit)) continue;
            rec.put("rechecks", Json.lng(rec, "rechecks", 0) + 1);
            Map<String, Object> city = cityOf(rec, cities);
            Object[] w = city == null ? null : winners(rec, city);
            if (w == null) continue;
            Map<String, Object> old = Json.obj(rec.get("result")), res = result(rec, w, unit, takeout, tax, now);
            String t = trust(rec, res, unit);
            boolean verified = "ikramiye".equals(t) || "devir".equals(t), hadWinners = old != null && Json.arr(old.get("winners")).size() == 6;
            if (!verified && hadWinners) continue; // eskisinden iyi değil
            if (hadWinners) res.put("previous", old.get("winners"));
            res.put("rechecked", true);
            rec.put("result", res);
            putOdds(rec, city); // 2.59
            if (verified) n++;
        }
        return n;
    }

    // ---- 2.59: ganyan oranları (sonuç tablolarından, kapanış) ------------------------------------------------

    /**
     * Ayakların ganyan oranları: her ayağa kazananı sonuçla aynı olan ve ayağın atlarını içeren koşu tablosu (koşu numarası
     * biliniyorsa o); belirsizse boş. [{at no: oran} ya da null] × 6.
     */
    static List<Object> legOdds(Map<String, Object> rec, Map<String, Object> city) {
        List<Object> out = new ArrayList<>(java.util.Collections.nCopies(6, (Object) null));
        Map<String, Object> res = Json.obj(rec.get("result"));
        List<Object> win = Json.arr(res == null ? null : res.get("winners")), legs = Json.arr(rec.get("legs"));
        if (city == null || win.size() != 6 || legs.size() < 6) return out;
        List<Object> races = Json.arr(city.get("races")), rs = Json.arr(rec.get("races"));
        Set<Integer> used = new HashSet<>();
        for (int k = 0; k < 6; k++) {
            Set<String> leg = agf(String.valueOf(legs.get(k))).keySet();
            long no = k < rs.size() ? Json.lng(Json.obj(rs.get(k)), "no", 0) : 0;
            int pick = -1, count = 0;
            for (int i = 0; i < races.size(); i++) {
                Map<String, Object> race = Json.obj(races.get(i));
                if (used.contains(i) || race.get("odds") == null || !String.valueOf(win.get(k)).equals(String.valueOf(race.get("winner")))
                        || contained(leg, race) < 0.6) continue;
                if (no > 0 && Json.lng(race, "no", -1) == no) {
                    pick = i;
                    count = 1;
                    break;
                }
                if (no > 0 && race.get("no") != null) continue; // numaralı ama başka koşu
                count++;
                if (pick < 0) pick = i;
            }
            if (pick < 0 || count != 1) continue;
            used.add(pick);
            out.set(k, Json.obj(races.get(pick)).get("odds"));
        }
        return out;
    }

    /** Sonuca ganyan oranlarını yazar (en az bir ayakta bulunduysa). */
    private static boolean putOdds(Map<String, Object> rec, Map<String, Object> city) {
        Map<String, Object> res = Json.obj(rec.get("result"));
        if (res == null || city == null) return false;
        List<Object> odds = legOdds(rec, city);
        boolean any = false;
        for (Object o : odds) any |= o != null;
        if (any) res.put("odds", odds);
        return any;
    }

    /**
     * Ganyanı okunacak mı: yerli (yabancı yarışların TJK sonuç sayfasında koşu tablosu yok: boşuna indirilmesin), ikramiyeyle
     * doğrulanmış, oranı yok, daha denenmemiş.
     */
    private static boolean needsOdds(Map<String, Object> rec, double unit) {
        Map<String, Object> res = Json.obj(rec.get("result"));
        return res != null && res.get("odds") == null && Json.lng(rec, "oddsTried", 0) < 1 && domestic(rec) && "ikramiye".equals(trust(rec, res, unit));
    }

    /** 2.59: ganyanı okunacak günler (analiz penceresinde), yeniden eskiye (son veriler önce). */
    public static List<String> oddsDays(Map<String, Object> store, String today, double unit) {
        Set<String> out = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
        String from = LocalDate.parse(today).minusDays(ANALYZE_DAYS).toString();
        for (Object o : records(store)) {
            Map<String, Object> r = Json.obj(o);
            String d = Json.str(r, "day");
            if (d != null && d.compareTo(today) < 0 && d.compareTo(from) >= 0 && needsOdds(r, unit)) out.add(d);
        }
        return new ArrayList<>(out);
    }

    /** O günün ganyanı okunacak altılılarının TJK şehirleri; TJK dışı varsa null (hepsi okunur). */
    public static Set<Integer> oddsCities(Map<String, Object> store, String day, double unit) {
        Set<Integer> out = new HashSet<>();
        for (Object o : records(store)) {
            Map<String, Object> r = Json.obj(o);
            if (!day.equals(Json.str(r, "day")) || !needsOdds(r, unit)) continue;
            if (!"TJK".equals(Json.str(r, "source")) || r.get("cityId") == null) return null;
            out.add((int) Json.lng(r, "cityId", 0));
        }
        return out;
    }

    /** 2.59: o günün sonuç sayfasından doğrulanmış altılılara ganyan oranları; bulunan sayısı. Her kayıt bir kez denenir. */
    public static int attachOdds(Map<String, Object> store, String day, Map<String, Object> results, double unit) {
        List<Object> cities = Json.arr(results == null ? null : results.get("cities"));
        int n = 0;
        for (Object o : records(store)) {
            Map<String, Object> rec = Json.obj(o);
            if (!day.equals(Json.str(rec, "day")) || !needsOdds(rec, unit)) continue;
            rec.put("oddsTried", 1L);
            if (putOdds(rec, cityOf(rec, cities))) n++;
        }
        return n;
    }

    // ---- değerlendirme ve öğrenme ---------------------------------------------------------------------------

    /** Kuponun her ayağında kazanan var mı (ayak ayak) ve tümü. */
    static boolean[] hits(Map<String, Object> coupon, List<Object> winners) {
        boolean[] h = new boolean[7];
        h[6] = true;
        List<Object> horses = Json.arr(coupon.get("horses"));
        for (int k = 0; k < 6; k++) {
            h[k] = k < horses.size() && Json.arr(horses.get(k)).contains(String.valueOf(winners.get(k)));
            h[6] &= h[k];
        }
        return h;
    }

    /** Toplu sonuç: {n, hits, cost, ret, roi}. */
    static Map<String, Object> agg(int n, int hits, double cost, double ret) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", (long) n);
        m.put("hits", (long) hits);
        m.put("cost", cost);
        m.put("ret", ret);
        m.put("roi", cost > 0 ? ret / cost : 0.0);
        return m;
    }

    /** Ayaklar: her biri {AGF payları, kazananın sırası}. */
    static final class LegData {
        final double[] p, lp; // 2.57: lp = log p (bir kez; öğrenmede p^a = e^(a·lp), hızlı)
        final int w, fav; // 2.51: AGF favorisi (ilk en yüksek)

        LegData(double[] p, int w) {
            this.p = p;
            this.w = w;
            int f = 0;
            lp = new double[p.length];
            for (int j = 0; j < p.length; j++) {
                lp[j] = Math.log(p[j]);
                if (p[j] > p[f]) f = j;
            }
            this.fav = f;
        }

        /** Σ p^a (a = 1 ise doğrudan). */
        double z(double a) {
            double z = 0;
            if (a == 1) for (double x : p) z += x;
            else for (double x : lp) z += Math.exp(a * x);
            return z;
        }
    }

    /** q ∝ p^alpha modelinin log-olabilirliği. */
    static double ll(List<LegData> legs, double alpha) {
        return ll(legs, alpha, 1);
    }

    /** 2.51: q ∝ p^alpha · beta^[favori] modelinin log-olabilirliği. */
    static double ll(List<LegData> legs, double alpha, double beta) {
        double s = 0, lb = Math.log(beta);
        for (LegData l : legs) {
            double z = l.z(alpha) + (beta - 1) * Math.exp(alpha * l.lp[l.fav]);
            s += alpha * l.lp[l.w] + (l.w == l.fav ? lb : 0) - Math.log(z);
        }
        return s;
    }

    /** 2.51: alpha verilince en çok olabilirlikli beta (0,3–3; log beta'ya göre türev azalan, ikiye bölme). */
    static double fitBeta(List<LegData> legs, double alpha) {
        if (legs.isEmpty()) return 1;
        int n = legs.size(), wins = 0;
        double[] z0 = new double[n], f = new double[n]; // 2.57: beta'dan bağımsız: bir kez
        for (int i = 0; i < n; i++) {
            LegData l = legs.get(i);
            z0[i] = l.z(alpha);
            f[i] = Math.exp(alpha * l.lp[l.fav]);
            if (l.w == l.fav) wins++;
        }
        double lo = Math.log(0.3), hi = Math.log(3);
        for (int it = 0; it < 40; it++) {
            double b = (lo + hi) / 2, beta = Math.exp(b), d = wins;
            for (int i = 0; i < n; i++) d -= beta * f[i] / (z0[i] + (beta - 1) * f[i]);
            if (d > 0) lo = b;
            else hi = b;
        }
        return Math.exp((lo + hi) / 2);
    }

    /** En çok olabilirlikli alpha (0,3–3 aralığında; türev azalan, ikiye bölme). */
    static double fit(List<LegData> legs) {
        if (legs.isEmpty()) return 1;
        // 2.57: Newton (türev azalan, ikinci türev −Σ varyans): birkaç adımda; aralık dışına çıkarsa ikiye bölme
        double lo = 0.3, hi = 3, a = 1;
        for (int it = 0; it < 60; it++) {
            double d = 0, dd = 0;
            for (LegData l : legs) {
                double z = 0, zl = 0, zll = 0;
                for (double x : l.lp) {
                    double e = Math.exp(a * x);
                    z += e;
                    zl += e * x;
                    zll += e * x * x;
                }
                double m = zl / z;
                d += l.lp[l.w] - m;
                dd -= zll / z - m * m;
            }
            if (d > 0) lo = a;
            else hi = a;
            double next = dd < 0 ? a - d / dd : (lo + hi) / 2;
            if (!(next > lo && next < hi)) next = (lo + hi) / 2;
            if (Math.abs(next - a) < 1e-7 || hi - lo < 1e-7) return Math.max(0.3, Math.min(3, next));
            a = next;
        }
        return a;
    }

    /**
     * Defterin analizi ve öğrenilenler: {settled, pending, suggested, all, legs: {n, expected, actual}, fav: {n, win, agf},
     * calib: {n, alpha, lr, holdout, use, alphaUse, why}, strategies: [{fraction, …}], fraction?, history}.
     */
    public static Map<String, Object> analyze(Map<String, Object> store, double unit, double kasa, String today) {
        return analyze(store, unit, kasa, today, 0);
    }

    /** legMin: kuponlar ayak başına bu tutmayla yeniden kurulur (uygulamadaki kural). */
    public static Map<String, Object> analyze(Map<String, Object> store, double unit, double kasa, String today, double legMin) {
        return analyze(store, unit, kasa, today, legMin, 0.15);
    }

    /** minHit: uygulamanın oynattığı kuponların en az 6/6 olasılığı (2.37 kuralı; karnede ayrı satır). */
    public static Map<String, Object> analyze(Map<String, Object> store, double unit, double kasa, String today, double legMin, double minHit) {
        return analyze(store, unit, kasa, today, legMin, minHit, 0.10);
    }

    /** minEdge: kâr kuralı (beklenen dönüş en az 1 + minEdge); karnede bu kurala uyanlar ayrı (2.45). */
    public static Map<String, Object> analyze(Map<String, Object> store, double unit, double kasa, String today, double legMin, double minHit,
            double minEdge) {
        return analyze(store, unit, kasa, today, legMin, minHit, minEdge, false);
    }

    /**
     * auto (2.49): 6/6 eşiği eşik taramasının en iyisi (en az MIN_SETTLED kupon); yoksa minHit. Kullanılan eşik
     * "minHitUse"; "Oynanacak olanlar" satırı onunla ve bölge kuralıyla.
     */
    public static Map<String, Object> analyze(Map<String, Object> store, double unit, double kasa, String today, double legMin, double minHit,
            double minEdge, boolean auto) {
        return analyze(store, unit, kasa, today, legMin, minHit, minEdge, auto, 0, 0.25);
    }

    /**
     * cap, km (2.51): kupon büyüklüğü taraması: seçilen kuralın kuponları STAKES büyüklüklerinde (en fazla cap) yeniden
     * kurulur; kasanın (km × kasa ile, Kelly payı) kupon başına ortalama log büyümesi en yüksek olan "capUse".
     */
    public static Map<String, Object> analyze(Map<String, Object> store, double unit, double kasa, String today, double legMin, double minHit,
            double minEdge, boolean auto, double cap, double km) {
        String from = LocalDate.parse(today).minusDays(ANALYZE_DAYS).toString();
        List<Map<String, Object>> settled = new ArrayList<>();
        int pending = 0, late = 0;
        for (Object o : records(store)) {
            Map<String, Object> r = Json.obj(o);
            if (String.valueOf(r.get("day")).compareTo(from) < 0) continue;
            if (Json.bool(r, "late", false)) { // 2.54: 1. ayak başladıktan sonra kaydedilmiş: sayılmaz
                late++;
                continue;
            }
            Map<String, Object> res = Json.obj(r.get("result"));
            if (res == null) pending++;
            else if (Json.arr(res.get("winners")).size() == 6) settled.add(r);
        }
        settled.sort(new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return String.valueOf(a.get("day")).compareTo(String.valueOf(b.get("day")));
            }
        });
        int sn = 0, sh = 0, an = 0, ah = 0, legN = 0, legHit = 0, favN = 0, favWin = 0, five = 0, fiveBanko = 0, en = 0;
        double ae = 0; // 2.43: beklenen 6/6 (tahmin edilen olasılıkların toplamı)
        int pn = 0, ph6 = 0, rwn = 0, bankoN = 0, bankoHit = 0;
        double bankoAgf = 0;
        List<double[]> coupons = new ArrayList<>(); // 2.49: {6/6 olasılığı, yerli, bedel, tuttu, ikramiye}
        List<Map<String, Object>> couponRecs = new ArrayList<>(); // 2.51: kuponların kayıtları (aynı sırada, günlere göre)
        Map<String, double[]> table = new LinkedHashMap<>(); // 2.48: {kupon, tuttu, beklenen, bedel, dönüş}
        double pc6 = 0, pr6 = 0, pe = 0, rwSum = 0, rwCost = 0; // 2.45: kâr kuralına uyanlar; bütün kuponların tahmini dönüşü
        double sc = 0, sr = 0, ac = 0, ar = 0, legExp = 0, favAgf = 0;
        List<LegData> data = new ArrayList<>(), dataHome = new ArrayList<>(), dataAway = new ArrayList<>();
        Map<String, double[]> groups = new LinkedHashMap<>();
        // 2.38: karne yalnızca doğrulanmış sonuçlarla: ikramiyesi kazanan kombinasyonun AGF payıyla tutarlı olan ya da
        // koşu numarasıyla eşleşen. Ötekiler sayılmaz; yalnızca karşılaştırma için favori istatistikleri gösterilir.
        List<Map<String, Object>> counted = new ArrayList<>();
        Set<Map<String, Object>> devir = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Map<String, Object>, Boolean>()); // 2.56
        int inconsistent = 0, unverified = 0;
        for (Map<String, Object> r : settled) {
            Map<String, Object> res = Json.obj(r.get("result"));
            String trust = trust(r, res, unit);
            if ("ikramiye".equals(trust)) { // 2.40: karne yalnızca ikramiyeyle doğrulanmış sonuçlarla
                counted.add(r);
                continue;
            }
            if ("devir".equals(trust) && !covers(r, Json.arr(res.get("winners")))) { // 2.56: kimse 6/6 yapamadı: kupon kesin kaçtı
                counted.add(r);
                devir.add(r);
                continue;
            }
            if ("tutarsız".equals(trust)) inconsistent++;
            else unverified++; // ikramiyesiz (koşu tablosundan okunanlar dahil)
            List<Object> win = Json.arr(res.get("winners")), legs = Json.arr(r.get("legs"));
            for (int k = 0; k < 6; k++) {
                double[] f = favorite(String.valueOf(legs.get(k)), String.valueOf(win.get(k)));
                if (f != null) count(groups, "tutarsız".equals(trust) ? "İkramiyeyle tutarsız (sayılmadı)" : "koşu no".equals(trust)
                        ? "Koşu tablosundan, ikramiyesiz (sayılmadı)" : "Doğrulanamayan (sayılmadı)", f[0] > 0, f[1]);
                if (f != null) count(groups, (domestic(r) ? "Yerli" : "Yabancı") + " hipodrom (sayılmadı)", f[0] > 0, f[1]); // 2.40
            }
        }
        // 2.53: aynı altılının ikinci kaydı (ör. Bilyoner ve TJK: aynı gün, aynı şehir, aynı kazananlar ve ikramiye) bir kez
        // sayılır; önce bildirilen, sonra TJK kaydı
        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        int dup = 0;
        for (Map<String, Object> r : counted) {
            Map<String, Object> res = Json.obj(r.get("result"));
            String city = norm(r.get("city") != null ? Json.str(r, "city") : Json.str(r, "label")) + " ";
            String k = r.get("day") + "|" + city.substring(0, city.indexOf(' ')) + "|" + Json.arr(res.get("winners")) + "|"
                    + Math.round(Json.dbl(res, "payout", 0) * 100);
            Map<String, Object> o = uniq.get(k);
            if (o == null) uniq.put(k, r);
            else {
                dup++;
                if (rank(r) > rank(o)) uniq.put(k, r);
            }
        }
        settled = new ArrayList<>(uniq.values()); // ilk görülme sırası: günlere göre sıralı kalır
        for (int i = 0; i < settled.size(); i++) {
            Map<String, Object> r = settled.get(i), res = Json.obj(r.get("result"));
            boolean sure = true, home = domestic(r); // 2.36: kaynak ve hipodrom
            boolean dv = devir.contains(r); // 2.56: kupon düzeyinde kaçmış; kazananlar doğrulanmadı: ayak, favori ve öğrenmede yok
            String source = "İkramiyeyle doğrulanan";
            List<Object> win = Json.arr(res.get("winners")), legs = Json.arr(r.get("legs"));
            double payout = Json.dbl(res, "payout", 0);
            Map<String, Object> nc = Json.obj(r.get("notified")), pc = Json.obj(r.get("plan"));
            if (nc != null) {
                boolean[] h = hits(nc, win);
                long kat = Json.lng(nc, "kat", 1);
                sn++;
                sc += Json.dbl(nc, "cost", 0) * kat;
                if (h[6]) {
                    sh++;
                    sr += payout * kat;
                }
            }
            if (pc != null) {
                boolean[] h = hits(pc, win);
                an++;
                ac += Json.dbl(pc, "cost", 0);
                if (h[6]) {
                    ah++;
                    ar += payout;
                }
                Double proi = Json.num(pc, "roi");
                if (proi != null) {
                    rwn++;
                    rwSum += proi * Json.dbl(pc, "cost", 0);
                    rwCost += Json.dbl(pc, "cost", 0);
                    if (proi >= 1 + minEdge - 1e-12) { // 2.45: kâr kuralına uyan kupon
                        pn++;
                        pc6 += Json.dbl(pc, "cost", 0);
                        pe += proi * Json.dbl(pc, "cost", 0);
                        if (h[6]) {
                            ph6++;
                            pr6 += payout;
                        }
                    }
                }
                Double ph = Json.num(pc, "hit");
                if (ph != null) { // 2.48: strateji tablosu: 6/6 olasılığı aralığına ve bölgeye göre gerçekleşen dönüş
                    int b = 0;
                    while (b < HIT_EDGES.length && ph >= HIT_EDGES[b] - 1e-12) b++;
                    for (String key : new String[] {"p" + b, domestic(r) ? "yerli" : "yabancı"}) {
                        double[] t = table.get(key);
                        if (t == null) table.put(key, t = new double[5]);
                        t[0]++;
                        t[1] += h[6] ? 1 : 0;
                        t[2] += ph;
                        t[3] += Json.dbl(pc, "cost", 0);
                        t[4] += h[6] ? payout : 0;
                    }
                }
                if (ph != null) {
                    en++;
                    ae += ph;
                    coupons.add(new double[] {ph, domestic(r) ? 1 : 0, Json.dbl(pc, "cost", 0), h[6] ? 1 : 0, h[6] ? payout : 0, dv ? 1 : 0});
                    couponRecs.add(r);
                }
                if (dv) continue; // 2.56
                List<Object> horses = Json.arr(pc.get("horses"));
                int miss = -1, misses = 0; // 2.33: tek ayakla kaçan kupon ve yatan ayak tek atlı (banko) mıydı
                for (int k = 0; k < 6; k++) if (!h[k]) {
                    misses++;
                    miss = k;
                }
                if (misses == 1) {
                    five++;
                    if (Json.arr(horses.get(miss)).size() == 1) fiveBanko++;
                }
                for (int k = 0; k < 6; k++) {
                    Map<String, Double> agf = agf(String.valueOf(legs.get(k)));
                    List<Object> hk = Json.arr(horses.get(k));
                    for (Object x : hk) {
                        Double p = agf.get(String.valueOf(x));
                        if (p != null) legExp += p;
                    }
                    legN++;
                    if (h[k]) legHit++;
                    if (hk.size() == 1) { // 2.51: banko (tek atlı) ayak: AGF'nin verdiği ve gerçekleşen
                        bankoN++;
                        if (h[k]) bankoHit++;
                        Double p = agf.get(String.valueOf(hk.get(0)));
                        if (p != null) bankoAgf += p;
                    }
                }
            }
            for (int k = 0; k < 6 && !dv; k++) {
                Map<String, Double> agf = agf(String.valueOf(legs.get(k)));
                String w = String.valueOf(win.get(k));
                if (!agf.containsKey(w) || agf.size() < 2) continue;
                double[] p = new double[agf.size()];
                int wi = 0, j = 0, fav = 0;
                for (Map.Entry<String, Double> e : agf.entrySet()) {
                    if (e.getKey().equals(w)) wi = j;
                    p[j] = e.getValue();
                    if (p[j] > p[fav]) fav = j;
                    j++;
                }
                if (sure) { // 2.36: öğrenme yalnızca güvenilir sonuçlarla (yerli ve yabancı ayrı da)
                    data.add(new LegData(p, wi));
                    (home ? dataHome : dataAway).add(new LegData(p, wi));
                }
                count(groups, source, fav == wi, p[fav]);
                count(groups, home ? "Yerli hipodrom" : "Yabancı hipodrom", fav == wi, p[fav]);
                if (sure) count(groups, "güvenilir", fav == wi, p[fav]);
                favN++;
                favAgf += p[fav];
                if (fav == wi) favWin++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settled", (long) settled.size());
        if (dup > 0) out.put("duplicates", (long) dup); // 2.53
        if (late > 0) out.put("late", (long) late); // 2.54
        if (!devir.isEmpty()) out.put("devir", (long) devir.size()); // 2.56
        if (inconsistent + unverified > 0) { // 2.38
            out.put("inconsistent", (long) inconsistent);
            out.put("unverified", (long) unverified);
        }
        out.put("pending", (long) pending);
        out.put("suggested", agg(sn, sh, sc, sr));
        out.put("all", agg(an, ah, ac, ar));
        if (!table.isEmpty()) { // 2.48
            List<Object> rows = new ArrayList<>();
            List<String> order = new ArrayList<>();
            for (int b = 0; b <= HIT_EDGES.length; b++) order.add("p" + b);
            order.add("yerli");
            order.add("yabancı");
            for (String key : order) {
                double[] t = table.get(key);
                if (t == null) continue;
                Map<String, Object> row = agg((int) t[0], (int) t[1], t[3], t[4]);
                row.put("expected", t[2]);
                if (key.startsWith("p")) {
                    int b = Integer.parseInt(key.substring(1));
                    row.put("lo", b == 0 ? 0.0 : HIT_EDGES[b - 1]);
                    if (b < HIT_EDGES.length) row.put("hi", HIT_EDGES[b]);
                } else row.put("region", key);
                rows.add(row);
            }
            out.put("table", rows);
        }
        if (rwn > 0) { // 2.45: kâr kuralı
            Map<String, Object> profit = agg(pn, ph6, pc6, pr6);
            profit.put("expectedRoi", pc6 > 0 ? pe / pc6 : 0.0);
            profit.put("minEdge", minEdge);
            out.put("profitRule", profit);
            Json.obj(out.get("all")).put("expectedRoi", rwCost > 0 ? rwSum / rwCost : 0.0);
        }
        if (en > 0) { // 2.43
            Json.obj(out.get("all")).put("expected", ae);
            Json.obj(out.get("all")).put("withHit", (long) en);
        }
        Map<String, Object> lg = new LinkedHashMap<>();
        lg.put("n", (long) legN);
        lg.put("expected", legN > 0 ? legExp / legN : 0.0);
        lg.put("actual", legN > 0 ? (double) legHit / legN : 0.0);
        lg.put("five", (long) five);
        lg.put("fiveBanko", (long) fiveBanko);
        if (bankoN > 0) { // 2.51
            Map<String, Object> bk = new LinkedHashMap<>();
            bk.put("n", (long) bankoN);
            bk.put("win", (double) bankoHit / bankoN);
            bk.put("agf", bankoAgf / bankoN);
            lg.put("banko", bk);
        }
        out.put("legs", lg);
        Map<String, Object> fv = new LinkedHashMap<>();
        fv.put("n", (long) favN);
        fv.put("win", favN > 0 ? (double) favWin / favN : 0.0);
        fv.put("agf", favN > 0 ? favAgf / favN : 0.0);
        // 2.35–2.36: öz denetim: favori beklenenden çok az kazanıyorsa ve bunu güvenilir sonuçlar (resmî 6'lı, koşu
        // numarası; en az 40 ayak) doğrulamıyorsa sonuçlar yanlış okunuyordur. Güvenilir sonuçlar da aynısını diyorsa gerçek.
        // 2.37: makul olmayan sonuç: bir grupta (en az 40 ayak) favori AGF'nin verdiğinin yarısından az ve 3 standart
        // sapmadan fazla az kazanıyorsa (gerçek yarışta olmaz: AGF favorisi kabaca her üç koşudan birini kazanır) sonuçlar
        // yanlış okunuyordur; kaynak "resmî" görünse de hiçbir sonuçla öğrenilmez.
        // 2.38: ikramiyeyle doğrulanan sonuç gerçektir; makul olmama denetimi yalnızca ikramiyesiz (koşu numarasıyla) olanlara
        // 2.40: yalnızca bilgi: ikramiyesiz (koşu tablosundan) okunan sonuçlar gerçek dışıysa uyarılır; sayılmadıkları için
        // öğrenmeyi etkilemez (öğrenme ikramiyeyle doğrulanmış gerçek sonuçlarla)
        boolean absurd = false;
        for (Map.Entry<String, double[]> g : groups.entrySet()) {
            if (g.getKey().endsWith("(sayılmadı)") && !g.getKey().startsWith("İkramiyeyle")) absurd |= absurd(g.getValue()[0], g.getValue()[1], g.getValue()[2]);
        }
        double[] rel = groups.containsKey("güvenilir") ? groups.get("güvenilir") : new double[3];
        boolean confirmed = !absurd && rel[0] >= 40 && low(rel[0], rel[1], rel[2]);
        boolean suspect = absurd || (low(favN, favWin, favAgf) && !confirmed);
        if (suspect) fv.put("suspect", true);
        if (absurd) fv.put("absurd", true);
        if (confirmed) fv.put("confirmed", true);
        List<Object> gs = new ArrayList<>();
        for (Map.Entry<String, double[]> e : groups.entrySet()) {
            if ("güvenilir".equals(e.getKey())) continue;
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("label", e.getKey());
            g.put("n", (long) e.getValue()[0]);
            g.put("win", e.getValue()[1] / e.getValue()[0]);
            g.put("agf", e.getValue()[2] / e.getValue()[0]);
            gs.add(g);
        }
        fv.put("groups", gs);
        out.put("fav", fv);
        Map<String, Object> calib = calibrate(data); // yalnızca güvenilir sonuçlar
        Map<String, Object> region = new LinkedHashMap<>(); // 2.36: yerli ve yabancı hipodromda ayrı düzeltme
        region.put("yerli", calibrate(dataHome));
        region.put("yabancı", calibrate(dataAway));
        if (suspect && !absurd && Json.bool(calib, "use", false) == false) {
            calib.put("why", "Sonuçlar şüpheli (AGF favorisi beklenenden çok az kazanmış, güvenilir sonuçlarla doğrulanmadı): öğrenmede kullanılmıyor.");
        }
        out.put("calib", calib);
        out.put("calibRegion", region);
        List<Map<String, Object>> verified = new ArrayList<>(); // 2.57: ikramiyesi doğrulanmış (devir değil)
        for (Map<String, Object> r : settled) if (!devir.contains(r)) verified.add(r);
        out.put("payout", payoutFit(verified, unit));
        if (cap > 0) { // 2.59: ganyan ile tahmin
            Map<String, Object> g = oddsCompare(verified, out, unit, cap, legMin);
            if (g != null) out.put("ganyan", g);
        }
        // 2.49–2.50: eşik ve bölge taraması: 6/6 kuralı (6/6 olasılığı ≥ eşik; hepsi, yerli ya da yabancı altılılar) her
        // eşikte nasıl dönerdi; en iyisi en az MIN_SETTLED kuponla en yüksek dönüş (eşitlikte düşük eşik, geniş bölge)
        List<Object> sweep = new ArrayList<>();
        for (double t : SWEEP) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("minHit", t);
            for (String sco : SCOPES) row.put(sco, rule(coupons, sco, t));
            sweep.add(row);
        }
        Object[] bp = pick(coupons);
        Double bestT = bp == null ? null : (Double) bp[0];
        String bestS = bp == null ? null : (String) bp[1];
        // 2.58: strateji araması (bölge × eşik × kupon büyüklüğü, son dönem sınamasıyla): varsa kural ondan
        Map<String, Object> search = cap > 0 && en > 0 ? strategySearch(coupons, couponRecs, out, unit, legMin) : null;
        Map<String, Object> sAll = search == null ? null : Json.obj(search.get("all"));
        if (sAll != null) {
            bestT = Json.dbl(sAll, "minHit", 0);
            bestS = (String) sAll.get("scope");
            out.put("search", search);
        }
        double use = auto && bestT != null ? bestT : minHit;
        String scope = auto && bestT != null ? bestS : "hepsi";
        if (!(auto && bestT != null)) { // elle eşik: o eşikte en iyi dönen bölge (en az MIN_SETTLED kupon)
            double br = -1;
            for (String sco : SCOPES) {
                Map<String, Object> cell = rule(coupons, sco, use);
                if (Json.lng(cell, "n", 0) >= MIN_SETTLED && Json.dbl(cell, "roi", 0) > br + 1e-12) {
                    br = Json.dbl(cell, "roi", 0);
                    scope = sco;
                }
            }
        }
        out.put("minHitUse", use);
        out.put("scopeUse", scope);
        // 2.42 → 2.50: bölge kuralı kupon sonuçlarıyla: taramada en iyi dönen bölge dışındaki altılılar önerilmez. Favori
        // istatistiği (AGF favorisi az kazanıyor mu) yalnızca bilgi: favori her iki bölgede de AGF'nin verdiğinden az kazanıyor,
        // ama yerli kuponlar beklenenden çok tuttu; ölçü kuponun kendisi. Veri değişince kural kendiliğinden değişir.
        Map<String, Object> trustRegion = new LinkedHashMap<>();
        for (String[] k : new String[][] {{"yerli", "Yerli hipodrom"}, {"yabancı", "Yabancı hipodrom"}}) {
            double[] g = groups.get(k[1]);
            Map<String, Object> cell = rule(coupons, k[0], 0);
            if (g == null && Json.lng(cell, "n", 0) == 0) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            if (g != null) {
                double p = g[2] / g[0], z = (g[1] - g[0] * p) / Math.sqrt(Math.max(1e-9, g[0] * p * (1 - p)));
                t.put("n", (long) g[0]);
                t.put("win", g[1] / g[0]);
                t.put("agf", p);
                t.put("z", z);
            }
            t.put("coupons", cell);
            t.put("weak", !"hepsi".equals(scope) && !k[0].equals(scope));
            trustRegion.put(k[0], t);
        }
        out.put("regionTrust", trustRegion);
        if (en > 0) {
            Map<String, Object> sw = new LinkedHashMap<>();
            sw.put("rows", sweep);
            if (bestT != null) {
                sw.put("best", bestT);
                sw.put("bestScope", bestS);
            }
            sw.put("min", (long) MIN_SETTLED);
            out.put("sweep", sw);
            Map<String, Object> rule = rule(coupons, scope, use); // 2.43: uygulamanın oynatacağı kuponlar
            rule.put("scope", scope);
            if (auto && bestT != null) rule.put("auto", true);
            List<Object> list = new ArrayList<>(); // 2.53: kurala uyan kuponlar tek tek (yeniden eskiye): denetim için
            for (int i = coupons.size() - 1; i >= 0; i--) {
                double[] x = coupons.get(i);
                if (!fits(x, scope, use)) continue;
                Map<String, Object> r = couponRecs.get(i), c = new LinkedHashMap<>();
                c.put("day", r.get("day"));
                c.put("label", r.get("label"));
                c.put("source", r.get("source"));
                if (Json.bool(r, "backtest", false)) c.put("backtest", true);
                if (r.get("notified") != null) c.put("notified", true);
                c.put("hit", x[0]);
                c.put("cost", x[2]);
                c.put("won", x[3] > 0);
                c.put("ret", x[4]);
                Map<String, Object> res = Json.obj(r.get("result")); // 2.54: denetim için kazananlar, ikramiye ve AGF tutarlılığı
                c.put("winners", res.get("winners"));
                c.put("payout", Json.dbl(res, "payout", 0));
                Double ratio = payoutRatio(r, res, unit);
                if (ratio != null) c.put("ratio", ratio);
                list.add(c);
            }
            rule.put("list", list);
            // 2.55: şehirlere göre (kâr nereden geliyor) ve verinin kapsadığı gün (yıllık kâr için)
            Map<String, double[]> cities = new LinkedHashMap<>();
            for (int i = 0; i < coupons.size(); i++) {
                double[] x = coupons.get(i);
                if (!fits(x, scope, use)) continue;
                Map<String, Object> r = couponRecs.get(i);
                String c = String.valueOf(r.get("city") != null ? r.get("city") : r.get("label")).trim();
                double[] g = cities.get(c);
                if (g == null) cities.put(c, g = new double[4]);
                g[0]++;
                g[1] += x[3];
                g[2] += x[2];
                g[3] += x[4];
            }
            List<Object> byCity = new ArrayList<>();
            for (Map.Entry<String, double[]> e : cities.entrySet()) {
                Map<String, Object> g = agg((int) e.getValue()[0], (int) e.getValue()[1], e.getValue()[2], e.getValue()[3]);
                g.put("city", e.getKey());
                byCity.add(g);
            }
            byCity.sort(new Comparator<Object>() {
                @Override
                public int compare(Object a, Object b) {
                    Map<String, Object> x = Json.obj(a), y = Json.obj(b);
                    return Double.compare(Json.dbl(y, "ret", 0) - Json.dbl(y, "cost", 0), Json.dbl(x, "ret", 0) - Json.dbl(x, "cost", 0));
                }
            });
            rule.put("byCity", byCity);
            if (!settled.isEmpty()) {
                long span = ChronoUnit.DAYS.between(LocalDate.parse(String.valueOf(settled.get(0).get("day"))), LocalDate.parse(today)) + 1;
                rule.put("days", Math.max(1, span));
            }
            out.put("rule", rule);
            // 2.51: son dönem sınaması: kural ilk üçte ikiyle seçilseydi seçimde kullanılmayan son üçte birde ne olurdu
            int cut = coupons.size() * 2 / 3;
            Object[] tp = pick(coupons.subList(0, cut));
            if (tp != null && cut < coupons.size()) {
                Map<String, Object> ho = rule(coupons.subList(cut, coupons.size()), (String) tp[1], (Double) tp[0]);
                Map<String, Object> tr = rule(coupons.subList(0, cut), (String) tp[1], (Double) tp[0]);
                ho.put("scope", tp[1]);
                ho.put("trainN", tr.get("n"));
                ho.put("trainRoi", tr.get("roi"));
                ho.put("from", couponRecs.get(cut).get("day"));
                out.put("holdout", ho);
            }
            if (cap > 0 && kasa > 0) out.putAll(stakes(coupons, couponRecs, scope, use, cap, km, kasa, unit, legMin, out));
            // 2.57: seçim yöntemi son dönem sınamasında kaybettirdiyse kural kanıtlanmadı (önerilmez; ayarla yine de)
            Map<String, Object> ho = Json.obj(out.get("holdout"));
            if (search != null) { // 2.58: sınama strateji aramasından (bölge, eşik ve kupon büyüklüğü birlikte)
                if (!Json.bool(search, "proven", false)) out.put("ruleUnproven", true);
                else if (sAll != null) { // kanıtlandı: kupon aramada seçilen büyüklükte (tavanı aşmadan); kasa ihtiyacı ona göre
                    out.put("capUse", Math.min(Json.dbl(sAll, "size", cap), cap));
                    if (sAll.get("kasaMin") != null) {
                        double needKm = Json.dbl(sAll, "kasaMin", 0) / Math.max(1e-9, km);
                        sAll.put("kasaNeed", needKm);
                        sAll.put("kasaRec", Json.dbl(sAll, "kasaKelly", 0) / Math.max(1e-9, km));
                        Map<String, Object> st = Json.obj(out.get("stakes"));
                        if (st != null) st.put("neg", kasa < needKm);
                    }
                }
            } else if (ho != null && Json.lng(ho, "n", 0) >= MIN_SETTLED && Json.dbl(ho, "roi", 0) < 1) out.put("ruleUnproven", true);
        }
        if (cap > 0 && settled.size() >= 3) { // 2.57: değer kuponu geriye dönük: ilk üçte ikiyle öğren, son üçte birde dene
            int cut = settled.size() * 2 / 3;
            List<Map<String, Object>> train = settled.subList(0, cut), test = settled.subList(cut, settled.size());
            double kref = Math.max(kasa, VALUE_KASA);
            Map<String, Object> vt = valueRun(test, devir, learnFrom(train, devir, unit), unit, kref, km, cap, legMin, minEdge);
            vt.put("from", test.get(0).get("day"));
            vt.put("kasa", kref);
            Map<String, Object> va = valueRun(settled, devir, out, unit, kref, km, cap, legMin, minEdge);
            Map<String, Object> vs = new LinkedHashMap<>();
            vs.put("test", vt);
            vs.put("all", va);
            boolean proven = Json.lng(vt, "n", 0) >= MIN_SETTLED && Json.dbl(vt, "roi", 0) > 1;
            vs.put("proven", proven);
            out.put("value", vs);
        }
        // bütçe stratejileri: geçmiş altılılar düz AGF ile, her bütçeyle yeniden kurulur
        List<Object> strategies = new ArrayList<>();
        double bestRoi = -1, bestFraction = 0;
        List<List<Altili.Leg>> parsed = new ArrayList<>(); // 2.57: ayaklar bir kez ayrıştırılır (her bütçe için değil)
        for (Map<String, Object> r : settled) {
            List<Altili.Leg> ls = new ArrayList<>();
            boolean ok = true;
            for (Object l : Json.arr(r.get("legs"))) {
                Altili.Leg leg = Altili.leg(String.valueOf(l));
                ok &= !leg.horses.isEmpty();
                ls.add(leg);
            }
            parsed.add(ok && ls.size() == 6 ? ls : null);
        }
        for (double f : FRACTIONS) {
            double budget = Math.max(unit, Math.floor(f * kasa / 10) * 10);
            long maxUnits = (long) Math.floor(budget / unit + 1e-9);
            int n = 0, h = 0;
            double cost = 0, ret = 0;
            for (int ri = 0; ri < settled.size(); ri++) {
                Map<String, Object> r = settled.get(ri);
                List<Object> win = Json.arr(Json.obj(r.get("result")).get("winners"));
                List<Altili.Leg> ls = parsed.get(ri);
                if (ls == null) continue;
                int[] cnt = Altili.mostLikely(ls, maxUnits, legMin);
                long units = 1;
                boolean hit = true;
                for (int k = 0; k < 6; k++) {
                    units *= cnt[k];
                    boolean in = false;
                    for (int j = 0; j < cnt[k]; j++) in |= ls.get(k).horses.get(j).no.equals(String.valueOf(win.get(k)));
                    hit &= in;
                }
                hit &= !devir.contains(r); // 2.56: devirde kimse tutturamadı
                n++;
                cost += units * unit;
                if (hit) {
                    h++;
                    ret += Json.dbl(Json.obj(r.get("result")), "payout", 0);
                }
            }
            Map<String, Object> s = agg(n, h, cost, ret);
            s.put("fraction", f);
            s.put("budget", budget);
            strategies.add(s);
            double roi = cost > 0 ? ret / cost : 0;
            if (n > 0 && roi > bestRoi + 1e-9) {
                bestRoi = roi;
                bestFraction = f;
            }
        }
        out.put("strategies", strategies);
        if (settled.size() >= MIN_SETTLED && bestFraction > 0) out.put("fraction", bestFraction);
        out.put("history", history(store));
        return out;
    }

    /**
     * AGF düzeltmesi: tüm veride alpha ve olabilirlik oranı; ayrıca ilk üçte ikide öğrenilen alpha son üçte birde düz
     * AGF'den iyi mi (öğrenmede görülmemiş veride doğrulama). Kullanılacak alpha büzülerek.
     */
    static Map<String, Object> calibrate(List<LegData> data) {
        Map<String, Object> c = new LinkedHashMap<>();
        int n = data.size();
        c.put("n", (long) n);
        double a = fit(data), lr = 2 * (ll(data, a) - ll(data, 1));
        c.put("alpha", a);
        c.put("lr", lr);
        boolean holdout = false;
        if (n >= 30) {
            int cut = n * 2 / 3;
            List<LegData> train = data.subList(0, cut), test = data.subList(cut, n);
            holdout = ll(test, fit(train)) > ll(test, 1);
        }
        c.put("holdout", holdout);
        boolean use = n >= MIN_LEGS && lr > 3.84 && holdout;
        c.put("use", use);
        c.put("alphaUse", use ? 1 + (a - 1) * n / (n + PRIOR_LEGS) : 1.0);
        String why;
        if (n < MIN_LEGS) why = "Doğrulanmış veri az (" + n + "/" + MIN_LEGS + " ayak): AGF olduğu gibi kullanılıyor.";
        else if (lr <= 3.84) why = "AGF'de anlamlı bir sapma yok: olduğu gibi kullanılıyor.";
        else if (!holdout) why = "Sapma görüldü ama son dönemde doğrulanmadı: kullanılmıyor.";
        else why = a > 1 ? "Kalabalık favorileri az, sürprizleri çok oynuyor: düzeltme kullanılıyor."
                : "Kalabalık favorileri fazla oynuyor: düzeltme kullanılıyor.";
        c.put("why", why);
        // 2.51: favori düzeltmesi: kullanılan alpha'nın üstüne AGF favorisinin payı (favori AGF'de abartılı mı); ayrı olabilirlik
        // oranı testi (%5), son dönem doğrulaması ve büzülme. Banko ayakların yatmasının başlıca nedeni.
        double au = Json.dbl(c, "alphaUse", 1), b = fitBeta(data, au), blr = 2 * (ll(data, au, b) - ll(data, au, 1));
        c.put("beta", b);
        c.put("betaLr", blr);
        boolean bh = false;
        if (n >= 30) {
            int cut = n * 2 / 3;
            List<LegData> train = data.subList(0, cut), test = data.subList(cut, n);
            bh = ll(test, au, fitBeta(train, au)) > ll(test, au, 1);
        }
        boolean bOn = n >= MIN_LEGS && blr > 3.84 && bh;
        c.put("betaOn", bOn);
        c.put("betaUse", bOn ? Math.exp(Math.log(b) * n / (n + PRIOR_LEGS)) : 1.0);
        c.put("betaWhy", n < MIN_LEGS ? "veri az" : blr <= 3.84 ? "favoride anlamlı sapma yok" : !bh ? "sapma son dönemde doğrulanmadı"
                : b < 1 ? "AGF favoriyi abartıyor: favorinin payı düşürülüyor" : "AGF favoriyi az veriyor: favorinin payı artırılıyor");
        return c;
    }

    /** Son kayıtlar (yeniden eskiye): gün, altılı, önerilen ya da kurulan kupon, sonuç. */
    static List<Object> history(Map<String, Object> store) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Object o : records(store)) all.add(Json.obj(o));
        all.sort(new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                int d = String.valueOf(b.get("day")).compareTo(String.valueOf(a.get("day")));
                if (d != 0) return d;
                return Boolean.compare(b.get("notified") != null, a.get("notified") != null);
            }
        });
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : all) {
            if (out.size() >= HISTORY) break;
            Map<String, Object> c = Json.obj(r.get("notified") != null ? r.get("notified") : r.get("plan"));
            if (c == null) continue;
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("day", r.get("day"));
            h.put("label", r.get("label"));
            h.put("source", r.get("source"));
            h.put("notified", r.get("notified") != null);
            if (Json.bool(r, "backtest", false)) h.put("backtest", true); // 2.43: geriye dönük tahmin
            h.put("cost", Json.dbl(c, "cost", 0) * Json.lng(c, "kat", 1));
            Map<String, Object> res = Json.obj(r.get("result"));
            if (res == null) h.put("status", "bekliyor");
            else if (Json.arr(res.get("winners")).size() != 6) h.put("status", "sonuç yok");
            else {
                boolean[] hit = hits(c, Json.arr(res.get("winners")));
                int legs = 0;
                for (int k = 0; k < 6; k++) if (hit[k]) legs++;
                h.put("status", hit[6] ? "tuttu" : "tutmadı");
                h.put("legs", (long) legs);
                h.put("winners", res.get("winners"));
                h.put("from", res.get("from"));
                h.put("trust", trust(r, res, 1)); // 2.38: doğrulama
                if (Json.bool(res, "rechecked", false)) h.put("rechecked", true); // 2.39: geçmiş yeniden doğrulandı
                if (res.get("previous") != null) h.put("previous", res.get("previous"));
                Double ratio = payoutRatio(r, res, 1);
                if (ratio != null) h.put("ratio", ratio);
                if (!Json.bool(res, "estimated", false) && res.get("payout") != null) h.put("payout", res.get("payout")); // 2.41
                h.put("ret", hit[6] ? Json.dbl(res, "payout", 0) * Json.lng(c, "kat", 1) : 0.0);
                h.put("estimated", Json.bool(res, "estimated", false));
            }
            out.add(h);
        }
        return out;
    }
}
