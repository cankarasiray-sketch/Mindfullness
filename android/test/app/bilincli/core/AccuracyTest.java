package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.ScoreResult;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 1.7: eşleştirme ve veri doğruluğu korumaları, tahmin defteri. */
public class AccuracyTest {
    static final Instant NOW = CoreTest.NOW;
    static final Instant KO = NOW.plusSeconds(6 * 3600);

    static BookEvent book(String ref, String home, String away, double o1, double ox, double o2) {
        return new BookEvent(ref, home, away, KO, "Lig", 1, CoreTest.ms(o1, ox, o2), ref);
    }

    static SharpEvent sharp(String ref, String home, String away, double p1, double px, double p2) {
        return new SharpEvent(ref, "lig", home, away, KO, CoreTest.ms(p1, px, p2), "pinnacle");
    }

    @Test
    public void variantTeamsDoNotMatch() {
        assertEquals("kadin", Matching.variant("Galatasaray (K)"));
        assertEquals("u19", Matching.variant("Fenerbahçe U19"));
        assertEquals("genc", Matching.variant("Barcelona B"));
        assertEquals("", Matching.variant("B. Mönchengladbach"));
        assertEquals("", Matching.variant("İstanbul Başakşehir"));
        List<BookEvent> b = Collections.singletonList(book("b", "Arsenal (K)", "Chelsea (K)", 1.8, 3.6, 4.2));
        List<SharpEvent> s = Collections.singletonList(sharp("s", "Arsenal", "Chelsea", 0.5, 0.27, 0.23));
        assertTrue(Matching.match(b, s).isEmpty());
        assertEquals(1, Matching.match(b, Collections.singletonList(sharp("s", "Arsenal W", "Chelsea W", 0.5, 0.27, 0.23))).size());
    }

    @Test
    public void inconsistentOrSwappedPairDropped() {
        List<BookEvent> book = new ArrayList<>(Arrays.asList(
                book("ok", "Ev A", "Dep A", 1.95, 3.5, 4.0),       // tutarlı (fark küçük)
                book("value", "Ev B", "Dep B", 2.30, 3.3, 3.2),    // gerçek avantaj: fark ~0,07
                book("wrong", "Ev C", "Dep C", 1.40, 4.5, 7.5),    // başka maç: büyük fark
                book("swap", "Ev D", "Dep D", 4.6, 3.6, 1.75)));   // ev/deplasman ters
        List<SharpEvent> sharp = Arrays.asList(
                sharp("s1", "Ev A", "Dep A", 0.48, 0.27, 0.25),
                sharp("s2", "Ev B", "Dep B", 0.48, 0.27, 0.25),
                sharp("s3", "Ev C", "Dep C", 0.25, 0.27, 0.48),
                sharp("s4", "Ev D", "Dep D", 0.52, 0.26, 0.22));
        // gerçek bülten: çoğu maç tutarlı (birkaç yanlış eşleşme tüm Maç Sonucu'nu kapatmamalı)
        List<SharpEvent> all = new ArrayList<>(sharp);
        for (int i = 0; i < 5; i++) {
            book.add(book("t" + i, "Takım " + i, "Rakip " + i, 2.5, 3.3, 2.9));
            all.add(sharp("st" + i, "Takım " + i, "Rakip " + i, 0.375, 0.285, 0.34));
        }
        Map<String, Object> r = Calibration.apply(book, all);
        assertTrue(String.valueOf(r.get("MS")), String.valueOf(r.get("MS")).startsWith("doğrulandı"));
        assertEquals(2L, r.get("mismatched"));
        assertFalse(book.get(0).odds.isEmpty());
        assertFalse(book.get(1).odds.isEmpty());
        assertTrue(book.get(2).odds.isEmpty());
        assertTrue(book.get(3).odds.isEmpty());
        assertTrue(Calibration.summary(r).contains("2 uyumsuz eşleşme"));
        assertTrue(String.valueOf(r.get("notes")).contains("ev/deplasman ters"));
    }

    @Test
    public void implausibleEdgeWithinConsistentPairStillFiltered() {
        // maç tutarlı (fark < 0,15) ama 1 seçeneğinde %32 "avantaj": yalnızca o seçenek ayıklanır
        List<BookEvent> book = new ArrayList<>(Collections.singletonList(book("b", "Ev", "Dep", 2.2, 3.8, 5.5)));
        Map<String, Object> r = Calibration.apply(book, Collections.singletonList(sharp("s", "Ev", "Dep", 0.60, 0.24, 0.16)));
        assertEquals(0L, r.get("mismatched"));
        assertEquals(1L, r.get("suspicious"));
        assertFalse(book.get(0).odds.get("MS").containsKey("1"));
        assertTrue(book.get(0).odds.get("MS").containsKey("X"));
    }

