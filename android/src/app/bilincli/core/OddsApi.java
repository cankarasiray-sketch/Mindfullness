package app.bilincli.core;

import app.bilincli.core.Models.ScoreResult;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Odds API: keskin piyasa oranları ve skorlar (Python: providers/theoddsapi.py).
 * Pinnacle varsa o kullanılır; yoksa en az minBooks sitenin arındırılmış ortalaması.
 */
public final class OddsApi {
    public static final String BASE = "https://api.the-odds-api.com/v4";

    private final Http http;
    private final Settings cfg;
    public String remaining, used;
    public final List<String> warnings = new ArrayList<>();

    public OddsApi(Http http, Settings cfg) throws Http.ProviderException {
        if (cfg.oddsApiKey == null || cfg.oddsApiKey.isEmpty()) {
            throw new Http.ProviderException("The Odds API anahtarı yok. Ayarlar'dan gir.");
        }
        this.http = http;
        this.cfg = cfg;
    }

    private Map<String, Map<String, Double>> marketProbs(Map<String, Object> market, String home, String away) {
        String key = Json.str(market, "key");
        Map<String, Double> byName = new LinkedHashMap<>();
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        if ("h2h".equals(key)) {
            for (Object o : Json.arr(market.get("outcomes"))) {
                Map<String, Object> oc = Json.obj(o);
                Double price = Json.num(oc, "price");
                if (price != null) byName.put(Json.str(oc, "name"), price);
            }
            Double h = byName.get(home), d = byName.get("Draw"), a = byName.get(away);
            if (h == null || d == null || a == null || h <= 1 || d <= 1 || a <= 1) return null;
            double[] p = OddsMath.devigPower(new double[] {h, d, a});
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("1", p[0]);
            m.put("X", p[1]);
            m.put("2", p[2]);
            out.put("MS", m);
            return out;
        }
        if ("btts".equals(key)) {
            for (Object o : Json.arr(market.get("outcomes"))) {
                Map<String, Object> oc = Json.obj(o);
                Double price = Json.num(oc, "price");
                if (price != null) byName.put(Json.str(oc, "name"), price);
            }
            Double y = byName.get("Yes"), n = byName.get("No");
            if (y == null || n == null || y <= 1 || n <= 1) return null;
            double[] p = OddsMath.devigPower(new double[] {y, n});
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("VAR", p[0]);
            m.put("YOK", p[1]);
            out.put("KG", m);
            return out;
        }
        if ("totals".equals(key)) {
            for (Object o : Json.arr(market.get("outcomes"))) {
                Map<String, Object> oc = Json.obj(o);
                Double point = Json.num(oc, "point"), price = Json.num(oc, "price");
                if (point != null && point == 2.5 && price != null) byName.put(Json.str(oc, "name"), price);
            }
            Double u = byName.get("Under"), ov = byName.get("Over");
            if (u == null || ov == null || u <= 1 || ov <= 1) return null;
            double[] p = OddsMath.devigPower(new double[] {u, ov});
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("ALT", p[0]);
            m.put("UST", p[1]);
            out.put("AU25", m);
            return out;
        }
        return null;
    }

    public List<SharpEvent> parseOdds(Object payload, String sportKey) {
        List<SharpEvent> events = new ArrayList<>();
        for (Object o : Json.arr(payload)) {
            Map<String, Object> ev = Json.obj(o);
            String home = Json.str(ev, "home_team"), away = Json.str(ev, "away_team");
            String commence = Json.str(ev, "commence_time");
            if (home == null || away == null || commence == null) continue;
            Map<String, Map<String, Map<String, Double>>> perMarket = new LinkedHashMap<>();
            for (Object bo : Json.arr(ev.get("bookmakers"))) {
                Map<String, Object> book = Json.obj(bo);
                String bk = Json.str(book, "key");
                for (Object mo : Json.arr(book.get("markets"))) {
                    Map<String, Map<String, Double>> parsed = marketProbs(Json.obj(mo), home, away);
                    if (parsed == null) continue;
                    for (Map.Entry<String, Map<String, Double>> e : parsed.entrySet()) {
                        Map<String, Map<String, Double>> books = perMarket.get(e.getKey());
                        if (books == null) perMarket.put(e.getKey(), books = new LinkedHashMap<>());
                        books.put(bk == null ? "?" : bk, e.getValue());
                    }
                }
            }
            Map<String, Map<String, Double>> fair = new LinkedHashMap<>();
            List<String> sources = new ArrayList<>();
            for (Map.Entry<String, Map<String, Map<String, Double>>> e : perMarket.entrySet()) {
                Map<String, Map<String, Double>> books = e.getValue();
                if (books.containsKey(cfg.preferredBook)) {
                    fair.put(e.getKey(), books.get(cfg.preferredBook));
                    sources.add(e.getKey() + ":" + cfg.preferredBook);
                } else if (books.size() >= cfg.minBooks) {
                    Map<String, Double> avg = new LinkedHashMap<>();
                    for (String outcome : books.values().iterator().next().keySet()) {
                        double s = 0;
                        for (Map<String, Double> b : books.values()) s += b.get(outcome);
                        avg.put(outcome, s / books.size());
                    }
                    double total = 0;
                    for (double v : avg.values()) total += v;
                    for (Map.Entry<String, Double> a : avg.entrySet()) a.setValue(a.getValue() / total);
                    fair.put(e.getKey(), avg);
                    sources.add(e.getKey() + ":ortalama(" + books.size() + ")");
                }
            }
            if (!fair.isEmpty()) {
                events.add(new SharpEvent(Json.str(ev, "id"), sportKey, home, away, Instant.parse(commence),
                        fair, String.join(", ", sources)));
            }
        }
        return events;
    }

