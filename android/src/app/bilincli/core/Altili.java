package app.bilincli.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 2.21: Altılı ganyan kupon planı (çekirdek: arayüz ve arka plan bildirimi aynı hesabı kullanır).
 *
 * <p>Havuz usulü: kazanan kombinasyona düşen ikramiye (havuz × (1 − kesinti) + devir) / o kombinasyonu oynayan birim
 * sayısı. Kalabalığın bir kombinasyona payı p, AGF'lerin çarpımıdır; kazanma olasılığı q ganyan oranından (ayaktaki her
 * atın oranı varsa, kesinti payı arındırılarak), yoksa AGF'den. Bir birimin beklenen getirisi q/p × (1 − kesinti +
 * devir/havuz) × (1 − vergi); kuponun getirisi ayaklara ayrılır: her ayakta seçilen atların q/p toplamlarının çarpımı
 * bölü kombinasyon sayısı. Ganyan yoksa q = p ve kâr yalnızca devirden gelir.
 *
 * <p>Kupon, kasanın uzun vadeli büyümesini (Kelly ölçütü) en çok artıran atlarla kurulur: en az parayla en çok kazanç ile
 * tutma olasılığı birlikte tartılır (tek tek uzak atlar ucuz ama nadiren tutar; çok at tutar ama pahalıdır). Tutar kasanın
 * Kelly payı × Kelly çarpanı; kupon bundan büyükse oynanmaz, küçükse aynı kupon kat kat oynanır.
 */
public final class Altili {
    private Altili() {}

    /** Ganyan oranlarının ima ettiği olasılıklar toplamı bu aralıkta değilse oranlar kullanılmaz (AtYarisi ile aynı). */
    static final double MIN_BOOK = 0.95, MAX_BOOK = 2.0;
    static final int MAX_PER_LEG = 10;
    static final long MAX_UNITS = 20000;
    /** Havuz biliniyorsa ikramiye bu kadar kombinasyona kadar tek tek hesaplanır (bizim kuponumuzun payı dahil). */
    static final long MAX_EXACT = MAX_UNITS;
    /** Ayak adayları: q + λ·(q/p) sırasıyla ilk m at (λ 0: en olası atlar, λ büyük: en az oynanmış atlar). */
    static final double[] LAMBDAS = {0, 0.01, 0.03, 0.1, 0.3, 1, 3, 10, 1e6};

    public static final class Horse {
        public final String no;
        /** AGF payı (0–1). */
        public final double p;
        public final Double odds;
        /** Kazanma olasılığı (0–1). */
        double q;

        Horse(String no, double p, Double odds) {
            this.no = no;
            this.p = p;
            this.odds = odds;
        }
    }

    static final class Leg {
        final List<Horse> horses;
        final boolean fromOdds;

        Leg(List<Horse> horses, boolean fromOdds) {
            this.horses = horses;
            this.fromOdds = fromOdds;
        }
    }

    public static final class Inputs {
        /** 2.26: geçmiş sonuçlardan öğrenilen AGF düzeltmesi (ganyan yokken q ∝ p^alpha; 1: düzeltme yok). */
        public double alpha = 1;
        public double takeout = 0.27, tax = 0, unit = 1, devir = 0, pool = 0, maxCost = 0;
        /** Kasa (TL), Kelly çarpanı ve kupon başına kasanın en fazla payı (futboldaki sınırla aynı). */
        public double kasa = 0, km = 0.25, maxFraction = 0.05;
        /** Oynanmayacak günde tutma odaklı kuponun bütçesi (TL; tek maçtaki tutar: kasanın %3'ü). */
        public double budget = 0;
    }

    private static double num(String s) {
        return Double.parseDouble(s.replace(',', '.'));
    }

    /** "1:32 4:20 7:15" ya da "1:32:2.5" (no:AGF yüzdesi[:ganyan]) -> q'ya göre azalan atlar. */
    static Leg leg(String text) {
        return leg(text, 1);
    }