    static String bk(String key, String update, double h, double d, double a) {
        return "{\"key\":\"" + key + "\",\"last_update\":\"" + update + "\",\"markets\":[{\"key\":\"h2h\",\"outcomes\":["
                + "{\"name\":\"A\",\"price\":" + h + "},{\"name\":\"Draw\",\"price\":" + d + "},{\"name\":\"B\",\"price\":" + a + "}]}]}";
    }

    static List<SharpEvent> parse(String commence, String... books) throws Exception {
        Settings cfg = new Settings();
        cfg.oddsApiKey = "k";
        return new OddsApi(null, cfg).parseOdds(Json.parse("[{\"id\":\"e1\",\"commence_time\":\"" + commence + "\","
                + "\"home_team\":\"A\",\"away_team\":\"B\",\"bookmakers\":[" + String.join(",", books) + "]}]"), "x");
    }

    @Test
    public void stalePinnacleLineIgnoredNearKickoff() throws Exception {
        String others = bk("a", "2026-10-03T15:30:00Z", 2.0, 3.5, 3.8) + "," + bk("b", "2026-10-03T15:31:00Z", 2.1, 3.5, 3.8)
                + "," + bk("c", "2026-10-03T15:29:00Z", 2.2, 3.5, 3.8);
        // maç 17:00; Pinnacle 11:00'den beri güncellenmemiş, diğerleri 15:30
        SharpEvent stale = parse("2026-10-03T17:00:00Z", bk("pinnacle", "2026-10-03T11:00:00Z", 1.6, 4.0, 6.0), others).get(0);
        assertTrue(stale.source, stale.source.contains("pinnacle bayat") && stale.source.contains("ortalama(3)"));
        // güncel Pinnacle kullanılır
        SharpEvent fresh = parse("2026-10-03T17:00:00Z", bk("pinnacle", "2026-10-03T15:20:00Z", 1.6, 4.0, 6.0), others).get(0);
        assertTrue(fresh.source, fresh.source.contains("MS:pinnacle") && !fresh.source.contains("bayat"));
        // maça çok varken (ertesi gün) yavaş güncelleme normaldir
        SharpEvent early = parse("2026-10-04T19:00:00Z", bk("pinnacle", "2026-10-03T11:00:00Z", 1.6, 4.0, 6.0), others).get(0);
        assertFalse(early.source.contains("bayat"));
        // tek başına Pinnacle (karşılaştıracak site yok): kullanılır
        assertFalse(parse("2026-10-03T17:00:00Z", bk("pinnacle", "2026-10-03T11:00:00Z", 1.6, 4.0, 6.0)).get(0).source.contains("bayat"));
    }

    @Test
    public void diagnosticReportExplainsEmptyResults() throws Exception {
        final List<String> calls = new ArrayList<>();
        Http http = new Http() {
            public Response get(String url, Map<String, String> headers) {
                calls.add(url);
                Map<String, String> h = new LinkedHashMap<>();
                h.put("x-requests-remaining", "499");
                if (url.contains("/events?") && url.contains("commenceTimeFrom")) {
                    // Süper Lig'in 24 saatte maçı yok; Premier Lig'in var
                    return new Response(200, url.contains("soccer_epl") ? "[{\"id\":\"e\"}]" : "[]", h);
                }
                if (url.contains("/events?")) {
                    return new Response(200, "[{\"id\":\"n\",\"commence_time\":\"2026-10-03T17:00:00Z\"}]", h);
                }
                h.put("x-requests-last", "1");
                return new Response(200, "[{\"id\":\"e\",\"commence_time\":\"2026-10-01T19:00:00Z\",\"home_team\":\"A\","
                        + "\"away_team\":\"B\",\"bookmakers\":[]}]", h);
            }
        };
        Settings cfg = new Settings();
        cfg.oddsApiKey = "k";
        OddsApi api = new OddsApi(http, cfg);
        api.diagnose = true;
        assertTrue(api.fetchEvents(Arrays.asList("soccer_turkey_super_league", "soccer_epl"), NOW).isEmpty());
        assertEquals(Collections.singletonList("soccer_turkey_super_league"), api.idle);
        assertEquals("Türkiye Süper Lig: 24 saatte maç yok, sıradaki 03.10 20:00", api.report.get(0));
        assertEquals("İngiltere Premier Lig: 24 saatte 1 maç; oran yanıtı 1 maç, kullanılabilir 0, bahis sitesi olmayan 1",
                api.report.get(1));
        assertEquals(1, api.spent);
        // tanı kapalıyken sıradaki maç sorulmaz
        calls.clear();
        api.diagnose = false;
        api.fetchEvents(Collections.singletonList("soccer_turkey_super_league"), NOW);
        assertEquals(1, calls.size());
    }

