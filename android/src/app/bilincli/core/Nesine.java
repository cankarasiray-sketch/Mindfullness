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
        for (Object o : Json.arr(root.get("EA"))) {
            Map<String, Object> ev = Json.obj(o);
            if (ev == null) continue;
            Long type = integer(ev.get("TYPE"));
            if (type != null && type != 1L) continue; // 1 = futbol
            String home = text(first(ev, "HN")), away = text(first(ev, "AN"));
            Instant ko = kickoff(ev);
            if (home == null || away == null || ko == null) continue;
            Map<String, Map<String, Double>> odds = new LinkedHashMap<>();
            Integer marketMbs = null;
            for (Object mo : Json.arr(ev.get("MA"))) {
                Map<String, Object> market = Json.obj(mo);
                if (market == null) continue;
                Long mtid = integer(market.get("MTID"));
                if (mtid == null || mtid != 1L) continue; // 1 = Maç Sonucu
                Map<String, Double> outcomes = new LinkedHashMap<>();
                for (Object oo : Json.arr(market.get("OCA"))) {
                    Map<String, Object> oc = Json.obj(oo);
                    if (oc == null) continue;
                    Long n = integer(oc.get("N"));
                    Double price = odds(oc.get("O"));
                    String key = n == null ? null : n == 1 ? "1" : n == 2 ? "X" : n == 3 ? "2" : null;
                    if (key != null && price != null) outcomes.put(key, price);
                }
                if (outcomes.size() == 3) {
                    odds.put("MS", outcomes);
                    Long mm = integer(market.get("MBS"));
                    if (mm != null && mm > 0) marketMbs = mm.intValue();
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
            events.add(be);
        }
        return events;
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
            throw new Http.ProviderException("Nesine yanıtı JSON değil");
        }
        return parse(payload);
    }
}
