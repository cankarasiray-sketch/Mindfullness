package app.bilincli.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bilyoner keşfi (Zirve Oran'ı otomatik okumak için). Bilyoner kampanya oranlarını ("Zirve Oran")
 * belgelenmiş bir yerden vermiyor; körlemesine okuma yanlış oran okutabilir. Bu sınıf telefonda
 * herkese açık iddaa sayfasını ve sitenin betiklerini indirir; Zirve Oran'ın ("topWin") kod izlerini ve
 * kısa alan adlarını listeler; başlıksız açılan futbol bültenini ve Zirve Oran sekmesini indirip
 * maç alanlarını, Zirve sekmesine özgü alanları ve bültenden farklı oranları yazar. Okuyucu bu gerçek
 * veriye göre yazılacak. Hesap ya da oturum kullanılmaz, kredi harcanmaz.
 */
public final class BilyonerProbe {
    private BilyonerProbe() {}

    static final String[] PAGES = {"https://www.bilyoner.com/iddaa/zirve-oran", "https://www.bilyoner.com/iddaa"};
    static final int MAX_SCRIPTS = 4;
    static final String API = "https://www.bilyoner.com/api";
    /** Başlıksız erişilebilen bülten (2.1.4 keşfi: HTTP 200, ~4 MB). tabType 1 futbol, 2 basketbol (tahmin). */
    static final String BULLETIN = "/v3/mobile/aggregator/gamelist/all/v1?tabType=%d&bulletinType=2";
    static final String TABS = "/v3/mobile/aggregator/gamelist/all/maintabs/v2";
    /** Sekme listesinde "Zirve Oran" (2.1.5 keşfi). */
    static final int ZIRVE_TAB = 160;
    static final String[] ZIRVE_PATHS = {String.format(Locale.ROOT, BULLETIN, ZIRVE_TAB),
            "/v3/mobile/aggregator/gamelist/all/v1?tabType=" + ZIRVE_TAB + "&bulletinType=1",
            "/v3/mobile/aggregator/gamelist/all/v1?tabType=" + ZIRVE_TAB};
    static final int MAX_ZIRVE_SHOWN = 3;
    /** Futbol bülteninde görülen oran alanları (2.1.5): Zirve sekmesinde bunların dışındakiler aranır. */
    static final List<String> BASE_ODD_KEYS = Arrays.asList("id", "mbs", "n", "val", "cht", "pr", "prl", "imo", "bt", "ei",
            "ocn", "mrId", "mrv", "mrt", "mrst", "mrs", "mrn", "hsc", "sov");

    private static final Pattern SCRIPT = Pattern.compile("(?i)<script[^>]+src=[\"']([^\"']+)[\"']");
    /** Zirve Oran kodda "topWin" adıyla geçiyor (topWinOdds, topWinEnabled). */
    private static final Pattern TOP_WIN = Pattern.compile("topWin[A-Za-z]*");
    /** Uzun adın kısa karşılığının tanımı: {topWinOdds:"two"} ya da e.topWinOdds="two". */
    private static final Pattern SHORT_DEF = Pattern.compile("\\b(topWin[A-Za-z]*|specialOddsType)\\s*[:=]\\s*[\"']([A-Za-z0-9_]{1,24})[\"']");
    /** Çeviri tablosunun kullanımı: topWinOdds:a[H.wBU.topWinOdds]. */
    private static final Pattern SHORT_USE = Pattern.compile("\\b(topWin[A-Za-z]*)\\s*:\\s*[\\w$]+\\[([\\w$.]{1,60})\\]");
    /** Ad tablosunun yeri (maç adı, tarih gibi alanların kısa adları da burada). */
    private static final Pattern NAME_TABLE = Pattern.compile("\\bspecialOddsType\\s*:\\s*[\"'][A-Za-z0-9_]{1,24}[\"']");
    /** Bülten veri yolları: "/v3/mobile/aggregator/gamelist/events/popular" gibi. */
    private static final Pattern GAMELIST = Pattern.compile("(?i)[\"'`](/[a-z0-9_/{}.$?=&-]*(?:gamelist|aggregator|bulletin|zirve)[a-z0-9_/{}.$?=&-]*)");
    /** İstek başlığı adayları. */
    private static final Pattern HEADER = Pattern.compile("(?i)[\"']((?:x-[a-z0-9-]{3,40})|platform-token|platform-type|client-token|device-id|app-version|channel-type)[\"']");
    /** Yanıtta Zirve Oran'a işaret edebilecek alan adları. */
    private static final Pattern ZIRVE_KEY = Pattern.compile("(?i)(topwin|zirve|boost)");

    /** Sayfaları, betikleri ve aday adresleri inceleyip özet satırları döndürür; istisna fırlatmaz. */
    public static String report(Http http) {
        StringBuilder b = new StringBuilder();
        String html = null, page = null;
        for (String p : PAGES) {
            html = fetch(http, p, "text/html", b);
            if (html != null) {
                page = p;
                break;
            }
        }
        Set<String> paths = new LinkedHashSet<>();
        if (html != null) {
            List<String> scripts = new ArrayList<>();
            Matcher m = SCRIPT.matcher(html);
            while (m.find() && scripts.size() < MAX_SCRIPTS * 3) scripts.add(absolute(page, m.group(1)));
            int scanned = 0;
            for (String js : scripts) {
                if (scanned >= MAX_SCRIPTS) break;
                if (!hostOf(js).endsWith("bilyoner.com")) continue; // yalnızca sitenin kendi betikleri
                String body = fetch(http, js, "*/*", b);
                scanned++;
                if (body != null) analyze(body, paths, b);
            }
        }
        probeEndpoints(http, paths, b);
        return b.length() == 0 ? "  (çıktı yok)\n" : b.toString();
    }

    private static Map<String, String> headers(String accept) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", accept);
        h.put("Accept-Language", "tr-TR,tr;q=0.9");
        h.put("Referer", "https://www.bilyoner.com/iddaa/zirve-oran");
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
        return h;
    }

    private static String fetch(Http http, String url, String accept, StringBuilder b) {
        try {
            Http.Response r = http.get(url, headers(accept));
            String body = r.body == null ? "" : r.body;
            b.append("  ").append(url).append(" -> HTTP ").append(r.status).append(", ").append(body.length()).append(" karakter\n");
            return body;
        } catch (Http.ProviderException | RuntimeException e) {
            b.append("  ").append(url).append(" -> alınamadı: ").append(shorten(String.valueOf(e.getMessage()), 160)).append('\n');
            return null;
        }
    }

    /** Betikte Zirve Oran izleri, kısa alan adları, bülten veri yolları ve başlık adayları. */
    static void analyze(String body, Set<String> paths, StringBuilder b) {
        snippets("\"topWin\" geçen yerler", TOP_WIN, body, 4, 90, b);
        shortNames(body, b);
        Set<String> found = new LinkedHashSet<>();
        Matcher g = GAMELIST.matcher(body);
        while (g.find() && found.size() < 15) found.add(g.group(1));
        if (!found.isEmpty()) {
            b.append("    bülten veri yolları (").append(found.size()).append("):\n");
            for (String s : found) b.append("      ").append(s).append('\n');
            paths.addAll(found);
        }
        Set<String> hs = new LinkedHashSet<>();
        Matcher h = HEADER.matcher(body);
        while (h.find() && hs.size() < 20) hs.add(h.group(1));
        if (!hs.isEmpty()) b.append("    istek başlığı adayları: ").append(String.join(", ", hs)).append('\n');
    }

    /**
     * Uzun alan adlarının API'deki kısa karşılıkları. Betik ham yanıtı {specialOddsType:a[H.wBU.specialOddsType]}
     * gibi bir tabloyla çeviriyor; Zirve Oran'ın (topWinOdds) bültende hangi kısa adla geldiği buradan okunur.
     */
    static void shortNames(String body, StringBuilder b) {
        Set<String> out = new LinkedHashSet<>();
        Matcher d = SHORT_DEF.matcher(body);
        while (d.find() && out.size() < 10) out.add(d.group(1) + " = \"" + d.group(2) + "\"");
        Matcher u = SHORT_USE.matcher(body);
        while (u.find() && out.size() < 16) out.add(u.group(1) + " <- " + u.group(2));
        if (!out.isEmpty()) b.append("    kısa ad eşlemesi: ").append(String.join(" · ", out)).append('\n');
        Matcher t = NAME_TABLE.matcher(body);
        if (t.find()) {
            int from = Math.max(0, t.start() - 700), to = Math.min(body.length(), t.end() + 700);
            b.append("    ad tablosu: … ").append(body.substring(from, to).replaceAll("\\s+", " ")).append(" …\n");
        }
    }

    private static void snippets(String title, Pattern p, String body, int max, int radius, StringBuilder b) {
        Matcher m = p.matcher(body);
        int count = 0;
        List<String> out = new ArrayList<>();
        while (m.find()) {
            count++;
            if (out.size() < max) {
                int from = Math.max(0, m.start() - radius), to = Math.min(body.length(), m.end() + radius);
                out.add(body.substring(from, to).replaceAll("\\s+", " ").trim());
            }
        }
        if (count == 0) return;
        b.append("    ").append(title).append(": ").append(count).append('\n');
        for (String s : out) b.append("      … ").append(s).append(" …\n");
    }

    /** Futbol/basketbol bülteninde görülen alanlar ve oranlar; Zirve sekmesi bunlarla karşılaştırılır. */
    static final class Known {
        final Set<String> eventKeys = new LinkedHashSet<>();
        final Set<String> oddKeys = new LinkedHashSet<>(BASE_ODD_KEYS);
        /** oran kimliği ("maç:pazar:sonuç") -> bültendeki oran */
        final Map<String, String> vals = new HashMap<>();
    }

    /**
     * Bilyoner bülteni: sekme listesi, futbol/basketbol bülteni ve Zirve Oran sekmesi (tabType 160)
     * indirilir. Futbol maçının alanları (takım adları, başlama saati) ve Zirve sekmesinde futbol
     * bülteninde olmayan alanlar ile bültenden farklı oranlar yazılır; okuyucu bunlara göre yazılacak.
     */
    static void probeEndpoints(Http http, Set<String> found, StringBuilder b) {
        b.append("  Bilyoner bülteni\n");
        String tabs = get(http, API + TABS, b);
        if (tabs != null) b.append("      ").append(shorten(tabs.replaceAll("\\s+", " "), 300)).append('\n');
        Known known = new Known();
        for (int tab = 1; tab <= 2; tab++) {
            String body = get(http, API + String.format(Locale.ROOT, BULLETIN, tab), b);
            if (body != null) describeBulletin(body, tab == 1, known, b);
        }
        b.append("  Zirve Oran sekmesi (tabType ").append(ZIRVE_TAB).append(")\n");
        for (String path : ZIRVE_PATHS) {
            String body = get(http, API + path, b);
            if (body != null && describeZirve(body, known, b)) break;
        }
    }

    private static String get(Http http, String url, StringBuilder b) {
        try {
            Http.Response r = http.get(url, headers("application/json"));
            String body = r.body == null ? "" : r.body;
            b.append("    ").append(url).append(" -> HTTP ").append(r.status).append(", ").append(body.length()).append(" karakter\n");
            if (r.status == 200) return body;
            b.append("      ").append(shorten(body.replaceAll("\\s+", " "), 200)).append('\n');
            return null;
        } catch (Http.ProviderException | RuntimeException e) {
            b.append("    ").append(url).append(" -> ").append(shorten(String.valueOf(e.getMessage()), 160)).append('\n');
            return null;
        }
    }

    private static Map<String, Object> parseRoot(String body, StringBuilder b) {
        try {
            Map<String, Object> root = Json.obj(Json.parse(body));
            if (root == null) b.append("      beklenmeyen biçim: ").append(shorten(body.replaceAll("\\s+", " "), 300)).append('\n');
            return root;
        } catch (RuntimeException e) {
            b.append("      JSON değil: ").append(shorten(body.replaceAll("\\s+", " "), 200)).append('\n');
            return null;
        }
    }

    /** Bülten özeti; alanlar ve oranlar known'a eklenir. full: ilk maçın alanları (oranlar hariç) ve ilk oranı da yazılır. */
    static void describeBulletin(String body, boolean full, Known known, StringBuilder b) {
        int raw = 0;
        Matcher tw = TOP_WIN.matcher(body);
        while (tw.find()) raw++;
        Map<String, Object> root = parseRoot(body, b);
        if (root == null) return;
        List<Map<String, Object>> events = eventsOf(root);
        int zirve = 0;
        for (Map<String, Object> e : events) {
            known.eventKeys.addAll(e.keySet());
            if (mentions(e, 0)) zirve++;
            for (Map<String, Object> o : odds(e)) {
                known.oddKeys.addAll(o.keySet());
                known.vals.put(String.valueOf(o.get("id")), String.valueOf(o.get("val")));
            }
        }
        b.append("      maç: ").append(events.size()).append(" · \"topWin\" geçen yer: ").append(raw)
                .append(" · Zirve alanı taşıyan maç: ").append(zirve).append('\n');
        if (!full || events.isEmpty()) return;
        Map<String, Object> first = events.get(0);
        b.append("      ilk maçın alanları: ").append(header(first, 1500)).append('\n');
        List<Map<String, Object>> os = odds(first);
        if (!os.isEmpty()) b.append("      ilk oranı: ").append(shorten(Json.write(os.get(0)), 500)).append('\n');
    }

    /**
     * Zirve sekmesi: maçların alanları, futbol bülteninde görülmeyen maç/oran alanları (örnek değerle)
     * ve aynı oran kimliğinin bültenden farklı olduğu yerler. Maç bulunduysa true.
     */
    static boolean describeZirve(String body, Known known, StringBuilder b) {
        Map<String, Object> root = parseRoot(body, b);
        if (root == null) return false;
        List<Map<String, Object>> events = eventsOf(root);
        b.append("      üst alanlar: ").append(String.join(", ", root.keySet())).append(" · maç: ").append(events.size()).append('\n');
        if (events.isEmpty()) {
            b.append("      ").append(shorten(body.replaceAll("\\s+", " "), 1500)).append('\n');
            return false;
        }
        Map<String, String> newEvent = new LinkedHashMap<>(), newOdd = new LinkedHashMap<>();
        Map<String, Integer> newOddCount = new LinkedHashMap<>();
        List<String> diffs = new ArrayList<>();
        int shown = 0;
        for (Map<String, Object> e : events) {
            for (Map.Entry<String, Object> x : e.entrySet()) {
                if (!known.eventKeys.contains(x.getKey()) && !newEvent.containsKey(x.getKey())) newEvent.put(x.getKey(), sample(x.getValue()));
            }
            List<Map<String, Object>> os = odds(e);
            boolean show = shown < MAX_ZIRVE_SHOWN;
            if (show) b.append("      Zirve maçı: ").append(header(e, 1200)).append('\n');
            int printed = 0;
            for (Map<String, Object> o : os) {
                boolean unusual = false;
                for (Map.Entry<String, Object> x : o.entrySet()) {
                    if (known.oddKeys.contains(x.getKey())) continue;
                    unusual = true;
                    if (!newOdd.containsKey(x.getKey())) newOdd.put(x.getKey(), sample(x.getValue()));
                    Integer c = newOddCount.get(x.getKey());
                    newOddCount.put(x.getKey(), c == null ? 1 : c + 1);
                }
                String id = String.valueOf(o.get("id")), val = String.valueOf(o.get("val"));
                String was = known.vals.get(id);
                boolean differs = was != null && !was.equals(val);
                if (differs && diffs.size() < 12) diffs.add(o.get("n") + " (" + id + "): bülten " + was + " -> Zirve sekmesi " + val);
                if (show && printed < 3 && (unusual || differs || mentions(o, 0))) {
                    b.append("        oran: ").append(shorten(Json.write(o), 600)).append('\n');
                    printed++;
                }
            }
            if (show && printed == 0 && !os.isEmpty()) b.append("        ilk oran: ").append(shorten(Json.write(os.get(0)), 600)).append('\n');
            shown++;
        }
        b.append("      futbol bülteninde olmayan maç alanları: ").append(newEvent.isEmpty() ? "yok" : joinSamples(newEvent, null)).append('\n');
        b.append("      futbol bülteninde olmayan oran alanları: ").append(newOdd.isEmpty() ? "yok" : joinSamples(newOdd, newOddCount)).append('\n');
        b.append("      bültenden farklı oranlar: ").append(diffs.isEmpty() ? "yok" : "").append('\n');
        for (String d : diffs) b.append("        ").append(d).append('\n');
        return true;
    }

    /** Bültenin maçları: "events" sözlük ya da liste olabilir. */
    static List<Map<String, Object>> eventsOf(Map<String, Object> root) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object ev = root.get("events");
        Iterable<?> items = ev instanceof Map ? ((Map<?, ?>) ev).values() : Json.arr(ev);
        for (Object o : items) {
            Map<String, Object> e = Json.obj(o);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Maçın tüm oranları (marketGroups[].odds[]). */
    static List<Map<String, Object>> odds(Map<String, Object> e) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object g : Json.arr(e.get("marketGroups"))) {
            Map<String, Object> gm = Json.obj(g);
            if (gm == null) continue;
            for (Object o : Json.arr(gm.get("odds"))) {
                Map<String, Object> om = Json.obj(o);
                if (om != null) out.add(om);
            }
        }
        return out;
    }

    /** Maçın alanları; oran grupları ve uzun listeler sayıyla özetlenir. */
    static String header(Map<String, Object> e, int max) {
        Map<String, Object> h = new LinkedHashMap<>();
        for (Map.Entry<String, Object> x : e.entrySet()) {
            Object v = x.getValue();
            if (x.getKey().equals("marketGroups")) v = "(" + odds(e).size() + " oran)";
            else if (v instanceof List && ((List<?>) v).size() > 4) v = "(" + ((List<?>) v).size() + " öğe)";
            else if (v instanceof Map && ((Map<?, ?>) v).size() > 12) v = "(" + ((Map<?, ?>) v).size() + " alan)";
            h.put(x.getKey(), v);
        }
        return shorten(Json.write(h), max);
    }

    private static String sample(Object v) {
        return shorten(v instanceof String ? (String) v : Json.write(v), 80);
    }

    private static String joinSamples(Map<String, String> m, Map<String, Integer> counts) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> x : m.entrySet()) {
            Integer c = counts == null ? null : counts.get(x.getKey());
            out.add(x.getKey() + "=" + x.getValue() + (c == null ? "" : " (" + c + " oranda)"));
        }
        return String.join(", ", out);
    }

    /** Nesnede (derinlemesine) Zirve Oran'a işaret eden, değeri boş olmayan bir alan var mı. */
    @SuppressWarnings("unchecked")
    static boolean mentions(Object o, int depth) {
        if (depth > 8) return false;
        if (o instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                Object v = e.getValue();
                boolean set = v != null && !Boolean.FALSE.equals(v) && !(v instanceof Number && ((Number) v).doubleValue() == 0);
                if (ZIRVE_KEY.matcher(e.getKey()).find() && set) return true;
                if (mentions(v, depth + 1)) return true;
            }
        } else if (o instanceof List) {
            for (Object x : (List<Object>) o) if (mentions(x, depth + 1)) return true;
        }
        return false;
    }

    private static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static String hostOf(String url) {
        int start = url.indexOf("://");
        if (start < 0) return "";
        int end = url.indexOf('/', start + 3);
        return url.substring(start + 3, end < 0 ? url.length() : end).toLowerCase(java.util.Locale.ROOT);
    }

    static String absolute(String page, String src) {
        if (src.startsWith("http://") || src.startsWith("https://")) return src;
        if (src.startsWith("//")) return "https:" + src;
        String origin = page.substring(0, page.indexOf('/', 8));
        return origin + (src.startsWith("/") ? src : "/" + src);
    }
}
