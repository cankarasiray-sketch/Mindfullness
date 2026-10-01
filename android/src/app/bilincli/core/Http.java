package app.bilincli.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Basit HTTP GET. Testlerde sahte uygulama verilir. */
public interface Http {
    final class Response {
        public final int status;
        public final String body;
        public final Map<String, String> headers;

        public Response(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    final class ProviderException extends Exception {
        private static final long serialVersionUID = 1L;

        public ProviderException(String msg) {
            super(msg);
        }
    }

    Response get(String url, Map<String, String> headers) throws ProviderException;

    static String query(Map<String, String> params) {
        StringBuilder b = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : params.entrySet()) {
                b.append(b.length() == 0 ? "?" : "&").append(URLEncoder.encode(e.getKey(), "UTF-8"))
                        .append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return b.toString();
    }

    /**
     * Bağlantı sıfırlanması / TLS el sıkışmasının kesilmesi, Türkiye'de erişim engeli uygulanan
     * adreslerde tipik görüntüdür; kullanıcıya ne yapabileceğini söyleyen açıklama.
     */
    static String blockedHint(Exception e) {
        return blockedHint(e, "");
    }

    static boolean isReset(Exception e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return e instanceof java.net.SocketException || e instanceof javax.net.ssl.SSLException
                || m.contains("reset") || m.contains("handshake") || m.contains("closed by peer");
    }

    /** Kaynağa göre: Nesine VPN'siz (Türkiye) açılır, The Odds API Türkiye'den VPN ister. */
    static String blockedHint(Exception e, String url) {
        if (!isReset(e)) return "";
        if (url.contains("nesine.com")) {
            return ". Bağlantı Nesine tarafından kesildi: VPN açıksa, VPN'in çıktığı sunucu Nesine tarafından"
                    + " engelleniyor olabilir. VPN'de Türkiye'ye yakın başka bir sunucu seç ya da VPN uygulamasında"
                    + " bu uygulamanın VPN'i atlamasına (bypass) izin veren bir ayar varsa aç.";
        }
        return ". Bağlantı karşı taraftan kesildi: bu adrese bulunduğun ağdan erişim engelleniyor olabilir."
                + " VPN açıkken dene; uygulamanın sabah kararı ve maç öncesi kontrolleri için VPN'i"
                + " 'Her zaman açık' yap (Android: Ayarlar > Ağ > VPN).";
    }

    /** Hata mesajlarında API anahtarı görünmesin. */
    static String safe(String url) {
        return url.replaceAll("(?i)(apiKey|token)=[^&]*", "$1=***");
    }

    class UrlHttp implements Http {
        /** Bağlantı denemesi: attempt 0 ilk yol; Android sürümü Nesine için VPN dışı ağı dener. */
        protected HttpURLConnection open(URL u, int attempt) throws IOException {
            return (HttpURLConnection) u.openConnection();
        }

        /** Kopan bağlantı (sıfırlanma) kaç kez denensin. */
        protected int attempts(URL u) {
            return 2;
        }

        /** Bu deneme başarısız olursa hata türü ne olursa olsun sonraki yol denensin mi (ör. VPN dışı ağ). */
        protected boolean alwaysRetry(URL u, int attempt) {
            return false;
        }

        @Override
        public Response get(String url, Map<String, String> headers) throws ProviderException {
            URL u;
            try {
                u = new URL(url);
            } catch (IOException e) {
                throw new ProviderException(safe(url) + " -> geçersiz adres");
            }
            IOException last = null;
            int n = attempts(u);
            for (int attempt = 0; attempt < n; attempt++) {
                try {
                    return once(u, url, headers, attempt);
                } catch (IOException e) {
                    last = e;
                    if (alwaysRetry(u, attempt)) continue; // başka ağ yolu: beklemeden dene
                    if (!isReset(e)) break; // zaman aşımı vb.: yeniden deneme anlamsız
                    try {
                        Thread.sleep(1500L * (attempt + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            throw new ProviderException(safe(url) + " -> bağlantı hatası: " + last.getMessage() + blockedHint(last, url));
        }

        private Response once(URL u, String url, Map<String, String> headers, int attempt) throws IOException, ProviderException {
            HttpURLConnection c = null;
            try {
                c = open(u, attempt);
                if (c.getConnectTimeout() == 0) c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) BilincliKupon/1.0");
                c.setRequestProperty("Accept", "application/json");
                if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) {
                    c.setRequestProperty(h.getKey(), h.getValue());
                }
                int status = c.getResponseCode();
                InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in != null && "gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
                String body = in == null ? "" : readAll(in);
                Map<String, String> hs = new LinkedHashMap<>();
                for (Map.Entry<String, java.util.List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() != null && !e.getValue().isEmpty()) {
                        hs.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue().get(0));
                    }
                }
                if (status >= 400) {
                    String detail = body.length() > 200 ? body.substring(0, 200) : body;
                    throw new ProviderException(safe(url) + " -> HTTP " + status + ": " + detail);
                }
                return new Response(status, body, hs);
            } finally {
                if (c != null) c.disconnect();
            }
        }

        private static String readAll(InputStream in) throws IOException {
            try (InputStream s = in) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
