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
        Map<String, Object> res = results(city(3, "İstanbul", Arrays.<Object>asList(combo(1234.5, "5", "7", "5", "1", "5", "2")), new ArrayList<Object>()));
        assertEquals(1, AltiliLog.settle(store, DAY, res, 1, 0.27, 0, TODAY, NOW));
        Map<String, Object> r = Json.obj(rec(store, "tjk/2026-10-21/3/1").get("result"));
        assertEquals(Arrays.asList("5", "7", "5", "1", "5", "2"), r.get("winners"));
        assertEquals(1234.5, Json.dbl(r, "payout", 0), 1e-9);
        assertEquals(Boolean.FALSE, r.get("estimated"));
        assertEquals("kombinasyon", r.get("from"));
        assertEquals(0, AltiliLog.pendingDays(store, TODAY).size());
        Map<String, Object> a = AltiliLog.analyze(store, 1, 2000, TODAY);
        Map<String, Object> s = Json.obj(a.get("suggested"));
        assertEquals(1L, s.get("n"));
        assertEquals(1L, s.get("hits"));
        assertEquals(1234.5, Json.dbl(s, "ret", 0), 1e-9);
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
        assertEquals("koşular", r.get("from"));
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
                c.put("payout", 500.0);
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
        Map<String, Object> few = Json.obj(AltiliLog.analyze(history(1.6, 5, 7), 1, 2000, TODAY).get("calib"));
        assertEquals(Boolean.FALSE, few.get("use")); // 60 ayak: veri az
        assertTrue((String) few.get("why"), ((String) few.get("why")).startsWith("Veri az"));
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
                + "<p>Bahis sonuçları: İKİLİ 5/3 12,50 TL · 1. 6'LI GANYAN 5/7/5/1/5/2 12.345,67 TL · 2. ALTILI GANYAN : 1-2-3-4-5-6 (6 BİLEN) 98,10 TL</p></body></html>";
        List<Map<String, Object>> combos = Tjk.combos(html);
        assertEquals(2, combos.size());
        assertEquals(Arrays.asList("5", "7", "5", "1", "5", "2"), combos.get(0).get("nos"));
        assertEquals(12345.67, (Double) combos.get(0).get("payout"), 1e-9);
        assertEquals(Arrays.asList("1", "2", "3", "4", "5", "6"), combos.get(1).get("nos"));
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
        assertTrue(String.valueOf(r.get("log")).contains("Bursa: 2 altılı sonucu, 2 koşu"));
    }
}
