package app.bilincli.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 2.2: Bilyoner Zirve Oran okuyucusu (2.1.6 keşfindeki gerçek yanıt biçimi). */
public class ZirveTest {
    static final Instant NOW = CoreTest.NOW;
    static final Instant KO = NOW.plusSeconds(6 * 3600);

    static String odd(String event, String mrId, int ocn, String n, String val, String tval, String mrn, String sov) {
        return "{\"id\":\"" + event + ":" + mrId + ":" + ocn + "\",\"mbs\":1,\"n\":\"" + n + "\",\"val\":\"" + val + "\""
                + (tval == null ? "" : ",\"tval\":\"" + tval + "\"") + ",\"cht\":3,\"pr\":15.38,\"prl\":\"\",\"imo\":true,\"bt\":1,\"ei\":" + event
                + ",\"ocn\":" + ocn + ",\"mrId\":" + mrId + ",\"mrv\":952727928,\"mrt\":1,\"mrst\":1,\"mrs\":1,\"mrn\":\"" + mrn + "\""
                + (sov == null ? "" : ",\"sov\":\"" + sov + "\"") + ",\"hsc\":false}";
    }

    static String event(String id, String home, String away, Instant ko, String... odds) {
        return "\"" + id + "\":{\"id\":" + id + ",\"competitionId\":1391,\"otherOdd\":{},\"marketGroups\":[{\"odds\":[" + String.join(",", odds)
                + "],\"id\":1,\"name\":\"Popüler\"}],\"slgn\":\"ULUSLAR\",\"esd\":\"2026-10-02T21:45:00\",\"esdl\":" + ko.toEpochMilli()
                + ",\"lgn\":\"UEFA Uluslar Ligi\",\"mbs\":1,\"strt\":\"21:45\",\"htn\":\"" + home + "\",\"atn\":\"" + away + "\",\"en\":\""
                + home + " - " + away + "\",\"htw\":true}";
    }

    static String body() {
        return "{\"events\":{" + event("3172993", "Belçika", "Türkiye", KO,
                odd("3172993", "83175177", 1, "MS 1", "1.37", "1.40", "Maç Sonucu", null),
                odd("3172993", "83175177", 2, "MS X", "4.60", null, "Maç Sonucu", null),
                odd("3172993", "83175177", 3, "MS 2", "6.50", "6.80", "Maç Sonucu", null),
                odd("3172993", "83175180", 1, "KG Var", "1.80", "1.90", "Karşılıklı Gol", null),
                odd("3172993", "83175181", 2, "2,5 Üst", "2.00", "2.10", "Toplam 2,5 Gol Alt/Üst", "2.5"),
                odd("3172993", "83175182", 1, "ÇŞ 1-X", "1.05", "1.08", "Çifte Şans", null),
                odd("3172993", "83175183", 1, "İY 1", "1.90", "1.95", "İlk Yarı Sonucu", null),
                odd("3172993", "83175184", 1, "İY 0,5 Üst", "1.30", "1.35", "İlk Yarı 0,5 Gol Alt/Üst", "0.5"))
                + "," + event("3176349", "Hırvatistan", "İngiltere", KO.plusSeconds(86400),
                odd("3176349", "83206618", 1, "MS 1", "3.48", "3.65", "Maç Sonucu", null))
                + "," + event("3100000", "Başlamış", "Maç", NOW.minusSeconds(600),
                odd("3100000", "1", 1, "MS 1", "2.00", "2.10", "Maç Sonucu", null))
                + "},\"onComingEvents\":[],\"marketGroups\":[],\"isAntepost\":false,\"sportType\":1}";
    }

