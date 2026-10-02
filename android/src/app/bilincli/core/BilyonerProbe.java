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
    static final int MAX_SCRIPTS = 3;

    private static final Pattern BOOST = Pattern.compile("(?i)(s[üu]per\\s?oran|superoran|super-oran|superodds|super_odds|boost|oran\\s?art[ıi]r)");
    private static final Pattern API = Pattern.compile("(?i)((?:https?:)?//[a-z0-9.-]*bilyoner\\.com)?(/api/[a-z0-9_./?=&{}-]{3,120})");
    private static final Pattern SCRIPT = Pattern.compile("(?i)<script[^>]+src=[\"']([^\"']+)[\"']");

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
                if (!js.contains("bilyoner")) continue; // yalnızca sitenin kendi betikleri
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
        Matcher m = BOOST.matcher(body);
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
            b.append("    \"süper oran\" geçen yer: ").append(count).append('\n');
            for (String s : snippets) b.append("      … ").append(s).append(" …\n");
        }
        Set<String> apis = new LinkedHashSet<>();
        Matcher a = API.matcher(body);
        while (a.find() && apis.size() < 15) apis.add((a.group(1) == null ? "" : a.group(1)) + a.group(2));
        if (!apis.isEmpty()) {
            b.append("    API adresleri (").append(apis.size()).append("):\n");
            for (String s : apis) b.append("      ").append(s).append('\n');
        }
    }

    static String absolute(String page, String src) {
        if (src.startsWith("http://") || src.startsWith("https://")) return src;
        if (src.startsWith("//")) return "https:" + src;
        String origin = page.substring(0, page.indexOf('/', 8));
        return origin + (src.startsWith("/") ? src : "/" + src);
    }
}
