package app.bilincli.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bilyoner keşfi (Zirve Oran'ı otomatik okumak için). Bilyoner kampanya oranlarını ("Zirve Oran")
 * belgelenmiş bir yerden vermiyor; körlemesine okuma yanlış oran okutabilir. Bu sınıf telefonda
 * herkese açık iddaa sayfasını ve sitenin betiklerini indirir; Zirve Oran'ın ("topWin") kod izlerini,
 * bülten veri yollarını ve istek başlığı adaylarını listeler; başlıksız açılan bülteni indirip bir
 * maçın ve Zirve Oran'lı bir maçın tam yapısını yazar. Okuyucu bu gerçek veriye göre yazılacak. Hesap ya da
 * oturum kullanılmaz, kredi harcanmaz.
 */
public final class BilyonerProbe {
    private BilyonerProbe() {}

    static final String[] PAGES = {"https://www.bilyoner.com/iddaa/zirve-oran", "https://www.bilyoner.com/iddaa"};
    static final int MAX_SCRIPTS = 4;
    static final String API = "https://www.bilyoner.com/api";
    /** Başlıksız erişilebilen bülten (2.1.4 keşfi: HTTP 200, ~4 MB). tabType 1 futbol, 2 basketbol (tahmin). */
    static final String BULLETIN = "/v3/mobile/aggregator/gamelist/all/v1?tabType=%d&bulletinType=2";
    static final String TABS = "/v3/mobile/aggregator/gamelist/all/maintabs/v2";

    private static final Pattern SCRIPT = Pattern.compile("(?i)<script[^>]+src=[\"']([^\"']+)[\"']");
    /** Zirve Oran kodda "topWin" adıyla geçiyor (topWinOdds, topWinEnabled). */
    private static final Pattern TOP_WIN = Pattern.compile("topWin[A-Za-z]*");
    private static final Pattern ZIRVE_ROUTE = Pattern.compile("zirve-oran|zirveOran|ZIRVE");
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

    /** Betikte Zirve Oran izleri, bülten veri yolları ve başlık adayları. */
    static void analyze(String body, Set<String> paths, StringBuilder b) {
        snippets("\"topWin\" geçen yerler", TOP_WIN, body, 10, 120, b);
        snippets("Zirve Oran sayfası/sekmesi geçen yerler", ZIRVE_ROUTE, body, 6, 150, b);
        Set<String> found = new LinkedHashSet<>();
        Matcher g = GAMELIST.matcher(body);
        while (g.find() && found.size() < 40) found.add(g.group(1));
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

    /**
     * Bilyoner bülteni: sekme listesi ve futbol/basketbol bülteni indirilir; bir maçın ve (varsa)
     * Zirve Oran'lı bir maçın tam yapısı yazılır. Okuyucu bu alan adlarına göre yazılacak.
     */
    static void probeEndpoints(Http http, Set<String> found, StringBuilder b) {
        b.append("  Bilyoner bülteni\n");
        String tabs = get(http, API + TABS, b);
        if (tabs != null) b.append("      ").append(shorten(tabs.replaceAll("\\s+", " "), 900)).append('\n');
        for (int tab = 1; tab <= 2; tab++) {
            String body = get(http, API + String.format(java.util.Locale.ROOT, BULLETIN, tab), b);
            if (body != null) describeBulletin(body, tab == 1, b);
        }
    }

    private static String get(Http http, String url, StringBuilder b) {
        try {
            Http.Response r = http.get(url, headers("application/json"));
            String body = r.body == null ? "" : r.body;
            b.append("    ").append(url).append(" -> HTTP ").append(r.status).append(", ").append(body.length()).append(" karakter\n");
            return body;
        } catch (Http.ProviderException | RuntimeException e) {
            b.append("    ").append(url).append(" -> ").append(shorten(String.valueOf(e.getMessage()), 160)).append('\n');
            return null;
        }
    }

    /** Bülten özeti; full: ilk maçın ve ilk Zirve maçının tam JSON'u da yazılır. */
    static void describeBulletin(String body, boolean full, StringBuilder b) {
        Matcher tw = TOP_WIN.matcher(body);
        int raw = 0;
        List<String> snips = new ArrayList<>();
        while (tw.find()) {
            raw++;
            if (snips.size() < 3) snips.add(body.substring(Math.max(0, tw.start() - 150), Math.min(body.length(), tw.end() + 150)));
        }
        Object json;
        try {
            json = Json.parse(body);
        } catch (RuntimeException e) {
            b.append("      JSON değil: ").append(shorten(body.replaceAll("\\s+", " "), 200)).append('\n');
            return;
        }
        Map<String, Object> root = Json.obj(json);
        if (root == null) {
            b.append("      beklenmeyen biçim\n");
            return;
        }
        Map<String, Object> events = Json.obj(root.get("events"));
        int zirve = 0;
        Map<String, Object> first = null, firstZirve = null;
        if (events != null) {
            for (Object ev : events.values()) {
                Map<String, Object> e = Json.obj(ev);
                if (e == null) continue;
                if (first == null) first = e;
                if (mentions(e, 0)) {
                    zirve++;
                    if (firstZirve == null) firstZirve = e;
                }
            }
        }
        b.append("      maç: ").append(events == null ? 0 : events.size()).append(" · \"topWin\" geçen yer: ").append(raw)
                .append(" · Zirve alanı taşıyan maç: ").append(zirve).append('\n');
        for (String sn : snips) b.append("      … ").append(sn.replaceAll("\\s+", " ")).append(" …\n");
        if (!full) return;
        List<Object> groups = Json.arr(root.get("marketGroups"));
        if (groups != null && !groups.isEmpty()) {
            b.append("      marketGroups[0]: ").append(shorten(Json.write(groups.get(0)), 500)).append('\n');
        }
        if (first != null) b.append("      ilk maç: ").append(shorten(Json.write(first), 1800)).append('\n');
        if (firstZirve != null && firstZirve != first) {
            b.append("      ilk Zirve maçı: ").append(shorten(Json.write(firstZirve), 2500)).append('\n');
        }
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
