package app.bilincli.core;

import app.bilincli.core.Models.BookEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * iddaa bülteni: Nesine'nin herkese açık bülten JSON'u (Python: providers/nesine.py).
 * Resmi bir API değildir; biçim değişirse "Kaynakları test et" sıfır maç gösterir.
 */
public final class Nesine {
    public static final String URL = "https://bulten.nesine.com/api/bulten/getprebultenfull";
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT);

    private Nesine() {}

    private static Object first(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !"".equals(v)) return v;
        }
        return null;
    }

    private static String text(Object v) {
        if (v == null) return null;
        if (v instanceof Double && (Double) v == Math.rint((Double) v)) return String.valueOf(((Double) v).longValue());
        return String.valueOf(v);
    }

    static Instant kickoff(Map<String, Object> ev) {
        String date = text(first(ev, "D", "Date")), time = text(first(ev, "T", "Time"));
        if (date == null) return null;
        try {
            if (date.contains("-")) {
                try {
                    return OffsetDateTime.parse(date).toInstant();
                } catch (DateTimeParseException e) {
                    return LocalDateTime.parse(date).atOffset(Fmt.TR).toInstant();
                }
            }
            return LocalDateTime.parse(date + " " + (time == null ? "00:00" : time), DMY).atOffset(Fmt.TR).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Double odds(Object v) {
        if (v == null) return null;
        try {
            double o = Double.parseDouble(String.valueOf(v).replace(',', '.'));
            return o > 1.0 ? o : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long integer(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static List<BookEvent> parse(Object payload) throws Http.ProviderException {
        Map<String, Object> root = Json.obj(payload);
        if (root == null) throw new Http.ProviderException("Nesine yanıtı beklenen biçimde değil");
        Map<String, Object> sg = Json.obj(root.get("sg"));
        if (sg != null) root = sg;
        List<BookEvent> events = new ArrayList<>();
        Inventory inv = new Inventory(), basketInv = new Inventory();
        Map<Long, Integer> types = new java.util.TreeMap<>();
        for (Object o : Json.arr(root.get("EA"))) {
            Map<String, Object> ev = Json.obj(o);
            if (ev == null) continue;
            Long type = integer(ev.get("TYPE"));
            if (type != null) types.put(type, types.containsKey(type) ? types.get(type) + 1 : 1);
            boolean basket = type != null && type == BASKETBALL_TYPE;
            if (type != null && type != 1L && !basket) continue; // 1 = futbol, 2 = basketbol
            String home = text(first(ev, "HN")), away = text(first(ev, "AN"));
            Instant ko = kickoff(ev);
            if (home == null || away == null || ko == null) continue;
            Map<String, Map<String, Double>> odds = new LinkedHashMap<>();
            Map<String, Integer> rawMbs = new LinkedHashMap<>();
            Integer marketMbs = null;
            for (Object mo : Json.arr(ev.get("MA"))) {
                Map<String, Object> market = Json.obj(mo);
                if (market == null) continue;
                Long mtid = integer(market.get("MTID"));
                if (mtid == null) continue;
                Map<String, Double> raw = new LinkedHashMap<>();
                for (Object oo : Json.arr(market.get("OCA"))) {
                    Map<String, Object> oc = Json.obj(oo);
                    if (oc == null) continue;
                    Long n = integer(oc.get("N"));
                    Double price = odds(oc.get("O"));
                    if (n != null && price != null) raw.put(String.valueOf(n), price);
                }
                Double sov = number(market.get("SOV"));
                (basket ? basketInv : inv).add(mtid, raw.size(), sov, market, home + " - " + away);
                // Nesine "özel değer yok"u boş değil 0,0 olarak gönderir (gerçek bültende görüldü)
                boolean special = sov != null && Math.abs(sov) > 1e-9;
                Long mm = integer(market.get("MBS"));
                if (basket) {
                    // Basketbolda beraberlik yok: özel değersiz iki seçenekli pazarlar maç sonucu
                    // adayıdır (uzatmalar dahil). Hangisi olduğunu Calibration, Pinnacle'la bulur.
                    // Özel değerli iki seçenekli pazarlar (toplam sayı, handikap, yarı/takım toplamı)
                    // Alt/Üst adayıdır; Calibration yalnızca Pinnacle'ın toplam çizgisine yakın
                    // olanları ve doğrulanan pazar kodunu kullanır.
                    if (raw.size() == 2 && special && raw.containsKey("1") && raw.containsKey("2")) {
                        String key = Calibration.RAW_BT + mtid + "@" + sov;
                        odds.put(key, raw);
                        if (mm != null && mm > 0) rawMbs.put(key, mm.intValue());
                    }
                    if (raw.size() == 2 && !special) {
                        List<Long> ns = new ArrayList<>();
                        for (String k : raw.keySet()) ns.add(Long.parseLong(k));
                        java.util.Collections.sort(ns);
                        Map<String, Double> two = new LinkedHashMap<>();
                        two.put("1", raw.get(String.valueOf(ns.get(0))));
                        two.put("2", raw.get(String.valueOf(ns.get(1))));
                        String key = Calibration.RAW_BS + mtid;
                        odds.put(key, two);
                        if (mm != null && mm > 0) rawMbs.put(key, mm.intValue());
                    }
                    continue;
                }
                if (mtid == 1L) { // Maç Sonucu: N 1 = ev, 2 = beraberlik, 3 = deplasman (Calibration doğrular)
                    Map<String, Double> outcomes = new LinkedHashMap<>();
                    if (raw.containsKey("1")) outcomes.put("1", raw.get("1"));
                    if (raw.containsKey("2")) outcomes.put("X", raw.get("2"));
                    if (raw.containsKey("3")) outcomes.put("2", raw.get("3"));
                    if (outcomes.size() == 3) {
                        odds.put("MS", outcomes);
                        if (mm != null && mm > 0) marketMbs = mm.intValue();
                    }
                } else if (raw.size() == 3 && special && raw.containsKey("1") && raw.containsKey("2") && raw.containsKey("3")) {
                    // 2.10: özel değerli üç seçenekli aday (handikaplı maç sonucu olabilir); Calibration gol modeliyle bulur
                    String key = Calibration.RAW_X3 + mtid + "@" + sov;
                    odds.put(key, raw);
                    if (mm != null && mm > 0) rawMbs.put(key, mm.intValue());
                } else if (raw.size() == 3 && !special && raw.containsKey("1") && raw.containsKey("2") && raw.containsKey("3")) {
                    // Üç seçenekli aday pazar (Çifte Şans olabilir; İlk Yarı Sonucu da üç seçeneklidir).
                    // Hangisi olduğunu Calibration, Pinnacle'dan türetilen olasılıklarla bulur.
                    String key = Calibration.RAW_CS + mtid;
                    odds.put(key, raw);
                    if (mm != null && mm > 0) rawMbs.put(key, mm.intValue());
                } else if (raw.size() == 2 && raw.containsKey("1") && raw.containsKey("2")) {
                    // İki seçenekli aday pazarlar; hangisinin 2,5 Alt/Üst ya da Karşılıklı Gol olduğunu
                    // Calibration, Pinnacle oranlarıyla karşılaştırarak bulur.
                    String key = special ? (Math.abs(sov - 2.5) < 1e-9 ? Calibration.RAW_AU25 + mtid : null)
                            : Calibration.RAW_KG + mtid;
                    if (key != null) {
                        odds.put(key, raw);
                        if (mm != null && mm > 0) rawMbs.put(key, mm.intValue());
                    }
                    if (special) { // 2.10: her çizgili iki seçenekli pazar ek pazar adayı (toplam / takım golü)
                        String x = Calibration.RAW_X2 + mtid + "@" + sov;
                        odds.put(x, raw);
                        if (mm != null && mm > 0) rawMbs.put(x, mm.intValue());
                    }
                }
            }
            if (odds.isEmpty()) continue;
            Long eventMbs = integer(ev.get("MBS"));
            // MBS okunamadıysa oynanamayacak tekli kupon önermemek için temkinli davran.
            int mbs = eventMbs != null ? eventMbs.intValue() : marketMbs != null ? 1 : 3;
            String code = text(first(ev, "C", "EC"));
            BookEvent be = new BookEvent("nesine:" + (code != null ? code : home + "-" + away), home, away, ko,
                    text(first(ev, "LN", "LC")), mbs, odds, code);
            if (marketMbs != null) be.marketMbs.put("MS", marketMbs);
            be.marketMbs.putAll(rawMbs);
            if (basket) be.sport = Models.BASKETBALL;
            events.add(be);
        }
        lastInventory = inv.render();
        lastBasketInventory = basketInv.render();
        StringBuilder t = new StringBuilder();
        for (Map.Entry<Long, Integer> e : types.entrySet()) {
            if (t.length() > 0) t.append(", ");
            t.append(e.getKey() == 1L ? "futbol" : e.getKey() == BASKETBALL_TYPE ? "basketbol" : "tür " + e.getKey())
                    .append(' ').append(e.getValue());
        }
        lastTypes = t.toString();
        return events;
    }

    /** Bültende basketbol maçlarının TYPE değeri. */
    static final long BASKETBALL_TYPE = 2L;

    /** Son okunan bültendeki pazarların özeti (pazar kodu, maç sayısı, seçenek sayısı, özel değerler). */
    public static volatile String lastInventory = "";
    /** Aynı özet, basketbol maçları için. */
    public static volatile String lastBasketInventory = "";
    /** Bültendeki maçların spor türüne göre sayısı ("futbol 655, basketbol 80, tür 3 40"). */
    public static volatile String lastTypes = "";

    private static Double number(Object v) {
        if (v == null) return null;
        try {
            return Double.parseDouble(String.valueOf(v).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Pazar envanteri: yeni pazarları (korner vb.) doğru eşleyebilmek için gerçek veriden özet. */
    static final class Inventory {
        private final Map<Long, Object[]> rows = new java.util.TreeMap<>(); // mtid -> {sayı, seçenekler, SOV'lar, alanlar, örnek}

        @SuppressWarnings("unchecked")
        void add(long mtid, int outcomes, Double sov, Map<String, Object> market, String sample) {
            Object[] r = rows.get(mtid);
            if (r == null) {
                r = new Object[] {0, new java.util.TreeSet<Integer>(), new java.util.TreeSet<Double>(),
                    new java.util.TreeSet<String>(market.keySet()), sample};
                rows.put(mtid, r);
            }
            r[0] = (Integer) r[0] + 1;
            ((java.util.Set<Integer>) r[1]).add(outcomes);
            java.util.Set<Double> sovs = (java.util.Set<Double>) r[2];
            if (sov != null && sovs.size() < 6) sovs.add(sov);
        }

        String render() {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<Long, Object[]> e : rows.entrySet()) {
                Object[] r = e.getValue();
                b.append("MTID ").append(e.getKey()).append(": ").append(r[0]).append(" maç, seçenek ").append(r[1])
                        .append(((java.util.Set<?>) r[2]).isEmpty() ? "" : ", değer " + r[2])
                        .append(", alanlar ").append(r[3]).append(", örnek ").append(r[4]).append('\n');
            }
            return b.toString();
        }
    }

    /** Son başarılı bülten (yarıda kesilen indirmelere karşı kısa süreli yedek). */
    private static volatile String lastBody;
    private static volatile Instant lastAt;

    /**
     * Canlı okunamazsa, en fazla maxCacheAgeS saniyelik son başarılı bülten kullanılır ve noteOut[0]'a
     * yazılır. maxCacheAgeS = 0: yalnızca canlı (maç öncesi kontrol ve otomatik oynama böyle çağırır).
     */
    public static List<BookEvent> fetch(Http http, long maxCacheAgeS, Instant now, String[] noteOut) throws Http.ProviderException {
        try {
            return fetch(http);
        } catch (Http.ProviderException e) {
            String body = lastBody;
            Instant at = lastAt;
            if (maxCacheAgeS <= 0 || body == null || at == null || at.plusSeconds(maxCacheAgeS).isBefore(now)) throw e;
            long min = Math.max(1, (now.getEpochSecond() - at.getEpochSecond()) / 60);
            if (noteOut != null) noteOut[0] = "Nesine canlı okunamadı (" + e.getMessage() + "); " + min + " dk önceki bülten kullanıldı.";
            return parse(Json.parse(body));
        }
    }

    /** Test için yedeği temizler. */
    static void clearCache() {
        lastBody = null;
        lastAt = null;
    }

    public static List<BookEvent> fetch(Http http) throws Http.ProviderException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Referer", "https://www.nesine.com/");
        headers.put("Origin", "https://www.nesine.com");
        Http.Response r = http.get(URL, headers);
        Object payload;
        try {
            payload = Json.parse(r.body);
        } catch (IllegalArgumentException e) {
            throw new Http.ProviderException("Nesine yanıtı JSON değil (yarıda kesilmiş olabilir)");
        }
        List<BookEvent> out = parse(payload);
        lastBody = r.body;
        lastAt = Instant.now();
        return out;
    }
}
