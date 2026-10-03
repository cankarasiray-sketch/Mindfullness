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

    /** Puanlamada kullanılan kasa payı (günün seçimi temel tutarı / kasa; kasa boşsa %1). */
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

    private static boolean eligible(String kickoff, double odds, double p, int mbs, double minProb, Settings cfg, Instant now) {
        if (kickoff == null || !(p > 0 && p < 1) || mbs > 1) return false;
        if (p < minProb || odds < MIN_ODDS) return false;
        Instant ko = Instant.parse(kickoff);
        return !ko.isBefore(now.plusSeconds(LEAD_S)) && !ko.isAfter(now.plusSeconds(Math.round(cfg.windowHours * 3600)));
    }

    /**
     * Tek oynanabilen (MBS 1), en az minProb tutan, başlamasına 30 dk–karar penceresi kalan tüm seçimler
     * (her pazar ve her oran; aynı seçimde normal oran ile Zirve Oran'dan iyi olanı), adil orana yakınlığa
     * (beklenen değer) göre azalan; eşitlikte sık tutan önce. Model pazarlarında olasılık güvenlik payı
     * düşülmüş gelir (Models.fair) ve "model" işaretlidir.
     */
    public static List<Map<String, Object>> singles(List<Object> fairs, Map<String, Object> zirve, Settings cfg, double minProb, Instant now) {
        return singles(fairs, zirve, cfg, minProb, DEFAULT_SHARE, now);
    }

    /** f: denge puanında kasa payı (Pick.fraction). Sıra: denge puanı (2.13), eşitlikte sık tutan. */
    public static List<Map<String, Object>> singles(List<Object> fairs, Map<String, Object> zirve, Settings cfg, double minProb, double f, Instant now) {
        Map<String, Map<String, Object>> best = new LinkedHashMap<>(); // ref|m|o -> en iyi oran
        for (Object o : fairs == null ? new ArrayList<Object>() : fairs) {
            Map<String, Object> r = Json.obj(o);
            if (r == null) continue;
            for (Object so : Json.arr(r.get("sel"))) {
                Map<String, Object> s = Json.obj(so);
                if (s == null) continue;
                double odds = Json.dbl(s, "i", 0), p = Json.dbl(s, "p", 0);
                int mbs = (int) Json.lng(s, "mbs", 1);
                if (!eligible(Json.str(r, "kickoff"), odds, p, mbs, minProb, cfg, now)) continue;
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
                if (!eligible(Json.str(z, "kickoff"), odds, p, mbs, minProb, cfg, now)) continue;
                String key = Json.str(z, "ref") + "|" + Json.str(z, "m") + "|" + Json.str(z, "o");
                Map<String, Object> old = best.get(key);
                if (old != null && Json.dbl(old, "odds", 0) >= odds) continue;
                best.put(key, entry(z, "zirve", Json.str(z, "m"), Json.str(z, "o"), Json.str(z, "label"), odds, Json.dbl(z, "val", 0), p, mbs));
            }
        }
        List<Map<String, Object>> all = new ArrayList<>(best.values());
        for (Map<String, Object> e : all) scored(e, f);
        Collections.sort(all, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                int c = Double.compare(Json.dbl(b, "score", 0), Json.dbl(a, "score", 0));
                return c != 0 ? c : Double.compare(Json.dbl(b, "p", 0), Json.dbl(a, "p", 0));
            }
        });
        return all;
    }

    /** Arayüzdeki "Tek maç fırsatları" listesi: en az %50 tutan ilk 100 seçim, günün seçimi tutarıyla. */
    public static List<Object> list(List<Object> fairs, Map<String, Object> zirve, Settings cfg, long balance, Instant now) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> e : singles(fairs, zirve, cfg, LIST_MIN_PROB, fraction(cfg, balance), now)) {
            if (out.size() >= LIST_SIZE) break;
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
     * yüksek olasılık katmanı: en az cfg.pickHighProb tutanların adil orana en yakını ("high"; ana seçim
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
        List<Map<String, Object>> all = singles(fairs, zirve, cfg, BANDS[0], fraction(cfg, balance), now);
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
        return "Günün seçimi: " + Fmt.localTime(Json.str(p, "kickoff")) + " " + Json.str(p, "home") + " – " + Json.str(p, "away") + " · "
                + Json.str(p, "label") + " @ " + Fmt.odds(Json.dbl(p, "odds", 0)) + ("zirve".equals(Json.str(p, "source")) ? " (Zirve Oran)" : "")
                + " · tutma %" + Fmt.num(Json.dbl(p, "p", 0) * 100, 0) + " · adil " + Fmt.odds(Json.dbl(p, "fair", 0))
                + " · beklenen " + Fmt.pct(Json.dbl(p, "ev", 0), true)
                + (Json.lng(p, "stake", 0) > 0 ? " · " + Fmt.tl(Json.lng(p, "stake", 0)) + " (tutarsa " + Fmt.tl(Json.lng(p, "win", 0)) + ")" : "");
    }
}
