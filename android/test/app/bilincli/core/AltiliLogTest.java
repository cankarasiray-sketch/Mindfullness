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
import java.util.Random;
import org.junit.Test;

/** 2.26: altılı defteri, TJK sonuçları ve öğrenme (AGF düzeltmesi, bütçe). */
public class AltiliLogTest {
    static final Instant NOW = Instant.parse("2026-10-22T07:00:00Z");
    static final String DAY = "2026-10-21", TODAY = "2026-10-22";
    static final List<String> LEGS = Arrays.asList("5:40 3:30 1:20 2:10", "7:50 2:30 4:20", "5:34 1:33 6:33", "1:60 4:25 2:15", "5:45 2:35 7:20",
            "2:79 1:12 3:9");

    static Altili.Inputs inputs() {
        Altili.Inputs in = new Altili.Inputs();
        in.kasa = 2000;
        in.budget = 60;
        return in;
    }

    static Map<String, Object> altili(String key, String city, long cityId, long no, List<String> legs) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("key", key);
        a.put("source", "TJK");
        a.put("city", city);
        a.put("cityId", cityId);
        a.put("no", no);
        a.put("label", city + " " + no + ". altılı");
        a.put("day", DAY);
        a.put("legs", new ArrayList<Object>(legs));
        a.put("plan", Altili.plan(legs, inputs()));
        return a;
    }

    static Map<String, Object> city(long id, String name, List<Object> combos, List<Object> races) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("cityId", id);
        c.put("city", name);
        c.put("combos", combos);
        c.put("races", races);
        return c;
    }

    static Map<String, Object> combo(double payout, String... nos) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("nos", new ArrayList<Object>(Arrays.asList((Object[]) nos)));
        c.put("payout", payout);
        return c;
    }

    static Map<String, Object> race(String winner, String... runners) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("runners", new ArrayList<Object>(Arrays.asList((Object[]) runners)));
        r.put("winner", winner);
        return r;
    }

    static Map<String, Object> results(Object... cities) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cities", new ArrayList<Object>(Arrays.asList(cities)));
        return r;
    }

    static Map<String, Object> rec(Map<String, Object> store, String key) {
        for (Object o : AltiliLog.records(store)) if (key.equals(Json.str(Json.obj(o), "key"))) return Json.obj(o);
        return null;
    }

    @Test
    public void recordNotifyAndSettleFromWinningCombination() {
        Map<String, Object> store = new LinkedHashMap<>();
        Map<String, Object> ist = altili("tjk/2026-10-21/3/1", "İstanbul", 3, 1, LEGS);
        AltiliLog.record(store, DAY, Arrays.<Object>asList(ist));
        AltiliLog.markNotified(store, DAY, "tjk/2026-10-21/3/1", Json.obj(ist.get("plan")), NOW);
        assertEquals(Arrays.asList(DAY), AltiliLog.pendingDays(store, TODAY));
        assertEquals(0, AltiliLog.pendingDays(store, DAY).size()); // bugünkü henüz beklenmez
        // kupon her ayağın favorisini içerir: 5/7/5/1/5/2 tutar
        Map<String, Object> res = results(city(3, "İstanbul", Arrays.<Object>asList(combo(50.0, "5", "7", "5", "1", "5", "2")), new ArrayList<Object>()));
        assertEquals(1, AltiliLog.settle(store, DAY, res, 1, 0.27, 0, TODAY, NOW));
        Map<String, Object> r = Json.obj(rec(store, "tjk/2026-10-21/3/1").get("result"));
        assertEquals(Arrays.asList("5", "7", "5", "1", "5", "2"), r.get("winners"));
        assertEquals(50.0, Json.dbl(r, "payout", 0), 1e-9); // 2.38: AGF ile tutarlı ikramiye (×0,73)
        assertEquals(Boolean.FALSE, r.get("estimated"));
        assertEquals("kombinasyon", r.get("from"));
        assertEquals(0, AltiliLog.pendingDays(store, TODAY).size());
        Map<String, Object> a = AltiliLog.analyze(store, 1, 2000, TODAY);
        Map<String, Object> s = Json.obj(a.get("suggested"));
        assertEquals(1L, s.get("n"));
        assertEquals(1L, s.get("hits"));
        assertEquals(50.0, Json.dbl(s, "ret", 0), 1e-9);
        Map<String, Object> h = Json.obj(Json.arr(a.get("history")).get(0));
        assertEquals("tuttu", h.get("status"));
        assertEquals(Boolean.TRUE, h.get("notified"));
        // sonuçlanmış kayıt yeni okumayla değişmez
        AltiliLog.record(store, DAY, Arrays.<Object>asList(altili("tjk/2026-10-21/3/1", "İstanbul", 3, 1, Arrays.asList("9:90 8:10", "9:90 8:10", "9:90 8:10",
                "9:90 8:10", "9:90 8:10", "9:90 8:10"))));
        assertEquals("5:40 3:30 1:20 2:10", Json.arr(rec(store, "tjk/2026-10-21/3/1").get("legs")).get(0));
    }

    @Test
    public void oneLegMissIsCounted() {
        // 2.33: "6 ayaktan hep 1 tanesi yatıyor": tek ayakla kaçan kupon ve yatan ayağın tek atlı (banko) olup olmadığı
        Map<String, Object> store = new LinkedHashMap<>();
        AltiliLog.record(store, DAY, Arrays.<Object>asList(altili("tjk/2026-10-21/3/1", "İstanbul", 3, 1, LEGS)));
        List<Object> horses = Json.arr(Json.obj(rec(store, "tjk/2026-10-21/3/1").get("plan")).get("horses"));
        String[] win = new String[6];
        for (int k = 0; k < 6; k++) win[k] = String.valueOf(Json.arr(horses.get(k)).get(0));
        win[5] = "3"; // 6. ayakta sürpriz
        assertFalse(Json.arr(horses.get(5)).contains("3"));
        assertEquals(1, AltiliLog.settle(store, DAY, results(city(3, "İstanbul", Arrays.<Object>asList(combo(500, win)), new ArrayList<Object>())),
                1, 0.27, 0, TODAY, NOW));
        Map<String, Object> lg = Json.obj(AltiliLog.analyze(store, 1, 2000, TODAY).get("legs"));
        assertEquals(1L, lg.get("five"));
        assertEquals(Json.arr(horses.get(5)).size() == 1 ? 1L : 0L, lg.get("fiveBanko"));
    }

    @Test
    public void settleFromRaceTablesWithEstimatedPayoutAndMissing() {
        Map<String, Object> store = new LinkedHashMap<>();
        AltiliLog.record(store, DAY, Arrays.<Object>asList(altili("bilyoner/Bursa", "Bursa", 99, 1, LEGS)));
        // şehir adıyla eşleşme (farklı şehir numarası), koşular sırasız; 3. ayakta sürpriz (6)
        List<Object> races = Arrays.<Object>asList(race("2", "1", "2", "3"), race("6", "5", "1", "6"), race("3", "5", "3", "1", "2"),
                race("2", "7", "2", "4"), race("7", "5", "2", "7"), race("4", "1", "4", "2"), race("1", "9", "1"));
        assertEquals(1, AltiliLog.settle(store, DAY, results(city(1, "BURSA", new ArrayList<Object>(), races)), 1, 0.27, 0, TODAY, NOW));
        Map<String, Object> r = Json.obj(rec(store, "bilyoner/Bursa").get("result"));
        assertEquals(Arrays.asList("3", "2", "6", "4", "7", "2"), r.get("winners"));
        assertEquals("atlar", r.get("from")); // 2.36: koşu numarası yok: atlarla (tek ve açık aday)
        assertEquals(Boolean.TRUE, r.get("estimated"));
        double pw = 0.30 * 0.30 * 0.33 * 0.25 * 0.20 * 0.79;
        assertEquals(0.73 / pw, Json.dbl(r, "payout", 0), 1e-6);
        // sonuç bulunamayan eski gün kapanır, yenisi bekler
        Map<String, Object> store2 = new LinkedHashMap<>();
        AltiliLog.record(store2, DAY, Arrays.<Object>asList(altili("tjk/x", "Elazığ", 7, 1, LEGS)));
        assertEquals(0, AltiliLog.settle(store2, DAY, results(), 1, 0.27, 0, TODAY, NOW));
        assertEquals(1, AltiliLog.settle(store2, DAY, results(), 1, 0.27, 0, "2026-10-27", NOW));
        assertEquals(Boolean.TRUE, Json.obj(rec(store2, "tjk/x").get("result")).get("missing"));
    }

    @Test
    public void inconsistentComboFallsBackToRaces() {
        Map<String, Object> store = new LinkedHashMap<>();
        AltiliLog.record(store, DAY, Arrays.<Object>asList(altili("k", "Karma", 5, 2, LEGS)));
        // 2. altılının kombinasyonu 1. sırada değil; ilki bu ayaklarla tutarsız (9 yok)
        Map<String, Object> res = results(city(5, "Karma", Arrays.<Object>asList(combo(10, "9", "9", "9", "9", "9", "9"), combo(55.5, "3", "2", "6", "4", "7", "2")),
                new ArrayList<Object>()));
        AltiliLog.settle(store, DAY, res, 1, 0.27, 0, TODAY, NOW);
        assertEquals(55.5, Json.dbl(Json.obj(rec(store, "k").get("result")), "payout", 0), 1e-9);
    }

    /** Kazananlar q ∝ p^gamma ile çekilen yapay geçmiş. */
    static Map<String, Object> history(double gamma, int days, long seed) {
        Random rnd = new Random(seed);
        Map<String, Object> store = new LinkedHashMap<>();
        LocalDate start = LocalDate.parse(TODAY).minusDays(days + 1);
        for (int d = 0; d < days; d++) {
            String day = start.plusDays(d).toString();
            for (int a = 0; a < 2; a++) {
                List<String> legs = new ArrayList<>();
                List<Object> win = new ArrayList<>();
                for (int k = 0; k < 6; k++) {
                    int n = 6 + rnd.nextInt(5);
                    double[] w = new double[n];
                    double sum = 0;
                    for (int i = 0; i < n; i++) sum += w[i] = Math.pow(rnd.nextDouble() + 0.05, 2);
                    StringBuilder b = new StringBuilder();
                    double z = 0;
                    for (int i = 0; i < n; i++) z += Math.pow(w[i] / sum, gamma);
                    double u = rnd.nextDouble(), acc = 0;
                    String winner = "1";
                    for (int i = 0; i < n; i++) {
                        b.append(i == 0 ? "" : " ").append(i + 1).append(':').append(Tjk.pct(100 * w[i] / sum));
                        acc += Math.pow(w[i] / sum, gamma) / z;
                        if (u <= acc && winner.equals("1") && acc - Math.pow(w[i] / sum, gamma) / z < u) winner = String.valueOf(i + 1);
                    }
                    legs.add(b.toString());
                    win.add(winner);
                }
                Map<String, Object> al = altili("h" + d + "/" + a, "Şehir" + a, 100 + a, 1, legs);
                al.put("day", day);
                AltiliLog.record(store, day, Arrays.<Object>asList(al));
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("nos", win);
                double pw = 1; // 2.38: gerçekçi ikramiye: birim × 0,73 / kazanan kombinasyonun AGF payı
                for (int k = 0; k < 6; k++) pw *= AltiliLog.agf(legs.get(k)).get(String.valueOf(win.get(k)));
                c.put("payout", 0.73 / pw);
                AltiliLog.settle(store, day, results(city(100 + a, "Şehir" + a, Arrays.<Object>asList(c), new ArrayList<Object>())), 1, 0.27, 0, TODAY, NOW);
            }
        }
        return store;
    }

    @Test
    public void learnsCrowdBiasOnlyWhenRealAndValidated() {
        Map<String, Object> biased = AltiliLog.analyze(history(1.6, 40, 7), 1, 2000, TODAY);
        Map<String, Object> c = Json.obj(biased.get("calib"));
        assertEquals(480L, c.get("n"));
        assertTrue("alpha " + c.get("alpha"), Json.dbl(c, "alpha", 0) > 1.3);
        assertEquals(Boolean.TRUE, c.get("use"));
        double use = Json.dbl(c, "alphaUse", 1);
        assertTrue("büzülmüş " + use, use > 1.1 && use < Json.dbl(c, "alpha", 0));
        assertTrue((String) c.get("why"), ((String) c.get("why")).startsWith("Kalabalık favorileri az"));
        Map<String, Object> fav = Json.obj(biased.get("fav"));
        assertTrue(Json.dbl(fav, "win", 0) > Json.dbl(fav, "agf", 1)); // favoriler AGF'nin verdiğinden sık kazanıyor
        assertEquals(3, Json.arr(biased.get("strategies")).size());
        assertTrue(biased.get("fraction") != null); // 80 sonuç ≥ 20: bütçe seçildi
        Map<String, Object> fair = Json.obj(AltiliLog.analyze(history(1.0, 40, 7), 1, 2000, TODAY).get("calib"));
        assertEquals(Boolean.FALSE, fair.get("use"));
        assertEquals(1.0, Json.dbl(fair, "alphaUse", 0), 1e-12);
        Map<String, Object> few = Json.obj(AltiliLog.analyze(history(1.6, 4, 7), 1, 2000, TODAY).get("calib"));
        assertEquals(Boolean.FALSE, few.get("use")); // 48 ayak: veri az (2.40: en az 60 doğrulanmış ayak)
        assertTrue((String) few.get("why"), ((String) few.get("why")).startsWith("Doğrulanmış veri az"));
        assertNull(AltiliLog.analyze(history(1.6, 5, 7), 1, 2000, TODAY).get("fraction"));
    }

    @Test
    public void learnedAlphaMovesProbabilityToFavorites() {
        Altili.Leg plain = Altili.leg("1:50 2:30 3:20"), learned = Altili.leg("1:50 2:30 3:20", 1.5);
        assertEquals(0.5, plain.horses.get(0).q, 1e-9);
        assertTrue(learned.horses.get(0).q > 0.55);
        assertEquals(1.0, learned.horses.get(0).q + learned.horses.get(1).q + learned.horses.get(2).q, 1e-9);
        assertFalse(Altili.leg("1:50:2 2:30:3 3:20:6", 1.5).horses.get(0).q != 0.5 / (0.5 + 1 / 3.0 + 1 / 6.0) * 1); // ganyan varsa düzeltme yok
    }

    @Test
    public void tjkResultPageParsing() {
        String html = "<html><body><h2>BURSA 21.10.2026</h2><table><tr><th>S</th><th>N</th><th>At İsmi</th><th>Derece</th></tr>"
                + "<tr><td>1</td><td>5</td><td>KARAYEL</td><td>1.25.36</td></tr><tr><td>2</td><td>3</td><td>RÜZGAR</td><td>1.25.50</td></tr>"
                + "<tr><td>3</td><td>1</td><td>ŞİMŞEK</td><td>1.26.00</td></tr></table>"
                + "<table><tr><th>N</th><th>At İsmi</th></tr><tr><td>7</td><td>A</td></tr><tr><td>2</td><td>B</td></tr></table>"
                + "<p>1. 6'LI GANYAN (1-2-3-4-5-6. Koşular) · 2. 6'LI GANYAN 4-5-6-7-8-9</p>" // 2.37: koşu numaraları, kazanan değil
                + "<p>Bahis sonuçları: İKİLİ 5/3 12,50 TL · 1. 6'LI GANYAN 5/7/5/1/5/2 12.345,67 TL · 2. ALTILI GANYAN : 4-2-3-1-5-6 (6 BİLEN) 98,10 TL</p></body></html>";
        List<Map<String, Object>> combos = Tjk.combos(html);
        assertEquals(2, combos.size());
        assertEquals(Arrays.asList("5", "7", "5", "1", "5", "2"), combos.get(0).get("nos"));
        assertEquals(12345.67, (Double) combos.get(0).get("payout"), 1e-9);
        assertEquals(Arrays.asList("4", "2", "3", "1", "5", "6"), combos.get(1).get("nos"));
        assertEquals(98.10, (Double) combos.get(1).get("payout"), 1e-9); // 2.41: "(6 BİLEN)" araya girse de
        // 2.41: TJK biçimi "6 BİLEN … 5 BİLEN …": 6/6 ikramiyesi alınır; 6 bilen çıkmadıysa ikramiye yok
        List<Map<String, Object>> bilen = Tjk.combos("<p>6'LI GANYAN 5/7/5/1/5/2 6 BİLEN: 734.512,50 TL 5 BİLEN: 2.345,10 TL</p>"
                + "<p>2. 6'LI GANYAN 3/1/4/1/5/2 5 BİLEN: 1.234,50 TL (6 bilen çıkmadı, devretti)</p>");
        assertEquals(734512.50, (Double) bilen.get(0).get("payout"), 1e-9);
        assertEquals(null, bilen.get(1).get("payout"));
        List<Map<String, Object>> races = Tjk.raceResults(html);
        assertEquals(2, races.size());
        assertEquals("5", races.get(0).get("winner")); // sıra sütununda 1
        assertEquals(Arrays.asList("5", "3", "1"), races.get(0).get("runners"));
        assertEquals("7", races.get(1).get("winner")); // sıra sütunu yok: ilk satır
        String main = "<a href=\"?SehirId=4&amp;QueryParameter_Tarih=21%2F10%2F2026&amp;SehirAdi=Bursa\">Bursa</a>";
        Map<Integer, String[]> links = Tjk.cityLinks(main, Tjk.resultsUrl(LocalDate.parse(DAY)));
        assertEquals("Bursa", links.get(4)[0]);
        assertEquals("https://www.tjk.org/TR/YarisSever/Info/Page/GunlukYarisSonuclari?SehirId=4&QueryParameter_Tarih=21%2F10%2F2026&SehirAdi=Bursa", links.get(4)[1]);
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put(Tjk.resultsUrl(LocalDate.parse(DAY)), main);
        pages.put(links.get(4)[1], html);
        Map<String, Object> r = Tjk.results(TjkTest.fake(pages, new ArrayList<String>()), LocalDate.parse(DAY), null);
        Map<String, Object> c = Json.obj(Json.arr(r.get("cities")).get(0));
        assertEquals("Bursa", c.get("city"));
        assertEquals(2, Json.arr(c.get("combos")).size());
        assertTrue(String.valueOf(r.get("log")), String.valueOf(r.get("log")).contains("Bursa: 2 altılı sonucu (5/7/5/1/5/2 → 12.345,67 TL, 4/2/3/1/5/6 → 98,10 TL), 2 koşu"));
    }

    /** Sonuç sayfası gibi: "N. Koşu HH.MM" başlığı, sonra bitiriş sırasıyla tablo (at no başlıksız: "5 KARAYEL"). */
    static String resultPage(String[][] races) {
        StringBuilder b = new StringBuilder("<div>Özet PDF Sonuçlar | CSV Sonuçlar</div>");
        for (String[] r : races) {
            b.append("<h3>").append(r[0]).append(". Koşu 20.").append(r[0]).append("0</h3><table><tr><td>Sıra</td><td>At</td><td>Derece</td><td>Gny</td></tr>");
            for (int i = 1; i < r.length; i++)
                b.append("<tr><td>").append(i).append("</td><td>").append(r[i]).append(" AT").append(r[i]).append(" (USA)</td><td>1.2").append(i).append(".10</td><td>4,").append(i).append("0</td></tr>");
            b.append("</table>");
        }
        return b.toString();
    }

    @Test
    public void liveLegWinnersByRaceNumber() {
        // 2.34: koşu numarası ve bitiriş sırasıyla; at no sütunu başlıksız ("5 AT5 (USA)"); sıra sütunu 1, 2, 3…
        String html = resultPage(new String[][] {{"1", "6", "1", "4", "7", "5"}, {"2", "9", "5", "8", "2"}, {"3", "3", "5", "1", "7"}});
        List<Map<String, Object>> races = Tjk.raceResults(html);
        assertEquals(3, races.size());
        assertEquals(1L, races.get(0).get("no"));
        assertEquals("6", races.get(0).get("winner"));
        assertEquals("9", races.get(1).get("winner"));
        assertEquals(Arrays.asList("3", "5", "1", "7"), races.get(2).get("runners"));
        // altılı 1–6. koşular: ilk üç koştu, kalanlar null
        Map<String, Object> a = altili("tjk/2026-10-21/31/1", "Keeneland ABD", 31, 1, Arrays.asList("1:40 6:20 4:15 7:15 5:10", "5:64 8:14 9:12 2:10",
                "5:30 1:25 3:15 7:11 2:10 4:9", "9:66 1:20 2:14", "6:40 5:20 10:12 4:8 1:20", "3:35 4:20 7:14 5:10 1:21"));
        List<Object> rs = new ArrayList<>();
        for (int k = 1; k <= 6; k++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("no", (long) k);
            r.put("time", "20:" + k + "0");
            rs.add(r);
        }
        a.put("races", rs);
        Map<String, Object> city = city(31, "Keeneland ABD", new ArrayList<Object>(), new ArrayList<Object>(races));
        assertEquals(Arrays.asList("6", "9", "3", null, null, null), AltiliLog.legWinners(a, city));
        String[] how = new String[1];
        AltiliLog.legWinners(a, city, how);
        assertEquals("koşu no", how[0]); // 2.36: kaynak karnede gösterilir
        // 2.35: koşu numarası yanlış koşuyu gösterirse (atlar örtüşmüyor) atlara göre tahmin edilmez: boş kalır
        rs.set(0, new LinkedHashMap<String, Object>(Json.obj(rs.get(1))));
        assertEquals(null, AltiliLog.legWinners(a, city).get(0));
        assertEquals(Arrays.<Object>asList(null, null, null, null, null, null), AltiliLog.legWinners(a, null));
        // şehir bağlantısı sayfa iskeleti (tablosuz): şehir sekmesinin adresi denenir
        String main = "<a href=\"?SehirId=31&amp;QueryParameter_Tarih=21%2F10%2F2026&amp;SehirAdi=Keeneland+ABD\">K</a>"
                + "<a href=\"?SehirId=3&amp;QueryParameter_Tarih=21%2F10%2F2026&amp;SehirAdi=%C4%B0stanbul\">I</a>";
        Map<Integer, String[]> links = Tjk.cityLinks(main, Tjk.resultsUrl(LocalDate.parse(DAY)));
        Map<String, String> pages = new LinkedHashMap<>();
        pages.put(Tjk.resultsUrl(LocalDate.parse(DAY)), main);
        pages.put(links.get(31)[1], "<div>| Özet PDF Sonuçlar | CSV Sonuçlar | 1. AGF Tablosu | 2. AGF Tablosu Tüm Koşular</div>");
        pages.put(Tjk.programAlternative(links.get(31)[1]), html);
        List<String> asked = new ArrayList<>();
        Map<String, Object> r = Tjk.results(TjkTest.fake(pages, asked), LocalDate.parse(DAY), null, new java.util.HashSet<>(Arrays.asList(31)));
        assertEquals(1, Json.arr(r.get("cities")).size()); // yalnızca istenen şehir
        assertEquals(3, Json.arr(Json.obj(Json.arr(r.get("cities")).get(0)).get("races")).size());
        assertEquals(3, asked.size());
        assertTrue(String.valueOf(r.get("log")), String.valueOf(r.get("log")).contains("Keeneland ABD: 0 altılı sonucu, 3 koşu (kazananlar 1→6, 2→9, 3→3; "
                + "sütunlar: Sıra | At | Derece | Gny → at no 2. sütun)"));
    }

    @Test
    public void resultsAreVerifiedByPayout() {
        // 2.38: ikramiye × kazanan kombinasyonun AGF payı ≈ 0,73: tutarlıysa doğrulanmış, değilse yanlış okunmuş (sayılmaz)
        Map<String, Object> store = new LinkedHashMap<>();
        AltiliLog.record(store, DAY, Arrays.<Object>asList(altili("tjk/2026-10-21/3/1", "İstanbul", 3, 1, LEGS)));
        double real = 0.4 * 0.5 * 0.34 * 0.6 * 0.45 * 0.79; // favoriler kazandı: 5/7/5/1/5/2
        // sayfada iki 6'lı sonucu: önce öbür altılınınki (1/2/1/4/2/1); ikramiyesi bu altılının AGF'siyle tutarlı olan seçilir
        Map<String, Object> page = results(city(3, "İstanbul", Arrays.<Object>asList(combo(950000, "1", "2", "1", "4", "2", "1"),
                combo(0.73 / real, "5", "7", "5", "1", "5", "2")), new ArrayList<Object>()));
        assertEquals(1, AltiliLog.settle(store, DAY, page, 1, 0.27, 0, TODAY, NOW));
        Map<String, Object> rec = rec(store, "tjk/2026-10-21/3/1"), res = Json.obj(rec.get("result"));
        assertEquals(Arrays.asList("5", "7", "5", "1", "5", "2"), res.get("winners"));
        assertEquals(0.73, AltiliLog.payoutRatio(rec, res, 1), 1e-9);
        assertEquals("ikramiye", AltiliLog.trust(rec, res, 1));
        // yanlış okunmuş kazananlar: oran çok küçük → tutarsız, karnede sayılmaz
        Map<String, Object> wrong = new LinkedHashMap<>(res);
        wrong.put("winners", new ArrayList<Object>(Arrays.asList("2", "4", "6", "2", "7", "3")));
        assertEquals("tutarsız", AltiliLog.trust(rec, wrong, 1));
        Map<String, Object> est = new LinkedHashMap<>(res);
        est.put("estimated", true);
        assertEquals("doğrulanamadı", AltiliLog.trust(rec, est, 1));
        Map<String, Object> a = AltiliLog.analyze(store, 1, 2000, TODAY);
        assertEquals(1L, a.get("settled"));
        assertEquals(null, a.get("inconsistent"));
        rec.put("result", wrong);
        Map<String, Object> b = AltiliLog.analyze(store, 1, 2000, TODAY);
        assertEquals(0L, b.get("settled"));
        assertEquals(1L, b.get("inconsistent"));
        assertEquals("İkramiyeyle tutarsız (sayılmadı)", Json.obj(Json.arr(Json.obj(b.get("fav")).get("groups")).get(0)).get("label"));
        Map<String, Object> h = Json.obj(Json.arr(b.get("history")).get(0));
        assertEquals("tutarsız", h.get("trust"));
        assertTrue((Double) h.get("ratio") < 0.1);
    }

    @Test
    public void backtestRecordsFeedTheKarne() {
        // 2.43: geriye dönük tahmin: bugünkü kurallarla kurulan kupon "geriye dönük" kaydedilir, sonuçla karşılaştırılır
        Map<String, Object> store = new LinkedHashMap<>();
        Map<String, Object> a = altili("tjk/2026-10-21/3/1", "İstanbul", 3, 1, LEGS);
        a.put("backtest", true);
        AltiliLog.record(store, DAY, Arrays.<Object>asList(a));
        assertEquals(new java.util.HashSet<>(Arrays.asList("tjk/2026-10-21/3/1")), AltiliLog.keys(store, DAY));
        Map<String, Object> rec = rec(store, "tjk/2026-10-21/3/1");
        assertEquals(Boolean.TRUE, rec.get("backtest"));
        double hit = (Double) Json.obj(rec.get("plan")).get("hit"); // tahmin edilen 6/6 kaydedilir
        assertEquals((Double) Json.obj(a.get("plan")).get("hit"), hit, 1e-12);
        double real = 0.4 * 0.5 * 0.34 * 0.6 * 0.45 * 0.79;
        AltiliLog.settle(store, DAY, results(city(3, "İstanbul", Arrays.<Object>asList(combo(0.73 / real, "5", "7", "5", "1", "5", "2")), new ArrayList<Object>())),
                1, 0.27, 0, TODAY, NOW);
        assertEquals(1, AltiliLog.outcome(store, DAY, AltiliLog.keys(store, DAY), 1)[1]); // ikramiyeyle doğrulandı
        Map<String, Object> an = AltiliLog.analyze(store, 1, 2000, TODAY, 0, 0);
        assertEquals(hit, (Double) Json.obj(an.get("all")).get("expected"), 1e-12);
        Map<String, Object> rule = Json.obj(an.get("rule"));
        assertEquals(1L, rule.get("n"));
        assertEquals(1L, rule.get("hits")); // favoriler kazandı, kupon tuttu
        assertEquals(0L, Json.obj(AltiliLog.analyze(store, 1, 2000, TODAY, 0, 0.99).get("rule")).get("n")); // eşik üstü yok
        assertEquals(Boolean.TRUE, Json.obj(Json.arr(an.get("history")).get(0)).get("backtest"));
        // 2.45: tahmin edilen dönüş kaydedilir; kâr kuralına (dönüş ≥ %110) uyanlar karnede ayrı
        double roi = (Double) Json.obj(rec.get("plan")).get("roi");
        Map<String, Object> pr = Json.obj(AltiliLog.analyze(store, 1, 2000, TODAY, 0, 0, 0.10).get("profitRule"));
        assertEquals(roi >= 1.10 ? 1L : 0L, pr.get("n"));
        assertEquals(roi, (Double) Json.obj(AltiliLog.analyze(store, 1, 2000, TODAY, 0, 0, 0.10).get("all")).get("expectedRoi"), 1e-12);
        assertEquals(1L, Json.obj(AltiliLog.analyze(store, 1, 2000, TODAY, 0, 0, -1).get("profitRule")).get("n")); // eşiksiz: hepsi
        // 2.48: strateji tablosu: 6/6 olasılığı aralığı ve bölge (İstanbul yerli)
        List<Object> table = Json.arr(an.get("table"));
        assertEquals(2, table.size());
        Map<String, Object> bucket = Json.obj(table.get(0)), region = Json.obj(table.get(1));
        assertTrue((Double) bucket.get("lo") <= hit && (bucket.get("hi") == null || hit < (Double) bucket.get("hi")));
        assertEquals(1L, bucket.get("hits"));
        assertEquals(hit, (Double) bucket.get("expected"), 1e-12);
        assertEquals("yerli", region.get("region"));
        assertEquals(1L, region.get("n"));
        assertEquals(false, new Settings().altiliPlayNoEdge); // varsayılan kâr modu
    }

    @Test
    public void regionWhereAgfFailsIsFlagged() {
        // 2.42: doğrulanmış sonuçlarda yerli favori hiç kazanmıyor (3 altılı, 18 ayak), yabancıda kazanıyor
        Map<String, Object> store = new LinkedHashMap<>();
        String[] days = {"2026-10-18", "2026-10-19", "2026-10-20"};
        for (int d = 0; d < 3; d++) {
            Map<String, Object> home = altili("tjk/" + days[d] + "/3/1", "İstanbul", 3, 1, LEGS), away = altili("tjk/" + days[d] + "/31/1", "Belmont Park ABD", 31, 1, LEGS);
            home.put("day", days[d]);
            away.put("day", days[d]);
            AltiliLog.record(store, days[d], Arrays.<Object>asList(home, away));
            List<Object> outsiders = Arrays.<Object>asList("2", "4", "6", "2", "7", "3"), favs = Arrays.<Object>asList("5", "7", "5", "1", "5", "2");
            for (Object[] x : new Object[][] {{home, outsiders}, {away, favs}}) {
                @SuppressWarnings("unchecked")
                List<Object> w = (List<Object>) x[1];
                double pw = 1;
                for (int k = 0; k < 6; k++) pw *= AltiliLog.agf(LEGS.get(k)).get(String.valueOf(w.get(k)));
                Map<String, Object> res = new LinkedHashMap<>();
                res.put("winners", new ArrayList<Object>(w));
                res.put("payout", 0.73 / pw); // ikramiyeyle doğrulanmış
                res.put("from", "kombinasyon");
                rec(store, Json.str((Map<String, Object>) x[0], "key")).put("result", res);
            }
        }
        Map<String, Object> rt = Json.obj(AltiliLog.analyze(store, 1, 2000, TODAY).get("regionTrust"));
        assertEquals(Boolean.TRUE, Json.obj(rt.get("yerli")).get("weak"));
        assertEquals(0.0, (Double) Json.obj(rt.get("yerli")).get("win"), 1e-9);
        assertEquals(Boolean.FALSE, Json.obj(rt.get("yabancı")).get("weak"));
        assertEquals(1.0, (Double) Json.obj(rt.get("yabancı")).get("win"), 1e-9);
    }

    @Test
    public void pastResultsAreRechecked() {
        // 2.39: eski, yanlış okunmuş (ikramiyesi tutarsız) ve bulunamamış sonuçlar o günün sayfası yeniden okunarak doğrulanır
        Map<String, Object> store = new LinkedHashMap<>();
        AltiliLog.record(store, DAY, Arrays.<Object>asList(altili("tjk/2026-10-21/3/1", "İstanbul", 3, 1, LEGS),
                altili("tjk/2026-10-21/4/1", "Bursa", 4, 1, LEGS)));
        double real = 0.4 * 0.5 * 0.34 * 0.6 * 0.45 * 0.79;
        Map<String, Object> wrong = new LinkedHashMap<>(), missing = new LinkedHashMap<>();
        wrong.put("winners", new ArrayList<Object>(Arrays.asList("2", "4", "6", "2", "7", "3")));
        wrong.put("payout", 0.73 / real);
        wrong.put("from", "kombinasyon");
        missing.put("missing", true);
        rec(store, "tjk/2026-10-21/3/1").put("result", wrong);
        rec(store, "tjk/2026-10-21/4/1").put("result", missing);
        assertEquals(Arrays.asList(DAY), AltiliLog.recheckDays(store, TODAY, 1));
        assertEquals(new java.util.HashSet<>(Arrays.asList(3, 4)), AltiliLog.recheckCities(store, DAY, 1));
        Map<String, Object> page = results(city(3, "İstanbul", Arrays.<Object>asList(combo(0.73 / real, "5", "7", "5", "1", "5", "2")), new ArrayList<Object>()),
                city(4, "Bursa", Arrays.<Object>asList(combo(0.73 / real, "5", "7", "5", "1", "5", "2")), new ArrayList<Object>()));
        assertEquals(2, AltiliLog.recheck(store, DAY, page, 1, 0.27, 0, NOW));
        Map<String, Object> r = Json.obj(rec(store, "tjk/2026-10-21/3/1").get("result"));
        assertEquals(Arrays.asList("5", "7", "5", "1", "5", "2"), r.get("winners"));
        assertEquals(Arrays.asList("2", "4", "6", "2", "7", "3"), r.get("previous")); // eski okuma saklanır
        assertEquals(Boolean.TRUE, r.get("rechecked"));
        assertEquals("ikramiye", AltiliLog.trust(rec(store, "tjk/2026-10-21/3/1"), r, 1));
        assertEquals(Arrays.asList("5", "7", "5", "1", "5", "2"), Json.obj(rec(store, "tjk/2026-10-21/4/1").get("result")).get("winners"));
        assertEquals(0, AltiliLog.recheckDays(store, TODAY, 1).size()); // hepsi doğrulandı
        assertEquals(2L, AltiliLog.analyze(store, 1, 2000, TODAY).get("settled"));
        // sayfa yine okunamazsa en çok 2 deneme, eski sonuca dokunulmaz
        Map<String, Object> s2 = new LinkedHashMap<>();
        AltiliLog.record(s2, DAY, Arrays.<Object>asList(altili("tjk/2026-10-21/3/1", "İstanbul", 3, 1, LEGS)));
        rec(s2, "tjk/2026-10-21/3/1").put("result", new LinkedHashMap<String, Object>(wrong));
        assertEquals(0, AltiliLog.recheck(s2, DAY, results(), 1, 0.27, 0, NOW));
        assertEquals(0, AltiliLog.recheck(s2, DAY, results(), 1, 0.27, 0, NOW));
        assertEquals(0, AltiliLog.recheckDays(s2, TODAY, 1).size());
        assertEquals(Arrays.asList("2", "4", "6", "2", "7", "3"), Json.obj(rec(s2, "tjk/2026-10-21/3/1").get("result")).get("winners"));
        // önceki sürümün sildiği (sonucu hiç olmayan) eski altılı da yeniden doğrulanır
        Map<String, Object> s3 = new LinkedHashMap<>();
        Map<String, Object> old = altili("tjk/2026-10-10/3/1", "İstanbul", 3, 1, LEGS);
        old.put("day", "2026-10-10");
        AltiliLog.record(s3, "2026-10-10", Arrays.<Object>asList(old));
        assertEquals(Arrays.asList("2026-10-10"), AltiliLog.recheckDays(s3, TODAY, 1));
        assertEquals(1, AltiliLog.recheck(s3, "2026-10-10", page, 1, 0.27, 0, NOW));
        assertEquals(null, Json.obj(rec(s3, "tjk/2026-10-10/3/1").get("result")).get("previous"));
    }

    @Test
    public void ambiguousRacesGiveNoWinner() {
        // 2.35: ABD'de atlar her koşuda 1, 2, 3…: koşu numarası yoksa atlarla ayırt edilemez → yanlış kazanan yerine boş
        List<Object> races = Arrays.<Object>asList(race("3", "1", "2", "3", "4", "5", "6", "7", "8"), race("6", "1", "2", "3", "4", "5", "6", "7", "8"),
                race("2", "1", "2", "3", "4", "5", "6", "7"));
        String leg8 = "1:30 2:20 3:15 4:10 5:10 6:5 7:5 8:5";
        Map<String, Object> a = altili("tjk/x/40/1", "Belmont Park ABD", 40, 1, Arrays.asList(leg8, leg8, leg8, leg8, leg8, leg8));
        assertEquals(Arrays.<Object>asList(null, null, null, null, null, null), AltiliLog.legWinners(a, city(40, "Belmont Park ABD", new ArrayList<Object>(), races)));
        // kazanan kombinasyon varsa o kesin
        Map<String, Object> withCombo = city(40, "Belmont Park ABD", Arrays.<Object>asList(combo(900, "3", "1", "4", "1", "5", "2")), races);
        assertEquals(Arrays.<Object>asList("3", "1", "4", "1", "5", "2"), AltiliLog.legWinners(a, withCombo));
        // 2.37: ardışık numaralar (1-2-3-4-5-6) koşu numarasıdır, kazanan değil
        Map<String, Object> raceNos = city(40, "Belmont Park ABD", Arrays.<Object>asList(combo(900, "1", "2", "3", "4", "5", "6")), races);
        assertEquals(Arrays.<Object>asList(null, null, null, null, null, null), AltiliLog.legWinners(a, raceNos));
        // başlıksız tabloda at no olabilecek iki sütun (at no ve start): hangisi bilinemez → koşu okunmaz
        String two = "<h3>1. Koşu</h3><table><tr><td>Sıra</td><td>At</td><td>St</td></tr><tr><td>1</td><td>5</td><td>3</td></tr>"
                + "<tr><td>2</td><td>2</td><td>7</td></tr><tr><td>3</td><td>8</td><td>1</td></tr></table>";
        assertEquals(0, Tjk.raceResults(two).size());
    }

    @Test
    public void suspiciousResultsAreFlaggedAndOldRaceMatchesReset() {
        // 2.38–2.40: favori neredeyse rastgele kazanıyor. İkramiyesiz (koşu tablosundan) sonuçlar sayılmaz; gerçek dışıysa uyarılır
        Map<String, Object> weak = history(0.01, 12, 7), byNo = history(0.01, 12, 7);
        for (Object o : AltiliLog.records(byNo)) {
            Map<String, Object> res = Json.obj(Json.obj(o).get("result"));
            res.put("from", "koşu no");
            res.put("estimated", true);
        }
        Map<String, Object> bad = AltiliLog.analyze(byNo, 1, 2000, TODAY);
        assertEquals(Boolean.TRUE, Json.obj(bad.get("fav")).get("absurd"));
        assertEquals(0L, bad.get("settled")); // sayılmadı
        assertEquals(24L, bad.get("unverified"));
        assertEquals(0L, Json.obj(bad.get("calib")).get("n")); // öğrenmeye girmez
        assertEquals(Boolean.FALSE, Json.obj(bad.get("calib")).get("use"));
        // ikramiyesi AGF ile tutarlı (doğrulanmış) sonuçlarda favori zayıfsa bu gerçektir: doğrulandı, öğrenilir
        Map<String, Object> real = AltiliLog.analyze(weak, 1, 2000, TODAY);
        Map<String, Object> fv = Json.obj(real.get("fav"));
        assertEquals(null, fv.get("absurd"));
        assertEquals(Boolean.TRUE, fv.get("confirmed"));
        assertEquals(144L, Json.obj(real.get("calib")).get("n"));
        assertEquals(144L, Json.obj(Json.obj(real.get("calibRegion")).get("yabancı")).get("n")); // "Şehir0" yabancı
        assertEquals(0L, Json.obj(Json.obj(real.get("calibRegion")).get("yerli")).get("n"));
        List<Object> gs = Json.arr(fv.get("groups"));
        assertEquals("İkramiyeyle doğrulanan", Json.obj(gs.get(0)).get("label"));
        assertEquals("Yabancı hipodrom", Json.obj(gs.get(1)).get("label"));
        assertEquals(null, Json.obj(AltiliLog.analyze(history(1.0, 40, 7), 1, 2000, TODAY).get("fav")).get("suspect"));
        // yerli: TJK şehir numarası 1–10 ya da yerli şehir adı
        assertTrue(AltiliLog.domestic(altili("tjk/x/3/1", "İstanbul", 3, 1, LEGS)));
        assertFalse(AltiliLog.domestic(altili("tjk/x/31/1", "Belmont Park ABD", 31, 1, LEGS)));
        Map<String, Object> bil = altili("bilyoner/İstanbul 3–8. koşu", "İstanbul", 99, 1, LEGS);
        bil.put("source", "Bilyoner");
        assertTrue(AltiliLog.domestic(bil));
        // atlarla eşleşmiş eski sonuçlar silinmez, "atlar" diye etiketlenir; kazanan kombinasyondan gelenler aynen
        Map<String, Object> store = new LinkedHashMap<>();
        AltiliLog.record(store, DAY, Arrays.<Object>asList(altili("k1", "A", 1, 1, LEGS), altili("k2", "B", 2, 1, LEGS)));
        AltiliLog.record(store, DAY, Arrays.<Object>asList(altili("k3", "C", 3, 1, LEGS)));
        Map<String, Object> r1 = new LinkedHashMap<>(), r2 = new LinkedHashMap<>(), r3 = new LinkedHashMap<>();
        r1.put("from", "koşular");
        r2.put("from", "kombinasyon");
        r2.put("winners", new ArrayList<Object>(Arrays.asList("5", "7", "5", "1", "5", "2")));
        r3.put("from", "kombinasyon");
        r3.put("winners", new ArrayList<Object>(Arrays.asList("3", "4", "5", "6", "7", "8"))); // koşu numaraları okunmuştu
        rec(store, "k1").put("result", r1);
        rec(store, "k2").put("result", r2);
        rec(store, "k3").put("result", r3);
        assertTrue(AltiliLog.migrate(store));
        assertEquals("atlar", Json.obj(rec(store, "k1").get("result")).get("from"));
        assertEquals(r2, rec(store, "k2").get("result"));
        assertEquals(null, rec(store, "k3").get("result")); // yeniden sonuçlanır
        assertFalse(AltiliLog.migrate(store)); // bir kez
    }
}
