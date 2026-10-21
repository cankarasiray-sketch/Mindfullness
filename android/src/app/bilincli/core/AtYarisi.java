package app.bilincli.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2.20: Bilyoner ve Nesine at yarışı verisi (AGF ve ganyan oranı). İki site de bu veriyi belgelenmiş bir adresten
 * vermiyor; okuyucu, Zirve Oran keşfi gibi, telefonda herkese açık at yarışı sayfasını, sayfaya gömülü veriyi ve
 * sitenin betiklerinde geçen veri adreslerini inceler. Bulduğu her JSON'da koşucu listelerini alan adlarından tanır
 * (at no + AGF ve/veya ganyan). Okunan her şey ve bulunamayanlar okuma ayrıntısına yazılır; kesin okuyucu bu
 * çıktıya göre yazılır. Hesap ya da oturum kullanılmaz, kredi harcanmaz.
 */
public final class AtYarisi {
    private AtYarisi() {}

    /** {ad, sayfa, alan adı}. */
    static final String[][] SITES = {
        {"Bilyoner", "https://www.bilyoner.com/at-yarisi", "bilyoner"},
        {"Nesine", "https://www.nesine.com/at-yarisi", "nesine"},
    };
    static final int MAX_SCRIPTS = 4, MAX_PATHS = 6, MAX_RACES_LOGGED = 12;
    /** Ganyan oranlarının ima ettiği olasılıklar toplamı (kesinti payı dahil) bu aralıkta değilse oranlar kullanılmaz. */
    static final double MIN_BOOK = 0.95, MAX_BOOK = 2.0;

    public static final class Runner {
        public final String no;
        /** AGF payı (yüzde) ya da yoksa null. */
        public final Double agf;
        /** Ganyan oranı ya da yoksa null. */
        public final Double odds;

        Runner(String no, Double agf, Double odds) {
            this.no = no;
            this.agf = agf;
            this.odds = odds;
        }
    }

    public static final class Race {
        final String path, place;
        final Long number;
        final List<Runner> runners;
        /** Koşu saati ("14:30") sitede varsa. */
        String time;

        Race(String path, String place, Long number, List<Runner> runners) {
            this.path = path;
            this.place = place;
            this.number = number;
            this.runners = runners;
        }

        boolean hasAgf() {
            int n = 0;
            double s = 0;
            for (Runner r : runners) {
                if (r.agf != null) {
                    n++;
                    s += r.agf;
                }
            }
            return n >= 2 && s >= Tjk.MIN_SUM && s <= Tjk.MAX_SUM;
        }

        /** Tüm koşucularda oran var ve oranların ima ettiği toplam akla yatkın. */
        boolean hasOdds() {
            double book = 0;
            for (Runner r : runners) {
                if (r.odds == null) return false;
                book += 1 / r.odds;
            }
            return runners.size() >= 2 && book >= MIN_BOOK && book <= MAX_BOOK;
        }

        /** Ayak metni "no:agf[:ganyan]" (AGF'ye göre azalan; AGF'siz atlar dışarıda). */
        String legText() {
            boolean odds = hasOdds();
            List<Runner> rs = new ArrayList<>();
            for (Runner r : runners) if (r.agf != null && r.agf > 0) rs.add(r);
            rs.sort(new java.util.Comparator<Runner>() {
                @Override
                public int compare(Runner x, Runner y) {
                    return x.agf.doubleValue() != y.agf.doubleValue() ? Double.compare(y.agf, x.agf)
                            : Integer.compare(Integer.parseInt(x.no), Integer.parseInt(y.no));
                }
            });
            StringBuilder b = new StringBuilder();
            for (Runner r : rs) {
                b.append(b.length() == 0 ? "" : " ").append(r.no).append(':').append(Tjk.pct(r.agf));
                if (odds) b.append(':').append(Tjk.pct(r.odds));
            }
            return b.toString();
        }
    }

