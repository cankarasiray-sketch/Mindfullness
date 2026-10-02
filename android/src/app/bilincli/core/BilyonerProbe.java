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
 * Bilyoner keşfi (Süper Oran için ilk adım). Bilyoner'in kampanya oranlarını hangi adresten ve hangi
 * biçimde verdiği bilinmiyor; körlemesine ayrıştırıcı yanlış oran okutabilir. Bu sınıf telefonda
 * herkese açık iddaa sayfasını ve sayfanın yüklediği ilk betikleri indirir, "Kaynakları test et"
 * çıktısına yapılarının özetini yazar: durum, boyut, gömülü veri, "süper oran" geçen yerler ve
 * API'ye benzeyen adresler. Ayrıştırıcı bu gerçek veriye göre yazılacak. Hesap ya da oturum
 * kullanılmaz, kredi harcanmaz.
 */
public final class BilyonerProbe {
    private BilyonerProbe() {}

    static final String[] PAGES = {"https://www.bilyoner.com/iddaa", "https://m.bilyoner.com/iddaa"};
    static final int MAX_SCRIPTS = 4;

    /** Kampanya metni (yalnızca Türkçe ad; "boost" başka özelliklerde de geçiyor). */
    private static final Pattern SUPER = Pattern.compile("(?i)(s[üu]per\\s?oran|oran\\s?art[ıi][şs]|art[ıi]r[ıi]lm[ıi][şs]\\s?oran|[öo]zel\\s?oran)");
    private static final Pattern API = Pattern.compile("(?i)((?:https?:)?//[a-z0-9.-]*bilyoner\\.com)?(/api/[a-z0-9_./?=&{}-]{3,120})");
    private static final Pattern SCRIPT = Pattern.compile("(?i)<script[^>]+src=[\"']([^\"']+)[\"']");
    private static final Pattern HOST = Pattern.compile("(?i)https?://([a-z0-9-]+(?:\\.[a-z0-9-]+)+)");
    /** Sürüm numaralı yol: "/sportsbook/v2/events" gibi. */
    private static final Pattern VERSIONED = Pattern.compile("(?i)[\"'`](/(?:[a-z0-9_-]+/){0,4}v[1-9][0-9]?/[a-z0-9_/{}.$-]{2,80})");
    /** Oran, bülten ve kampanyayla ilgili yollar. */
    private static final Pattern TOPIC_PATH = Pattern.compile("(?i)[\"'`](/[a-z0-9_/{}.$-]*(?:bulletin|bulten|program|odds|oran|event|match|market|boost|special|ozel|promo|campaign|kampanya)[a-z0-9_/{}.$-]*)");
    /** Kampanya oranı alanına benzeyen tanımlayıcılar (ör. boostedOdd, isSpecial, superOdds). */
    private static final Pattern KEY = Pattern.compile("\\b([a-zA-Z]*(?:[Bb]oost|[Ss]pecial|[Ee]nhanced|[Ii]ncreased|[Ss]uper[A-Z]|[Oo]zel|[Aa]rtir|[Pp]romo)[a-zA-Z]*)\\b");

    /** Sayfaları ve ilk betikleri indirip özet satırları döndürür; hiçbir durumda istisna fırlatmaz. */
    public static String report(Http http) {
        StringBuilder b = new StringBuilder();
        for (String page : PAGES) {
            String html = fetch(http, page, "text/html", b);
            if (html == null) continue;
            summarize(page, html, b);
            List<String> scripts = new ArrayList<>();
            Matcher m = SCRIPT.matcher(html);
            while (m.find() && scripts.size() < MAX_SCRIPTS * 3) scripts.add(absolute(page, m.group(1)));
            int scanned = 0;
            for (String js : scripts) {
                if (scanned >= MAX_SCRIPTS) break;
                if (!hostOf(js).endsWith("bilyoner.com")) continue; // yalnızca sitenin kendi betikleri
                String body = fetch(http, js, "*/*", b);
                scanned++;
                if (body != null) summarize(js, body, b);
            }
            break; // ilk açılan sayfa yeterli
        }
        return b.length() == 0 ? "  (çıktı yok)\n" : b.toString();
    }

    private static String fetch(Http http, String url, String accept, StringBuilder b) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Accept", accept);
        h.put("Accept-Language", "tr-TR,tr;q=0.9");
        h.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36");
        try {
            Http.Response r = http.get(url, h);
            String body = r.body == null ? "" : r.body;
            b.append("  ").append(url).append(" -> HTTP ").append(r.status).append(", ").append(body.length()).append(" karakter\n");
            return body;
        } catch (Http.ProviderException | RuntimeException e) {
            b.append("  ").append(url).append(" -> alınamadı: ").append(String.valueOf(e.getMessage())).append('\n');
            return null;
        }
    }

    static void summarize(String url, String body, StringBuilder b) {
        if (body.contains("__NEXT_DATA__")) b.append("    gömülü veri: __NEXT_DATA__ var\n");
        if (body.contains("__NUXT__")) b.append("    gömülü veri: __NUXT__ var\n");
        if (body.contains("__INITIAL_STATE__") || body.contains("__PRELOADED_STATE__")) b.append("    gömülü veri: başlangıç durumu var\n");
        Matcher m = SUPER.matcher(body);
        int count = 0;
        List<String> snippets = new ArrayList<>();
        while (m.find()) {
            count++;
            if (snippets.size() < 3) {
                int from = Math.max(0, m.start() - 50), to = Math.min(body.length(), m.end() + 70);
                snippets.add(body.substring(from, to).replaceAll("\\s+", " ").trim());
            }
        }
        if (count > 0) {
            b.append("    kampanya metni (süper oran / oran artışı / özel oran): ").append(count).append('\n');
            for (String s : snippets) b.append("      … ").append(s).append(" …\n");
        }
        list("API adresleri", API, body, 2, 12, b);
        list("sunucular", HOST, body, 1, 12, b);
        list("sürümlü veri yolları", VERSIONED, body, 1, 15, b);
        list("oran / bülten / kampanya yolları", TOPIC_PATH, body, 1, 15, b);
        list("kampanya alanı adayları", KEY, body, 1, 15, b);
    }

    /** Desenin eşleşmelerini sıklığa göre (en sık önce) yazar; group: alınacak grup. */
    private static void list(String title, Pattern p, String body, int group, int max, StringBuilder b) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        Matcher m = p.matcher(body);
        int guard = 0;
        while (m.find() && guard++ < 200000) {
            String v = group == 2 && m.group(1) != null ? m.group(1) + m.group(2) : m.group(group);
            if (v == null || v.length() < 3) continue;
            Integer c = seen.get(v);
            seen.put(v, c == null ? 1 : c + 1);
        }
        if (seen.isEmpty()) return;
        List<Map.Entry<String, Integer>> e = new ArrayList<>(seen.entrySet());
        java.util.Collections.sort(e, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> x, Map.Entry<String, Integer> y) {
                return Integer.compare(y.getValue(), x.getValue());
            }
        });
        b.append("    ").append(title).append(" (").append(seen.size()).append("):\n");
        for (int i = 0; i < Math.min(max, e.size()); i++) {
            b.append("      ").append(e.get(i).getKey()).append(e.get(i).getValue() > 1 ? " ×" + e.get(i).getValue() : "").append('\n');
        }
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
