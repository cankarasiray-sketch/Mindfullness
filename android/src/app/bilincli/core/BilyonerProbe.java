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
 * herkese açık iddaa sayfasını ve sitenin betiklerini indirir; Zirve Oran'la ilgili kod parçalarını,
 * bülten veri yollarını ve istek başlığı adaylarını listeler, aday veri adreslerini dener ve
 * yanıtların yapısını (alan adları) özetler. Okuyucu bu gerçek veriye göre yazılacak. Hesap ya da
 * oturum kullanılmaz, kredi harcanmaz.
 */
public final class BilyonerProbe {
    private BilyonerProbe() {}

    static final String[] PAGES = {"https://www.bilyoner.com/iddaa/zirve-oran", "https://www.bilyoner.com/iddaa"};
    static final int MAX_SCRIPTS = 4;
    static final int MAX_REQUESTS = 10;
    static final String[] BASES = {"https://www.bilyoner.com/api", "https://aping.bilyoner.com"};
    /** Topluluk projelerinde görülen bülten adresi (doğrulanmadı; denenecek). */
    static final String GUESS = "/v3/mobile/aggregator/gamelist/all/v1?tabType=1&bulletinType=2";

    private static final Pattern SCRIPT = Pattern.compile("(?i)<script[^>]+src=[\"']([^\"']+)[\"']");
    private static final Pattern ZIRVE = Pattern.compile("(?i)zirve");
    private static final Pattern SPECIAL_ODDS = Pattern.compile("specialOdds[A-Za-z]*");
    private static final Pattern TAB_TYPE = Pattern.compile("tabType");
    /** Bülten veri yolları: "/v3/mobile/aggregator/gamelist/events/popular" gibi. */
    private static final Pattern GAMELIST = Pattern.compile("(?i)[\"'`](/[a-z0-9_/{}.$?=&-]*(?:gamelist|aggregator|bulletin|zirve)[a-z0-9_/{}.$?=&-]*)");
    /** İstek başlığı adayları. */
    private static final Pattern HEADER = Pattern.compile("(?i)[\"']((?:x-[a-z0-9-]{3,40})|platform-token|platform-type|client-token|device-id|app-version|channel-type)[\"']");
    /** Yanıtta aranan alan adları. */
    private static final Pattern FIELD = Pattern.compile("(?i)(zirve|boost|special|increase|top|odd|oran|price|outcome|market|event|match|home|away)");

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
        snippets("\"zirve\" geçen yerler", ZIRVE, body, 8, 90, b);
        snippets("\"specialOdds\" geçen yerler", SPECIAL_ODDS, body, 3, 90, b);
        snippets("\"tabType\" geçen yerler", TAB_TYPE, body, 4, 70, b);
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
     * Aday veri adresleri: betiklerde bulunan, kullanıcıya özel olmayan (auth içermeyen, değişkensiz)
     * bülten yolları ve topluluk tahmini; iki olası kök adresle. En fazla MAX_REQUESTS istek.
     */
    static void probeEndpoints(Http http, Set<String> found, StringBuilder b) {
        List<String> candidates = new ArrayList<>();
        for (String p : found) {
            if (p.contains("${") || p.contains("/auth/") || !p.contains("gamelist")) continue;
            if (p.toLowerCase(java.util.Locale.ROOT).contains("zirve")) candidates.add(0, p);
            else candidates.add(p);
        }
        if (!candidates.contains(GUESS)) candidates.add(GUESS);
        b.append("  Aday veri adresleri\n");
        int requests = 0;
        String workingBase = null;
        for (String path : candidates) {
            for (String base : BASES) {
                if (workingBase != null && !workingBase.equals(base)) continue;
                if (requests++ >= MAX_REQUESTS) return;
                String url = base + path;
                try {
                    Http.Response r = http.get(url, headers("application/json"));
                    String body = r.body == null ? "" : r.body;
                    b.append("    ").append(url).append(" -> HTTP ").append(r.status).append(", ").append(body.length()).append(" karakter\n");
                    describe(body, b);
                    workingBase = base;
                    break;
                } catch (Http.ProviderException | RuntimeException e) {
                    b.append("    ").append(url).append(" -> ").append(shorten(String.valueOf(e.getMessage()), 140)).append('\n');
                }
            }
        }
    }

    /** JSON yanıtın yapısı: üst alanlar ve ilgili alan adları (ilk görüldükleri yolla). */
    static void describe(String body, StringBuilder b) {
        Object json;
        try {
            json = Json.parse(body);
        } catch (RuntimeException e) {
            b.append("      JSON değil: ").append(shorten(body.replaceAll("\\s+", " "), 160)).append('\n');
            return;
        }
        if (json instanceof Map) {
            List<String> top = new ArrayList<>();
            for (Map.Entry<String, Object> e : Json.obj(json).entrySet()) top.add(e.getKey() + ":" + kind(e.getValue()));
            b.append("      üst alanlar: ").append(shorten(String.join(", ", top), 300)).append('\n');
        } else if (json instanceof List) {
            b.append("      liste, ").append(((List<?>) json).size()).append(" öğe\n");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        walk(json, "", 0, fields);
        if (!fields.isEmpty()) {
            b.append("      ilgili alanlar:\n");
            int n = 0;
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (n++ >= 30) break;
                b.append("        ").append(e.getValue()).append('\n');
            }
        }
    }

    private static String kind(Object v) {
        if (v instanceof Map) return "nesne";
        if (v instanceof List) return "liste(" + ((List<?>) v).size() + ")";
        if (v instanceof String) return "metin";
        if (v instanceof Number) return "sayı";
        if (v instanceof Boolean) return "mantık";
        return "boş";
    }

    @SuppressWarnings("unchecked")
    private static void walk(Object o, String path, int depth, Map<String, String> out) {
        if (depth > 7 || out.size() >= 60) return;
        if (o instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                String p = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                if (FIELD.matcher(e.getKey()).find() && !out.containsKey(e.getKey())) {
                    Object v = e.getValue();
                    String sample = v instanceof Map || v instanceof List ? kind(v) : shorten(String.valueOf(v), 40);
                    out.put(e.getKey(), p + " = " + sample);
                }
                walk(e.getValue(), p, depth + 1, out);
            }
        } else if (o instanceof List && !((List<Object>) o).isEmpty()) {
            walk(((List<Object>) o).get(0), path + "[0]", depth + 1, out);
        }
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