    static Map<String, Object> sel(String m, String o, double p, double i) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("m", m);
        s.put("o", o);
        s.put("label", Models.outcomeLabel(m, o));
        s.put("p", p);
        s.put("i", i);
        return s;
    }

    static Map<String, Object> row(String ref, String sport, String home, String away, Instant ko, Map<String, Object>... sel) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ref", ref);
        r.put("sref", "s" + ref);
        r.put("sport", sport);
        r.put("code", "123");
        r.put("home", home);
        r.put("away", away);
        r.put("kickoff", ko.toString());
        r.put("league", "UEFA Nations League");
        r.put("sel", new ArrayList<Object>(Arrays.asList((Object[]) sel)));
        return r;
    }

    @SuppressWarnings("unchecked")
    static List<Object> fairs() {
        // Nesine adları (büyük harf/ek farkı olabilir); saat 5 dk farklı
        return new ArrayList<Object>(Arrays.asList(row("b1", "soccer_uefa_nations_league", "BELÇİKA", "Türkiye", KO.plusSeconds(300),
                sel("MS", "1", 0.75, 1.37), sel("MS", "X", 0.17, 4.60), sel("MS", "2", 0.08, 6.50), sel("KG", "VAR", 0.50, 1.80),
                sel("AU25", "UST", 0.45, 2.30))));
    }

    static Map<String, Object> rowFor(Map<String, Object> view, String name) {
        for (Object x : Json.arr(view.get("rows"))) {
            Map<String, Object> r = Json.obj(x);
            if (name.equals(r.get("name")) && "Belçika".equals(r.get("home"))) return r;
        }
        return null;
    }

    @Test
    public void parsesBoostedOddsFromZirveTab() {
        List<Zirve.Offer> offers = Zirve.parse(body());
        assertEquals(9, offers.size()); // MS X artırılmamış
        Zirve.Offer ms = offers.get(0);
        assertEquals("3172993:83175177:1", ms.id);
        assertEquals("Belçika", ms.home);
        assertEquals("Türkiye", ms.away);
        assertEquals("UEFA Uluslar Ligi", ms.league);
        assertEquals(KO, ms.kickoff);
        assertEquals(1.37, ms.val, 0);
        assertEquals(1.40, ms.tval, 0);
        assertTrue(ms.threeWay);
        assertArrayEquals(new String[] {"MS", "1"}, Zirve.map(ms, false));
        assertNull(Zirve.map(ms, true)); // basketbolda üç seçenekli maç sonucu iki seçenekliyle karıştırılmaz
        assertArrayEquals(new String[] {"KG", "VAR"}, Zirve.map(offers.get(2), false));
        assertArrayEquals(new String[] {"AU25", "UST"}, Zirve.map(offers.get(3), false));
        assertArrayEquals(new String[] {"CS", "1X"}, Zirve.map(offers.get(4), false));
        assertNull(Zirve.map(offers.get(5), false)); // ilk yarı
        assertNull(Zirve.map(offers.get(6), false)); // ilk yarı alt/üst
        // esdl yoksa esd Türkiye saati
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("esd", "2026-10-02T21:45:00");
        assertEquals(Instant.parse("2026-10-02T18:45:00Z"), Zirve.kickoff(e));
    }

    @Test
    public void evaluatesAgainstFairOddsWithSafetyChecks() {
        Settings cfg = new Settings();
        Map<String, Object> v = Zirve.evaluate(Zirve.parse(body()), fairs(), NOW.toString(), cfg, 500000, NOW);
        assertEquals((long) Zirve.VIEW_VERSION, v.get("v")); // eski sürümün kaydı tanınır, hemen yenilenir
        assertEquals(2L, v.get("events")); // başlamış maç alınmaz
        assertEquals(8L, v.get("offers"));
        assertEquals(1L, v.get("matched"));
        Map<String, Object> ms = rowFor(v, "MS 1");
        assertEquals("oyna", ms.get("status")); // 0,75 x 1,40 - 1 = +%5
        assertEquals(0.05, Json.dbl(ms, "ev", 0), 1e-9);
        assertEquals(0.75 * 1.37 - 1, Json.dbl(ms, "evNormal", 0), 1e-9);
        assertEquals("b1", ms.get("ref"));
        assertEquals("MS 1", ms.get("label"));
        assertEquals(15000L, Json.lng(ms, "stake", 0));
        assertEquals("oynama", rowFor(v, "MS 2").get("status"));
        assertEquals("oynama", rowFor(v, "KG Var").get("status"));
        assertEquals("degisti", rowFor(v, "2,5 Üst").get("status")); // Bilyoner 2,00, taramada 2,30
        assertEquals("secim", rowFor(v, "ÇŞ 1-X").get("status")); // ÇŞ adil oranı tabloda yok
        assertEquals("pazar", rowFor(v, "İY 1").get("status"));
        Map<String, Object> other = null;
        for (Object x : Json.arr(v.get("rows"))) if ("Hırvatistan".equals(Json.obj(x).get("home"))) other = Json.obj(x);
        assertEquals("mac", other.get("status"));
        assertEquals(1L, v.get("play"));
        assertEquals(3L, v.get("evaluated"));
        // farklı saatteki aynı adlı maç eşleşmez
        List<Object> late = new ArrayList<>();
        Map<String, Object> r = Json.obj(fairs().get(0));
        r.put("kickoff", KO.plusSeconds(3600).toString());
        late.add(r);
        assertEquals(0L, Zirve.evaluate(Zirve.parse(body()), late, null, cfg, 500000, NOW).get("matched"));
        // kanıt koruması devredeyse olasılık küçültülür, oynanmaz
        cfg.edgeRatios = new LinkedHashMap<>();
        cfg.edgeRatios.put("*", 0.2);
        assertEquals("oynama", rowFor(Zirve.evaluate(Zirve.parse(body()), fairs(), null, cfg, 500000, NOW), "MS 1").get("status"));
    }

    @Test
    public void basketballMapsToTwoWayAndIddaaLine() {
        String body = "{\"events\":[{\"id\":9,\"esdl\":" + KO.toEpochMilli() + ",\"htn\":\"Fenerbahçe\",\"atn\":\"BC Dubai\",\"marketGroups\":[{\"odds\":["
                + odd("9", "1", 1, "MS 1", "1.30", "1.36", "Maç Sonucu", null) + ","
                + odd("9", "2", 2, "Üst", "1.85", "1.95", "Alt/Üst", "161.5") + ","
                + odd("9", "3", 1, "1. Yarı Üst", "1.85", "1.95", "1. Yarı Alt/Üst", "80.5") + "]}]}]}";
        List<Object> fairs = new ArrayList<>();
        fairs.add(row("b9", "basketball_euroleague", "Fenerbahçe", "BC Dubai", KO, sel("BS", "1", 0.78, 1.30), sel("BT@161.5", "UST", 0.50, 1.85)));
        Map<String, Object> v = Zirve.evaluate(Zirve.parse(body), fairs, null, new Settings(), 500000, NOW);
        List<Object> rows = Json.arr(v.get("rows"));
        assertEquals(3, rows.size());
        Map<String, Object> bs = null, bt = null, half = null;
        for (Object x : rows) {
            Map<String, Object> r = Json.obj(x);
            if ("MS 1".equals(r.get("name"))) bs = r;
            else if ("Üst".equals(r.get("name"))) bt = r;
            else half = r;
        }
        assertEquals("BS", bs.get("m"));
        assertEquals("oyna", bs.get("status")); // 0,78 x 1,36 - 1 = +%6,1
        assertEquals("BT@161.5", bt.get("m"));
        assertEquals("Basket 161,5 Üst", bt.get("label"));
        assertEquals("oynama", bt.get("status")); // 0,50 x 1,95 - 1 = −%2,5
        assertEquals("pazar", half.get("status"));
    }

    @Test
    public void noticeOnlyOncePerBoostedOdd() {
        Map<String, Object> v = Zirve.evaluate(Zirve.parse(body()), fairs(), null, new Settings(), 500000, NOW);
        List<Object> notified = new ArrayList<>();
        String[] n = Zirve.notice(v, notified);
        assertNotNull(n);
        assertEquals("Zirve Oran fırsatı · Belçika – Türkiye", n[0]);
        assertTrue(n[1], n[1].startsWith("Belçika – Türkiye · MS 1 · Zirve 1,40 (normal 1,37) · adil 1,33 · avantaj +%5,0 · önerilen 150,00 TL\n"));
        assertTrue(n[1], n[1].contains("kampanya şartlarını"));
        assertNull(Zirve.notice(v, notified)); // aynı oran tekrar bildirilmez
        // Zirve oranı yükselirse yeniden
        Map<String, Object> ms = rowFor(v, "MS 1");
        ms.put("tval", 1.45);
        assertNotNull(Zirve.notice(v, notified));
        assertNull(Zirve.notice(null, notified));
        // test çıktısı: önce oynanabilir
        String s = Zirve.summary(v);
        assertTrue(s, s.startsWith("  2 maçta 8 artırılmış oran · son taramada eşleşen maç 1 · değerlendirilen 3 · değerli 1\n"
                + "  Belçika – Türkiye · MS 1 · Zirve 1,45"));
        assertTrue(s, s.contains("→ OYNA") && s.contains("→ değer yok") && s.contains("taramadan beri değişmiş"));
    }

    @Test
    public void unmatchedEventExplainsWhatTheScanHadAtThatTime() {
        List<Object> fairs = new ArrayList<>();
        fairs.add(row("b5", "soccer_uefa_nations_league", "Fransa", "İtalya", KO, sel("MS", "1", 0.7, 1.31)));
        Map<String, Object> v = Zirve.evaluate(Zirve.parse(body()), fairs, null, new Settings(), 500000, NOW);
        Map<String, Object> ms = rowFor(v, "MS 1");
        assertEquals("mac", ms.get("status"));
        assertEquals("son taramada bu saatte Fransa – İtalya var; bu maçla eşleşmedi", ms.get("why"));
        Map<String, Object> other = null;
        for (Object x : Json.arr(v.get("rows"))) if ("Hırvatistan".equals(Json.obj(x).get("home"))) other = Json.obj(x);
        assertEquals("son taramada bu saatte maç yok", other.get("why"));
        assertTrue(Zirve.summary(v), Zirve.summary(v).contains("→ adil oran yok (son taramada bu saatte Fransa – İtalya var"));
        // eşleşen maçta lig kodu ve adil oranın alındığı an
        Map<String, Object> r = Json.obj(fairs().get(0));
        r.put("at", NOW.minusSeconds(600).toString());
        List<Object> one = new ArrayList<>();
        one.add(r);
        Map<String, Object> matched = rowFor(Zirve.evaluate(Zirve.parse(body()), one, null, new Settings(), 500000, NOW), "MS 1");
        assertEquals("soccer_uefa_nations_league", matched.get("sport"));
        assertEquals(NOW.minusSeconds(600).toString(), matched.get("fairAt"));
    }

    @Test
    public void leagueOfZirveMatchIsFoundForTargetedScan() {
        List<String[]> candidates = new ArrayList<>(Arrays.asList(Settings.KNOWN_LEAGUES));
        candidates.add(new String[] {"soccer_fifa_world_cup_qualifiers_europe", "FIFA World Cup Qualifiers - Europe"});
        assertEquals("soccer_uefa_nations_league", Zirve.leagueKey("UEFA Uluslar Ligi", null, candidates));
        assertEquals("soccer_epl", Zirve.leagueKey("İngiltere Premier Lig", null, candidates));
        assertEquals("soccer_uefa_champs_league", Zirve.leagueKey("UEFA Şampiyonlar Ligi", null, candidates));
        assertEquals("basketball_euroleague", Zirve.leagueKey("EuroLeague", null, candidates));
        assertEquals("soccer_spain_segunda_division", Zirve.leagueKey("İspanya La Liga 2", null, candidates));
        assertNull(Zirve.leagueKey("Ukrayna Premier Lig", null, candidates)); // benzer ama başka lig
        assertNull(Zirve.leagueKey("", null, candidates));
        // öğrenilmiş eşleme önce gelir
        Map<String, String> learned = new LinkedHashMap<>();
        learned.put("Dünya Kupası Elemeleri", "soccer_fifa_world_cup_qualifiers_europe");
        assertEquals("soccer_fifa_world_cup_qualifiers_europe", Zirve.leagueKey("Dünya Kupası Elemeleri", learned, candidates));
        // aynı maç: saat ve adlar
        assertTrue(Zirve.sameEvent("Belçika", "Türkiye", KO, "BELÇİKA", "Türkiye", KO.plusSeconds(600)) > 0.9);
        assertEquals(0, Zirve.sameEvent("Belçika", "Türkiye", KO, "Belçika", "Türkiye", KO.plusSeconds(3600)), 0);
        assertEquals(0, Zirve.sameEvent("Belçika", "Türkiye", KO, "Fransa", "İtalya", KO), 0);
    }

    @Test
    public void partialScanMergesIntoFairTable() {
        Radar radar = new Radar(new Ledger.MemoryStorage(null));
        radar.update(Arrays.asList(FeatureTest.book(1, 1.80, 1, 8), FeatureTest.book(2, 1.95, 1, 9)),
                Arrays.asList(FeatureTest.sharp(1, 0.50, 8), FeatureTest.sharp(2, 0.50, 9)), NOW, new Settings(), true);
        assertEquals(2, Json.arr(radar.view().get("fairs")).size());
        // hedefli tarama: maç 2 tazelenir, maç 3 eklenir, maç 1 kalır
        Instant later = NOW.plusSeconds(3600);
        radar.mergeFairs(Matching.match(Arrays.asList(FeatureTest.book(2, 2.05, 1, 9), FeatureTest.book(3, 1.70, 1, 30)),
                Arrays.asList(FeatureTest.sharp(2, 0.55, 9), FeatureTest.sharp(3, 0.60, 30))), later);
        List<Object> fairs = Json.arr(radar.view().get("fairs"));
        assertEquals(3, fairs.size());
        assertEquals("b1", Json.obj(fairs.get(0)).get("ref")); // başlama saatine göre sıralı
        assertEquals(NOW.toString(), Json.obj(fairs.get(0)).get("at"));
        Map<String, Object>[] two = Promo.find(fairs, "b2", "MS", "1");
        assertEquals(0.55, Json.dbl(two[1], "p", 0), 1e-12);
        assertEquals(2.05, Json.dbl(two[1], "i", 0), 1e-12);
        assertEquals(later.toString(), two[0].get("at"));
        assertNotNull(Promo.find(fairs, "b3", "MS", "1"));
        assertEquals(NOW.toString(), radar.view().get("fairsAt")); // tam tarama zamanı değişmez
        // başlamış maç atılır; kalıcı
        Ledger.MemoryStorage store = new Ledger.MemoryStorage(null);
        Radar r2 = new Radar(store);
        r2.update(Collections.singletonList(FeatureTest.book(1, 1.80, 1, 8)), Collections.singletonList(FeatureTest.sharp(1, 0.50, 8)), NOW, new Settings(), true);
        r2.mergeFairs(Matching.match(Collections.singletonList(FeatureTest.book(3, 1.70, 1, 30)),
                Collections.singletonList(FeatureTest.sharp(3, 0.60, 30))), NOW.plusSeconds(9 * 3600));
        List<Object> f2 = Json.arr(new Radar(store).view().get("fairs"));
        assertEquals(1, f2.size());
        assertEquals("b3", Json.obj(f2.get(0)).get("ref"));
        // lineup gibi kısmi radar taraması da tabloyu tazeler
        radar.update(Collections.singletonList(FeatureTest.book(1, 1.85, 1, 8)), Collections.singletonList(FeatureTest.sharp(1, 0.52, 8)),
                later, new Settings(), false);
        assertEquals(0.52, Json.dbl(Promo.find(Json.arr(radar.view().get("fairs")), "b1", "MS", "1")[1], "p", 0), 1e-12);
    }

    @Test
    public void targetedScanUsesBilyonerOddsAndGuardsMapping() {
        List<Zirve.Offer> all = Zirve.parseAll(body());
        java.util.Set<String> target = new java.util.HashSet<>(Arrays.asList("3172993"));
        List<Models.BookEvent> book = Zirve.books(all, target, new java.util.HashSet<String>());
        assertEquals(1, book.size());
        Models.BookEvent b = book.get(0);
        assertEquals("z3172993", b.ref);
        assertEquals(KO, b.kickoff);
        assertEquals(Models.FOOTBALL, b.sport);
        assertEquals(4.60, b.odds.get("MS").get("X"), 0); // artırılmamış oran da
        assertEquals(1.80, b.odds.get("KG").get("VAR"), 0);
        assertEquals(2.00, b.odds.get("AU25").get("UST"), 0);
        assertEquals(1.05, b.odds.get("CS").get("1X"), 0);
        assertEquals(4, b.odds.size()); // ilk yarı pazarları alınmaz
        // Pinnacle'daki İngilizce adlarla eşleşir (iddaa adlarıyla aynı eşleştirici)
        Map<String, Map<String, Double>> fair = new LinkedHashMap<>(CoreTest.ms(0.70, 0.19, 0.11));
        Map<String, Double> kg = new LinkedHashMap<>();
        kg.put("VAR", 0.5);
        kg.put("YOK", 0.5);
        fair.put("KG", kg);
        Models.SharpEvent sharp = new Models.SharpEvent("s1", "soccer_uefa_nations_league", "Belgium", "Turkey", KO, fair, "pinnacle");
        List<Models.Pair> pairs = Matching.match(book, Arrays.asList(sharp));
        assertEquals(1, pairs.size());
        // MS tutarlı kalır; KG tek seçenekli (karşılaştırılamaz) atılır
        assertEquals(1, Zirve.verify(pairs));
        assertTrue(b.odds.containsKey("MS"));
        assertFalse(b.odds.containsKey("KG"));
        // tablo ve değerlendirme: iddaa oranı Bilyoner'in normal oranı
        List<Object> table = Promo.table(pairs, NOW);
        Map<String, Object> v = Zirve.evaluate(Zirve.parse(body()), table, null, new Settings(), 500000, NOW);
        Map<String, Object> ms = rowFor(v, "MS 1");
        assertEquals("z3172993", ms.get("ref"));
        assertEquals(0.70 * 1.40 - 1, Json.dbl(ms, "ev", 0), 1e-9);
        assertEquals("oynama", ms.get("status"));
        // ters okunmuş Alt/Üst eşleme korumasına takılır
        Map<String, Map<String, Double>> odds = new LinkedHashMap<>();
        Map<String, Double> au = new LinkedHashMap<>();
        au.put("ALT", 2.46);
        au.put("UST", 1.30);
        odds.put("AU25", au);
        Map<String, Map<String, Double>> f2 = new LinkedHashMap<>();
        Map<String, Double> auf = new LinkedHashMap<>();
        auf.put("ALT", 0.62);
        auf.put("UST", 0.38);
        f2.put("AU25", auf);
        Models.Pair swapped = new Models.Pair(new Models.BookEvent("z1", "A", "B", KO, "L", 1, odds, null),
                new Models.SharpEvent("s2", "soccer_x", "A", "B", KO, f2, "pinnacle"), 1);
        assertEquals(1, Zirve.verify(Arrays.asList(swapped)));
        assertTrue(swapped.book.odds.isEmpty());
        // basketbol maçı basketbol olarak kurulur
        List<Models.BookEvent> bb = Zirve.books(all, target, target);
        assertEquals(Models.BASKETBALL, bb.get(0).sport);
        assertFalse(bb.get(0).odds.containsKey("MS"));
    }

    @Test
    public void fetchReportsHttpErrors() throws Exception {
        Http ok = new Http() {
            public Response get(String url, Map<String, String> headers) {
                assertEquals(Zirve.URL, url);
                assertTrue(headers.get("User-Agent").contains("Mozilla"));
                return new Response(200, body(), new LinkedHashMap<String, String>());
            }
        };
        List<Zirve.Offer> all = Zirve.fetch(ok);
        assertEquals(10, all.size()); // artırılmamış MS X de (normal oran hedefli taramada iddaa tarafı)
        assertEquals(8L, Zirve.evaluate(all, fairs(), null, new Settings(), 500000, NOW).get("offers")); // değerlendirmede yalnızca artırılmışlar
        Http bad = new Http() {
            public Response get(String url, Map<String, String> headers) {
                return new Response(200, "<html>bakım</html>", new LinkedHashMap<String, String>());
            }
        };
        try {
            Zirve.fetch(bad);
            assertFalse("istisna bekleniyordu", true);
        } catch (Http.ProviderException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Bilyoner Zirve Oran: yanıt okunamadı"));
        }
    }
}