    @Test
    public void nationalTeamsMatchAcrossLanguages() {
        List<BookEvent> b = Arrays.asList(book("b1", "Türkiye", "İspanya", 3.1, 3.3, 2.3),
                book("b2", "Bosna Hersek", "Galler", 2.6, 3.1, 2.9), book("b3", "Türkiye U21", "İspanya U21", 2.5, 3.2, 2.8));
        List<SharpEvent> s = Arrays.asList(sharp("s1", "Turkey", "Spain", 0.30, 0.29, 0.41),
                sharp("s2", "Bosnia & Herzegovina", "Wales", 0.36, 0.31, 0.33));
        List<Models.Pair> pairs = Matching.match(b, s);
        assertEquals(2, pairs.size());
        for (Models.Pair p : pairs) assertFalse(p.book.ref.equals("b3")); // U21 ana takımla eşleşmez
    }

    @Test
    public void internationalTournamentsDiscovered() throws Exception {
        Http http = new Http() {
            public Response get(String url, Map<String, String> headers) {
                assertTrue(url.contains("/v4/sports?"));
                return new Response(200, "[{\"key\":\"soccer_uefa_nations_league\",\"group\":\"Soccer\",\"title\":\"UEFA Nations League\",\"active\":true,\"has_outrights\":false},"
                        + "{\"key\":\"soccer_fifa_world_cup_winner\",\"group\":\"Soccer\",\"title\":\"FIFA World Cup Winner\",\"active\":true,\"has_outrights\":true},"
                        + "{\"key\":\"soccer_epl\",\"group\":\"Soccer\",\"title\":\"EPL\",\"active\":true,\"has_outrights\":false},"
                        + "{\"key\":\"basketball_nba\",\"group\":\"Basketball\",\"title\":\"NBA\",\"active\":true,\"has_outrights\":false}]",
                        new LinkedHashMap<String, String>());
            }
        };
        Settings cfg = new Settings();
        cfg.oddsApiKey = "k";
        List<String[]> sports = new OddsApi(http, cfg).fetchSports();
        assertEquals(2, sports.size()); // futbol, aktif, şampiyonluk pazarı olmayan
        assertTrue(OddsApi.isInternational("soccer_uefa_nations_league"));
        assertTrue(OddsApi.isInternational("soccer_fifa_world_cup_qualifiers_europe"));
        assertTrue(OddsApi.isInternational("soccer_international_friendlies"));
        assertFalse(OddsApi.isInternational("soccer_epl"));
        assertFalse(OddsApi.isInternational("soccer_fifa_womens_world_cup"));
        Settings.EXTRA_NAMES.put("soccer_uefa_nations_league", "UEFA Nations League");
        assertEquals("UEFA Nations League", CreditPlan.leagueName("soccer_uefa_nations_league"));
        Settings back = Settings.fromMap(Json.parseObject(Json.write(new Settings().toMap())));
        assertTrue(back.internationals);
    }

