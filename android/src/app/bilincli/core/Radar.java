package app.bilincli.core;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.Candidate;
import app.bilincli.core.Models.Pair;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Piyasa radarı: (1) değerli oranlar listesi — iddaa oranının adil orandan yüksek olduğu tüm
 * seçimler; (2) düşen oranlar — Pinnacle'ın adil olasılığının gün içinde belirgin yükseldiği
 * (oranın düştüğü) sonuçlar. Keskin piyasadaki hızlı düşüş genellikle profesyonel paranın o
 * yöne girdiğini gösterir; iddaa oranı henüz düşmediyse bu bir değer fırsatıdır.
 * Durum ayrı bir JSON belgesinde (piyasa.json) tutulur.
 */
public final class Radar {
    /** Düşen oran sayılması için adil olasılıktaki en az artış (yüzde puan). */
    public static final double MOVE_THRESHOLD = 0.03;
    static final int MAX_VALUES = 40;
    static final long KEEP_S = 36 * 3600;

    private final Ledger.Storage storage;
    private Map<String, Object> state;

    public Radar(Ledger.Storage storage) {
        this.storage = storage;
        String data = storage.read();
        state = data == null || data.trim().isEmpty() ? new LinkedHashMap<String, Object>() : Json.parseObject(data);
    }