    /**
     * alpha ≠ 1 (2.26, öğrenilen): ganyan yoksa kazanma olasılığı AGF'nin üssüyle düzeltilir, q = p^alpha / Σ p^alpha
     * (alpha > 1: kalabalık favorileri az oynuyor, sürprizleri çok).
     */
    static Leg leg(String text, double alpha) {
        List<Horse> out = new ArrayList<>();
        for (String tok : String.valueOf(text == null ? "" : text).trim().split("[\\s;]+")) {
            String[] f = tok.split("[:=]");
            if (f.length < 2 || f.length > 3 || !f[0].matches("\\d{1,2}")) continue;
            try {
                double p = num(f[1].replace("%", "")) / 100;
                Double odds = f.length == 3 ? num(f[2]) : null;
                if (p > 0 && p <= 1) out.add(new Horse(String.valueOf(Integer.parseInt(f[0])), p, odds));
            } catch (NumberFormatException e) {
                // geçersiz parça atlanır
            }
        }
        double book = 0;
        boolean all = out.size() >= 2;
        for (Horse h : out) {
            if (h.odds != null && h.odds > 1) book += 1 / h.odds;
            else all = false;
        }
        boolean fromOdds = all && book >= MIN_BOOK && book <= MAX_BOOK;
        double pow = 0;
        for (Horse h : out) pow += Math.pow(h.p, alpha);
        for (Horse h : out) h.q = fromOdds ? 1 / h.odds / book : alpha == 1 ? h.p : Math.pow(h.p, alpha) / pow;
        out.sort(new Comparator<Horse>() {
            @Override
            public int compare(Horse a, Horse b) {
                return a.q != b.q ? Double.compare(b.q, a.q) : Double.compare(b.p, a.p);
            }
        });
        return new Leg(out, fromOdds);
    }

    /** Bir ayakta oynanacak at kümesi: seçilenlerin q toplamı (tutma), q/p toplamı (getiri), sayısı. */
    static final class Cand {
        final long mask;
        final int m;
        final double sq, sr;

        Cand(long mask, int m, double sq, double sr) {
            this.mask = mask;
            this.m = m;
            this.sq = sq;
            this.sr = sr;
        }
    }

