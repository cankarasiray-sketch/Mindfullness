package app.bilincli.core;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

    static final int KEEP_DAYS = 150, SETTLE_DAYS = 5, ANALYZE_DAYS = 120;
    static final int MIN_LEGS = 150, MIN_SETTLED = 20, HISTORY = 12;
    /** alpha'nın 1'e büzülmesi: kullanılan = 1 + (bulunan − 1) × n / (n + PRIOR_LEGS). */
    static final double PRIOR_LEGS = 200;
    static final double[] FRACTIONS = {0.01, 0.03, 0.06};
    /** Kazanan at AGF listesinde yoksa payı (tahmini ikramiye için). */
    static final double MISSING_P = 0.005;

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
        return c;
    }

    /** Okunan altılıları (planlarıyla) deftere yazar; sonuçlanmışlara dokunmaz. Gün: altılının günü, yoksa bugün. */
    public static void record(Map<String, Object> store, String today, List<Object> altilis) {
        List<Object> recs = records(store);
        for (Object o : altilis) {
            Map<String, Object> a = Json.obj(o), plan = Json.obj(a == null ? null : a.get("plan"));
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
            for (String f : new String[] {"label", "city", "cityId", "no", "source"}) if (a.get(f) != null) r.put(f, a.get(f));
            r.put("legs", new ArrayList<>(legs.subList(0, 6)));
            r.put("plan", coupon(plan));
        }
        String oldest = LocalDate.parse(today).minusDays(KEEP_DAYS).toString();
        for (int i = recs.size() - 1; i >= 0; i--) if (String.valueOf(Json.obj(recs.get(i)).get("day")).compareTo(oldest) < 0) recs.remove(i);
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
        Map<String, Double> out = new LinkedHashMap<>();
        for (Altili.Horse h : Altili.leg(leg).horses) out.put(h.no, h.p);
        return out;
    }

    static String norm(String s) {
        String t = Normalizer.normalize(String.valueOf(s).toLowerCase(new Locale("tr")), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return t.replace('ı', 'i').replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static Map<String, Object> cityOf(Map<String, Object> rec, List<Object> cities) {
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

    /** Altılının kazananları: önce sayfadaki kazanan kombinasyon (ayaklarla tutarlıysa), yoksa koşu tablolarından. */
    static Object[] winners(Map<String, Object> rec, Map<String, Object> city) {
        List<Map<String, Double>> legs = new ArrayList<>();
        for (Object l : Json.arr(rec.get("legs"))) legs.add(agf(String.valueOf(l)));
        List<Object> combos = Json.arr(city.get("combos"));
        List<Object> order = new ArrayList<>();
        int no = (int) Json.lng(rec, "no", 0);
        if (no >= 1 && no <= combos.size()) order.add(combos.get(no - 1));
        order.addAll(combos);
        for (Object o : order) {
            List<Object> nos = Json.arr(Json.obj(o).get("nos"));
            if (nos.size() != 6) continue;
            boolean ok = true;
            for (int k = 0; k < 6 && ok; k++) ok = legs.get(k).containsKey(String.valueOf(nos.get(k)));
            if (ok) return new Object[] {nos, Json.num(Json.obj(o), "payout"), "kombinasyon"};
        }
        List<Object> races = Json.arr(city.get("races"));
        List<Object> nos = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (Map<String, Double> leg : legs) {
            int best = -1;
            double score = 0.6;
            for (int i = 0; i < races.size(); i++) {
                Map<String, Object> race = Json.obj(races.get(i));
                if (used.contains(i) || race.get("winner") == null) continue;
                Set<String> runners = new LinkedHashSet<>();
                for (Object x : Json.arr(race.get("runners"))) runners.add(String.valueOf(x));
                double j = jaccard(runners, leg.keySet());
                if (j >= score) {
                    score = j;
                    best = i;
                }
            }
            if (best < 0) return null;
            used.add(best);
            nos.add(String.valueOf(Json.obj(races.get(best)).get("winner")));
        }
        return new Object[] {nos, null, "koşular"};
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
            Map<String, Object> res = new LinkedHashMap<>();
            if (w == null) {
                if (!old) continue;
                res.put("missing", true);
            } else {
                @SuppressWarnings("unchecked")
                List<Object> nos = (List<Object>) w[0];
                double pw = 1;
                List<Object> legs = Json.arr(rec.get("legs"));
                for (int k = 0; k < 6; k++) {
                    Double p = agf(String.valueOf(legs.get(k))).get(String.valueOf(nos.get(k)));
                    pw *= p == null ? MISSING_P : p;
                }
                Double payout = (Double) w[1];
                res.put("winners", nos);
                res.put("agf", pw);
                res.put("payout", payout != null ? payout : unit * (1 - takeout) * (1 - tax) / pw);
                res.put("estimated", payout == null);
                res.put("from", w[2]);
            }
            res.put("at", now.toString());
            rec.put("result", res);
            n++;
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
        final double[] p;
        final int w;

        LegData(double[] p, int w) {
            this.p = p;
            this.w = w;
        }
    }

    /** q ∝ p^alpha modelinin log-olabilirliği. */
    static double ll(List<LegData> legs, double alpha) {
        double s = 0;
        for (LegData l : legs) {
            double z = 0;
            for (double p : l.p) z += Math.pow(p, alpha);
            s += alpha * Math.log(l.p[l.w]) - Math.log(z);
        }
        return s;
    }

    /** En çok olabilirlikli alpha (0,3–3 aralığında; türev azalan, ikiye bölme). */
    static double fit(List<LegData> legs) {
        if (legs.isEmpty()) return 1;
        double lo = 0.3, hi = 3;
        for (int it = 0; it < 40; it++) {
            double a = (lo + hi) / 2, d = 0;
            for (LegData l : legs) {
                double z = 0, zl = 0;
                for (double p : l.p) {
                    double x = Math.pow(p, a);
                    z += x;
                    zl += x * Math.log(p);
                }
                d += Math.log(l.p[l.w]) - zl / z;
            }
            if (d > 0) lo = a;
            else hi = a;
        }
        return (lo + hi) / 2;
    }

    /**
     * Defterin analizi ve öğrenilenler: {settled, pending, suggested, all, legs: {n, expected, actual}, fav: {n, win, agf},
     * calib: {n, alpha, lr, holdout, use, alphaUse, why}, strategies: [{fraction, …}], fraction?, history}.
     */
    public static Map<String, Object> analyze(Map<String, Object> store, double unit, double kasa, String today) {
        String from = LocalDate.parse(today).minusDays(ANALYZE_DAYS).toString();
        List<Map<String, Object>> settled = new ArrayList<>();
        int pending = 0;
        for (Object o : records(store)) {
            Map<String, Object> r = Json.obj(o);
            if (String.valueOf(r.get("day")).compareTo(from) < 0) continue;
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
        int sn = 0, sh = 0, an = 0, ah = 0, legN = 0, legHit = 0, favN = 0, favWin = 0;
        double sc = 0, sr = 0, ac = 0, ar = 0, legExp = 0, favAgf = 0;
        List<LegData> data = new ArrayList<>();
        List<Integer> dayIndex = new ArrayList<>();
        for (int i = 0; i < settled.size(); i++) {
            Map<String, Object> r = settled.get(i), res = Json.obj(r.get("result"));
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
                List<Object> horses = Json.arr(pc.get("horses"));
                for (int k = 0; k < 6; k++) {
                    Map<String, Double> agf = agf(String.valueOf(legs.get(k)));
                    for (Object x : Json.arr(horses.get(k))) {
                        Double p = agf.get(String.valueOf(x));
                        if (p != null) legExp += p;
                    }
                    legN++;
                    if (h[k]) legHit++;
                }
            }
            for (int k = 0; k < 6; k++) {
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
                data.add(new LegData(p, wi));
                dayIndex.add(i);
                favN++;
                favAgf += p[fav];
                if (fav == wi) favWin++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settled", (long) settled.size());
        out.put("pending", (long) pending);
        out.put("suggested", agg(sn, sh, sc, sr));
        out.put("all", agg(an, ah, ac, ar));
        Map<String, Object> lg = new LinkedHashMap<>();
        lg.put("n", (long) legN);
        lg.put("expected", legN > 0 ? legExp / legN : 0.0);
        lg.put("actual", legN > 0 ? (double) legHit / legN : 0.0);
        out.put("legs", lg);
        Map<String, Object> fv = new LinkedHashMap<>();
        fv.put("n", (long) favN);
        fv.put("win", favN > 0 ? (double) favWin / favN : 0.0);
        fv.put("agf", favN > 0 ? favAgf / favN : 0.0);
        out.put("fav", fv);
        out.put("calib", calibrate(data));
        // bütçe stratejileri: geçmiş altılılar düz AGF ile, her bütçeyle yeniden kurulur
        List<Object> strategies = new ArrayList<>();
        double bestRoi = -1, bestFraction = 0;
        for (double f : FRACTIONS) {
            double budget = Math.max(unit, Math.floor(f * kasa / 10) * 10);
            long maxUnits = (long) Math.floor(budget / unit + 1e-9);
            int n = 0, h = 0;
            double cost = 0, ret = 0;
            for (Map<String, Object> r : settled) {
                List<Object> legs = Json.arr(r.get("legs")), win = Json.arr(Json.obj(r.get("result")).get("winners"));
                List<Altili.Leg> ls = new ArrayList<>();
                for (Object l : legs) ls.add(Altili.leg(String.valueOf(l)));
                boolean ok = true;
                for (Altili.Leg l : ls) ok &= !l.horses.isEmpty();
                if (!ok) continue;
                int[] cnt = Altili.mostLikely(ls, maxUnits);
                long units = 1;
                boolean hit = true;
                for (int k = 0; k < 6; k++) {
                    units *= cnt[k];
                    boolean in = false;
                    for (int j = 0; j < cnt[k]; j++) in |= ls.get(k).horses.get(j).no.equals(String.valueOf(win.get(k)));
                    hit &= in;
                }
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
        if (n < MIN_LEGS) why = "Veri az (" + n + "/" + MIN_LEGS + " ayak): AGF olduğu gibi kullanılıyor.";
        else if (lr <= 3.84) why = "AGF'de anlamlı bir sapma yok: olduğu gibi kullanılıyor.";
        else if (!holdout) why = "Sapma görüldü ama son dönemde doğrulanmadı: kullanılmıyor.";
        else why = a > 1 ? "Kalabalık favorileri az, sürprizleri çok oynuyor: düzeltme kullanılıyor."
                : "Kalabalık favorileri fazla oynuyor: düzeltme kullanılıyor.";
        c.put("why", why);
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
                h.put("ret", hit[6] ? Json.dbl(res, "payout", 0) * Json.lng(c, "kat", 1) : 0.0);
                h.put("estimated", Json.bool(res, "estimated", false));
            }
            out.add(h);
        }
        return out;
    }
}
