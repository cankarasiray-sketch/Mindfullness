package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 2.20: Bilyoner/Nesine at yarışı keşfi ve esnek okuma (yapay sayfalarla; gerçek biçim telefonda doğrulanır). */
public class AtYarisiTest {
    static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");

    /** Next.js biçiminde koşular: AGF yalnızca altılı ayaklarında (agfFrom..), ganyan her koşuda. */
    static String races(int count, int agfFrom) {
        StringBuilder b = new StringBuilder("[");
        for (int r = 1; r <= count; r++) {
            b.append(r > 1 ? "," : "").append("{\"raceNo\":").append(r).append(",\"distance\":1400,\"horses\":[");
            String[][] h = {{"1", "50", Tjk.pct(1.8 + 0.02 * r)}, {"2", "30", "3.1"}, {"3", "20", "4.6"}}; // koşular birbirinden farklı
            for (int i = 0; i < h.length; i++) {
                b.append(i > 0 ? "," : "").append("{\"no\":").append(h[i][0]).append(",\"name\":\"AT").append(r).append(i)
                        .append("\",\"weight\":58,\"ganyan\":\"").append(h[i][2].replace('.', ',')).append("\"")
                        .append(r >= agfFrom ? ",\"agfRank\":" + (i + 1) + ",\"agf\":\"%" + h[i][1] + ",00\"" : "").append('}');
            }
            b.append("]}");
        }
        return b.append(']').toString();
    }

    static String bilyonerPage() {
        return "<html><head><script src=\"/_next/static/chunks/main-1.js\"></script><script src=\"/_next/static/chunks/pages/at-yarisi-2.js\"></script>"
                + "<script src=\"https://www.googletagmanager.com/gtm.js\"></script></head><body><div id=\"__next\"></div>"
                + "<script id=\"__NEXT_DATA__\" type=\"application/json\">{\"props\":{\"pageProps\":{\"program\":[{\"hippodrome\":\"İstanbul\",\"races\":"
                + races(8, 3) + "}]}},\"page\":\"/at-yarisi\"}</script></body></html>";
    }

    static Http fake(final Map<String, String> pages, final List<String> asked) {
        return (url, headers) -> {
            asked.add(url);
            String body = pages.get(url);
            if (body == null) throw new Http.ProviderException(url + " -> HTTP 404: Not Found");
            return new Http.Response(200, body, new LinkedHashMap<String, String>());
        };
    }

    @Test
    public void embeddedNextDataGivesAltiliWithGanyan() {
        List<AtYarisi.Race> races = new ArrayList<>();
        AtYarisi.harvest(Json.parse("{\"program\":[{\"hippodrome\":\"İstanbul\",\"races\":" + races(8, 3) + "}]}"), "$", null, null, races, 0);
        assertEquals(8, races.size());
        assertFalse(races.get(0).hasAgf());
        assertTrue(races.get(2).hasAgf());
        assertTrue(races.get(2).hasOdds());
        assertEquals("İstanbul", races.get(2).place);
        assertEquals(Long.valueOf(3), races.get(2).number);
        assertEquals("1:50:1.86 2:30:3.1 3:20:4.6", races.get(2).legText());
        List<Map<String, Object>> a = AtYarisi.altilis("Bilyoner", races);
        assertEquals(1, a.size());
        assertEquals("İstanbul 3–8. koşu", a.get(0).get("label"));
        assertEquals(Boolean.TRUE, a.get(0).get("odds"));
        List<Object> rs = Json.arr(a.get(0).get("races"));
        assertEquals(6, rs.size()); // 2.27: ayak ayak koşu numarası
        assertEquals(3L, Json.obj(rs.get(0)).get("no"));
        assertEquals(8L, Json.obj(rs.get(5)).get("no"));
        assertEquals(6, ((List<?>) a.get(0).get("legs")).size());
    }

    @Test
    public void moreThanSixAgfRacesOfferFirstAndLastSix() {
        List<AtYarisi.Race> races = new ArrayList<>();
        AtYarisi.harvest(Json.parse("{\"hippodrome\":\"Ankara\",\"races\":" + races(9, 1) + "}"), "$", null, null, races, 0);
        List<Map<String, Object>> a = AtYarisi.altilis("Nesine", races);
        assertEquals(2, a.size());
        assertEquals("Ankara 1–6. koşu", a.get(0).get("label"));
        assertEquals("Ankara 4–9. koşu", a.get(1).get("label"));
        assertEquals("nesine/Ankara 4–9. koşu", a.get(1).get("key"));
    }

    @Test
    public void fractionsAndImplausibleOdds() {
        List<AtYarisi.Race> races = new ArrayList<>();
        // AGF 0–1 kesir, oranlar akla yatkın değil (toplam 1/9 × 3 = %33): ayak oransız
        AtYarisi.harvest(Json.parse("[{\"atNo\":\"1\",\"agfOran\":0.5,\"odds\":9},{\"atNo\":\"2\",\"agfOran\":0.3,\"odds\":9},{\"atNo\":\"3\",\"agfOran\":0.2,\"odds\":9}]"),
                "$", null, null, races, 0);
        assertEquals(1, races.size());
        assertFalse(races.get(0).hasOdds());
        assertEquals("1:50 2:30 3:20", races.get(0).legText());
        races.clear();
        AtYarisi.harvest(Json.parse("[{\"id\":5,\"name\":\"a\"},{\"id\":6,\"name\":\"b\"}]"), "$", null, null, races, 0); // koşucu değil
        assertEquals(0, races.size());
    }

