package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertTrue(log, log.contains("Ankara 1. altılı: AGF henüz oluşmadı")); // sayfa var, paylar boş (2.25.3)
        assertEquals(1L, r.get("waiting"));
        assertEquals(6, asked.size()); // + İstanbul programı (sayfa ve şehir sekmesi); Ankara'nın 1. altılısı yoksa 2.si istenmez
        assertTrue(log, log.contains("İstanbul 1. altılı: koşu saatleri bulunamadı: program okunamadı"));
        assertEquals(null, a.get("races"));
        assertTrue((String) a.get("racesWhy"), ((String) a.get("racesWhy")).startsWith("program okunamadı"));
        assertEquals(NOW.toString(), r.get("at"));
    }

    /** 2.32.8: TJK biçimi: her ayak ayrı tablo, "Sıra | At No (%pay)"; "*" ile başlayan atın satırında sıra hücresi yok. */
    static String perLegTables(String[][][] legs) {
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < legs.length; k++) {
            b.append("<table><tr><th colspan=\"2\">").append(k + 1).append(". AYAK</th></tr><tr><th>Sıra</th><th>AT NO</th></tr>");
            int rank = 0;
            for (String[] h : legs[k]) {
                b.append("<tr>");
                if (!h[0].startsWith("*")) b.append("<td>").append(++rank).append("</td>");
                b.append("<td><b>").append(h[0].replace("*", "")).append("</b> (%").append(h[1]).append(")</td></tr>");
            }
            b.append("</table>");
        }
        return b.toString();
    }

    @Test
    public void rowsWithoutRankStayInTheirLeg() {
        // Belmont Avustralya / Bursa: sırasız satırlar ayrı "ayak" sanılıyor, ayaklar kayıyordu ("4. ayak okunamadı, %0.48")
        String[][][] legs = LEGS.clone();
        legs[0] = new String[][] {{"1", "39,97"}, {"2", "27,25"}, {"7", "16,85"}, {"8", "10,56"}, {"6", "1,4"}, {"5", "1,29"}, {"3", "1,2"}, {"*4", "1,48"}};
        legs[3] = new String[][] {{"4", "59,52"}, {"1", "25"}, {"2", "15"}, {"*5", "0,2"}, {"*6", "0,18"}, {"*7", "0,1"}};
        List<List<Tjk.Horse>> parsed = Tjk.parseAgf(page(perLegTables(legs)));
        String[] why = {null};
        List<List<Tjk.Horse>> six = Tjk.six(parsed, 1, why);
        assertEquals(why[0], 6, six == null ? 0 : six.size());
        assertEquals("1:39.97 2:27.25 7:16.85 8:10.56 4:1.48 6:1.4 5:1.29 3:1.2", Tjk.legText(six.get(0)));
        assertEquals("4:59.52 1:25 2:15 5:0.2 6:0.18 7:0.1", Tjk.legText(six.get(3)));
        assertEquals("2:45.5 7:30.5 4:24", Tjk.legText(six.get(1)));
        // tek tabloda altı ayak (sütunlar) yine ayrı ayrı okunur
        assertEquals(6, Tjk.parseAgf(page(columns(LEGS))).size());
    }

    @Test
    public void secondAltiliIsTriedWhenFirstCannotBeRead() {
        // 2.32.8: 1. altılının tablosu bozuksa (bir ayak %30) 2. altılı yine okunur (Belmont Park ABD 2. altılı)
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put(Tjk.programUrl(DAY), "<a href=\"?SehirId=31&SehirAdi=Belmont+Park+ABD\">B</a>");
        String[][][] broken = LEGS.clone();
        broken[2] = new String[][] {{"1", "20"}, {"6", "10"}};
        pages.put(Tjk.agfUrl(31, DAY, 1), page(perLegTables(broken)));
        String[][][] second = LEGS.clone();
        second[0] = new String[][] {{"9", "70"}, {"3", "30"}};
        pages.put(Tjk.agfUrl(31, DAY, 2), page(perLegTables(second)));
        Map<String, Object> r = Tjk.read(fake(pages, new ArrayList<String>()), DAY, NOW, null);
        assertEquals(1, Json.arr(r.get("altilis")).size());
        assertEquals("Belmont Park ABD 2. altılı", altili(r, 0).get("label"));
        String log = String.join("\n", (List) Json.arr(r.get("log")));
        assertTrue(log, log.contains("Belmont Park ABD 1. altılı: 3. ayak okunamadı (paylar toplamı %30, 2 at)"));
    }

    @Test
    public void manyTracksAreAllRead() {
        // 2.32.7: yabancı hipodromlu gün: 16 hipodrom × 2 altılı + programlar 30 sayfayı aşar; sona kalan da okunur
        Map<String, String> pages = new LinkedHashMap<>();
        StringBuilder program = new StringBuilder();
        for (int c = 11; c <= 26; c++) {
            program.append("<a href=\"?SehirId=").append(c).append("&SehirAdi=Hipodrom").append(c).append("\">H</a> ");
            for (int no = 1; no <= 2; no++) {
                String[][][] legs = LEGS.clone();
                legs[0] = new String[][] {{String.valueOf(c * 2 + no), "60"}, {"1", "40"}}; // her altılının tablosu farklı
                pages.put(Tjk.agfUrl(c, DAY, no), page(columns(legs)));
            }
        }
        pages.put(Tjk.programUrl(DAY), program.toString());
        List<String> asked = new ArrayList<>();
        Map<String, Object> r = Tjk.read(fake(pages, asked), DAY, NOW, null);
        assertEquals(32, Json.arr(r.get("altilis")).size());
        assertEquals("Hipodrom26 2. altılı", altili(r, 31).get("label"));
        assertTrue(asked.size() > 30);
        assertTrue(asked.size() <= Tjk.MAX_REQUESTS);
    }

    @Test
    public void previouslyReadAltiliIsKept() {
        // 2.32.7: önceki okumada olan bugünkü/yarınki TJK altılısı yeni okumada yoksa önceki AGF'yle kalır
        Map<String, Object> prev = new LinkedHashMap<>();
        prev.put("at", "2026-10-21T14:49:00Z");
        List<Object> old = new ArrayList<>();
        String[][] rows = {{"tjk/2026-10-21/31/2", "2026-10-21", "Belmont Park ABD 2. altılı", null}, {"tjk/2026-10-21/3/1", "2026-10-21", "İstanbul 1. altılı", null},
            {"tjk/2026-10-20/3/1", "2026-10-20", "dünkü", null}, {"tjk/2026-10-22/3/1", "2026-10-22", "22.10 · İstanbul 1. altılı", "2026-10-21T08:00:00Z"},
            {"bilyoner/x", "2026-10-21", "site", null}};
        for (String[] row : rows) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("key", row[0]);
            a.put("day", row[1]);
            a.put("label", row[2]);
            if (row[3] != null) a.put("kept", row[3]);
            old.add(a);
        }
        prev.put("altilis", old);
        List<Object> fresh = new ArrayList<>();
        Map<String, Object> ist = new LinkedHashMap<>();
        ist.put("key", "tjk/2026-10-21/3/1");
        ist.put("day", "2026-10-21");
        fresh.add(ist);
        List<Object> log = new ArrayList<>();
        assertEquals(2, Tjk.keepMissing(fresh, prev, "2026-10-21", log));
        assertEquals(3, fresh.size());
        assertEquals(null, Json.obj(fresh.get(0)).get("kept")); // yeni okunan aynen
        assertEquals("Belmont Park ABD 2. altılı", Json.obj(fresh.get(1)).get("label"));
        assertEquals("2026-10-21T14:49:00Z", Json.obj(fresh.get(1)).get("kept"));
        assertEquals("2026-10-21T08:00:00Z", Json.obj(fresh.get(2)).get("kept")); // AGF'nin asıl okunduğu an korunur
        assertEquals("Belmont Park ABD 2. altılı: bu okumada yok, önceki okumadaki AGF kullanılıyor", log.get(0));
        assertEquals(0, Tjk.keepMissing(fresh, null, "2026-10-21", log));
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

    @Test
    public void emptyAgfIsReportedAsNotYetFormed() {
        // 21.10 07:00 gerçek görüntüsü: yerli sayfada yalnızca başlıklar, yurt dışında bütün atlar %0
        String domestic = "<html><body><h3>1. 6'LI GANYAN AGF TABLOSU (GAYRİ RESMİ)</h3><table><tr><th>1. AYAK</th><th>2. AYAK</th><th>3. AYAK</th>"
                + "<th>4. AYAK</th><th>5. AYAK</th><th>6. AYAK</th></tr></table><p>At noya göre sıralamak için tıklayınız...</p><p>Son güncellenme zamanı :</p></body></html>";
        StringBuilder zeros = new StringBuilder("<h3>AVUSTRALYA AGF TABLOSU (GAYRİ RESMİ)</h3>");
        for (int k = 1; k <= 6; k++) {
            zeros.append("<table><tr><th>").append(k).append(". AYAK</th></tr>");
            for (int h = 1; h <= 8; h++) zeros.append("<tr><td>").append(h).append(" (%0)</td></tr>");
            zeros.append("</table>");
        }
        assertTrue(Tjk.notYet(domestic));
        assertTrue(Tjk.notYet(zeros.toString()));
        assertFalse(Tjk.notYet(page(columns(LEGS))));
        assertFalse(Tjk.notYet("<p>Sayfa bulunamadı</p>"));
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put(Tjk.programUrl(DAY), "<a href=\"?SehirId=3&SehirAdi=Bursa\">Bursa</a> <a href=\"?SehirId=31&SehirAdi=Avustralya\">Avustralya</a>");
        pages.put(Tjk.agfUrl(3, DAY, 1), domestic);
        pages.put(Tjk.agfUrl(31, DAY, 1), zeros.toString());
        Map<String, Object> r = Tjk.read(fake(pages, new ArrayList<String>()), DAY, NOW, null);
        assertEquals(0, Json.arr(r.get("altilis")).size());
        assertEquals(2L, r.get("waiting"));
        String log = String.valueOf(r.get("log"));
        assertTrue(log, log.contains("Bursa 1. altılı: AGF henüz oluşmadı (TJK'da paylar boş; bahisler başlayınca dolar)"));
        assertTrue(log, log.contains("Avustralya 1. altılı: AGF henüz oluşmadı"));
    }

    /** Şehir programı: 1–3. koşular başka atlarla, 4–9. koşular altılının ayakları; başlık bazen ayrı tabloda, saat noktalı. */
    static String cityProgram() {
        StringBuilder b = new StringBuilder("<html><body><h1>İSTANBUL GÜNLÜK YARIŞ PROGRAMI</h1>");
        String[][] others = {{"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"}, {"21", "22", "23"}, {"13", "14"}};
        for (int r = 1; r <= 9; r++) {
            String time = String.format(java.util.Locale.ROOT, "%02d:%02d", 13 + (r - 1) / 2, (r - 1) % 2 * 30);
            String[] horses;
            if (r <= 3) horses = others[r - 1];
            else {
                String[][] leg = LEGS[r - 4];
                horses = new String[leg.length + 1];
                for (int i = 0; i < leg.length; i++) horses[i] = leg[i][0];
                horses[leg.length] = "12"; // AGF'de olmayan (payı 0) bir at daha
            }
            if (r == 5) b.append("<table><tr><td>").append(r).append(". KOŞU ").append(time.replace(':', '.')).append(" 1400 Kum</td></tr></table>");
            else b.append("<div class=\"race\"><h3>").append(r).append(". Koşu</h3><span>").append(time).append("</span> <span>1. 6'LI GANYAN</span></div>");
            b.append("<table><tr><th>N</th><th>At İsmi</th><th>Sıklet</th><th>AGF</th></tr>");
            for (String h : horses) b.append("<tr><td>").append(h).append("</td><td>AT").append(h).append("</td><td>58.5</td><td>%1,00(3)</td></tr>");
            b.append("</table>");
        }
        return b.append("</body></html>").toString();
    }

    @Test
    public void raceNumbersAndTimesFromCityProgram() {
        List<Tjk.ProgramRace> races = Tjk.programRaces(cityProgram());
        assertEquals(9, races.size());
        assertEquals(5, races.get(4).no);
        assertEquals("15:00", races.get(4).time);
        assertEquals("13:00", races.get(0).time);
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put(Tjk.programUrl(DAY), "<a href=\"?SehirId=3&SehirAdi=%C4%B0stanbul\">İstanbul</a>");
        pages.put(Tjk.agfUrl(3, DAY, 1), page(columns(LEGS)));
        pages.put("https://www.tjk.org/TR/YarisSever/Info/Page/GunlukYarisProgrami?SehirId=3&SehirAdi=%C4%B0stanbul", cityProgram());
        Map<String, Object> r = Tjk.read(fake(pages, new ArrayList<String>()), DAY, NOW, null);
        Map<String, Object> a = altili(r, 0);
        List<Object> rs = Json.arr(a.get("races"));
        assertEquals(6, rs.size());
        assertEquals(4L, Json.obj(rs.get(0)).get("no"));
        assertEquals("14:30", Json.obj(rs.get(0)).get("time"));
        assertEquals(9L, Json.obj(rs.get(5)).get("no"));
        assertEquals("17:00", Json.obj(rs.get(5)).get("time"));
        assertEquals("14:30", a.get("start")); // başlamış altılı bildirilmez
        assertTrue(String.valueOf(r.get("log")).contains("İstanbul 1. altılı: koşular 4 (14:30), 5 (15:00), 6 (15:30), 7 (16:00), 8 (16:30), 9 (17:00)"));
        // kupon metni koşu numarası ve saatiyle
        Map<String, Object> plan = Altili.plan(Tjk.texts(Tjk.parseAgf(page(columns(LEGS)))), AltiliLogTest.inputs());
        String coupon = Texts.altiliCoupon(plan, rs);
        assertTrue(coupon, coupon.startsWith("1. ayak (4. koşu 14:30): 5"));
        assertTrue(coupon, coupon.contains("\n6. ayak (9. koşu 17:00): 7"));
        assertEquals(Texts.altiliCoupon(plan), Texts.altiliCoupon(plan, null));
    }

    @Test
    public void raceTimesFromAgfHeadersOrCityTabOrHeaderlessTables() {
        // 1) AGF sayfasının ayak başlıklarında koşu ve saat: program hiç istenmez
        String agf = page(columns(LEGS));
        for (int k = 1; k <= 6; k++) agf = agf.replace("<th>" + k + ". Ayak</th>", "<th>" + k + ". Ayak (" + (k + 3) + ".Koşu " + (13 + k) + ":15)</th>");
        List<Object> fromAgf = Tjk.agfLegRaces(agf);
        assertEquals(4L, Json.obj(fromAgf.get(0)).get("no"));
        assertEquals("14:15", Json.obj(fromAgf.get(0)).get("time"));
        assertEquals("19:15", Json.obj(fromAgf.get(5)).get("time"));
        assertEquals(null, Tjk.agfLegRaces(page(columns(LEGS)))); // başlıkta bilgi yok
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put(Tjk.programUrl(DAY), "<a href=\"?SehirId=3&SehirAdi=Bursa\">Bursa</a>");
        pages.put(Tjk.agfUrl(3, DAY, 1), agf);
        List<String> asked = new ArrayList<>();
        Map<String, Object> a = altili(Tjk.read(fake(pages, asked), DAY, NOW, null), 0);
        assertEquals("14:15", a.get("start"));
        for (String u : asked) assertFalse(u, u.contains("SehirId")); // şehir programı istenmedi
        // 2) bağlantıdaki program sayfası boş (içerik sekmeyle yükleniyor): şehir sekmesi adresi denenir
        String page = "https://www.tjk.org/TR/YarisSever/Info/Page/GunlukYarisProgrami?SehirId=3&SehirAdi=Bursa";
        pages.put(Tjk.agfUrl(3, DAY, 1), page(columns(LEGS)));
        pages.put(page, "<html><body><div id=\"tabs\">Bursa</div><script>load()</script></body></html>");
        pages.put(Tjk.programAlternative(page), cityProgram());
        a = altili(Tjk.read(fake(pages, new ArrayList<String>()), DAY, NOW, null), 0);
        assertEquals("14:30", a.get("start"));
        assertEquals("https://www.tjk.org/TR/YarisSever/Info/Sehir/GunlukYarisProgrami?SehirId=3&SehirAdi=Bursa", Tjk.programAlternative(page));
        // 3) at tablosunda "N" başlığı yok: numara sütunu kendiliğinden bulunur
        pages.put(Tjk.programAlternative(page), cityProgram().replace("<tr><th>N</th><th>At İsmi</th><th>Sıklet</th><th>AGF</th></tr>", "<tr><th></th><th>At</th><th>Kilo</th><th>Agf</th></tr>"));
        a = altili(Tjk.read(fake(pages, new ArrayList<String>()), DAY, NOW, null), 0);
        assertEquals("14:30", a.get("start"));
        // 4) hiçbiri yoksa neden kartta
        pages.remove(Tjk.programAlternative(page));
        a = altili(Tjk.read(fake(pages, new ArrayList<String>()), DAY, NOW, null), 0);
        assertEquals(null, a.get("start"));
        assertTrue((String) a.get("racesWhy"), ((String) a.get("racesWhy")).startsWith("programda koşu tablosu bulunamadı ("));
    }

    @Test
    public void cityLinksResolveRelativePathsAndEncodeNames() {
        String page = Tjk.programUrl(DAY);
        assertEquals("https://www.tjk.org/TR/YarisSever/Info/Page/GunlukYarisProgrami?SehirId=3", AtYarisi.absolute(page, "GunlukYarisProgrami?SehirId=3"));
        assertEquals("https://www.tjk.org/TR/YarisSever/Info/Sehir/GunlukYarisProgrami?SehirId=3", AtYarisi.absolute(page, "../Sehir/GunlukYarisProgrami?SehirId=3"));
        assertEquals("https://www.tjk.org/TR/x?SehirId=3", AtYarisi.absolute(page, "/TR/x?SehirId=3"));
        assertEquals("https://www.tjk.org/TR/YarisSever/Info/Page/GunlukYarisProgrami?SehirId=3", AtYarisi.absolute(page, "?SehirId=3"));
        assertEquals("https://x.org/a?SehirAdi=Belmont%20Park%20ABD&b=%C4%B0stanbul", AtYarisi.safeUrl("https://x.org/a?SehirAdi=Belmont Park ABD&b=İstanbul"));
        assertEquals("https://x.org/a?b=%C4%B0", AtYarisi.safeUrl("https://x.org/a?b=%C4%B0")); // kodlanmış olan bozulmaz
        Map<Integer, String[]> links = Tjk.cityLinks("<a href=\" GunlukYarisProgrami?SehirId=41&amp;SehirAdi=Belmont Park ABD\">Belmont Park ABD</a>", page);
        assertEquals("https://www.tjk.org/TR/YarisSever/Info/Page/GunlukYarisProgrami?SehirId=41&SehirAdi=Belmont%20Park%20ABD", links.get(41)[1]);
        assertEquals("Belmont Park ABD", links.get(41)[0]);
    }

    static List<Tjk.Horse> leg(int n) {
        List<Tjk.Horse> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) l.add(new Tjk.Horse(String.valueOf(i), 100.0 / n));
        return l;
    }

    @Test
    public void consecutiveRacesResolveSameFieldSizes() {
        int[] sizes = {8, 6, 7, 6, 9, 5, 8, 7, 10}; // 2. ve 4. koşu aynı at sayısında
        List<Tjk.ProgramRace> program = new ArrayList<>();
        for (int r = 0; r < sizes.length; r++) {
            java.util.Set<String> set = new java.util.LinkedHashSet<>();
            for (int i = 1; i <= sizes[r]; i++) set.add(String.valueOf(i));
            program.add(new Tjk.ProgramRace(r + 1, String.format(java.util.Locale.ROOT, "%02d:00", 12 + r), set));
        }
        List<List<Tjk.Horse>> legs = new ArrayList<>();
        for (int r = 3; r < 9; r++) legs.add(leg(sizes[r]));
        List<Object> races = Tjk.legRaces(legs, program);
        assertEquals(4L, Json.obj(races.get(0)).get("no")); // eski ayak ayak eşleme 2. koşuyu seçerdi
        assertEquals("15:00", Json.obj(races.get(0)).get("time"));
        assertEquals(9L, Json.obj(races.get(5)).get("no"));
        // eşleşmeyince neden: ayak ve koşu at sayıları
        List<List<Tjk.Horse>> other = new ArrayList<>();
        for (int k = 0; k < 6; k++) other.add(leg(14));
        assertEquals(null, Tjk.legRaces(other, program.subList(0, 3)));
        String why = Tjk.matchWhy(other, program.subList(0, 3));
        assertEquals("programdaki 3 koşu ayaklarla eşleşmedi (ayak at sayıları 14/14/14/14/14/14; koşular 1:8 2:6 3:7; 1. ayak 1,2,3,4,5,6,7,8,9,10,11,12…; 1. koşu 1,2,3,4,5,6,7,8)", why);
    }

    @Test
    public void horseNumbersWithEkuriOrNamesInCells() {
        String table = "<table><tr><th>N</th><th>At</th></tr><tr><td>1</td><td>A</td></tr><tr><td>1A</td><td>B</td></tr><tr><td>2 SECRETARIAT (USA)</td><td>C</td></tr>"
                + "<tr><td>3</td><td>D</td></tr></table>";
        List<java.util.Set<String>> cs = Tjk.runnerCandidates(table);
        boolean found = false;
        for (java.util.Set<String> c : cs) found |= c.containsAll(Arrays.asList("1", "2", "3"));
        assertTrue(String.valueOf(cs), found);
    }

    static List<Tjk.Horse> horses(int... nos) {
        List<Tjk.Horse> l = new ArrayList<>();
        for (int n : nos) l.add(new Tjk.Horse(String.valueOf(n), 100.0 / nos.length));
        return l;
    }

    @Test
    public void agfWithoutZeroHorsesMatchesByContainment() {
        // 21.10 Keeneland 2. altılı: AGF'de payı %0 olan atlar yok (ayak at sayıları 6/4/7/5/6/13; koşular 9,9,11,9,11,9,11,16)
        int[] sizes = {9, 9, 11, 9, 11, 9, 11, 16};
        List<Tjk.ProgramRace> program = new ArrayList<>();
        for (int r = 0; r < sizes.length; r++) {
            java.util.Set<String> set = new java.util.LinkedHashSet<>();
            for (int i = 1; i <= sizes[r]; i++) set.add(String.valueOf(i));
            program.add(new Tjk.ProgramRace(r + 1, null, set));
        }
        List<List<Tjk.Horse>> legs = Arrays.asList(horses(5, 9, 3, 6, 1, 10), horses(2, 4, 7, 9), horses(1, 2, 3, 5, 8, 10, 11), horses(1, 3, 4, 6, 9),
                horses(2, 4, 5, 7, 9, 11), horses(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 15));
        List<Object> races = Tjk.legRaces(legs, program);
        assertEquals(3L, Json.obj(races.get(0)).get("no"));
        assertEquals(8L, Json.obj(races.get(5)).get("no"));
    }
}
