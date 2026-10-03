package app.bilincli.core;

import app.bilincli.core.Models.ScoreResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sanal takip: pas günlerinde "en yakın seçim" (normal iddaa oranlarında ve Zirve Oran'da ayrı ayrı)
 * 100 TL'lik sanal bahis olarak kaydedilir ve maç bitince sonuçlandırılır. Kasaya, kupon
 * istatistiklerine ve kanıt korumasına dokunmaz; "avantajı yok ama tutuyor" düşüncesini gerçek
 * sonuçlarla, para riske atmadan sınamak içindir. Günde tür başına bir kayıt: seçim gün içinde
 * değişirse, önceki seçimin maçı başlamadıysa yenisi yerine geçer (sonucu bilinmeden; seçim yanlılığı yok).
 */
public final class Virtual {
    /** Sanal bahis tutarı (kuruş): 100 TL. */
    public static final long STAKE = 10000;
    static final int KEEP = 500;
    public static final String NORMAL = "normal", ZIRVE = "zirve";
    /** Günün seçimi (2.9): her gün önerilen seçim, oynansın oynanmasın. */
    public static final String PICK = "secim";

    private final Ledger.Storage storage;
    private final List<Map<String, Object>> rows = new ArrayList<>();

    public Virtual(Ledger.Storage storage) {
        this.storage = storage;
        String raw = storage.read();
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            for (Object o : Json.arr(Json.parseObject(raw).get("rows"))) {
                Map<String, Object> r = Json.obj(o);
                if (r != null && Json.str(r, "key") != null) rows.add(r);
            }
        } catch (RuntimeException e) {
            rows.clear(); // bozuk dosya: baştan başla (kasa verisi ayrı dosyada)
        }
    }

    private void save() {
        while (rows.size() > KEEP) rows.remove(0);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("rows", new ArrayList<Object>(rows));
        storage.write(Json.write(root));
    }

    /**
     * Günün en yakın seçimini kaydeder. sel: {home, away, kickoff, sport, sref, market, outcome, odds, p}
     * (sport ve sref sonuç için gerekli). Aynı gün ve türde kayıt varsa, maçı başlamadıysa değiştirilir.
     * Kaydedildiyse true.
     */
    public synchronized boolean record(String day, String kind, Map<String, Object> sel, Instant now) {
        if (sel == null || Json.str(sel, "sport") == null || Json.str(sel, "sref") == null || Json.str(sel, "kickoff") == null
                || Json.str(sel, "market") == null || Json.str(sel, "outcome") == null) return false;
        double odds = Json.dbl(sel, "odds", 0), p = Json.dbl(sel, "p", 0);
        if (!(odds > 1) || !(p > 0 && p < 1)) return false;
        Instant kickoff = Instant.parse(Json.str(sel, "kickoff"));
        if (!kickoff.isAfter(now)) return false;
        String key = day + "|" + kind;
        Map<String, Object> old = null;
        for (Map<String, Object> r : rows) if (key.equals(Json.str(r, "key"))) old = r;
        if (old != null) {
            if (!Instant.parse(Json.str(old, "kickoff")).isAfter(now)) return false; // maçı başladı: sabit kalır
            if (Json.str(sel, "sref").equals(Json.str(old, "sref")) && Json.str(sel, "market").equals(Json.str(old, "market"))
                    && Json.str(sel, "outcome").equals(Json.str(old, "outcome")) && odds == Json.dbl(old, "odds", 0)) return false;
            rows.remove(old);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", key);
        r.put("day", day);
        r.put("kind", kind);
        r.put("home", Json.str(sel, "home"));
        r.put("away", Json.str(sel, "away"));
        r.put("kickoff", kickoff.toString());
        r.put("sport", Json.str(sel, "sport"));
        r.put("sref", Json.str(sel, "sref"));
        r.put("market", Json.str(sel, "market"));
        r.put("outcome", Json.str(sel, "outcome"));
        r.put("label", Models.outcomeLabel(Json.str(sel, "market"), Json.str(sel, "outcome")));
        r.put("odds", odds);
        r.put("p", p);
        r.put("ev", p * odds - 1);
        r.put("stake", STAKE);
        r.put("at", now.toString());
        rows.add(r);
        save();
        return true;
    }

    /** Sonucu beklenen (maçı bitmiş olması gereken) kayıtların lig kodları. */
    public synchronized Set<String> dueSports(Instant now) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            if (r.get("result") != null) continue;
            if (!Settlement.resultDue(Json.str(r, "kickoff"), Json.str(r, "sport")).isAfter(now)) out.add(Json.str(r, "sport"));
        }
        return out;
    }

    /**
     * Skorlarla sonuçlandırır (kupon sonuçlandırmasının skorları; ek kredi yok). 3 günde sonucu
     * bulunamayan (ertelenmiş) kayıt iade sayılır. Sonuçlanan kayıt sayısı.
     */
    public synchronized int resolve(Map<String, ScoreResult> scores, Instant now) {
        int n = 0;
        for (Map<String, Object> r : rows) {
            if (r.get("result") != null) continue;
            Instant ko = Instant.parse(Json.str(r, "kickoff"));
            if (Settlement.resultDue(Json.str(r, "kickoff"), Json.str(r, "sport")).isAfter(now)) continue;
            ScoreResult s = scores == null ? null : scores.get(Json.str(r, "sref"));
            if (s != null && s.completed && s.home != null && s.away != null) {
                r.put("result", Settlement.legResult(Json.str(r, "market"), Json.str(r, "outcome"), s.home, s.away));
                r.put("score", s.home + "-" + s.away);
                r.put("settledAt", now.toString());
                n++;
            } else if (!ko.plusSeconds(Settlement.MANUAL_AFTER_S).isAfter(now)) {
                r.put("result", Models.VOID);
                r.put("score", "sonuç bulunamadı");
                r.put("settledAt", now.toString());
                n++;
            }
        }
        if (n > 0) save();
        return n;
    }

    /** Sanal kâr/zarar (kuruş): tutarsa tutar x (oran − 1), yatarsa −tutar, iade 0. */
    static long pl(Map<String, Object> r) {
        String res = Json.str(r, "result");
        long stake = Json.lng(r, "stake", STAKE);
        if (Models.WON.equals(res)) return Math.round(stake * (Json.dbl(r, "odds", 1) - 1));
        if (Models.LOST.equals(res)) return -stake;
        return 0;
    }

    private static Map<String, Object> stats(List<Map<String, Object>> list) {
        int won = 0, lost = 0, voids = 0, open = 0;
        long pl = 0, staked = 0;
        double expHits = 0, expPl = 0;
        for (Map<String, Object> r : list) {
            String res = Json.str(r, "result");
            if (res == null) {
                open++;
                continue;
            }
            if (Models.VOID.equals(res)) {
                voids++;
                continue;
            }
            if (Models.WON.equals(res)) won++;
            else lost++;
            long stake = Json.lng(r, "stake", STAKE);
            staked += stake;
            pl += pl(r);
            expHits += Json.dbl(r, "p", 0);
            expPl += stake * Json.dbl(r, "ev", 0);
        }
        int n = won + lost;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", (long) n);
        m.put("won", (long) won);
        m.put("lost", (long) lost);
        m.put("voids", (long) voids);
        m.put("open", (long) open);
        m.put("pl", pl);
        m.put("staked", staked);
        m.put("expectedPl", Math.round(expPl));
        m.put("hitRate", n == 0 ? null : (Object) ((double) won / n));
        m.put("expectedHitRate", n == 0 ? null : (Object) (expHits / n));
        m.put("roi", staked == 0 ? null : (Object) ((double) pl / staked));
        return m;
    }

    /** Arayüz: toplam, tür başına (normal / Zirve) ölçüler ve son kayıtlar (yeniden eskiye). */
    public synchronized Map<String, Object> view() {
        List<Map<String, Object>> normal = new ArrayList<>(), zirve = new ArrayList<>(), pick = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String k = Json.str(r, "kind");
            (ZIRVE.equals(k) ? zirve : PICK.equals(k) ? pick : normal).add(r);
        }
        Map<String, Object> v = stats(rows);
        v.put("normal", stats(normal));
        v.put("zirve", stats(zirve));
        v.put("secim", stats(pick));
        List<Object> recent = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0 && recent.size() < 20; i--) {
            Map<String, Object> r = new LinkedHashMap<>(rows.get(i));
            if (r.get("result") != null) r.put("pl", pl(r));
            recent.add(r);
        }
        v.put("recent", recent);
        return v;
    }
}