    @Test
    public void readFindsEmbeddedDataScriptPathsAndEndpoints() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put("https://www.bilyoner.com/at-yarisi", bilyonerPage());
        pages.put("https://www.bilyoner.com/_next/static/chunks/pages/at-yarisi-2.js", "fetch(\"/api/v1/horse-race/program?day=today\").then(r=>r.json());var img=\"/img/race.png\";");
        pages.put("https://www.bilyoner.com/api/v1/horse-race/program?day=today", "{\"status\":\"ok\",\"data\":{\"items\":[]}}");
        pages.put("https://www.nesine.com/at-yarisi", "<html><head><script src=\"https://www.nesine.com/static/app.js\"></script></head><body>At Yarışı</body></html>");
        StringBuilder nes = new StringBuilder("[");
        for (int r = 1; r <= 6; r++) {
            nes.append(r > 1 ? "," : "").append("{\"kosuNo\":").append(r).append(",\"sehir\":\"Ankara\",\"atlar\":[{\"atNo\":\"4\",\"agfOran\":\"%60,5\"},{\"atNo\":\"7\",\"agfOran\":\"%39,5\"}]}");
        }
        pages.put("https://www.nesine.com/static/app.js", "var u='/api/at-yarisi/agf?tarih=20.10.2026';");
        pages.put("https://www.nesine.com/api/at-yarisi/agf?tarih=20.10.2026", nes.append(']').toString());
        List<String> asked = new ArrayList<>();
        Map<String, Object> r = AtYarisi.read(fake(pages, asked), NOW, null);
        List<Object> altilis = Json.arr(r.get("altilis"));
        assertEquals(2, altilis.size());
        Map<String, Object> b = Json.obj(altilis.get(0)), n = Json.obj(altilis.get(1));
        assertEquals("Bilyoner", b.get("source"));
        assertEquals("İstanbul 3–8. koşu", b.get("label"));
        assertEquals("Nesine", n.get("source"));
        assertEquals("Ankara 1–6. koşu", n.get("label"));
        assertEquals("4:60.5 7:39.5", Json.arr(n.get("legs")).get(0));
        assertEquals(Boolean.FALSE, n.get("odds"));
        String log = String.join("\n", (List) Json.arr(r.get("log")));
        assertTrue(log, log.contains("gömülü veri 1"));
        assertTrue(log, log.contains("8 koşucu listesi (AGF'li 6, ganyanlı 8)"));
        assertTrue(log, log.contains("/api/v1/horse-race/program?day=today"));
        assertTrue(log, log.contains("0 koşucu listesi (AGF'li 0, ganyanlı 0); yapı: {status, data: {items: [0]}}"));
        assertFalse(log, log.contains("race.png"));
        assertFalse(asked.contains("https://www.googletagmanager.com/gtm.js")); // yalnızca sitenin kendi betikleri
        assertTrue(asked.indexOf("https://www.bilyoner.com/_next/static/chunks/pages/at-yarisi-2.js")
                < asked.indexOf("https://www.bilyoner.com/_next/static/chunks/main-1.js")); // at yarışı betiği önce
    }

    @Test
    public void unreachableSitesAreLoggedNotThrown() {
        Http down = (url, headers) -> {
            throw new Http.ProviderException(url + " -> bağlantı hatası: Connection reset");
        };
        Map<String, Object> r = AtYarisi.read(down, NOW, null);
        assertEquals(0, Json.arr(r.get("altilis")).size());
        String log = String.valueOf(r.get("log"));
        assertTrue(log, log.contains("Bilyoner: altılı bulunamadı"));
        assertTrue(log, log.contains("Nesine: altılı bulunamadı"));
    }

    @Test
    public void poolInfoFromSiteGoesToItsAltili() {
        String json = "{\"hippodrome\":\"İstanbul\",\"bets\":[{\"name\":\"Ganyan\",\"unitPrice\":1},{\"name\":\"1. 6'LI GANYAN\",\"unitPrice\":\"1,25\","
                + "\"carryOver\":\"1.500.000,50\",\"poolAmount\":4000000}],\"races\":" + races(6, 1) + "}";
        Object root = Json.parse(json);
        List<AtYarisi.Race> races = new ArrayList<>();
        AtYarisi.harvest(root, "$", null, null, races, 0);
        List<AtYarisi.PoolInfo> pools = new ArrayList<>();
        AtYarisi.pools(root, null, pools, 0);
        assertEquals(1, pools.size()); // "Ganyan" altılı değil
        Map<String, Object> a = AtYarisi.altilis("Bilyoner", races, pools).get(0);
        assertEquals(1.25, (Double) a.get("unit"), 1e-9);
        assertEquals(1_500_000.5, (Double) a.get("devir"), 1e-6);
        assertEquals(4_000_000, (Double) a.get("pool"), 1e-6);
        assertEquals("İstanbul", a.get("city"));
        assertEquals(Double.valueOf(2500), AtYarisi.amount("₺ 2.500"));
        assertEquals(Double.valueOf(1234567.89), AtYarisi.amount("1234567.89"));
    }

    @Test
    public void tjkPageAmounts() {
        String html = "<div>1. ALTILI GANYAN · Devir: <b>2.345.678,90 TL</b></div><p>Toplam bahis 5.000.000 TL</p>";
        assertEquals(2_345_678.90, Tjk.amountNear(html, Tjk.DEVIR_TEXT), 1e-6);
        assertEquals(5_000_000, Tjk.amountNear(html, Tjk.POOL_TEXT), 1e-6);
        assertEquals(null, Tjk.amountNear("<p>Devir yok</p>", Tjk.DEVIR_TEXT));
    }
}