    /** 2.22: sitede altılı bahsinin birim fiyatı, devri ve havuzu yazıyorsa (hipodroma göre). */
    public static final class PoolInfo {
        final String place;
        final Double unit, devir, pool;

        PoolInfo(String place, Double unit, Double devir, Double pool) {
            this.place = place;
            this.unit = unit;
            this.devir = devir;
            this.pool = pool;
        }
    }

    private static final Pattern ALTILI_NAME = Pattern.compile("(?i)(alt[ıi]l[ıi]|6\\s*'?\\s*l[ıi]\\b|\\bsix|pick\\s*6)");
    private static final Pattern UNIT_KEY = Pattern.compile("(?i)^(unit_?price|unit_?amount|unit_?value|birim_?fiyat\\w*|birim|bet_?unit\\w*|min_?bet\\w*)$");
    private static final Pattern DEVIR_KEY = Pattern.compile("(?i)^(devir\\w*|devreden\\w*|carry_?over\\w*|jackpot\\w*|rollover\\w*)$");
    private static final Pattern POOL_KEY = Pattern.compile("(?i)^(pool\\w*|havuz\\w*|total_?(amount|pool|bet)\\w*|hasilat\\w*|turnover\\w*)$");

    /** TL tutarı: sayı ya da "1.234.567,89", "1234567.89", "₺ 2.500" -> sayı (okunamazsa null). */
    static Double amount(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (!(v instanceof String)) return null;
        String t = ((String) v).replaceAll("[^0-9.,]", "");
        if (t.isEmpty()) return null;
        if (t.indexOf(',') >= 0) t = t.replace(".", "").replace(',', '.');
        else if (t.matches("\\d{1,3}(\\.\\d{3})+")) t = t.replace(".", "");
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Altılı bahsini anlatan nesnelerde (adında "altılı"/"6'lı" geçen) birim fiyat, devir ve havuz. */
    @SuppressWarnings("unchecked")
    static void pools(Object node, String place, List<PoolInfo> out, int depth) {
        if (depth > 40) return;
        if (node instanceof List) {
            for (Object o : (List<Object>) node) pools(o, place, out, depth + 1);
        } else if (node instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) node;
            boolean altili = false;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!(e.getValue() instanceof String)) continue;
                String v = (String) e.getValue();
                if (PLACE_KEY.matcher(e.getKey()).matches() && v.length() <= 40) place = v;
                if (v.length() <= 60 && ALTILI_NAME.matcher(v).find()) altili = true;
            }
            if (altili) {
                Double unit = null, devir = null, pool = null;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    Double d = amount(e.getValue());
                    if (d == null) continue;
                    if (UNIT_KEY.matcher(e.getKey()).matches() && d >= 0.01 && d <= 100) unit = d;
                    else if (DEVIR_KEY.matcher(e.getKey()).matches() && d >= 0) devir = d;
                    else if (POOL_KEY.matcher(e.getKey()).matches() && d > 0) pool = d;
                }
                if (unit != null || devir != null || pool != null) out.add(new PoolInfo(place, unit, devir, pool));
            }
            for (Map.Entry<String, Object> e : m.entrySet()) pools(e.getValue(), place, out, depth + 1);
        }
    }

    // ---- JSON'da koşucu listeleri ---------------------------------------------------------------------------

    private static final Pattern HORSE_KEY = Pattern.compile(
            "(?i)^(no|num|number|horse_?no|horse_?number|start_?no|start_?number|program_?no|program_?number|at_?no|atnumarasi|saddle_?(no|number|cloth)|runner_?no|runner_?number)$");
    private static final Pattern AGF_KEY = Pattern.compile("(?i)agf");
    private static final Pattern RANK_KEY = Pattern.compile("(?i)(rank|order|sira|sort|index|position)");
    private static final Pattern ODDS_KEY = Pattern.compile("(?i)(ganyan|win_?odds?|^odds?$|^odd_?value$|^price$|^oran$|^winprice$)");
    private static final Pattern PLACE_KEY = Pattern.compile("(?i)^(hippodrome\\w*|hipodrom\\w*|city\\w*|sehir\\w*|track\\w*|location\\w*|venue\\w*)$");
    private static final Pattern RACE_NO_KEY = Pattern.compile("(?i)^(race_?(no|number|order)|kosu_?(no|sira|sirasi)?|run_?no)$");
    private static final Pattern TIME_KEY = Pattern.compile("(?i)^(time|start_?time|race_?time|post_?time|saat|kosu_?saati|start_?date|race_?date|date_?time)$");
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,4}(?:[.,]\\d{1,4})?)");

    static Double number(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (!(v instanceof String)) return null;
        Matcher m = NUMBER.matcher((String) v);
        return m.find() ? Double.valueOf(m.group(1).replace(',', '.')) : null;
    }

    private static Integer horseNo(Object v) {
        Double d = v instanceof String && !((String) v).trim().matches("\\d{1,2}") ? null : number(v);
        if (d == null || d != Math.rint(d) || d < 1 || d > 30) return null;
        return d.intValue();
    }

    /** Liste koşucu listesiyse koşu (aksi halde null). */
    @SuppressWarnings("unchecked")
    static Race race(List<Object> list, String path, String place, Long number) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object o : list) if (o instanceof Map) rows.add((Map<String, Object>) o);
        if (rows.size() < 2 || rows.size() < list.size() * 0.8) return null;
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) keys.addAll(r.keySet());
        String horse = null, agf = null, odds = null;
        for (String k : keys) {
            if (horse == null && HORSE_KEY.matcher(k).matches() && uniqueHorses(rows, k)) horse = k;
            else if (AGF_KEY.matcher(k).find() && !RANK_KEY.matcher(k).find() && numeric(rows, k, 0, 100)) {
                if (agf == null || k.toLowerCase(Locale.ROOT).matches(".*(rate|percent|ratio|value|oran|yuzde).*")) agf = k;
            } else if (odds == null && ODDS_KEY.matcher(k).find() && !AGF_KEY.matcher(k).find() && numeric(rows, k, 1, 1000)) odds = k;
        }
        if (horse == null || (agf == null && odds == null)) return null;
        List<Runner> runners = new ArrayList<>();
        double agfSum = 0;
        for (Map<String, Object> r : rows) {
            Integer no = horseNo(r.get(horse));
            if (no == null) continue;
            Double a = agf == null ? null : number(r.get(agf)), o = odds == null ? null : number(r.get(odds));
            if (o != null && !(o > 1)) o = null;
            if (a != null) agfSum += a;
            runners.add(new Runner(String.valueOf(no), a, o));
        }
        if (agf != null && agfSum > 0 && agfSum <= 1.5) { // AGF 0–1 kesir olarak gelmiş: yüzdeye çevir
            List<Runner> scaled = new ArrayList<>();
            for (Runner r : runners) scaled.add(new Runner(r.no, r.agf == null ? null : r.agf * 100, r.odds));
            runners = scaled;
        }
        return runners.size() >= 2 ? new Race(path, place, number, runners) : null;
    }

    private static boolean uniqueHorses(List<Map<String, Object>> rows, String k) {
        Set<Integer> seen = new HashSet<>();
        int n = 0;
        for (Map<String, Object> r : rows) {
            Integer no = horseNo(r.get(k));
            if (no != null) {
                n++;
                seen.add(no);
            }
        }
        return n >= rows.size() * 0.8 && seen.size() >= n * 0.9;
    }

    private static boolean numeric(List<Map<String, Object>> rows, String k, double min, double max) {
        int n = 0;
        for (Map<String, Object> r : rows) {
            Double d = number(r.get(k));
            if (d != null && d >= min && d <= max) n++;
        }
        return n >= 2 && n >= rows.size() * 0.5;
    }

    /** JSON ağacındaki tüm koşucu listeleri (sırayla); hipodrom ve koşu numarası üst nesnelerden alınır. */
    @SuppressWarnings("unchecked")
    static void harvest(Object node, String path, String place, Long number, List<Race> out, int depth) {
        harvest(node, path, place, number, null, out, depth);
    }

    private static void harvest(Object node, String path, String place, Long number, String time, List<Race> out, int depth) {
        if (depth > 40) return;
        if (node instanceof List) {
            List<Object> l = (List<Object>) node;
            Race r = race(l, path, place, number);
            if (r != null) {
                r.time = time;
                out.add(r);
            }
            for (int i = 0; i < l.size() && i < 2000; i++) harvest(l.get(i), path + "[" + i + "]", place, number, time, out, depth + 1);
        } else if (node instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) node;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getValue() instanceof String && PLACE_KEY.matcher(e.getKey()).matches() && ((String) e.getValue()).length() <= 40) {
                    place = (String) e.getValue();
                } else if (RACE_NO_KEY.matcher(e.getKey()).matches()) {
                    Double d = number(e.getValue());
                    if (d != null && d == Math.rint(d) && d >= 1 && d <= 20) number = d.longValue();
                } else if (e.getValue() instanceof String && TIME_KEY.matcher(e.getKey()).matches()) {
                    String v = (String) e.getValue();
                    try { // saat dilimli ISO zaman: Türkiye saatine
                        v = java.time.OffsetDateTime.parse(v).atZoneSameInstant(Fmt.TR).toLocalTime().toString();
                    } catch (java.time.format.DateTimeParseException ignored) {
                        // yerel saat ("14:30", "2026-10-20T14:30:00")
                    }
                    Matcher c = CLOCK.matcher(v);
                    if (c.find()) time = String.format(Locale.ROOT, "%02d:%s", Integer.parseInt(c.group(1)), c.group(2));
                }
            }
            for (Map.Entry<String, Object> e : m.entrySet()) harvest(e.getValue(), path + "." + e.getKey(), place, number, time, out, depth + 1);
        }
    }

    /** Koşu listesinin üst yolu ("...races[3].horses" -> "...races"): aynı programın koşuları. */
    static String parent(String path) {
        int at = path.lastIndexOf('[');
        return at < 0 ? path : path.substring(0, at);
    }

    /**
     * Altılılar: aynı programdaki (aynı üst yol ve hipodrom) AGF'li koşular. Tam 6 ise bir altılı; daha çoksa ilk 6
     * ve son 6 ayrı ayrı sunulur (hangisinin 1. altılı olduğu sitede yazmıyorsa kullanıcı seçer).
     */
    static List<Map<String, Object>> altilis(String site, List<Race> races) {
        return altilis(site, races, new ArrayList<PoolInfo>());
    }

    /** Hipodromun altılı bilgisi: aynı hipodromda birden çok altılı varsa sırayla (ilk altılı ilk bilgi). */
    private static PoolInfo poolFor(String place, int nth, List<PoolInfo> pools) {
        List<PoolInfo> same = new ArrayList<>();
        for (PoolInfo p : pools) if (place == null ? p.place == null : place.equalsIgnoreCase(String.valueOf(p.place))) same.add(p);
        if (same.isEmpty() && pools.size() == 1) same.add(pools.get(0));
        return same.isEmpty() ? null : same.get(Math.min(nth, same.size() - 1));
    }

    static List<Map<String, Object>> altilis(String site, List<Race> races, List<PoolInfo> pools) {
        Map<String, List<Race>> groups = new LinkedHashMap<>();
        for (Race r : races) {
            if (!r.hasAgf()) continue;
            String g = parent(r.path) + "|" + r.place;
            List<Race> l = groups.get(g);
            if (l == null) groups.put(g, l = new ArrayList<>());
            l.add(r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Race> g : groups.values()) {
            if (g.size() < 6) continue;
            int[] starts = g.size() == 6 ? new int[] {0} : new int[] {0, g.size() - 6};
            int nth = 0;
            for (int from : starts) {
                List<Race> six = g.subList(from, from + 6);
                List<Object> legs = new ArrayList<>();
                boolean odds = true;
                for (Race r : six) {
                    legs.add(r.legText());
                    odds &= r.hasOdds();
                }
                if (!seen.add(legs.toString())) continue;
                Race first = six.get(0), last = six.get(5);
                String place = first.place == null ? "" : first.place + " ";
                String range = first.number != null && last.number != null ? first.number + "–" + last.number + ". koşu" : (from + 1) + "–" + (from + 6) + ". AGF'li koşu";
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("key", site.toLowerCase(Locale.ROOT) + "/" + place + range);
                a.put("source", site);
                a.put("label", place + range);
                if (first.place != null) a.put("city", first.place);
                a.put("legs", legs);
                a.put("odds", odds);
                if (first.time != null) a.put("start", first.time); // 1. ayağın saati (Türkiye)
                List<Object> raceInfo = new ArrayList<>(); // 2.27: ayak ayak koşu numarası ve saati
                boolean any = false;
                for (Race r : six) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (r.number != null) m.put("no", r.number);
                    if (r.time != null) m.put("time", r.time);
                    any |= !m.isEmpty();
                    raceInfo.add(m);
                }
                if (any) a.put("races", raceInfo);
                PoolInfo info = poolFor(first.place, nth++, pools);
                if (info != null) {
                    if (info.unit != null) a.put("unit", info.unit);
                    if (info.devir != null) a.put("devir", info.devir);
                    if (info.pool != null) a.put("pool", info.pool);
                }
                out.add(a);
            }
        }
        return out;
    }

    // ---- sayfa, gömülü veri, betikler -----------------------------------------------------------------------

    private static final Pattern JSON_SCRIPT = Pattern.compile("(?is)<script[^>]*type\\s*=\\s*[\"']application/(?:ld\\+)?json[\"'][^>]*>(.*?)</script>");
    private static final Pattern STATE_SCRIPT = Pattern.compile("(?is)window\\.(__[A-Z_]{3,40}__)\\s*=\\s*(\\{.*?\\})\\s*;?\\s*</script>");
    private static final Pattern SCRIPT_SRC = Pattern.compile("(?i)<script[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']");
    /** Betikte at yarışı veri yolu adayları. */
    private static final Pattern DATA_PATH = Pattern.compile(
            "(?i)[\"'`]((?:https?:)?(?://[a-z0-9.-]+)?/[a-z0-9_/.?=&-]*(?:at-?yaris|horse|race|yaris|kosu|agf|ganyan|altili|hipodrom|hippodrome)[a-z0-9_/.?=&-]*)[\"'`]");
    private static final Pattern API_LIKE = Pattern.compile("(?i)(/api/|/service|/v\\d+/|//api\\.|\\.json)");

    static Map<String, String> headers(String accept, String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", accept);
        h.put("Accept-Language", "tr-TR,tr;q=0.9");
        h.put("Referer", referer);
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
        return h;
    }

    static String absolute(String page, String src) {
        if (src.startsWith("http://") || src.startsWith("https://")) return src;
        if (src.startsWith("//")) return "https:" + src;
        if (src.startsWith("?")) return page.replaceFirst("\\?.*$", "") + src; // yalnızca sorgu: aynı sayfa
        int host = page.indexOf('/', page.indexOf("//") + 2);
        String origin = host < 0 ? page : page.substring(0, host);
        return src.startsWith("/") ? origin + src : origin + "/" + src;
    }

    private static String host(String url) {
        int s = url.indexOf("//");
        if (s < 0) return "";
        int e = url.indexOf('/', s + 2);
        return (e < 0 ? url.substring(s + 2) : url.substring(s + 2, e)).toLowerCase(Locale.ROOT);
    }

    static String shorten(String s, int n) {
        String t = String.valueOf(s).replaceAll("\\s+", " ").trim();
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }

    /** JSON'un kısa yapı özeti (üst alanlar ve ilk dizinin örnek elemanı): kesin okuyucuyu yazmak için. */
    @SuppressWarnings("unchecked")
    static String shape(Object v, int depth) {
        if (v instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) v;
            StringBuilder b = new StringBuilder("{");
            int i = 0;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (i++ >= 12) {
                    b.append("…");
                    break;
                }
                b.append(i > 1 ? ", " : "").append(e.getKey());
                if (depth < 3 && (e.getValue() instanceof Map || e.getValue() instanceof List)) b.append(": ").append(shape(e.getValue(), depth + 1));
            }
            return b.append('}').toString();
        }
        if (v instanceof List) {
            List<Object> l = (List<Object>) v;
            return "[" + l.size() + (l.isEmpty() || depth >= 3 ? "" : " × " + shape(l.get(0), depth + 1)) + "]";
        }
        return String.valueOf(v);
    }

    private static String fetch(Http http, String url, String accept, String referer, List<Object> log) {
        try {
            Http.Response r = http.get(url, headers(accept, referer));
            String body = r.body == null ? "" : r.body;
            log.add("  " + url + " -> HTTP " + r.status + ", " + body.length() / 1024 + " KB");
            return body;
        } catch (Http.ProviderException | RuntimeException e) {
            log.add("  " + url + " -> alınamadı: " + shorten(e.getMessage(), 160));
            return null;
        }
    }

    /** JSON metnindeki koşular; JSON değilse null. */
    private static List<Race> races(String json, String where, List<Object> log, List<PoolInfo> pools) {
        Object root;
        try {
            root = Json.parse(json.trim());
        } catch (RuntimeException e) {
            return null;
        }
        List<Race> out = new ArrayList<>();
        harvest(root, "$", null, null, out, 0);
        int before = pools.size();
        pools(root, null, pools, 0);
        for (int i = before; i < pools.size(); i++) {
            PoolInfo p = pools.get(i);
            log.add("    altılı bilgisi" + (p.place == null ? "" : " · " + p.place) + ": birim " + p.unit + ", devir " + p.devir + ", havuz " + p.pool);
        }
        int agf = 0, odds = 0;
        for (Race r : out) {
            if (r.hasAgf()) agf++;
            if (r.hasOdds()) odds++;
        }
        log.add("    " + where + ": " + out.size() + " koşucu listesi (AGF'li " + agf + ", ganyanlı " + odds + ")"
                + (out.isEmpty() ? "; yapı: " + shorten(shape(root, 0), 400) : ""));
        for (int i = 0; i < out.size() && i < MAX_RACES_LOGGED; i++) {
            Race r = out.get(i);
            log.add("      " + r.path + (r.place == null ? "" : " · " + r.place) + (r.number == null ? "" : " · koşu " + r.number)
                    + ": " + r.runners.size() + " at, " + shorten(r.legText().isEmpty() ? first(r) : r.legText(), 80));
        }
        return out;
    }

    private static String first(Race r) {
        Runner x = r.runners.get(0);
        return x.no + " ganyan " + x.odds;
    }

    /** Bir sitenin sayfası, gömülü verisi, betiklerdeki veri adresleri; bulunan koşular. */
    static List<Race> site(Http http, String name, String page, String domain, List<Object> log, Daily.Progress progress) {
        return site(http, name, page, domain, log, progress, new ArrayList<PoolInfo>());
    }

    static List<Race> site(Http http, String name, String page, String domain, List<Object> log, Daily.Progress progress, List<PoolInfo> pools) {
        List<Race> all = new ArrayList<>();
        if (progress != null) progress.step(name + " at yarışı sayfası inceleniyor…");
        String html = fetch(http, page, "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8", page, log);
        if (html == null) return all;
        int blocks = 0;
        Matcher j = JSON_SCRIPT.matcher(html);
        while (j.find()) {
            List<Race> r = races(j.group(1), "gömülü veri " + (++blocks) + " (" + j.group(1).length() / 1024 + " KB)", log, pools);
            if (r != null) all.addAll(r);
        }
        Matcher st = STATE_SCRIPT.matcher(html);
        while (st.find()) {
            List<Race> r = races(st.group(2), "window." + st.group(1), log, pools);
            if (r != null) all.addAll(r);
            blocks++;
        }
        if (blocks == 0) log.add("    gömülü veri yok (" + shorten(Tjk.text(html), 160) + ")");
        Set<String> paths = new LinkedHashSet<>();
        collectPaths(html, paths);
        List<String> scripts = new ArrayList<>();
        Matcher s = SCRIPT_SRC.matcher(html);
        while (s.find()) {
            String js = absolute(page, Tjk.decode(s.group(1)));
            if (host(js).contains(domain) && !scripts.contains(js)) scripts.add(js);
        }
        // at yarışına özgü görünen betikler önce
        List<String> ordered = new ArrayList<>();
        for (String js : scripts) if (DATA_PATH.matcher("\"" + js.replaceFirst("^https?://[^/]+", "") + "\"").find()) ordered.add(js);
        for (String js : scripts) if (!ordered.contains(js)) ordered.add(js);
        log.add("    betik: " + scripts.size() + " (en fazla " + MAX_SCRIPTS + " incelenir)");
        for (int i = 0; i < ordered.size() && i < MAX_SCRIPTS; i++) {
            if (progress != null) progress.step(name + " betikleri inceleniyor (" + (i + 1) + "/" + Math.min(MAX_SCRIPTS, ordered.size()) + ")…");
            String body = fetch(http, ordered.get(i), "*/*", page, log);
            if (body != null) collectPaths(body, paths);
        }
        log.add("    veri yolu adayları (" + paths.size() + "): " + (paths.isEmpty() ? "yok" : shorten(String.join(" ", paths), 900)));
        int tried = 0;
        for (String p : paths) {
            if (tried >= MAX_PATHS) break;
            if (!API_LIKE.matcher(p).find()) continue;
            String url = absolute(page, p.startsWith("//") ? "https:" + p : p);
            if (!host(url).contains(domain)) continue;
            tried++;
            if (progress != null) progress.step(name + " veri adresi deneniyor (" + tried + ")…");
            String body = fetch(http, url, "application/json, text/plain, */*", page, log);
            if (body == null) continue;
            List<Race> r = races(body, "yanıt", log, pools);
            if (r == null) log.add("    JSON değil: " + shorten(body, 160));
            else all.addAll(r);
        }
        return all;
    }

    static void collectPaths(String body, Set<String> paths) {
        Matcher m = DATA_PATH.matcher(body);
        while (m.find() && paths.size() < 40) {
            String p = m.group(1);
            if (p.length() < 4 || p.matches("(?i).*\\.(png|jpe?g|svg|webp|gif|css|woff2?|ico)(\\?.*)?$")) continue;
            paths.add(p);
        }
    }

    /** Bilyoner ve Nesine'den altılılar: {altilis: [{key, source, label, legs, odds}], log}. İstisna fırlatmaz. */
    public static Map<String, Object> read(Http http, Instant now, Daily.Progress progress) {
        List<Object> log = new ArrayList<>(), altilis = new ArrayList<>();
        for (String[] s : SITES) {
            log.add(s[0] + " (keşif: bu bölümü paylaşırsan okuma kesinleşir)");
            List<Race> races;
            List<PoolInfo> pools = new ArrayList<>();
            try {
                races = site(http, s[0], s[1], s[2], log, progress, pools);
            } catch (RuntimeException e) {
                log.add("  hata: " + shorten(e.getMessage(), 200));
                continue;
            }
            List<Map<String, Object>> found = altilis(s[0], races, pools);
            altilis.addAll(found);
            log.add("  " + s[0] + ": " + (found.isEmpty() ? "altılı bulunamadı" : found.size() + " altılı"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", now.toString());
        out.put("altilis", altilis);
        out.put("log", log);
        return out;
    }
}