    public static Map<String, ScoreResult> parseScores(Object payload) {
        Map<String, ScoreResult> out = new LinkedHashMap<>();
        for (Object o : Json.arr(payload)) {
            Map<String, Object> ev = Json.obj(o);
            Map<String, String> scores = new LinkedHashMap<>();
            for (Object so : Json.arr(ev.get("scores"))) {
                Map<String, Object> s = Json.obj(so);
                scores.put(Json.str(s, "name"), Json.str(s, "score"));
            }
            Integer hg = null, ag = null;
            try {
                String h = scores.get(Json.str(ev, "home_team")), a = scores.get(Json.str(ev, "away_team"));
                if (h != null) hg = Integer.parseInt(h.trim());
                if (a != null) ag = Integer.parseInt(a.trim());
            } catch (NumberFormatException e) {
                hg = ag = null;
            }
            boolean done = Json.bool(ev, "completed", false) && hg != null && ag != null;
            out.put(Json.str(ev, "id"), new ScoreResult(done, hg, ag));
        }
        return out;
    }

    private Object get(String path, Map<String, String> params) throws Http.ProviderException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("apiKey", cfg.oddsApiKey);
        p.putAll(params);
        Http.Response r = http.get(BASE + path + Http.query(p), null);
        String rem = r.headers.get("x-requests-remaining");
        if (rem != null) remaining = rem;
        String u = r.headers.get("x-requests-used");
        if (u != null) used = u;
        try {
            return Json.parse(r.body);
        } catch (IllegalArgumentException e) {
            throw new Http.ProviderException(path + ": JSON çözülemedi");
        }
    }

    public List<SharpEvent> fetchEvents() throws Http.ProviderException {
        return fetchEvents(cfg.leagues);
    }

    /** Yalnızca verilen ligler (kontrol ve kapanış için; kredi tasarrufu). */
    public List<SharpEvent> fetchEvents(java.util.Collection<String> leagues) throws Http.ProviderException {
        List<SharpEvent> events = new ArrayList<>();
        Http.ProviderException last = null;
        int ok = 0;
        for (String league : leagues) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("regions", cfg.regions);
            params.put("markets", cfg.totals ? "h2h,totals" : "h2h");
            params.put("oddsFormat", "decimal");
            params.put("dateFormat", "iso");
            try {
                events.addAll(parseOdds(get("/sports/" + league + "/odds", params), league));
                ok++;
            } catch (Http.ProviderException e) {
                last = e;
                warnings.add(league + " atlandı: " + e.getMessage());
            }
        }
        if (ok == 0 && last != null) throw last;
        return events;
    }

    /**
     * Ek pazar (ör. btts) tek maç için çekilir; ek pazarlar yalnızca maç bazında sunulur ve
     * maç başına kredi harcar. Bulunan adil olasılıklar SharpEvent'e eklenir.
     */
    public void enrichEvent(SharpEvent ev, String markets) throws Http.ProviderException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("regions", cfg.regions);
        params.put("markets", markets);
        params.put("oddsFormat", "decimal");
        params.put("dateFormat", "iso");
        Object payload = get("/sports/" + ev.sportKey + "/events/" + ev.ref + "/odds", params);
        List<Object> one = new ArrayList<>();
        one.add(payload);
        List<SharpEvent> parsed = parseOdds(one, ev.sportKey);
        if (!parsed.isEmpty()) {
            for (Map.Entry<String, Map<String, Double>> e : parsed.get(0).fair.entrySet()) ev.fair.put(e.getKey(), e.getValue());
        }
    }

    public Map<String, ScoreResult> fetchScores(Set<String> sportKeys) throws Http.ProviderException {
        Map<String, ScoreResult> out = new LinkedHashMap<>();
        for (String league : sportKeys) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("daysFrom", "3");
            params.put("dateFormat", "iso");
            try {
                out.putAll(parseScores(get("/sports/" + league + "/scores", params)));
            } catch (Http.ProviderException e) {
                warnings.add(league + " skorları alınamadı: " + e.getMessage());
            }
        }
        return out;
    }
}
