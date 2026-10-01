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
 * Pinnacle varsa o kullanılır; aynı yanıtta likit bir bahis borsası (Betfair, Matchbook, Smarkets)
 * da varsa ikisinin ağırlıklı ortalaması alınır (ek kredi gerekmez) ve ikisi belirgin biçimde
 * ayrışıyorsa (biri bayat) o pazar o maç için kullanılmaz. Pinnacle yoksa en az minBooks sitenin
 * arındırılmış ortalaması.
 */
public final class OddsApi {
    public static final String BASE = "https://api.the-odds-api.com/v4";

    private final Http http;
    private final Settings cfg;
    public String remaining, used;
    public final List<String> warnings = new ArrayList<>();
    /** Son taramada pencerede maçı olmadığı için (kredi harcamadan) atlanan ligler. */
    public final List<String> idle = new ArrayList<>();

    /** Bahis borsaları: komisyonlu ama marjsız piyasa; likitse keskin bir ikinci görüş. */
    static final String[] EXCHANGES = {"betfair_ex_eu", "betfair_ex_uk", "matchbook", "smarkets"};
    /** Borsa fiyatlarının toplam ima olasılığı bunu aşıyorsa piyasa likit değildir. */
    static final double EXCHANGE_MAX_OVERROUND = 1.04;
    /** Pinnacle ile borsa arasında bundan büyük olasılık farkı: biri bayat, pazar kullanılmaz. */
    static final double MAX_DISAGREE = 0.05;
    static final double PINNACLE_WEIGHT = 0.6;

    public OddsApi(Http http, Settings cfg) throws Http.ProviderException {
        if (cfg.oddsApiKey == null || cfg.oddsApiKey.isEmpty()) {
            throw new Http.ProviderException("The Odds API anahtarı yok. Ayarlar'dan gir.");
        }
        this.http = http;
        this.cfg = cfg;
    }

    private static double implied(double... prices) {
        double s = 0;
        for (double p : prices) s += 1 / p;
        return s;
    }

    private Map<String, Map<String, Double>> marketProbs(Map<String, Object> market, String home, String away) {
        return marketProbs(market, home, away, null);
    }