    @Test
    public void nesineBackupOnlyForShortOutagesNeverForPlayChecks() throws Exception {
        Nesine.clearCache();
        final boolean[] fail = {false};
        Http http = new Http() {
            public Response get(String url, Map<String, String> headers) throws ProviderException {
                if (fail[0]) throw new ProviderException(url + " -> bağlantı hatası: unexpected end of stream");
                return new Response(200, "{\"sg\":{\"EA\":[{\"TYPE\":1,\"HN\":\"A\",\"AN\":\"B\",\"D\":\"03.10.2026\","
                        + "\"T\":\"20:00\",\"C\":1,\"MBS\":1,\"MA\":[{\"MTID\":1,\"SOV\":0.0,\"OCA\":[{\"N\":1,\"O\":2.1},"
                        + "{\"N\":2,\"O\":3.3},{\"N\":3,\"O\":3.4}]}]}]}}", new LinkedHashMap<String, String>());
            }
        };
        assertEquals(1, Nesine.fetch(http, 1200, Instant.now(), null).size());
        fail[0] = true;
        String[] note = new String[1];
        assertEquals(1, Nesine.fetch(http, 1200, Instant.now().plusSeconds(300), note).size());
        assertTrue(note[0], note[0].contains("5 dk önceki bülten kullanıldı"));
        try {
            Nesine.fetch(http, 0, Instant.now(), null); // oynama kontrolü: yalnızca canlı
            throw new AssertionError("yedek kullanılmamalıydı");
        } catch (Http.ProviderException expected) {
            assertTrue(expected.getMessage().contains("unexpected end of stream"));
        }
        try {
            Nesine.fetch(http, 1200, Instant.now().plusSeconds(1500), null); // 25 dk: çok eski
            throw new AssertionError("eski yedek kullanılmamalıydı");
        } catch (Http.ProviderException expected) {
            // beklenen
        }
        Nesine.clearCache();
    }

    @Test
    public void goalModelRecoversExpectedGoals() {
        double[] o = GoalModel.outcomes(1.6, 1.0);
        double[] l = GoalModel.fit(o[0], o[2], o[3]);
        assertEquals(1.6, l[0], 0.01);
        assertEquals(1.0, l[1], 0.01);
        assertEquals((1 - Math.exp(-1.6)) * (1 - Math.exp(-1.0)), GoalModel.btts(o[0], o[2], o[3]), 0.005);
    }

    @Test
    public void kgMarketDiscoveredWithModelWhenPinnacleKgIsScarce() {
        double[][] lam = {{1.6, 1.0}, {1.1, 1.3}, {2.1, 0.7}, {1.3, 1.2}, {0.9, 1.5}, {1.8, 1.1}};
        List<BookEvent> book = new ArrayList<>();
        List<SharpEvent> sharp = new ArrayList<>();
        for (int i = 0; i < lam.length; i++) {
            double[] o = GoalModel.outcomes(lam[i][0], lam[i][1]);
            Map<String, Map<String, Double>> fair = CoreTest.ms(o[0], o[1], o[2]);
            Map<String, Double> au = new LinkedHashMap<>();
            au.put("ALT", 1 - o[3]);
            au.put("UST", o[3]);
            fair.put("AU25", au);
            sharp.add(new SharpEvent("s" + i, "lig", "Ev " + i, "Dep " + i, KO, fair, "pinnacle")); // Pinnacle KG yok
            Map<String, Map<String, Double>> odds = CoreTest.ms(1 / (o[0] * 1.08), 1 / (o[1] * 1.08), 1 / (o[2] * 1.08));
            Map<String, Double> kg = new LinkedHashMap<>(); // bültende N1 = Yok, N2 = Var
            kg.put("1", 1 / ((1 - o[4]) * 1.07));
            kg.put("2", 1 / (o[4] * 1.07));
            odds.put(Calibration.RAW_KG + "38", kg);
            Map<String, Double> oddEven = new LinkedHashMap<>(); // yanıltıcı: tek/çift
            oddEven.put("1", 1.87);
            oddEven.put("2", 1.87);
            odds.put(Calibration.RAW_KG + "41", oddEven);
            book.add(new BookEvent("b" + i, "Ev " + i, "Dep " + i, KO, "Lig", 1, odds, String.valueOf(i)));
        }
        Map<String, Object> memory = new LinkedHashMap<>();
        Map<String, Object> r = Calibration.apply(book, sharp, memory);
        String kg = String.valueOf(r.get("KG"));
        assertTrue(kg, kg.startsWith("doğrulandı (pazar 38") && kg.contains("model destekli"));
        assertEquals(1 / (0.0 + GoalModel.outcomes(1.6, 1.0)[4] * 1.07), book.get(0).odds.get("KG").get("VAR"), 1e-9);
        assertEquals("KG#38|1", memory.get("KG")); // eşleme hatırlanır
        // bahis yine yalnızca Pinnacle KG fiyatıyla: modelden adil olasılık eklenmez
        assertNull(sharp.get(0).fair.get("KG"));
    }