    static List<Cand> candidates(final List<Horse> horses) {
        Map<Long, Cand> out = new LinkedHashMap<>();
        int n = Math.min(horses.size(), 60);
        for (final double lambda : LAMBDAS) {
            Integer[] idx = new Integer[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            Arrays.sort(idx, new Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    Horse x = horses.get(a), y = horses.get(b);
                    return Double.compare(y.q + lambda * y.q / y.p, x.q + lambda * x.q / x.p);
                }
            });
            long mask = 0;
            double sq = 0, sr = 0;
            for (int m = 1; m <= Math.min(n, MAX_PER_LEG); m++) {
                Horse h = horses.get(idx[m - 1]);
                mask |= 1L << Math.min(idx[m - 1], 62);
                sq += h.q;
                sr += h.q / h.p;
                if (!out.containsKey(mask)) out.put(mask, new Cand(mask, m, sq, sr));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Bir kuponun değerlendirmesi (büyük havuz yaklaşımı ya da kesin ikramiyeyle). */
    static final class Eval {
        double hit, roi, growth, score, cost, stake, x;
        long units, kat;
    }

    /** Getiri çarpanı: (1 − vergi) × (1 − kesinti + devir/havuz); havuz bilinmiyorsa devir hesaba katılmaz. */
    static double factor(Inputs in) {
        return (1 - in.tax) * (1 - in.takeout + (in.pool > 0 ? in.devir / in.pool : 0));
    }

    static Eval eval(double hit, double roi, long units, Inputs in) {
        Eval e = new Eval();
        e.hit = hit;
        e.roi = roi;
        e.units = units;
        e.cost = units * in.unit;
        if (units > MAX_UNITS) {
            e.score = -2;
            return e;
        }
        if (!(roi > 1) || !(hit > 0)) {
            e.score = -1 - (1 - roi);
            return e;
        }
        e.x = roi / hit; // tutarsa ortalama kaç katı
        double fStar = (roi - 1) / (e.x - 1), f = in.km * fStar;
        e.stake = f * in.kasa;
        long kat = (long) Math.floor(e.stake / e.cost + 1e-9);
        double room = Math.min(e.stake, in.maxFraction * in.kasa);
        if (in.maxCost > 0) room = Math.min(room, in.maxCost);
        kat = Math.min(kat, (long) Math.floor(room / e.cost + 1e-9));
        if (kat < 1) {
            e.score = -0.5 - (e.cost - room) / Math.max(in.kasa, 1); // kârlı ama kasaya göre büyük: küçüldükçe iyi
            return e;
        }
        e.kat = kat;
        double fa = kat * e.cost / in.kasa;
        e.growth = hit * Math.log(1 + fa * (e.x - 1)) + (1 - hit) * Math.log(1 - fa);
        e.score = e.growth;
        return e;
    }

    private static Eval evalChoice(List<List<Cand>> cands, int[] pick, Inputs in) {
        double hit = 1, r = 1;
        long units = 1;
        for (int k = 0; k < 6; k++) {
            Cand c = cands.get(k).get(pick[k]);
            hit *= c.sq;
            r *= c.sr;
            units *= c.m;
        }
        return eval(hit, factor(in) * r / units, units, in);
    }

    private static int find(List<Cand> cs, long mask) {
        for (int i = 0; i < cs.size(); i++) if (cs.get(i).mask == mask) return i;
        return 0;
    }

    /** Ayaklar üzerinde sırayla iyileştirme (birkaç başlangıçtan); en yüksek puanlı seçim. */
    static int[] search(List<List<Cand>> cands, List<Leg> legs, Inputs in) {
        List<int[]> starts = new ArrayList<>();
        int[] fav = new int[6], top2 = new int[6], value = new int[6];
        for (int k = 0; k < 6; k++) {
            List<Cand> cs = cands.get(k);
            fav[k] = find(cs, 1L);
            top2[k] = legs.get(k).horses.size() >= 2 ? find(cs, 3L) : fav[k];
            value[k] = 0;
            double best = -1;
            for (int i = 0; i < cs.size(); i++) {
                if (cs.get(i).m == 1 && cs.get(i).sr > best) {
                    best = cs.get(i).sr;
                    value[k] = i;
                }
            }
        }
        starts.add(fav);
        starts.add(top2);
        starts.add(value);
        int[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int[] s : starts) {
            int[] cur = s.clone();
            double score = evalChoice(cands, cur, in).score;
            for (int round = 0; round < 40; round++) {
                boolean improved = false;
                for (int k = 0; k < 6; k++) {
                    int keep = cur[k];
                    for (int i = 0; i < cands.get(k).size(); i++) {
                        cur[k] = i;
                        double sc = evalChoice(cands, cur, in).score;
                        if (sc > score + 1e-12) {
                            score = sc;
                            keep = i;
                            improved = true;
                        }
                    }
                    cur[k] = keep;
                }
                if (!improved) break;
            }
            if (score > bestScore) {
                bestScore = score;
                best = cur;
            }
        }
        return best;
    }

    private static List<Horse> chosen(Leg leg, Cand c) {
        List<Horse> out = new ArrayList<>();
        for (int i = 0; i < leg.horses.size(); i++) if ((c.mask & (1L << Math.min(i, 62))) != 0) out.add(leg.horses.get(i));
        return out;
    }

    /**
     * Havuz biliniyorsa kesin getiri (1 TL başına): kombinasyon kazanırsa birim başına N/(U·p + kat), kat bizim o
     * kombinasyondaki birimimiz; beklenen değer q ağırlıklı toplam, bölü kupon bedeli.
     */
    static double exactRoi(List<List<Horse>> pick, long units, long kat, Inputs in) {
        final double n = in.pool * (1 - in.takeout) + in.devir, u = in.pool / in.unit;
        final double[] gross = {0};
        walk(pick, 0, 1, 1, n, u, Math.max(1, kat), gross);
        return gross[0] * (1 - in.tax) / (units * in.unit);
    }

    private static void walk(List<List<Horse>> pick, int k, double q, double p, double n, double u, long kat, double[] gross) {
        if (k == pick.size()) {
            gross[0] += q * n / (u * p + kat);
            return;
        }
        for (Horse h : pick.get(k)) walk(pick, k + 1, q * h.q, p * h.p, n, u, kat, gross);
    }

    /** Kesin değerlendirme: önce 1 kat, Kelly daha çok kat veriyorsa o katla bir kez daha. */
    static Eval exactEval(List<List<Cand>> cands, List<Leg> legs, int[] pick, Inputs in) {
        double hit = 1;
        long units = 1;
        List<List<Horse>> hs = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            Cand c = cands.get(k).get(pick[k]);
            hit *= c.sq;
            units *= c.m;
            hs.add(chosen(legs.get(k), c));
        }
        if (units > MAX_EXACT) return eval(hit, 0, units, in);
        Eval e = eval(hit, exactRoi(hs, units, 1, in), units, in);
        if (e.kat > 1) {
            Eval x = eval(hit, exactRoi(hs, units, e.kat, in), units, in);
            if (x.kat >= 1) e = x;
        }
        return e;
    }

    /** Kesin hesapla birkaç tur ayak ayak iyileştirme (yaklaşık aramanın sonucundan başlayarak). */
    static int[] refine(List<List<Cand>> cands, List<Leg> legs, int[] start, Inputs in) {
        int[] cur = start.clone();
        double score = exactEval(cands, legs, cur, in).score;
        for (int round = 0; round < 6; round++) {
            boolean improved = false;
            for (int k = 0; k < 6; k++) {
                int keep = cur[k];
                for (int i = 0; i < cands.get(k).size(); i++) {
                    if (i == keep) continue;
                    cur[k] = i;
                    double sc = exactEval(cands, legs, cur, in).score;
                    if (sc > score + 1e-12) {
                        score = sc;
                        keep = i;
                        improved = true;
                    }
                }
                cur[k] = keep;
            }
            if (!improved) break;
        }
        return cur;
    }

    /**
     * 2.23: bütçeyle tutma olasılığı en yüksek kupon (her ayakta en olası atlardan, bedele göre tutmayı en çok artıran
     * at eklenir; ayak başına en çok MAX_PER_LEG at). Ayak başına seçilen at sayısı.
     */
    static int[] mostLikely(List<Leg> legs, long maxUnits) {
        int[] n = new int[6];
        double[] s = new double[6];
        long units = 1;
        for (int k = 0; k < 6; k++) {
            n[k] = 1;
            s[k] = legs.get(k).horses.get(0).q;
        }
        for (;;) {
            int best = -1;
            double gain = 0;
            for (int k = 0; k < 6; k++) {
                List<Horse> hs = legs.get(k).horses;
                if (n[k] >= Math.min(hs.size(), MAX_PER_LEG) || units / n[k] * (n[k] + 1) > maxUnits) continue;
                double g = Math.log((s[k] + hs.get(n[k]).q) / s[k]) / Math.log((n[k] + 1.0) / n[k]);
                if (g > gain) {
                    gain = g;
                    best = k;
                }
            }
            if (best < 0) return n;
            s[best] += legs.get(best).horses.get(n[best]).q;
            units = units / n[best] * (n[best] + 1);
            n[best]++;
        }
    }

    /**
     * Altılı planı: {play, legs: [{horses: [no], share, odds}], units, kat, unit, cost, stake, hit, roi, ev, ifHit,
     * oddsLegs, avg?, needDevir?, reason} ya da {error}. Tutarlar TL.
     */
    public static Map<String, Object> plan(List<String> legTexts, Inputs in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (legTexts == null || legTexts.size() < 6) {
            out.put("error", "6 ayağın AGF'si gerekli.");
            return out;
        }
        List<Leg> legs = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            Leg l = leg(legTexts.get(k), in.alpha);
            if (l.horses.isEmpty()) {
                out.put("error", (k + 1) + ". ayağın AGF'si okunamadı.");
                return out;
            }
            legs.add(l);
        }
        if (!(in.unit > 0)) {
            out.put("error", "Birim fiyatı gir.");
            return out;
        }
        List<List<Cand>> cands = new ArrayList<>();
        int oddsLegs = 0;
        for (Leg l : legs) {
            cands.add(candidates(l.horses));
            if (l.fromOdds) oddsLegs++;
        }
        int[] best = search(cands, legs, in);
        List<List<Horse>> pick = new ArrayList<>();
        double r = 1;
        for (int k = 0; k < 6; k++) {
            Cand c = cands.get(k).get(best[k]);
            pick.add(chosen(legs.get(k), c));
            r *= c.sr;
        }
        Eval e = evalChoice(cands, best, in);
        if (in.pool > 0) { // havuz biliniyor: kendi kuponumuzun ikramiyeyi bölmesi dahil kesin hesapla yerel iyileştirme
            best = refine(cands, legs, best, in);
            pick.clear();
            r = 1;
            for (int k = 0; k < 6; k++) {
                Cand c = cands.get(k).get(best[k]);
                pick.add(chosen(legs.get(k), c));
                r *= c.sr;
            }
            e = exactEval(cands, legs, best, in);
        }
        boolean play = e.kat >= 1 && e.roi > 1;
        double kellyRoi = e.roi, kellyStake = e.stake, budget = in.maxCost > 0 ? in.maxCost : in.budget;
        boolean tutma = !play && budget >= in.unit;
        if (tutma) { // oynanmayacak günde tek atlı kupon yerine bütçeyle tutma olasılığı en yüksek, alternatifli kupon
            int[] n = mostLikely(legs, (long) Math.floor(budget / in.unit + 1e-9));
            pick.clear();
            r = 1;
            double hit = 1;
            long units = 1;
            for (int k = 0; k < 6; k++) {
                List<Horse> hs = new ArrayList<>(legs.get(k).horses.subList(0, n[k]));
                double sq = 0, sr = 0;
                for (Horse h : hs) {
                    sq += h.q;
                    sr += h.q / h.p;
                }
                pick.add(hs);
                hit *= sq;
                r *= sr;
                units *= n[k];
            }
            double roi = in.pool > 0 && units <= MAX_EXACT ? exactRoi(pick, units, 1, in) : factor(in) * r / units;
            e = eval(hit, roi, units, in);
            e.kat = 1;
        }
        List<Object> legsOut = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            Map<String, Object> l = new LinkedHashMap<>();
            List<Object> nos = new ArrayList<>();
            double share = 0, agf = 0;
            for (Horse h : pick.get(k)) {
                nos.add(h.no);
                share += h.q;
                agf += h.p;
            }
            l.put("horses", nos);
            l.put("share", share);
            l.put("agf", agf);
            l.put("odds", legs.get(k).fromOdds);
            legsOut.add(l);
        }
        out.put("play", play);
        if (tutma) {
            out.put("mode", "tutma");
            out.put("budget", budget);
        }
        out.put("legs", legsOut);
        out.put("units", e.units);
        out.put("kat", e.kat);
        out.put("unit", in.unit);
        out.put("cost", e.cost);
        out.put("stake", play ? e.kat * e.cost : 0.0);
        out.put("kelly", e.stake);
        out.put("hit", e.hit);
        out.put("roi", e.roi);
        out.put("ev", play ? e.kat * e.cost * (e.roi - 1) : e.cost * (e.roi - 1));
        out.put("ifHit", e.hit > 0 ? (play ? e.kat : 1) * e.cost * e.roi / e.hit : 0.0);
        out.put("oddsLegs", (long) oddsLegs);
        // 2.28: kâr ve tutma olasılığı tek ölçüde: bu kuponla kasanın beklenen log büyümesi (tutar = önerilen ya da 1 kat)
        double stakeTl = (play ? e.kat : 1) * e.cost;
        if (in.kasa > 0 && e.hit > 0 && stakeTl < in.kasa) {
            double f = stakeTl / in.kasa, x = e.roi / e.hit;
            out.put("growth", e.hit * Math.log(1 + f * (x - 1)) + (1 - e.hit) * Math.log(1 - f));
        }
        if (in.pool > 0) {
            out.put("avg", factor(in));
            if (!play) {
                double need = in.pool * (e.units / ((1 - in.tax) * r) - (1 - in.takeout));
                out.put("needDevir", Math.max(0, need));
            }
        }
        String why;
        if (play) {
            why = oddsLegs > 0 && (in.pool <= 0 || factor(in) <= 1)
                    ? "Ganyan oranlarına göre seçilen atlar altılıda az oynanmış; ortalamada kâr."
                    : "Devir havuzu kesintiyi aşıyor; ortalamada kâr.";
        } else if (kellyRoi > 1) {
            why = "Ortalamada kârlı ama kupon kasana göre büyük (Kelly payı " + Fmt.tl(Math.round(kellyStake * 100)) + ").";
        } else {
            why = "Ortalamada zarar: " + (tutma ? "bu kupon" : "en iyi kupon bile") + " her 100 TL'nin ortalama " + Fmt.num(e.roi * 100, 0)
                    + " TL'sini geri getirir.";
            if (oddsLegs == 0) why += " Ganyan oranı okunamadı: AGF tek başına kalabalığın tahmini, kâr yalnızca devirle mümkün"
                    + (in.pool > 0 ? "." : " (devir ve havuzu gir).");
        }
        out.put("reason", why);
        return out;
    }

    /**
     * 2.24: günün en mantıklı altılısı (indeks; yoksa −1). Önce oynanabilir olanlar (beklenen kazancı büyük olan), sonra
     * ortalama getirisi belirgin (2 puandan çok) yüksek olan, sonra aynı bütçeyle tutma olasılığı yüksek olan: getiri
     * aynıysa favorileri net, daha tahmin edilebilir koşular. Hatalı planlar (null ya da error) dışarıda.
     */
    public static int best(List<Map<String, Object>> plans) {
        int best = -1;
        for (int i = 0; i < plans.size(); i++) {
            Map<String, Object> p = plans.get(i);
            if (p == null || p.get("error") != null || p.get("legs") == null) continue;
            if (best < 0 || better(p, plans.get(best))) best = i;
        }
        return best;
    }

    /**
     * Sıralama (2.28: kâr ve tutma birlikte): önce oynanabilir olan; sonra kasanın beklenen log büyümesi (Kelly ölçütü:
     * getiri yüksekse ve aynı getiride tutma olasılığı yüksekse büyük); hesaplanamıyorsa getiri, sonra tutma olasılığı.
     */
    static boolean better(Map<String, Object> a, Map<String, Object> b) {
        boolean pa = Json.bool(a, "play", false), pb = Json.bool(b, "play", false);
        if (pa != pb) return pa;
        Double ga = Json.num(a, "growth"), gb = Json.num(b, "growth");
        if (ga != null && gb != null && Math.abs(ga - gb) > 1e-12) return ga > gb;
        if (pa) return Json.dbl(a, "ev", 0) > Json.dbl(b, "ev", 0);
        double ra = Json.dbl(a, "roi", 0), rb = Json.dbl(b, "roi", 0);
        if (Math.abs(ra - rb) > 0.02) return ra > rb;
        return Json.dbl(a, "hit", 0) > Json.dbl(b, "hit", 0);
    }

    /** Planların sırası (en mantıklı önce); null planlar sonda. */
    public static List<Integer> order(final List<Map<String, Object>> plans) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) idx.add(i);
        idx.sort(new Comparator<Integer>() {
            @Override
            public int compare(Integer x, Integer y) {
                Map<String, Object> a = plans.get(x), b = plans.get(y);
                boolean va = a != null && a.get("error") == null && a.get("legs") != null, vb = b != null && b.get("error") == null && b.get("legs") != null;
                if (va != vb) return va ? -1 : 1;
                if (!va) return Integer.compare(x, y);
                return better(a, b) ? -1 : better(b, a) ? 1 : Integer.compare(x, y);
            }
        });
        return idx;
    }
}
