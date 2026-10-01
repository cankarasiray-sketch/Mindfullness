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

    /** Hata mesajlarında API anahtarı görünmesin. */
    static String safe(String url) {
        return url.replaceAll("(?i)(apiKey|token)=[^&]*", "$1=***");
    }

    final class UrlHttp implements Http {
        @Override
        public Response get(String url, Map<String, String> headers) throws ProviderException {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(45000);
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
            } catch (IOException e) {
                throw new ProviderException(safe(url) + " -> bağlantı hatası: " + e.getMessage());
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