    @Test
    public void forecastLogScoresAccuracy() {
        Ledger.MemoryStorage store = new Ledger.MemoryStorage(null);
        Forecasts f = new Forecasts(store);
        List<BookEvent> book = new ArrayList<>();
        List<SharpEvent> sharp = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            book.add(book("b" + i, "Ev " + i, "Dep " + i, 2.0, 3.4, 3.6));
            sharp.add(sharp("s" + i, "Ev " + i, "Dep " + i, 0.40, 0.30, 0.30));
        }
        f.record(Matching.match(book, sharp), NOW);
        // maç öncesi Pinnacle güncellendi (ilk değer saklanır)
        f.recordSharp(Collections.singletonList(sharp("s0", "Ev 0", "Dep 0", 0.60, 0.25, 0.15)), NOW.plusSeconds(3600));
        Map<String, ScoreResult> scores = new LinkedHashMap<>();
        scores.put("s0", new ScoreResult(true, 2, 0));
        scores.put("s1", new ScoreResult(true, 1, 1));
        scores.put("s2", new ScoreResult(false, null, null)); // henüz bitmedi
        assertEquals(2, f.resolve(scores));
        assertEquals(0, f.resolve(scores)); // ikinci kez çözülmez
        Map<String, Object> s = f.summary();
        assertEquals(2L, s.get("n"));
        assertEquals(2L, s.get("pending"));
        double brierLate = ((0.4 * 0.4 + 0.25 * 0.25 + 0.15 * 0.15) + (0.4 * 0.4 + 0.7 * 0.7 + 0.3 * 0.3)) / 2;
        assertEquals(brierLate, Json.dbl(Json.obj(s.get("model")), "brier", 0), 1e-12);
        assertEquals((-Math.log(0.6) - Math.log(0.3)) / 2, Json.dbl(Json.obj(s.get("model")), "logLoss", 0), 1e-12);
        assertEquals((-Math.log(0.4) - Math.log(0.3)) / 2, Json.dbl(Json.obj(s.get("first")), "logLoss", 0), 1e-12);
        double[] q = {1 / 2.0, 1 / 3.4, 1 / 3.6};
        double qs = q[0] + q[1] + q[2];
        assertEquals((-Math.log(q[0] / qs) - Math.log(q[1] / qs)) / 2, Json.dbl(Json.obj(s.get("book")), "logLoss", 0), 1e-12);
        assertFalse(Json.arr(s.get("bins")).isEmpty());
        double d0 = (-Math.log(q[0] / qs) + Math.log(0.6)), d1 = (-Math.log(q[1] / qs) + Math.log(0.3));
        assertEquals((d0 + d1) / 2, Json.dbl(s, "diff", 0), 1e-12);
        double m = (d0 + d1) / 2, sd = Math.sqrt(((d0 - m) * (d0 - m) + (d1 - m) * (d1 - m)) / 1);
        assertEquals(sd / Math.sqrt(2), Json.dbl(s, "diffSe", 0), 1e-9);
        // kalıcı; sonucu gelmeyen maç 5 gün sonra silinir
        Forecasts again = new Forecasts(new Ledger.MemoryStorage(store.read()));
        assertEquals(2L, again.summary().get("n"));
        again.record(new ArrayList<Models.Pair>(), NOW.plusSeconds(7 * 86400));
        assertEquals(0L, again.summary().get("pending"));
        assertEquals(2L, again.summary().get("n"));
        // bozuk dosya uygulamayı düşürmez
        assertEquals(0L, new Forecasts(new Ledger.MemoryStorage("{bozuk")).summary().get("n"));
    }

    @Test
    public void demoReportsAccuracy() {
        Map<String, Object> out = DemoSim.run(new Ledger.MemoryStorage(null), 30, 1000000, 5, NOW);
        Map<String, Object> acc = Json.obj(out.get("accuracy"));
        assertTrue(Json.lng(acc, "n", 0) > 100);
        // iki tahmin de yazı-turadan (her sonuca 1/3) iyi; aradaki fark bu örneklemde anlamlı değil
        assertTrue(Json.dbl(Json.obj(acc.get("model")), "logLoss", 9) < Math.log(3));
        assertTrue(Json.dbl(Json.obj(acc.get("book")), "logLoss", 9) < Math.log(3));
        assertTrue(Math.abs(Json.dbl(acc, "diff", 9)) < 2 * Json.dbl(acc, "diffSe", 0) + 0.01);
    }
}