    public synchronized Map<String, Object> view() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("scannedAt", state.get("scannedAt"));
        v.put("values", state.containsKey("values") ? state.get("values") : new ArrayList<Object>());
        v.put("moves", state.containsKey("moves") ? state.get("moves") : new ArrayList<Object>());
        v.put("fairs", state.containsKey("fairs") ? state.get("fairs") : new ArrayList<Object>());
        v.put("fairsAt", state.get("fairsAt"));
        return v;
    }

    private Map<String, Object> snapshots() {
        Map<String, Object> s = Json.obj(state.get("snapshots"));
        if (s == null) state.put("snapshots", s = new LinkedHashMap<String, Object>());
        return s;
    }

    private static Map<String, Object> probs(Map<String, Double> fair) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : fair.entrySet()) m.put(e.getKey(), e.getValue());
        return m;
    }

    /**
     * Yeni piyasa verisini işler. full=true ise (tüm ligler çekildiyse) değer listesi de yenilenir.
     * Daha önce bildirilmemiş, iddaa'da hâlâ değer taşıyan düşen oranları döndürür.
     */
    public synchronized List<Map<String, Object>> update(List<BookEvent> book, List<SharpEvent> sharp, Instant now,
                                                         Settings cfg, boolean full) {
        Map<String, Object> snaps = snapshots();
        for (SharpEvent s : sharp) {
            String market = s.fair.containsKey("MS") ? "MS" : "BS"; // basketbolda maç sonucu iki seçenekli
            Map<String, Double> fair = s.fair.get(market);
            if (fair == null) continue;
            Map<String, Object> snap = Json.obj(snaps.get(s.ref));
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("ts", now.toString());
            point.put("fair", probs(fair));
            if (snap == null) {
                snap = new LinkedHashMap<>();
                snap.put("home", s.home);
                snap.put("away", s.away);
                snap.put("kickoff", s.kickoff.toString());
                snap.put("first", point);
                snap.put("market", market);
                snaps.put(s.ref, snap);
            }
            snap.put("last", point);
        }
        // eski maçları at
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, Object> e : snaps.entrySet()) {
            String ko = Json.str(Json.obj(e.getValue()), "kickoff");
            if (ko == null || Instant.parse(ko).plusSeconds(KEEP_S).isBefore(now)) drop.add(e.getKey());
        }
        for (String k : drop) snaps.remove(k);

        // iddaa tarafı: eşleşen maçların güncel oranı
        List<Pair> pairs = Matching.match(book, sharp);
        Map<String, Pair> pairBySharp = new LinkedHashMap<>();
        for (Pair p : pairs) pairBySharp.put(p.sharp.ref, p);

        if (full) {
            state.put("fairs", Promo.table(pairs, now)); // promosyon kontrolü için (kredi harcamadan)
            state.put("fairsAt", now.toString());
            List<Candidate> all = Engine.buildCandidates(pairs, now, cfg).get(1);
            List<Candidate> value = new ArrayList<>();
            for (Candidate c : all) if (c.ev() >= cfg.minLegEv) value.add(c);
            Collections.sort(value, new Comparator<Candidate>() {
                @Override
                public int compare(Candidate a, Candidate b) {
                    return Double.compare(b.ev(), a.ev());
                }
            });
            List<Object> rows = new ArrayList<>();
            for (Candidate c : value.subList(0, Math.min(MAX_VALUES, value.size()))) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("bookRef", c.book.ref);
                r.put("code", c.book.code);
                r.put("home", c.book.home);
                r.put("away", c.book.away);
                r.put("kickoff", c.book.kickoff.toString());
                r.put("league", c.book.league);
                r.put("sport", c.sharp.sportKey);
                r.put("market", c.market);
                r.put("outcome", c.outcome);
                r.put("odds", c.odds);
                r.put("fair", c.prob);
                r.put("ev", c.ev());
                r.put("mbs", (long) c.mbs());
                r.put("inRange", c.odds >= cfg.minLegOdds && c.odds <= cfg.maxLegOdds);
                rows.add(r);
            }
            state.put("values", rows);
            state.put("scannedAt", now.toString());
        }

        // düşen oranlar
        List<Map<String, Object>> moves = new ArrayList<>();
        for (Map.Entry<String, Object> e : snaps.entrySet()) {
            Map<String, Object> snap = Json.obj(e.getValue());
            Map<String, Object> first = Json.obj(snap.get("first")), last = Json.obj(snap.get("last"));
            if (Json.str(first, "ts").equals(Json.str(last, "ts"))) continue;
            if (!Instant.parse(Json.str(snap, "kickoff")).isAfter(now)) continue;
            Map<String, Object> f0 = Json.obj(first.get("fair")), f1 = Json.obj(last.get("fair"));
            for (String o : f1.keySet()) {
                double a = Json.dbl(f0, o, -1), b = Json.dbl(f1, o, -1);
                if (a <= 0 || b <= 0 || b - a < MOVE_THRESHOLD) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", e.getKey() + ":" + o);
                m.put("home", snap.get("home"));
                m.put("away", snap.get("away"));
                m.put("kickoff", snap.get("kickoff"));
                m.put("outcome", o);
                m.put("from", a);
                m.put("to", b);
                m.put("since", first.get("ts"));
                Pair p = pairBySharp.get(e.getKey());
                String market = Json.str(snap, "market") == null ? "MS" : Json.str(snap, "market");
                m.put("market", market);
                if (p != null && p.book.odds.get(market) != null && p.book.odds.get(market).get(o) != null) {
                    double odds = p.book.odds.get(market).get(o);
                    m.put("iddaa", odds);
                    m.put("ev", b * odds - 1);
                    m.put("bookRef", p.book.ref);
                }
                moves.add(m);
            }
        }
        Collections.sort(moves, new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> x, Map<String, Object> y) {
                return Double.compare(Json.dbl(y, "to", 0) - Json.dbl(y, "from", 0), Json.dbl(x, "to", 0) - Json.dbl(x, "from", 0));
            }
        });
        state.put("moves", new ArrayList<Object>(moves));

        Set<String> notified = new HashSet<>();
        for (Object k : Json.arr(state.get("notified"))) notified.add(String.valueOf(k));
        List<Map<String, Object>> fresh = new ArrayList<>();
        List<Object> keep = new ArrayList<>();
        for (Map<String, Object> m : moves) {
            String key = Json.str(m, "key");
            if (m.get("ev") != null && Json.dbl(m, "ev", -1) >= cfg.minLegEv && !notified.contains(key)) fresh.add(m);
            if (Json.dbl(m, "ev", -1) >= cfg.minLegEv || notified.contains(key)) keep.add(key);
        }
        state.put("notified", keep);
        storage.write(Json.write(state));
        return fresh;
    }
}
