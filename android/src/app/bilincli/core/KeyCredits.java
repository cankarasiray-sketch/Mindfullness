package app.bilincli.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Birden fazla The Odds API anahtarının kredi kaydı. Her anahtar kendi hesabının kotasıdır; kalıcı
 * kayıtta anahtarın yerine Settings.keyId geçer. Kredi planı anahtarların toplamını tek kota gibi
 * kullanır.
 */
public final class KeyCredits {
    private KeyCredits() {}

    /**
     * JSON {kimlik: [kalan, kullanılan, ölçüm ms]} -> harita. notBeforeMs'ten eski ölçümler atılır:
     * kota o tarihten sonra yenilenmiş olabilir; anahtar bir sonraki sorguda yeniden ölçülür.
     */
    public static Map<String, long[]> parse(String json, long notBeforeMs) {
        Map<String, long[]> out = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            for (Map.Entry<String, Object> e : Json.parseObject(json).entrySet()) {
                List<Object> v = Json.arr(e.getValue());
                if (v == null || v.size() != 3) continue;
                long[] c = {((Number) v.get(0)).longValue(), ((Number) v.get(1)).longValue(), ((Number) v.get(2)).longValue()};
                if (c[2] >= notBeforeMs) out.put(e.getKey(), c);
            }
        } catch (RuntimeException e) {
            out.clear(); // bozuk kayıt: anahtarlar yeniden ölçülür
        }
        return out;
    }

    public static String write(Map<String, long[]> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : m.entrySet()) {
            List<Object> v = new ArrayList<>();
            for (long x : e.getValue()) v.add(x);
            out.put(e.getKey(), v);
        }
        return Json.write(out);
    }

    /** Eski kayıt + bu çalışmanın ölçümleri; yalnızca hâlâ girili anahtarlar kalır. */
    public static Map<String, long[]> merge(Map<String, long[]> old, Map<String, long[]> fresh, List<String> keys) {
        Map<String, long[]> out = new LinkedHashMap<>();
        for (String k : keys) {
            String id = Settings.keyId(k);
            long[] a = old.get(id), b = fresh.get(id);
            long[] c = a == null ? b : b == null ? a : (b[2] >= a[2] ? b : a);
            if (c != null) out.put(id, c);
        }
        return out;
    }

    /** {toplam kalan, toplam kullanılan}; hiç ölçüm yoksa null. */
    public static long[] totals(Map<String, long[]> m) {
        if (m.isEmpty()) return null;
        long rem = 0, used = 0;
        for (long[] c : m.values()) {
            rem += c[0];
            used += c[1];
        }
        return new long[] {rem, used};
    }

    /** Son kota yenilenmesinin başlangıcı (Türkiye saatiyle resetDay günü 00:00), epoch ms. */
    public static long lastResetMillis(LocalDate today, int resetDay) {
        LocalDate d = today.withDayOfMonth(Math.min(resetDay, today.lengthOfMonth()));
        if (d.isAfter(today)) {
            LocalDate prev = today.minusMonths(1);
            d = prev.withDayOfMonth(Math.min(resetDay, prev.lengthOfMonth()));
        }
        return d.atStartOfDay().atOffset(Fmt.TR).toInstant().toEpochMilli();
    }

    /** Arayüz için anahtar sırasıyla [{label, remaining (yoksa null), at (yoksa null)}]. */
    public static List<Object> view(Map<String, long[]> m, List<String> keys) {
        List<Object> out = new ArrayList<>();
        for (String k : keys) {
            long[] c = m.get(Settings.keyId(k));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("label", Settings.keyLabel(k));
            r.put("remaining", c == null ? null : c[0]);
            r.put("at", c == null ? null : java.time.Instant.ofEpochMilli(c[2]).toString());
            out.add(r);
        }
        return out;
    }
}
