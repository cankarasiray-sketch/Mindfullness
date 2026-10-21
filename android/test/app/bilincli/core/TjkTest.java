package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 2.19: TJK AGF okuyucusu (yapay sayfalarla; gerçek sayfa biçimi telefonda okuma ayrıntısıyla doğrulanır). */
public class TjkTest {
    static final LocalDate DAY = LocalDate.of(2026, 10, 20);
    static final Instant NOW = Instant.parse("2026-10-20T09:00:00Z");

    /** 6 ayak: her ayakta at no ve pay (toplam 100). */
    static final String[][][] LEGS = {
        {{"5", "30,25"}, {"3", "25,75"}, {"1", "20"}, {"8", "14"}, {"2", "10"}},
        {{"2", "45,5"}, {"7", "30,5"}, {"4", "24"}},
        {{"1", "33"}, {"6", "33"}, {"9", "20"}, {"3", "14"}},
        {{"4", "60"}, {"1", "25"}, {"2", "15"}},
        {{"11", "40"}, {"3", "35"}, {"6", "25"}},
        {{"7", "50"}, {"2", "30"}, {"5", "20"}},
    };

    /** Tek tablo, her ayak bir sütun (satır i: her ayağın i. favorisi). */
    static String columns(String[][][] legs) {
        StringBuilder b = new StringBuilder("<table class=\"agf\"><thead><tr>");
        for (int k = 0; k < legs.length; k++) b.append("<th>").append(k + 1).append(". Ayak</th>");
        b.append("</tr></thead><tbody>");
        int rows = 0;
        for (String[][] l : legs) rows = Math.max(rows, l.length);
        for (int r = 0; r < rows; r++) {
            b.append("<tr>");
            for (String[][] l : legs) b.append("<td>").append(r < l.length ? "<b>" + l[r][0] + "</b> (%" + l[r][1] + ")" : "&nbsp;").append("</td>");
            b.append("</tr>");
        }
        return b.append("</tbody></table>").toString();
    }

    static String page(String body) {
        return "<!DOCTYPE html><html><head><title>Tjk</title><script>var t = '<table><tr><td>1 (%99)</td></tr></table>';</script></head><body>"
                + "<h1>ALTILI GANYAN AGF TABLOSU (RESMİ)</h1><table><tr><td>" + body + "</td></tr></table></body></html>";
    }

    static Map<String, Object> altili(Map<String, Object> r, int i) {
        return Json.obj(Json.arr(r.get("altilis")).get(i));
    }

    @Test
    public void columnsLayout() {
        List<List<Tjk.Horse>> legs = Tjk.parseAgf(page(columns(LEGS)));
        assertEquals(6, legs.size());
        assertEquals(Arrays.asList("5:30.25 3:25.75 1:20 8:14 2:10", "2:45.5 7:30.5 4:24", "1:33 6:33 9:20 3:14",
                "4:60 1:25 2:15", "11:40 3:35 6:25", "7:50 2:30 5:20"), Tjk.texts(legs));
    }

    @Test
    public void tablePerLegWithSeparateCells() {
        // program sayfası gibi: "N" sütununda at no, "AGF" sütununda "%pay(sıra)"; önce sıra sütunu var
        StringBuilder b = new StringBuilder();
        for (String[][] leg : LEGS) {
            b.append("<table><tr><th>Sıra</th><th>N</th><th>At İsmi</th><th>Sıklet</th><th>AGF</th></tr>");
            for (int i = leg.length - 1; i >= 0; i--) { // sayfa at numarasına göre sıralı olabilir
                b.append("<tr><td>").append(i + 1).append("</td><td>").append(leg[i][0]).append("</td><td>KARAYEL</td><td>58</td><td>%")
                        .append(leg[i][1]).append("(").append(i + 1).append(")</td></tr>");
            }
            b.append("</table>");
        }
        assertEquals(Tjk.texts(Tjk.parseAgf(page(columns(LEGS)))), Tjk.texts(Tjk.parseAgf(page(b.toString()))));
    }

    @Test
    public void rowsLayout() {
        StringBuilder b = new StringBuilder("<table>");
        for (int k = 0; k < LEGS.length; k++) {
            b.append("<tr><td>").append(k + 1).append(". Ayak</td>");
            for (String[] h : LEGS[k]) b.append("<td>").append(h[0]).append(" (").append(h[1]).append("%)</td>");
            b.append("</tr>");
        }
        b.append("</table>");
        assertEquals(Tjk.texts(Tjk.parseAgf(page(columns(LEGS)))), Tjk.texts(Tjk.parseAgf(page(b.toString()))));
    }

