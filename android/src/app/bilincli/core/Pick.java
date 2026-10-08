package app.bilincli.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Günün seçimi (2.9) ve tek maç fırsatları (2.10): her gün tek seçim önerisi. Değerli (adil oranı
 * geçen) seçim yoksa da oynamak isteyen için: tek maç oynanabilen (MBS 1), tutma olasılığı en az
 * cfg.pickMinProb (ve ikinci katman cfg.pickHighProb), her pazar ve her oran (1,05 üstü), en az 30 dk
 * sonra ve karar penceresi içinde başlayan seçimler arasından beklenen değeri en yüksek, yani adil orana
 * en yakın olan. Normal iddaa oranları (son taramanın
 * adil oran tablosu) ve Bilyoner Zirve Oran birlikte değerlendirilir. Tutar küçük ve sabittir (Kelly değil):
 * beklenen değer çoğu gün eksidir ve açıkça yazılır.
 */
public final class Pick {
    private Pick() {}

    /** Seçim en az bu kadar sonra başlamalı (oynamaya vakit kalsın). */
    static final long LEAD_S = 30 * 60;
    /** Tutar ayarı 0 ise kasanın bu payı (10 TL'ye aşağı yuvarlı, en az 10 TL). */
    static final double DEFAULT_SHARE = 0.03; // 2.15.2: kullanıcı isteğiyle %1 -> %3

    /** Günün seçiminin temel tutarı (kuruş). */
    public static long stake(Settings cfg, long balance) {
        if (cfg.pickStake > 0) return Math.round(cfg.pickStake * 100);
        if (balance <= 0) return 0;
        return Math.max(1000, (long) (balance * DEFAULT_SHARE) / 1000 * 1000);
    }

    /**
     * Tutar (kuruş): değerliyse (beklenen ≥ cfg.minLegEv) temel tutar ile Kelly tutarının büyüğü; beklenen
     * kayıp cfg.pickMaxLoss'a kadarsa (adil oranın %15 altına kadar) temel tutar; daha kötüyse 0 (oynama).
     * 2.15.1: kullanıcı isteğiyle yarım tutar kademesi kaldırıldı, sınıra kadar hep tam tutar.
     */
    public static long stakeFor(double p, double odds, Settings cfg, long balance) {
        long base = stake(cfg, balance);
        if (base <= 0) return 0;
        double ev = p * odds - 1;
        if (ev >= cfg.minLegEv) return Math.max(base, (long) (balance * Engine.stakeFraction(p, odds, cfg)) / 1000 * 1000);
        // 1e-9: tam sınırdaki oran (ör. adil 2,00 / 1,70 = −%15) kayan nokta yüzünden dışarıda kalmasın
        return ev >= -cfg.pickMaxLoss - 1e-9 ? base : 0;
    }

    /**
     * Denge puanı: tutma olasılığı ile ödemeyi birlikte tartan beklenen log büyüme (Kelly ölçütü), kasanın
     * f payı oynanırken TL başına. Beklenen değer aynıysa sık tutan, tutma aynıysa ödemesi iyi olan öne geçer;
     * uzun vadede kasayı en çok büyüten (en az küçülten) seçim en yüksek puanı alır.
     */
    static double score(double p, double odds, double f) {
        f = Math.max(0.002, Math.min(0.2, f));
        return (p * Math.log(1 + f * (odds - 1)) + (1 - p) * Math.log(1 - f)) / f;
    }

    /**
     * Fırsat sıralaması (2.15.4, kullanıcı isteği: kâr amaçlı oran ve tutma olasılığı öncelikli). Negatifse a önce.
     * 1) kârlı olanlar (beklenen ≥ 0: oran adil oranı geçiyor) en üstte; 2) sonra denge puanı: kâr ile tutma
     * olasılığı birlikte (kasanın f payı oynanırken beklenen log büyüme); 3) eşitlikte sık tutan.
     * Tek maç fırsatları, günün seçimi, Değerli oranlar ve Zirve Oran listesi bu sırayla dizilir.
     */
    public static int rank(double evA, double pA, double oddsA, double evB, double pB, double oddsB, double f) {
        return rankScored(evA, score(pA, oddsA, f), pA, evB, score(pB, oddsB, f), pB);
    }

    /** {@link #rank} denge puanı önceden hesaplanmışken (2.16: uzun listede her karşılaştırmada logaritma yok). */
    static int rankScored(double evA, double scoreA, double pA, double evB, double scoreB, double pB) {
        boolean ga = evA >= 0, gb = evB >= 0;
        if (ga != gb) return ga ? -1 : 1;
        int c = Double.compare(scoreB, scoreA);
        return c != 0 ? c : Double.compare(pB, pA);
    }

