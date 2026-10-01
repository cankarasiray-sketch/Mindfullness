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
    /** Lig bazında ayrıntı ("Kaynakları test et" için). */
    public final List<String> report = new ArrayList<>();
    /** Önceden (ücretsiz listeyle) öğrenilmiş pencere maç sayıları: tekrar sorulmaz. */
    public Map<String, Integer> knownInWindow;

    /** Liglerin karar penceresindeki maç sayıları (kota harcamaz; alınamazsa -1). */
    public Map<String, Integer> activeCounts(java.util.Collection<String> leagues, Instant now) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String l : leagues) {
            out.put(l, eventsInWindow(l, now.plusSeconds(Math.round(cfg.minLeadMinutes * 60)),
                    now.plusSeconds(Math.round(cfg.windowHours * 3600))));
        }
        return out;
    }

    /** Arayüze ilerleme (null olabilir). */
    public Daily.Progress progress;
    /** true: maçı olmayan lig için sıradaki maç tarihi de sorulur (ücretsiz maç listesi). */
    public boolean diagnose;
    /** Bu nesneyle yapılan sorguların toplam kredi maliyeti (x-requests-last). */
    public int spent;
    /** Son parseOdds: {yanıttaki maç, bahis sitesi olmayan, kullanılamayan, Pinnacle-borsa ayrışması, bayat}. */
    int[] lastParse = new int[5];

    /** Bahis borsaları: komisyonlu ama marjsız piyasa; likitse keskin bir ikinci görüş. */
    static final String[] EXCHANGES = {"betfair_ex_eu", "betfair_ex_uk", "matchbook", "smarkets"};
    /** Borsa fiyatlarının toplam ima olasılığı bunu aşıyorsa piyasa likit değildir. */
    static final double EXCHANGE_MAX_OVERROUND = 1.04;
    /** Pinnacle ile borsa arasında bundan büyük olasılık farkı: biri bayat, pazar kullanılmaz. */
    static final double MAX_DISAGREE = 0.05;
    static final double PINNACLE_WEIGHT = 0.6;
    /**
     * Bayat çizgi: maça 6 saatten az kala Pinnacle'ın son güncellemesi, aynı pazardaki en yeni
     * güncellemeden 3 saatten eskiyse fiyat bayattır (pazar askıda ya da kapatılmış olabilir);
     * kullanılmaz. Haber (sakatlık, kadro) anında oluşan sahte "avantajlar" böyle ayıklanır.
     */
    static final long STALE_GAP_S = 3 * 3600, STALE_WINDOW_S = 6 * 3600;

    private final List<String> keys;
    /**
     * Anahtar başına {kalan, kullanılan, ölçüm zamanı (ms)}; kimlik Settings.keyId. Çağıran önceki
     * ölçümlerle doldurabilir (seçim için), çalışma sonunda kalıcı saklar.
     */
    public final Map<String, long[]> keyCredits = new LinkedHashMap<>();
    /** Bu çalışmada kullanılamayan anahtarlar: kısa ad -> neden. Bir daha sorulmazlar. */
    public final Map<String, String> keyProblems = new LinkedHashMap<>();

    public OddsApi(Http http, Settings cfg) throws Http.ProviderException {
        this.keys = cfg.apiKeys();
        if (keys.isEmpty()) {
            throw new Http.ProviderException("The Odds API anahtarı yok. Ayarlar'dan gir.");
        }
        this.http = http;
        this.cfg = cfg;
    }

    /**
     * Denenecek anahtar sırası: hiç ölçülmemiş olan önce (kalan kredisi öğrenilsin), sonra kalan
     * kredisi en çok olan. Böylece kredi anahtarlara yayılır; biri iptal edilse de diğerleri kalır.
     */
    List<String> keyOrder() {
        List<String> order = new ArrayList<>();
        for (String k : keys) if (!keyProblems.containsKey(Settings.keyLabel(k))) order.add(k);
        java.util.Collections.sort(order, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                long[] ca = keyCredits.get(Settings.keyId(a)), cb = keyCredits.get(Settings.keyId(b));
                return Long.compare(cb == null ? Long.MAX_VALUE : cb[0], ca == null ? Long.MAX_VALUE : ca[0]);
            }
        });
        return order;
    }

    /** Bilinen anahtarların toplamı (kredi planı tek kota gibi görür). */
    private void refreshTotals() {
        long rem = 0, use = 0;
        boolean any = false;
        for (String k : keys) {
            long[] c = keyCredits.get(Settings.keyId(k));
            if (c == null) continue;
            any = true;
            rem += c[0];
            use += c[1];
        }
        if (any) {
            remaining = String.valueOf(rem);
            used = String.valueOf(use);
        }
    }

    private static long number(String v, long fallback) {
        if (v == null) return fallback;
        try {
            return (long) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** "…ab12 480 kalan, …cd34 geçersiz ya da kredisi bitti (401)" ("Kaynakları test et" için). */
    public String keySummary() {
        List<String> parts = new ArrayList<>();
        for (String k : keys) {
            String label = Settings.keyLabel(k), problem = keyProblems.get(label);
            long[] c = keyCredits.get(Settings.keyId(k));
            parts.add(label + " " + (problem != null ? problem : c != null ? c[0] + " kalan" : "henüz kullanılmadı"));
        }
        return String.join(", ", parts);
    }

    private static double implied(double... prices) {
        double s = 0;
        for (double p : prices) s += 1 / p;
        return s;
    }

    /**
     * overround: pazar -> fiyatların toplam ima olasılığı (borsanın likitliği için). basket: basketbol
     * maç sonucu iki seçeneklidir (uzatmalar dahil, beraberlik yok) ve "BS" olarak döner.
     */
    private Map<String, Map<String, Double>> marketProbs(Map<String, Object> market, String home, String away,
                                                          Map<String, Double> overround, boolean basket) {
        String key = Json.str(market, "key");
        Map<String, Double> byName = new LinkedHashMap<>();
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        if ("h2h".equals(key) && basket) {
            for (Object o : Json.arr(market.get("outcomes"))) {
                Map<String, Object> oc = Json.obj(o);
                Double price = Json.num(oc, "price");
                if (price != null) byName.put(Json.str(oc, "name"), price);
            }
            Double h = byName.get(home), a = byName.get(away);
            // beraberlik seçeneği olan bir fiyat (normal süre) uzatmalı maç sonucuyla karşılaştırılamaz
            if (byName.size() != 2 || h == null || a == null || h <= 1 || a <= 1) return null;
            double[] p = OddsMath.devigPower(new double[] {h, a});
            if (overround != null) overround.put("BS", implied(h, a));
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("1", p[0]);
            m.put("2", p[1]);
            out.put("BS", m);
            return out;
        }
        if ("totals".equals(key) && basket) {
            // toplam sayı: çizgi maçtan maça değişir; pazar çizgiyle anahtarlanır ("BT@163.5") ki
            // farklı çizgideki siteler birbirine karıştırılmasın
            Double point = null;
            for (Object o : Json.arr(market.get("outcomes"))) {
                Map<String, Object> oc = Json.obj(o);
                Double pt = Json.num(oc, "point"), price = Json.num(oc, "price");
                if (pt == null || price == null) continue;
                if (point != null && !point.equals(pt)) return null; // iki ayrı çizgi: belirsiz
                point = pt;
                byName.put(Json.str(oc, "name"), price);
            }
            Double u = byName.get("Under"), ov = byName.get("Over");
            if (point == null || u == null || ov == null || u <= 1 || ov <= 1) return null;
            double[] p = OddsMath.devigPower(new double[] {u, ov});
            String k = "BT@" + point;
            if (overround != null) overround.put(k, implied(u, ov));
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("ALT", p[0]);
            m.put("UST", p[1]);
            out.put(k, m);
            return out;
        }
        if (basket) return null; // basketbolda maç sonucu ve toplam sayı
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
        boolean basket = Models.BASKETBALL.equals(Models.sportOf(sportKey));
        lastParse = new int[5];
        List<Object> all = Json.arr(payload);
        lastParse[0] = all == null ? 0 : all.size();
        for (Object o : Json.arr(payload)) {
            Map<String, Object> ev = Json.obj(o);
            String home = Json.str(ev, "home_team"), away = Json.str(ev, "away_team");
            String commence = Json.str(ev, "commence_time");
            if (home == null || away == null || commence == null) continue;
            if (Json.arr(ev.get("bookmakers")) == null || Json.arr(ev.get("bookmakers")).isEmpty()) lastParse[1]++;
            Map<String, Map<String, Map<String, Double>>> perMarket = new LinkedHashMap<>();
            Map<String, Map<String, Double>> liquid = new LinkedHashMap<>(); // pazar -> borsa -> ima toplamı
            Map<String, Map<String, Instant>> updated = new LinkedHashMap<>(); // pazar -> site -> son güncelleme
            for (Object bo : Json.arr(ev.get("bookmakers"))) {
                Map<String, Object> book = Json.obj(bo);
                String bk = Json.str(book, "key");
                Instant bookUpdate = instant(Json.str(book, "last_update"));
                for (Object mo : Json.arr(book.get("markets"))) {
                    Instant mu = instant(Json.str(Json.obj(mo), "last_update"));
                    if (mu == null) mu = bookUpdate;
                    Map<String, Double> over = new LinkedHashMap<>();
                    Map<String, Map<String, Double>> parsed = marketProbs(Json.obj(mo), home, away, over, basket);
                    if (parsed == null) continue;
                    for (Map.Entry<String, Map<String, Double>> e : parsed.entrySet()) {
                        Map<String, Map<String, Double>> books = perMarket.get(e.getKey());
                        if (books == null) perMarket.put(e.getKey(), books = new LinkedHashMap<>());
                        books.put(bk == null ? "?" : bk, e.getValue());
                        Map<String, Double> l = liquid.get(e.getKey());
                        if (l == null) liquid.put(e.getKey(), l = new LinkedHashMap<>());
                        if (over.containsKey(e.getKey())) l.put(bk == null ? "?" : bk, over.get(e.getKey()));
                        if (mu != null) {
                            Map<String, Instant> u = updated.get(e.getKey());
                            if (u == null) updated.put(e.getKey(), u = new LinkedHashMap<>());
                            u.put(bk == null ? "?" : bk, mu);
                        }
                    }
                }
            }
            Map<String, Map<String, Double>> fair = new LinkedHashMap<>();
            List<String> sources = new ArrayList<>();
            Instant start = instant(commence);
            for (Map.Entry<String, Map<String, Map<String, Double>>> e : perMarket.entrySet()) {
                Map<String, Map<String, Double>> books = e.getValue();
                if (stale(updated.get(e.getKey()), cfg.preferredBook, start)) {
                    books = new LinkedHashMap<>(books);
                    books.remove(cfg.preferredBook);
                    sources.add(e.getKey() + ":" + cfg.preferredBook + " bayat");
                    lastParse[4]++;
                }
                if (books.containsKey(cfg.preferredBook)) {
                    Map<String, Double> pin = books.get(cfg.preferredBook);
                    Map<String, Double> ex = exchangeAverage(books, liquid.get(e.getKey()));
                    if (ex == null) {
                        fair.put(e.getKey(), pin);
                        sources.add(e.getKey() + ":" + cfg.preferredBook);
                    } else if (maxDiff(pin, ex) > MAX_DISAGREE) {
                        sources.add(e.getKey() + ":ayrışma"); // Pinnacle ile borsa uyuşmuyor: kullanılmaz
                        lastParse[3]++;
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
                } else if (books.size() >= cfg.minBooks && !e.getKey().startsWith("BT@")) {
                    // (basketbol Alt/Üst yalnızca Pinnacle'ın çizgisinden: çizgiler sitelere göre değişir)
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
            // basketbol Alt/Üst: Pinnacle'ın çizgisi ve olasılıkları "BT" olarak saklanır; iddaa'nın
            // çizgisine dönüşüm Models.fair'de
            for (String k : new ArrayList<>(fair.keySet())) {
                if (!k.startsWith("BT@")) continue;
                Map<String, Double> f = fair.remove(k);
                if (fair.containsKey("BT")) continue;
                Map<String, Double> bt = new LinkedHashMap<>();
                bt.put("LINE", Models.line(k));
                bt.put("ALT", f.get("ALT"));
                bt.put("UST", f.get("UST"));
                fair.put("BT", bt);
            }
            if (fair.isEmpty()) lastParse[2]++;
            if (!fair.isEmpty()) {
                events.add(new SharpEvent(Json.str(ev, "id"), sportKey, home, away, Instant.parse(commence),
                        fair, String.join(", ", sources)));
            }
        }
        return events;
    }

    static Instant instant(String iso) {
        if (iso == null) return null;
        try {
            return Instant.parse(iso);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** Pinnacle'ın bu pazardaki fiyatı, diğer sitelerin en yenisine göre bayat mı? */
    static boolean stale(Map<String, Instant> updates, String preferred, Instant start) {
        if (updates == null || start == null) return false;
        Instant pin = updates.get(preferred), newest = null;
        if (pin == null) return false;
        for (Map.Entry<String, Instant> u : updates.entrySet()) {
            if (u.getKey().equals(preferred)) continue;
            if (newest == null || u.getValue().isAfter(newest)) newest = u.getValue();
        }
        if (newest == null) return false;
        return newest.getEpochSecond() - pin.getEpochSecond() > STALE_GAP_S
                && start.getEpochSecond() - newest.getEpochSecond() < STALE_WINDOW_S;
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

    /** Milli takım turnuvalarını tanıyan anahtar parçaları (The Odds API spor kodları). */
    static final String[] INTERNATIONAL = {"world_cup", "nations_league", "euro_qual", "european_championship",
        "friendl", "africa_cup", "copa_america", "gold_cup", "asian_cup", "international"};

    public static boolean isInternational(String key) {
        if (key == null || !key.startsWith("soccer_") || key.contains("women")) return false;
        for (String p : INTERNATIONAL) if (key.contains(p)) return true;
        return false;
    }

    /**
     * Şu an aktif futbol turnuvaları {kod, ad} (/v4/sports, kota harcamaz). Şampiyonluk
     * (outright) pazarları hariç.
     */
    public List<String[]> fetchSports() throws Http.ProviderException {
        List<String[]> out = new ArrayList<>();
        for (Object o : Json.arr(get("/sports", new LinkedHashMap<String, String>()))) {
            Map<String, Object> s = Json.obj(o);
            if (s == null || !"Soccer".equalsIgnoreCase(Json.str(s, "group")) || !Json.bool(s, "active", false)
                    || Json.bool(s, "has_outrights", false) || Json.str(s, "key") == null) continue;
            out.add(new String[] {Json.str(s, "key"), Json.str(s, "title") == null ? Json.str(s, "key") : Json.str(s, "title")});
        }
        return out;
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
        Http.ProviderException last = null;
        for (String key : keyOrder()) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("apiKey", key);
            p.putAll(params);
            Http.Response r;
            try {
                r = http.get(BASE + path + Http.query(p), null);
            } catch (Http.ProviderException e) {
                String m = String.valueOf(e.getMessage());
                boolean quota = m.contains("HTTP 401"), limited = m.contains("HTTP 429");
                if (keys.size() < 2 || !(quota || limited)) throw e;
                // bu anahtarın kredisi bitti, anahtar geçersiz ya da istek sınırı: sıradakiyle dene
                keyProblems.put(Settings.keyLabel(key), quota ? "geçersiz ya da kredisi bitti (401)" : "istek sınırı (429)");
                if (quota) {
                    long[] c = keyCredits.get(Settings.keyId(key));
                    keyCredits.put(Settings.keyId(key), new long[] {0, c == null ? 0 : c[1], System.currentTimeMillis()});
                    refreshTotals();
                }
                last = e;
                continue;
            }
            String rem = r.headers.get("x-requests-remaining"), u = r.headers.get("x-requests-used");
            if (rem != null) {
                long[] prev = keyCredits.get(Settings.keyId(key));
                keyCredits.put(Settings.keyId(key), new long[] {number(rem, 0), number(u, prev == null ? 0 : prev[1]),
                    System.currentTimeMillis()});
                refreshTotals();
            }
            String lastCost = r.headers.get("x-requests-last");
            if (lastCost != null) {
                try {
                    spent += (int) Double.parseDouble(lastCost.trim());
                } catch (NumberFormatException ignored) {
                    // başlık okunamadı: maliyet bilinmiyor
                }
            }
            try {
                return Json.parse(r.body);
            } catch (IllegalArgumentException e) {
                throw new Http.ProviderException(path + ": JSON çözülemedi");
            }
        }
        throw new Http.ProviderException("Tüm API anahtarları kullanılamadı (" + keySummary() + ")"
                + (last != null ? ": " + last.getMessage() : ""));
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
        return eventsInWindow(league, from, to) != 0;
    }

    /** Pencerdeki maç sayısı; liste alınamazsa -1 (temkinli: oran çekilir). */
    int eventsInWindow(String league, Instant from, Instant to) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dateFormat", "iso");
        params.put("commenceTimeFrom", iso(from));
        params.put("commenceTimeTo", iso(to));
        try {
            Object payload = get("/sports/" + league + "/events", params);
            if (!(payload instanceof List)) return -1;
            return ((List<?>) payload).size();
        } catch (Http.ProviderException | RuntimeException e) {
            report.add(CreditPlan.leagueName(league) + ": maç listesi alınamadı (" + e.getMessage() + ")");
            return -1;
        }
    }

    /** Ligin sıradaki maçı (ücretsiz maç listesinden); yoksa null. */
    Instant nextEvent(String league, Instant now) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dateFormat", "iso");
        try {
            Instant best = null;
            for (Object o : Json.arr(get("/sports/" + league + "/events", params))) {
                Instant t = instant(Json.str(Json.obj(o), "commence_time"));
                if (t != null && t.isAfter(now) && (best == null || t.isBefore(best))) best = t;
            }
            return best;
        } catch (Http.ProviderException | RuntimeException e) {
            return null;
        }
    }

    private static final java.time.format.DateTimeFormatter DM = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm", java.util.Locale.ROOT);

    /** now verilirse karar penceresinde maçı olmayan ligler atlanır. */
    List<SharpEvent> fetchEvents(java.util.Collection<String> leagues, Instant now) throws Http.ProviderException {
        List<SharpEvent> events = new ArrayList<>();
        Http.ProviderException last = null;
        int ok = 0;
        idle.clear();
        report.clear();
        int index = 0;
        for (String league : leagues) {
            String name = CreditPlan.leagueName(league);
            index++;
            if (progress != null) progress.step("Pinnacle oranları " + index + "/" + leagues.size() + ": " + name);
            int inWindow = now == null ? -1 : knownInWindow != null && knownInWindow.containsKey(league) ? knownInWindow.get(league)
                    : eventsInWindow(league, now.plusSeconds(Math.round(cfg.minLeadMinutes * 60)),
                    now.plusSeconds(Math.round(cfg.windowHours * 3600)));
            if (inWindow == 0) {
                idle.add(league);
                ok++;
                if (diagnose) {
                    Instant next = nextEvent(league, now);
                    report.add(name + ": 24 saatte maç yok" + (next == null ? " (listede maç yok)"
                            : ", sıradaki " + next.atOffset(Fmt.TR).format(DM)));
                }
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("regions", cfg.regions);
            params.put("markets", Settings.markets(league, cfg.totals));
            params.put("oddsFormat", "decimal");
            params.put("dateFormat", "iso");
            try {
                List<SharpEvent> parsed = parseOdds(get("/sports/" + league + "/odds", params), league);
                events.addAll(parsed);
                ok++;
                int[] s = lastParse;
                report.add(name + ": " + (inWindow >= 0 ? "24 saatte " + inWindow + " maç; " : "")
                        + "oran yanıtı " + s[0] + " maç, kullanılabilir " + parsed.size()
                        + (s[1] > 0 ? ", bahis sitesi olmayan " + s[1] : "")
                        + (s[3] > 0 ? ", Pinnacle-borsa ayrışması " + s[3] : "")
                        + (s[4] > 0 ? ", bayat Pinnacle " + s[4] : "")
                        + (s[2] > s[1] ? ", adil oran kurulamayan " + (s[2] - s[1]) : ""));
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