    /** overround: pazar -> fiyatların toplam ima olasılığı (borsanın likitliği için). */
    private Map<String, Map<String, Double>> marketProbs(Map<String, Object> market, String home, String away,
                                                          Map<String, Double> overround) {
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
            if (overround != null) overround.put("MS", implied(h, d, a));
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
            if (overround != null) overround.put("KG", implied(y, n));
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
            if (overround != null) overround.put("AU25", implied(u, ov));
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
            Map<String, Map<String, Double>> liquid = new LinkedHashMap<>(); // pazar -> borsa -> ima toplamı
            for (Object bo : Json.arr(ev.get("bookmakers"))) {
                Map<String, Object> book = Json.obj(bo);
                String bk = Json.str(book, "key");
                for (Object mo : Json.arr(book.get("markets"))) {
                    Map<String, Double> over = new LinkedHashMap<>();
                    Map<String, Map<String, Double>> parsed = marketProbs(Json.obj(mo), home, away, over);
                    if (parsed == null) continue;
                    for (Map.Entry<String, Map<String, Double>> e : parsed.entrySet()) {
                        Map<String, Map<String, Double>> books = perMarket.get(e.getKey());
                        if (books == null) perMarket.put(e.getKey(), books = new LinkedHashMap<>());
                        books.put(bk == null ? "?" : bk, e.getValue());
                        Map<String, Double> l = liquid.get(e.getKey());
                        if (l == null) liquid.put(e.getKey(), l = new LinkedHashMap<>());
                        if (over.containsKey(e.getKey())) l.put(bk == null ? "?" : bk, over.get(e.getKey()));
                    }
                }
            }
            Map<String, Map<String, Double>> fair = new LinkedHashMap<>();
            List<String> sources = new ArrayList<>();
            for (Map.Entry<String, Map<String, Map<String, Double>>> e : perMarket.entrySet()) {
                Map<String, Map<String, Double>> books = e.getValue();
                if (books.containsKey(cfg.preferredBook)) {
                    Map<String, Double> pin = books.get(cfg.preferredBook);
                    Map<String, Double> ex = exchangeAverage(books, liquid.get(e.getKey()));
                    if (ex == null) {
                        fair.put(e.getKey(), pin);
                        sources.add(e.getKey() + ":" + cfg.preferredBook);
                    } else if (maxDiff(pin, ex) > MAX_DISAGREE) {
                        sources.add(e.getKey() + ":ayrışma"); // Pinnacle ile borsa uyuşmuyor: kullanılmaz
                    } else {
                        Map<String, Double> mix = new LinkedHashMap<>();
                        double total = 0;
                        for (String k : pin.keySet()) {
                            double v = PINNACLE_WEIGHT * pin.get(k) + (1 - PINNACLE_WEIGHT) * ex.get(k);
                            mix.put(k, v);
                            total += v;
                        }
                        for (Map.Entry<String, Double> m : mix.entrySet()) m.setValue(m.getValue() / total);
                        fair.put(e.getKey(), mix);
                        sources.add(e.getKey() + ":" + cfg.preferredBook + "+borsa");
                    }
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
            Map<String, Double> cs = doubleChance(fair.get("MS"));
            if (cs != null) fair.put("CS", cs);
            if (!fair.isEmpty()) {
                events.add(new SharpEvent(Json.str(ev, "id"), sportKey, home, away, Instant.parse(commence),
                        fair, String.join(", ", sources)));
            }
        }
        return events;
    }

    /** Likit borsaların arındırılmış olasılık ortalaması (yoksa null). */
    static Map<String, Double> exchangeAverage(Map<String, Map<String, Double>> books, Map<String, Double> overround) {
        List<Map<String, Double>> ok = new ArrayList<>();
        for (String x : EXCHANGES) {
            Map<String, Double> b = books.get(x);
            Double o = overround == null ? null : overround.get(x);
            if (b != null && o != null && o <= EXCHANGE_MAX_OVERROUND) ok.add(b);
        }
        if (ok.isEmpty()) return null;
        Map<String, Double> avg = new LinkedHashMap<>();
        for (String k : ok.get(0).keySet()) {
            double s = 0;
            for (Map<String, Double> b : ok) s += b.get(k);
            avg.put(k, s / ok.size());
        }
        return avg;
    }

    static double maxDiff(Map<String, Double> a, Map<String, Double> b) {
        double d = 0;
        for (String k : a.keySet()) if (b.get(k) != null) d = Math.max(d, Math.abs(a.get(k) - b.get(k)));
        return d;
    }

    /** Çifte Şans adil olasılıkları Maç Sonucu'ndan türetilir (ek kredi gerekmez). */
    public static Map<String, Double> doubleChance(Map<String, Double> ms) {
        if (ms == null || ms.get("1") == null || ms.get("X") == null || ms.get("2") == null) return null;
        Map<String, Double> cs = new LinkedHashMap<>();
        cs.put("1X", ms.get("1") + ms.get("X"));
        cs.put("12", ms.get("1") + ms.get("2"));
        cs.put("X2", ms.get("X") + ms.get("2"));
        return cs;
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

    /** Günlük ve radar taraması: pencerede maçı olmayan ligler için oran çekilmez. */
    public List<SharpEvent> fetchEvents() throws Http.ProviderException {
        return fetchEvents(cfg.leagues, Instant.now());
    }

    /** Yalnızca verilen ligler (kontrol ve kapanış için; kredi tasarrufu). */
    public List<SharpEvent> fetchEvents(java.util.Collection<String> leagues) throws Http.ProviderException {
        return fetchEvents(leagues, null);
    }

    static String iso(Instant t) {
        return t.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    /**
     * Ligde [from, to] aralığında maç var mı? The Odds API'nin maç listesi (/events) kota
     * harcamaz. Hata olursa temkinli davranılır: var sayılır ve oranlar her zamanki gibi çekilir.
     */
    boolean hasEvents(String league, Instant from, Instant to) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dateFormat", "iso");
        params.put("commenceTimeFrom", iso(from));
        params.put("commenceTimeTo", iso(to));
        try {
            Object payload = get("/sports/" + league + "/events", params);
            return !(payload instanceof List) || !((List<?>) payload).isEmpty();
        } catch (Http.ProviderException | RuntimeException e) {
            return true;
        }
    }

    /** now verilirse karar penceresinde maçı olmayan ligler atlanır. */
    List<SharpEvent> fetchEvents(java.util.Collection<String> leagues, Instant now) throws Http.ProviderException {
        List<SharpEvent> events = new ArrayList<>();
        Http.ProviderException last = null;
        int ok = 0;
        idle.clear();
        for (String league : leagues) {
            if (now != null && !hasEvents(league, now.plusSeconds(Math.round(cfg.minLeadMinutes * 60)),
                    now.plusSeconds(Math.round(cfg.windowHours * 3600)))) {
                idle.add(league);
                ok++;
                continue;
            }
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