    @Test
    public void divLayoutWithAyakHeadings() {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < LEGS.length; k++) {
            b.append("<div class=\"leg\"><h3>").append(k + 1).append(". AYAK</h3>");
            for (String[] h : LEGS[k]) b.append("<span>").append(h[0]).append(" (%").append(h[1]).append(")</span>");
            b.append("</div>");
        }
        String html = "<html><body>" + b + "</body></html>";
        assertEquals(Tjk.texts(Tjk.parseAgf(page(columns(LEGS)))), Tjk.texts(Tjk.parseAgf(html)));
    }

    @Test
    public void duplicateViewsCollapseAndSecondAltiliTakesItsHalf() {
        String one = columns(LEGS);
        assertEquals(6, Tjk.parseAgf(page(one + one)).size()); // mobil + masaüstü kopyası
        String[][][] other = {LEGS[5], LEGS[4], LEGS[3], LEGS[2], LEGS[1], LEGS[0]};
        List<List<Tjk.Horse>> both = Tjk.parseAgf(page(one + columns(other)));
        assertEquals(12, both.size());
        String[] why = {null};
        assertEquals("7:50 2:30 5:20", Tjk.legText(Tjk.six(both, 2, why).get(0)));
        assertEquals("5:30.25 3:25.75 1:20 8:14 2:10", Tjk.legText(Tjk.six(both, 1, why).get(0)));
    }

    @Test
    public void implausibleSumsAreRejected() {
        String[][][] bad = LEGS.clone();
        bad[2] = new String[][] {{"1", "90"}, {"2", "80"}, {"3", "70"}}; // toplam %240: yanlış tablo
        String[] why = {null};
        assertNull(Tjk.six(Tjk.parseAgf(page(columns(bad))), 1, why));
        assertTrue(why[0], why[0].startsWith("3. ayak okunamadı"));
        assertNull(Tjk.six(Tjk.parseAgf(page("<p>Veri yok</p>")), 1, why));
        assertEquals("AGF tablosu bulunamadı", why[0]);
    }

    @Test
    public void citiesFromProgramLinks() {
        String html = "<ul><li><a href=\"/TR/YarisSever/Info/Page/GunlukYarisProgrami?SehirId=3&amp;QueryParameter_Tarih=20%2F10%2F2026&amp;SehirAdi=%C4%B0stanbul\">İstanbul (1. Yarış 13:30)</a></li>"
                + "<li><a href='?QueryParameter_Tarih=20%2F10%2F2026&sehirId=2'>  İzmir </a></li>"
                + "<li><a href=\"/TR/YarisSever/Info/Page/GunlukYarisProgrami?SehirId=3&amp;SehirAdi=Istanbul\">tekrar</a></li>"
                + "<li><a href=\"?SehirId=31&SehirAdi=Fransa\">Fransa</a></li><li><a href=\"/hakkimizda\">Hakkımızda</a></li></ul>";
        Map<Integer, String> c = Tjk.cities(html);
        assertEquals(Arrays.asList(3, 2, 31), new ArrayList<>(c.keySet()));
        assertEquals("İstanbul", c.get(3));
        assertEquals("İzmir", c.get(2));
        assertEquals("Fransa", c.get(31));
        assertEquals("https://www.tjk.org/TR/YarisSever/Info/Page/GunlukYarisProgrami?QueryParameter_Tarih=20%2F10%2F2026", Tjk.programUrl(DAY));
        assertEquals("https://www.tjk.org/AGFv2/3/20102026/TR/2/1", Tjk.agfUrl(3, DAY, 2));
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
    public void readCollectsAltilisAndExplainsMisses() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put(Tjk.programUrl(DAY), "<a href=\"?SehirId=3&SehirAdi=%C4%B0stanbul\">İstanbul</a> <a href=\"?SehirId=5&SehirAdi=Ankara\">Ankara</a>");
        pages.put(Tjk.agfUrl(3, DAY, 1), page(columns(LEGS)));
        pages.put(Tjk.agfUrl(3, DAY, 2), page(columns(LEGS))); // ikinci altılı sayfası aynı tabloyu verirse atlanır
        pages.put(Tjk.agfUrl(5, DAY, 1), page("<p>AGF henüz yayınlanmadı</p>"));
        List<String> asked = new ArrayList<>();
        Map<String, Object> r = Tjk.read(fake(pages, asked), DAY, NOW, null);
        assertEquals(1, Json.arr(r.get("altilis")).size());
        Map<String, Object> a = altili(r, 0);
        assertEquals("İstanbul 1. altılı", a.get("label"));
        assertEquals("tjk/2026-10-20/3/1", a.get("key")); // gün anahtarda: bugünkü ve yarınki ayrı
        assertEquals("2026-10-20", a.get("day"));
        assertEquals(3, ((Number) a.get("cityId")).intValue());
        assertEquals("2:45.5 7:30.5 4:24", Json.arr(a.get("legs")).get(1));
        String log = String.join("\n", (List) Json.arr(r.get("log")));
        assertTrue(log, log.contains("Program: İstanbul, Ankara"));
        assertTrue(log, log.contains("İstanbul 1. altılı: 6 ayak okundu (at sayıları 5-3-4-3-3-3)"));
        assertTrue(log, log.contains("İstanbul 2. altılı: önceki altılıyla aynı tablo"));
        assertTrue(log, log.contains("Ankara 1. altılı: AGF tablosu bulunamadı"));
        assertTrue(log, log.contains("henüz yayınlanmadı"));
        assertEquals(4, asked.size()); // Ankara'nın 1. altılısı yoksa 2.si istenmez
        assertEquals(NOW.toString(), r.get("at"));
    }

    @Test
    public void programWithoutLinksTriesKnownTracks() {
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put(Tjk.programUrl(DAY), "<html><body>Program</body></html>");
        pages.put(Tjk.agfUrl(2, DAY, 1), page(columns(LEGS)));
        List<String> asked = new ArrayList<>();
        Map<String, Object> r = Tjk.read(fake(pages, asked), DAY, NOW, null);
        assertEquals("İzmir 1. altılı", altili(r, 0).get("label"));
        assertTrue(asked.size() <= Tjk.MAX_REQUESTS);
    }

    @Test
    public void unreachableTjkStopsEarly() {
        List<String> asked = new ArrayList<>();
        Http down = (url, headers) -> {
            asked.add(url);
            throw new Http.ProviderException(url + " -> bağlantı hatası: Connection reset");
        };
        Map<String, Object> r = Tjk.read(down, DAY, NOW, null);
        assertEquals(1, asked.size());
        assertEquals(0, Json.arr(r.get("altilis")).size());
        assertTrue(String.valueOf(r.get("log")).contains("Program okunamadı"));
    }
}