    /** Puanlamada kullanılan kasa payı (günün seçimi temel tutarı / kasa; kasa boşsa %3). */
    static double fraction(Settings cfg, long balance) {
        long base = stake(cfg, balance);
        return balance > 0 && base > 0 ? (double) base / balance : DEFAULT_SHARE;
    }

    private static Map<String, Object> entry(Map<String, Object> row, String source, String m, String o, String label,
                                             double odds, double normal, double p, int mbs) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", source);
        e.put("ref", Json.str(row, "ref"));
        e.put("m", m);
        e.put("o", o);
        e.put("label", label != null ? label : Models.outcomeLabel(m, o));
        e.put("home", Json.str(row, "home"));
        e.put("away", Json.str(row, "away"));
        e.put("kickoff", Json.str(row, "kickoff"));
        e.put("league", Json.str(row, "league"));
        e.put("code", Json.str(row, "code"));
        e.put("sport", Json.str(row, "sport")); // sanal takipte sonuç için
        e.put("sref", Json.str(row, "sref"));
        e.put("odds", odds);
        if (normal > 1 && Math.abs(normal - odds) > 1e-9) e.put("normalOdds", normal);
        e.put("p", p);
        e.put("fair", 1 / p);
        e.put("ev", p * odds - 1);
        e.put("mbs", (long) mbs);
        return e;
    }

    private static void scored(Map<String, Object> e, double f) {
        e.put("score", score(Json.dbl(e, "p", 0), Json.dbl(e, "odds", 1), f));
    }

    /** Tek maç seçimlerinde en düşük oran (2.10: her oran değerlendirilir; 1,05 altı anlamsız). */
    static final double MIN_ODDS = 1.05;
    /** Tek maç fırsatları listesi: en az bu tutma olasılığı ve en fazla bu kadar satır. */
    public static final double LIST_MIN_PROB = 0.50;
    static final int LIST_SIZE = 100; // %65+ gibi süzgeçlerde de liste dolsun
    /**
     * 2.15.6: ilk 100'e girmese de her bahis türünün (MS, ÇŞ, Alt/Üst, KG, handikap, takım golü, basketbol)
     * en iyi bu kadar seçimi listede olur; arayüzdeki bahis türü süzgecinde MS 2 ya da 1,5 Üst kaybolmasın.
     */
    static final int PER_GROUP = 25;

    /** Bahis türü grubu (tek maç listesi süzgeci; arayüzde aynı eşleme). */
    public static String group(String m) {
        if ("MS".equals(m) || "CS".equals(m) || "KG".equals(m)) return m;
        if ("AU25".equals(m) || m.startsWith("AU@")) return "AU";
        if (m.startsWith("HMS@")) return "HMS";
        if (m.startsWith("EVG@") || m.startsWith("DEPG@")) return "TG";
        return "BASKET"; // BS, BT@, BH@
    }

    private static boolean eligible(String kickoff, double odds, double p, int mbs, double minProb, Settings cfg, double hours, Instant now) {
        if (kickoff == null || !(p > 0 && p < 1) || mbs > 1) return false;
        if (p < minProb || odds < Math.max(MIN_ODDS, cfg.pickMinOdds)) return false; // 2.15.6: ayardan (varsayılan 1,18)
        Instant ko = Instant.parse(kickoff);
        return !ko.isBefore(now.plusSeconds(LEAD_S)) && !ko.isAfter(now.plusSeconds(Math.round(hours * 3600)));
    }

    /**
     * Tek oynanabilen (MBS 1), en az minProb tutan, başlamasına 30 dk–karar penceresi kalan tüm seçimler
     * (her pazar ve her oran; aynı seçimde normal oran ile Zirve Oran'dan iyi olanı), {@link #rank} sırasıyla:
     * önce kârlı, sonra denge puanı, eşitlikte sık tutan. Model pazarlarında olasılık güvenlik payı
     * düşülmüş gelir (Models.fair) ve "model" işaretlidir.
     */
    public static List<Map<String, Object>> singles(List<Object> fairs, Map<String, Object> zirve, Settings cfg, double minProb, Instant now) {
        return singles(fairs, zirve, cfg, minProb, DEFAULT_SHARE, now);
    }

    /** f: denge puanında kasa payı (Pick.fraction). Sıra: {@link #rank} (kârlı önce, sonra denge puanı, sonra sık tutan). */
    public static List<Map<String, Object>> singles(List<Object> fairs, Map<String, Object> zirve, Settings cfg, double minProb, double f, Instant now) {
        return singles(fairs, zirve, cfg, minProb, f, cfg.windowHours, now);
    }

    /** hours: başlangıca en fazla bu kadar saat kalan maçlar (günün seçimi: windowHours; Fırsatlar: scanHours, 2.15.7). */
    static List<Map<String, Object>> singles(List<Object> fairs, Map<String, Object> zirve, Settings cfg, double minProb, double f,
                                             double hours, Instant now) {
        Map<String, Map<String, Object>> best = new LinkedHashMap<>(); // ref|m|o -> en iyi oran
        for (Object o : fairs == null ? new ArrayList<Object>() : fairs) {
            Map<String, Object> r = Json.obj(o);
            if (r == null) continue;
            for (Object so : Json.arr(r.get("sel"))) {
                Map<String, Object> s = Json.obj(so);
                if (s == null) continue;
                double odds = Json.dbl(s, "i", 0), p = Json.dbl(s, "p", 0);
                int mbs = (int) Json.lng(s, "mbs", 1);
                if (!eligible(Json.str(r, "kickoff"), odds, p, mbs, minProb, cfg, hours, now)) continue;
                Map<String, Object> e = entry(r, "iddaa", Json.str(s, "m"), Json.str(s, "o"), Json.str(s, "label"), odds, 0, p, mbs);
                if (Json.bool(s, "model", false)) e.put("model", true);
                best.put(Json.str(r, "ref") + "|" + Json.str(s, "m") + "|" + Json.str(s, "o"), e);
            }
        }
        if (zirve != null) {
            for (Object o : Json.arr(zirve.get("rows"))) {
                Map<String, Object> z = Json.obj(o);
                String st = Json.str(z, "status");
                // adil oranı olan ve Bilyoner'in normal oranı taramayla tutarlı satırlar
                if (z == null || !("oyna".equals(st) || "oynama".equals(st) || "dusuk".equals(st)) || Json.str(z, "ref") == null) continue;
                double odds = Json.dbl(z, "tval", 0), p = Json.dbl(z, "p", 0);
                int mbs = (int) Json.lng(z, "mbs", 1);
                if (!eligible(Json.str(z, "kickoff"), odds, p, mbs, minProb, cfg, hours, now)) continue;
                String key = Json.str(z, "ref") + "|" + Json.str(z, "m") + "|" + Json.str(z, "o");
                Map<String, Object> old = best.get(key);
                if (old != null && Json.dbl(old, "odds", 0) >= odds) continue;
                Map<String, Object> e = entry(z, "zirve", Json.str(z, "m"), Json.str(z, "o"), Json.str(z, "label"), odds, Json.dbl(z, "val", 0), p, mbs);
                e.put("zstatus", st); // "oyna" ise Zirve bildirimi zaten gider (profitNotice tekrarlamaz)
                best.put(key, e);
            }
        }
        List<Map<String, Object>> all = new ArrayList<>(best.values());
        for (Map<String, Object> e : all) scored(e, f);
        Collections.sort(all, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return rankScored(Json.dbl(a, "ev", 0), Json.dbl(a, "score", 0), Json.dbl(a, "p", 0),
                        Json.dbl(b, "ev", 0), Json.dbl(b, "score", 0), Json.dbl(b, "p", 0)); // puan scored() ile bir kez
            }
        });
        return all;
    }

    /**
     * Arayüzdeki "Tek maç fırsatları" listesi: en az %50 tutan ilk 100 seçim ve ayrıca her bahis türünün en iyi
     * PER_GROUP seçimi (sıra {@link #rank}), günün seçimi tutarıyla.
     */
    public static List<Object> list(List<Object> fairs, Map<String, Object> zirve, Settings cfg, long balance, Instant now) {
        return listOf(singles(fairs, zirve, cfg, LIST_MIN_PROB, fraction(cfg, balance), cfg.scanHours(), now), cfg, balance);
    }

    /**
     * Günün seçimi, tek maç listesi ve denge tablosu tek seferde: {seçim, liste, bantlar} (2.16: ekran her
     * yenilendiğinde Fırsatlar penceresinin seçimleri bir kez kurulur; sonuç list/bands/choose ile aynı).
     */
    public static Object[] all(List<Object> fairs, Map<String, Object> zirve, Settings cfg, long balance, Instant now) {
        List<Map<String, Object>> wide = singles(fairs, zirve, cfg, LIST_MIN_PROB, fraction(cfg, balance), cfg.scanHours(), now);
        return new Object[] {choose(fairs, zirve, cfg, balance, now), listOf(wide, cfg, balance), bandsOf(wide)};
    }

    private static List<Object> listOf(List<Map<String, Object>> sorted, Settings cfg, long balance) {
        List<Object> out = new ArrayList<>();
        Map<String, Integer> per = new java.util.HashMap<>();
        for (Map<String, Object> e : sorted) {
            String g = group(Json.str(e, "m"));
            int n = per.containsKey(g) ? per.get(g) : 0;
            if (out.size() >= LIST_SIZE && n >= PER_GROUP) continue;
            per.put(g, n + 1);
            out.add(withStake(e, cfg, balance));
        }
        return out;
    }

    private static Map<String, Object> withStake(Map<String, Object> e, Settings cfg, long balance) {
        Map<String, Object> pick = new LinkedHashMap<>(e);
        double ev = Json.dbl(pick, "ev", 0);
        long stake = stakeFor(Json.dbl(pick, "p", 0), Json.dbl(pick, "odds", 1), cfg, balance);
        if (stake == 0 && stake(cfg, balance) > 0) pick.put("skip", true); // beklenen kayıp sınırı aşıyor: oynama
        pick.put("stake", stake);
        pick.put("win", Math.round(stake * Json.dbl(pick, "odds", 1)));
        pick.put("expected", Math.round(stake * ev));
        pick.put("monthly", Math.round(30 * stake * ev));
        pick.put("value", ev >= cfg.minLegEv); // gerçekten değerli (nadir)
        return pick;
    }

    /**
     * fairs: Radar adil oran tablosu ({ref, home, away, kickoff, league, code, sel:[{m, o, label, p, i, mbs}]}).
     * zirve: Zirve görünümü (null olabilir; satırlar {ref, m, o, label, p, tval, val, mbs, kickoff, status}).
     * Seçim yoksa null. Dönen: kaynak, maç, seçim, oran, adil oran, tutma olasılığı, beklenen değer, tutar,
     * tutarsa ödeme, beklenen sonuç, her gün oynanırsa aylık beklenen, en fazla 3 alternatif ve (2.10)
     * yüksek olasılık katmanı: en az cfg.pickHighProb tutanların en iyisi ({@link #rank}; "high"; ana seçim
     * zaten o katmandaysa "isHigh").
     */
    public static Map<String, Object> choose(List<Object> fairs, Map<String, Object> zirve, Settings cfg, long balance, Instant now) {
        if (!cfg.dailyPick) return null;
        List<Map<String, Object>> all = singles(fairs, zirve, cfg, cfg.pickMinProb, fraction(cfg, balance), now);
        if (all.isEmpty()) return null;
        Map<String, Object> pick = withStake(all.get(0), cfg, balance);
        List<Object> alt = new ArrayList<>();
        for (int i = 1; i < all.size() && alt.size() < 3; i++) alt.add(all.get(i));
        pick.put("alternatives", alt);
        pick.put("candidates", (long) all.size());
        pick.put("maxLoss", cfg.pickMaxLoss);
        int highCount = 0;
        Map<String, Object> high = null;
        for (Map<String, Object> e : all) {
            if (Json.dbl(e, "p", 0) < cfg.pickHighProb) continue;
            highCount++;
            if (high == null) high = e;
        }
        pick.put("highCount", (long) highCount);
        if (high == all.get(0)) pick.put("isHigh", true);
        else if (high != null) pick.put("high", withStake(high, cfg, balance));
        return pick;
    }

    /** Tutma olasılığı bantları (2.13): her bantta seçim sayısı ve en iyi beklenen değer. */
    static final double[] BANDS = {0.50, 0.60, 0.65, 0.70, 0.80, 1.01};

    /**
     * Bugünün tek maç seçimlerinde olasılık bandına göre iddaa'nın kesintisi: {from, to, n, bestEv, bestScore}.
     * Hangi tutma aralığında kâra (en az kayba) en yakın seçim olduğunu gösterir.
     */
    public static List<Object> bands(List<Object> fairs, Map<String, Object> zirve, Settings cfg, long balance, Instant now) {
        return bandsOf(singles(fairs, zirve, cfg, BANDS[0], fraction(cfg, balance), cfg.scanHours(), now));
    }

    /** all: en az BANDS[0] (= LIST_MIN_PROB) tutan, sıralı seçimler. */
    private static List<Object> bandsOf(List<Map<String, Object>> all) {
        List<Object> out = new ArrayList<>();
        for (int b = 0; b + 1 < BANDS.length; b++) {
            long n = 0;
            Map<String, Object> best = null;
            for (Map<String, Object> e : all) {
                double p = Json.dbl(e, "p", 0);
                if (p < BANDS[b] || p >= BANDS[b + 1]) continue;
                n++;
                if (best == null) best = e; // liste puana göre sıralı
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("from", BANDS[b]);
            r.put("to", Math.min(1.0, BANDS[b + 1]));
            r.put("n", n);
            if (best != null) {
                r.put("bestEv", Json.dbl(best, "ev", 0));
                r.put("bestScore", Json.dbl(best, "score", 0));
                r.put("label", Json.str(best, "home") + " – " + Json.str(best, "away") + " · " + Json.str(best, "label"));
            }
            out.add(r);
        }
        return out;
    }

    /** Sanal takip kaydı için seçim (Engine.selection biçimi). */
    public static Map<String, Object> selection(Map<String, Object> p) {
        return Engine.selection(Json.str(p, "home"), Json.str(p, "away"), Json.str(p, "kickoff"), Json.str(p, "sport"), Json.str(p, "sref"),
                Json.str(p, "m"), Json.str(p, "o"), Json.dbl(p, "odds", 0), Json.dbl(p, "p", 0));
    }

    /** Bildirim ve özet satırı: "Günün seçimi: Ev – Dep · MS 1 @ 1,53 (Zirve) · tutma %60 · adil 1,67 · beklenen −%8,4 · 50,00 TL". */
    public static String line(Map<String, Object> p) {
        if (p == null) return null;
        return "Günün seçimi: " + body(p);
    }

    private static String body(Map<String, Object> p) {
        return Fmt.localTime(Json.str(p, "kickoff")) + " " + Json.str(p, "home") + " – " + Json.str(p, "away") + " · "
                + Json.str(p, "label") + " @ " + Fmt.odds(Json.dbl(p, "odds", 0)) + ("zirve".equals(Json.str(p, "source")) ? " (Zirve Oran)" : "")
                + " · tutma %" + Fmt.num(Json.dbl(p, "p", 0) * 100, 0) + " · adil " + Fmt.odds(Json.dbl(p, "fair", 0))
                + " · beklenen " + Fmt.pct(Json.dbl(p, "ev", 0), true)
                + (Json.lng(p, "stake", 0) > 0 ? " · " + Fmt.tl(Json.lng(p, "stake", 0)) + " (tutarsa " + Fmt.tl(Json.lng(p, "win", 0)) + ")" : "");
    }

    /** Kârlı seçim bildiriminde hatırlanan en fazla anahtar. */
    static final int MAX_NOTIFIED = 300;

    /**
     * Kârlı seçim bildirimi (2.15.5): tek maç listesinde (Pick.list) oranı adil oranı geçen (beklenen ≥ 0) ve bu
     * oranla daha önce bildirilmemiş seçimler. Zirve Oran'ın "oyna" satırları Zirve bildirimiyle zaten gelir,
     * tekrarlanmaz. notified: bildirilen anahtarlar (ref|pazar|sonuç@oran), yerinde güncellenir; oran
     * yükselirse yeniden bildirilir. Yeni yoksa null; varsa {başlık, metin}.
     */
    public static String[] profitNotice(List<Object> list, List<Object> notified) {
        List<Map<String, Object>> fresh = new ArrayList<>();
        for (Object o : list == null ? new ArrayList<Object>() : list) {
            Map<String, Object> e = Json.obj(o);
            if (e == null || Json.dbl(e, "ev", -1) < 0 || "oyna".equals(Json.str(e, "zstatus"))) continue;
            String key = Json.str(e, "ref") + "|" + Json.str(e, "m") + "|" + Json.str(e, "o") + "@" + Json.dbl(e, "odds", 0);
            if (notified.contains(key)) continue;
            notified.add(key);
            fresh.add(e);
        }
        while (notified.size() > MAX_NOTIFIED) notified.remove(0);
        if (fresh.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> e : fresh.subList(0, Math.min(5, fresh.size()))) b.append(body(e)).append('\n');
        if (fresh.size() > 5) b.append('+').append(fresh.size() - 5).append(" seçim daha: Fırsatlar → Tek maç fırsatları.\n");
        b.append("Oran adil oranı geçiyor (beklenen artıda). Oynamadan önce oranın Bilyoner'de hâlâ aynı olduğunu kontrol et.");
        Map<String, Object> first = fresh.get(0);
        String title = fresh.size() == 1 ? "Kârlı seçim · " + Json.str(first, "home") + " – " + Json.str(first, "away")
                : fresh.size() + " kârlı seçim";
        return new String[] {title, b.toString()};
    }
}
