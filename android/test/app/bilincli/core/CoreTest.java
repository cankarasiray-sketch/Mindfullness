package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.bilincli.core.Ledger.Coupon;
import app.bilincli.core.Ledger.LedgerException;
import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.Candidate;
import app.bilincli.core.Models.Proposal;
import app.bilincli.core.Models.ScoreResult;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class CoreTest {
    public static final Instant NOW = Instant.parse("2026-10-01T03:00:00Z"); // 06:00 Türkiye

    public static final class TestClock implements Ledger.Clock {
        Instant now = NOW;

        @Override
        public Instant now() {
            return now;
        }
    }

    TestClock clock;
    Ledger.MemoryStorage storage;
    Ledger ledger;

    @Before
    public void setUp() {
        clock = new TestClock();
        storage = new Ledger.MemoryStorage(null);
        ledger = new Ledger(storage, clock);
    }

    static Map<String, Map<String, Double>> ms(double a, double b, double c) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("1", a);
        m.put("X", b);
        m.put("2", c);
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        out.put("MS", m);
        return out;
    }

    static Candidate leg(int i, double odds, double prob, int hours) {
        Instant ko = NOW.plusSeconds(hours * 3600L);
        BookEvent b = new BookEvent("b" + i, "Ev " + i, "Dep " + i, ko, "Lig", 1, ms(odds, 3.4, 4.0), String.valueOf(i));
        SharpEvent s = new SharpEvent("s" + i, "lig", "Ev " + i, "Dep " + i, ko, ms(prob, 0.25, 1 - prob - 0.25), "");
        return new Candidate(b, s, "MS", "1", odds, prob);
    }

    static Proposal proposal(int n, double odds) {
        List<Candidate> legs = new ArrayList<>();
        for (int i = 0; i < n; i++) legs.add(leg(i, odds, 0.55, 10));
        return new Proposal(legs, Math.pow(odds, n), Math.pow(0.55, n), 0.02, 0.001);
    }

    // ---- Json -----------------------------------------------------------
    @Test
    public void jsonRoundTrip() {
        String text = "{\"a\":[1,2.5,\"x\\\"y\",true,null],\"b\":{\"ç\":\"ğüş\\u00e7\"},\"c\":-3e2}";
        Map<String, Object> m = Json.parseObject(text);
        assertEquals(1L, Json.arr(m.get("a")).get(0));
        assertEquals(2.5, Json.arr(m.get("a")).get(1));
        assertEquals("x\"y", Json.arr(m.get("a")).get(2));
        assertEquals("ğüşç", Json.obj(m.get("b")).get("ç"));
        assertEquals(-300.0, m.get("c"));
        assertEquals(m, Json.parseObject(Json.write(m)));
        assertTrue(Json.write("</script>").contains("\\u003c/script\\u003e"));
        try {
            Json.parse("{\"a\":1,}");
            fail();
        } catch (IllegalArgumentException expected) {
            // beklenen
        }
    }

    // ---- biçimlendirme ----------------------------------------------------
    @Test
    public void formatting() {
        Locale.setDefault(new Locale("tr", "TR")); // Türkçe telefonda da aynı çıktı
        assertEquals("1.234.567,89 TL", Fmt.tl(123456789));
        assertEquals("-50,00 TL", Fmt.tl(-5000));
        assertEquals("0,05 TL", Fmt.tl(5));
        assertEquals("+%7,5", Fmt.pct(0.075, true));
        assertEquals("2,10", Fmt.odds(2.1));
        assertEquals("2026-10-01", Fmt.dayKey(NOW));
        assertEquals("2026-09-30", Fmt.dayKey(Instant.parse("2026-09-30T20:59:00Z")));
        assertEquals("istanbul", Matching.normalize("ISTANBUL"));
        Locale.setDefault(Locale.ROOT);
    }

    // ---- kasa -------------------------------------------------------------
    @Test
    public void depositWithdraw() {
        ledger.deposit(100000, "");
        ledger.withdraw(30000, "");
        assertEquals(70000, ledger.balance());
        try {
            ledger.withdraw(80000, "");
            fail();
        } catch (LedgerException expected) {
            assertTrue(expected.getMessage().contains("yeterli"));
        }
    }

    @Test
    public void wonCouponPaysOut() {
        ledger.deposit(100000, "");
        long cid = ledger.addCoupon(proposal(2, 2.0), "2026-10-01", 2000);
        ledger.markPlayed(cid, 2000, null);
        assertEquals(98000, ledger.balance());
        ledger.setLegResult(cid, 1, Models.WON, "2-0");
        assertNull(ledger.settleCoupon(cid));
        ledger.setLegResult(cid, 2, Models.WON, "1-0");
        assertEquals(Models.WON, ledger.settleCoupon(cid));
        assertEquals(98000 + 8000, ledger.balance());
        Ledger.Stats s = ledger.stats();
        assertEquals(1, s.won);
        assertEquals(6000, s.bettingPl());
        assertEquals(3.0, s.roi(), 1e-12);
    }

    @Test
    public void lostOnFirstLossAndVoidCountsAsOne() {
        ledger.deposit(100000, "");
        long a = ledger.addCoupon(proposal(2, 2.0), "2026-10-01", 2000);
        ledger.markPlayed(a, 2000, null);
        ledger.setLegResult(a, 1, Models.LOST, "0-1");
        assertEquals(Models.LOST, ledger.settleCoupon(a));
        long b = ledger.addCoupon(proposal(2, 2.0), "2026-10-01", 2000);
        ledger.markPlayed(b, 2000, null);
        ledger.setLegResult(b, 1, Models.VOID, null);
        ledger.setLegResult(b, 2, Models.WON, "1-0");
        assertEquals(Models.WON, ledger.settleCoupon(b));
        assertEquals(100000 - 2000 - 2000 + 4000, ledger.balance());
    }

    @Test
    public void markPlayedValidations() {
        ledger.deposit(1000, "");
        long cid = ledger.addCoupon(proposal(2, 2.0), "2026-10-01", 2000);
        try {
            ledger.markPlayed(cid, 2000, null);
            fail();
        } catch (LedgerException expected) {
            // kasa yetmiyor
        }
        try {
            ledger.markPlayed(cid, 500, Collections.singletonList(1.9));
            fail();
        } catch (LedgerException expected) {
            // eksik oran
        }
        assertEquals(1000, ledger.balance());
        assertFalse(ledger.coupon(cid).played);
        ledger.markPlayed(cid, 500, Arrays.asList(1.9, 2.1));
        assertEquals(3.99, ledger.coupon(cid).totalOdds, 1e-12);
    }

    @Test
    public void persistenceRoundTrip() {
        ledger.deposit(100000, "ilk");
        long cid = ledger.addCoupon(proposal(3, 1.8), "2026-10-01", 1500);
        ledger.markPlayed(cid, 1500, null);
        ledger.recordRun("2026-10-01", "kupon", "", cid, "özet");
        Settings s = ledger.settings();
        s.oddsApiKey = "abc";
        s.leagues = Arrays.asList("soccer_epl");
        ledger.saveSettings(s);
        Ledger again = new Ledger(new Ledger.MemoryStorage(storage.read()), clock);
        assertEquals(ledger.export(), again.export());
        assertEquals(98500, again.balance());
        assertEquals("abc", again.settings().oddsApiKey);
        assertEquals(3, again.coupon(cid).legs.size());
        assertEquals("özet", again.runFor("2026-10-01").summary);
        // yedekten geri yükleme
        Ledger fresh = new Ledger(new Ledger.MemoryStorage(null), clock);
        fresh.replaceWith(ledger.export());
        assertEquals(98500, fresh.balance());
        try {
            fresh.replaceWith("{\"x\":1}");
            fail();
        } catch (LedgerException expected) {
            // yedek değil
        }
    }

    @Test
    public void settingsValidation() {
        Settings s = new Settings();
        assertNull(s.validate());
        s.kellyMultiplier = 0;
        assertNotNull(s.validate());
        try {
            ledger.saveSettings(s);
            fail();
        } catch (LedgerException expected) {
            // geçersiz ayar kaydedilmez
        }
    }

    // ---- sonuçlandırma -----------------------------------------------------
    @Test
    public void legResults() {
        assertEquals(Models.WON, Settlement.legResult("MS", "1", 2, 1));
        assertEquals(Models.WON, Settlement.legResult("MS", "X", 1, 1));
        assertEquals(Models.LOST, Settlement.legResult("MS", "2", 1, 1));
        assertEquals(Models.WON, Settlement.legResult("AU25", "UST", 2, 1));
        assertEquals(Models.WON, Settlement.legResult("AU25", "ALT", 1, 1));
    }

    @Test
    public void settleOpenFetchesOnlyWhenDue() throws Exception {
        ledger.deposit(100000, "");
        List<Candidate> legs = new ArrayList<>();
        legs.add(leg(1, 2.2, 0.5, 10));
        long cid = ledger.addCoupon(new Proposal(legs, 2.2, 0.5, 0.02, 0.001), "2026-10-01", 2000);
        ledger.markPlayed(cid, 2000, null);
        final List<Set<String>> calls = new ArrayList<>();
        Settlement.ScoreFetcher fetch = new Settlement.ScoreFetcher() {
            @Override
            public Map<String, ScoreResult> fetch(Set<String> sportKeys) {
                calls.add(sportKeys);
                Map<String, ScoreResult> m = new HashMap<>();
                m.put("s1", new ScoreResult(true, 2, 0));
                return m;
            }
        };
        assertTrue(Settlement.settleOpen(ledger, fetch, NOW).isEmpty());
        assertTrue(calls.isEmpty());
        clock.now = NOW.plusSeconds(13 * 3600);
        List<String> msgs = Settlement.settleOpen(ledger, fetch, clock.now);
        assertEquals(1, calls.size());
        assertTrue(msgs.get(0), msgs.get(0).contains("TUTTU"));
        assertEquals(100000 - 2000 + 4400, ledger.balance());
        // 3 gün sonra sonuç yoksa elle giriş ister
        List<Candidate> l2 = new ArrayList<>();
        l2.add(leg(2, 2.0, 0.55, 20));
        long c2 = ledger.addCoupon(new Proposal(l2, 2.0, 0.55, 0.02, 0.001), "2026-10-01", 0);
        msgs = Settlement.settleOpen(ledger, fetch, NOW.plusSeconds(5 * 86400));
        assertTrue(msgs.get(0).contains("#" + c2) && msgs.get(0).contains("elle"));
    }

    // ---- koruma -------------------------------------------------------------
    @Test
    public void weekStartIsMonday0600() {
        Instant ws = Guard.weekStart(NOW); // 1 Ekim 2026 Perşembe
        assertEquals("2026-09-28T03:00:00Z", ws.toString());
        assertEquals("2026-09-21T03:00:00Z", Guard.weekStart(Instant.parse("2026-09-28T02:59:00Z")).toString());
    }

    private long lose(long stake) {
        long cid = ledger.addCoupon(proposal(1, 2.0), "2026-10-01", stake);
        ledger.markPlayed(cid, stake, null);
        ledger.setLegResult(cid, 1, Models.LOST, null);
        ledger.settleCoupon(cid);
        return cid;
    }

    @Test
    public void weeklyLossLimit() {
        Settings cfg = new Settings();
        cfg.chaseCooldownHours = 0;
        clock.now = Guard.weekStart(NOW).minusSeconds(86400);
        ledger.deposit(100000, "");
        clock.now = NOW;
        assertNull(Guard.check(ledger, cfg, NOW));
        for (int i = 0; i < 3; i++) lose(5000);
        String msg = Guard.check(ledger, cfg, NOW);
        assertTrue(msg, msg.contains("Haftalık kayıp limiti"));
        assertNull(Guard.check(ledger, cfg, NOW.plusSeconds(7 * 86400)));
    }

    @Test
    public void chaseCooldownOnlyWhenLosing() {
        Settings cfg = new Settings();
        cfg.weeklyLossLimit = 0;
        ledger.deposit(100000, "");
        assertNull(Guard.check(ledger, cfg, NOW));
        lose(5000);
        clock.now = NOW.plusSeconds(3600);
        ledger.deposit(50000, "");
        assertTrue(Guard.check(ledger, cfg, clock.now).contains("Kovalama"));
        assertNull(Guard.check(ledger, cfg, clock.now.plusSeconds(49 * 3600)));
    }

    // ---- veri kaynakları ------------------------------------------------------
    @Test
    public void nesineParser() throws Exception {
        String payload = "{\"sg\":{\"EA\":["
                + "{\"C\":4321,\"HN\":\"Galatasaray\",\"AN\":\"Fenerbahçe\",\"D\":\"03.10.2026\",\"T\":\"20:00\","
                + "\"TYPE\":1,\"MBS\":1,\"LN\":\"Süper Lig\",\"MA\":[{\"MTID\":1,\"MBS\":1,\"OCA\":["
                + "{\"N\":1,\"O\":1.95},{\"N\":2,\"O\":3.40},{\"N\":3,\"O\":3.60}]},{\"MTID\":99,\"OCA\":[]}]},"
                + "{\"C\":1,\"HN\":\"Basket A\",\"AN\":\"B\",\"D\":\"03.10.2026\",\"T\":\"20:00\",\"TYPE\":2,\"MA\":[]},"
                + "{\"C\":2,\"HN\":\"Eksik\",\"AN\":\"Oran\",\"D\":\"03.10.2026\",\"T\":\"21:00\",\"TYPE\":1,"
                + "\"MA\":[{\"MTID\":1,\"OCA\":[{\"N\":1,\"O\":1.5}]}]},"
                + "{\"C\":3,\"HN\":\"MBS Yok\",\"AN\":\"Takım\",\"D\":\"03.10.2026\",\"T\":\"21:00\",\"TYPE\":1,"
                + "\"MA\":[{\"MTID\":1,\"OCA\":[{\"N\":1,\"O\":\"1,50\"},{\"N\":2,\"O\":3.9},{\"N\":3,\"O\":5.5}]}]}"
                + "]}}";
        List<BookEvent> events = Nesine.parse(Json.parse(payload));
        assertEquals(2, events.size());
        BookEvent gs = events.get(0);
        assertEquals("4321", gs.code);
        assertEquals(1, gs.mbs);
        assertEquals(Instant.parse("2026-10-03T17:00:00Z"), gs.kickoff);
        assertEquals(1.95, gs.odds.get("MS").get("1"), 0);
        assertEquals(3, events.get(1).mbs); // MBS okunamazsa temkinli
        assertEquals(1.5, events.get(1).odds.get("MS").get("1"), 0);
        try {
            Nesine.parse(Json.parse("[]"));
            fail();
        } catch (Http.ProviderException expected) {
            // beklenen
        }
    }

    static String book(String key, double h, double d, double a) {
        return "{\"key\":\"" + key + "\",\"markets\":[{\"key\":\"h2h\",\"outcomes\":["
                + "{\"name\":\"Galatasaray\",\"price\":" + h + "},{\"name\":\"Draw\",\"price\":" + d + "},"
                + "{\"name\":\"Fenerbahce\",\"price\":" + a + "}]}]}";
    }

    static final String EVENT = "\"id\":\"abc\",\"commence_time\":\"2026-10-03T17:00:00Z\","
            + "\"home_team\":\"Galatasaray\",\"away_team\":\"Fenerbahce\"";

    @Test
    public void oddsApiParsing() throws Exception {
        Settings cfg = new Settings();
        cfg.oddsApiKey = "k";
        OddsApi api = new OddsApi(null, cfg);
        List<SharpEvent> ev = api.parseOdds(Json.parse("[{" + EVENT + ",\"bookmakers\":[" + book("pinnacle", 2.0, 3.6, 3.9)
                + "," + book("other", 1.8, 3.3, 3.6) + "]}]"), "soccer_turkey_super_league");
        assertEquals(1, ev.size());
        Map<String, Double> fair = ev.get(0).fair.get("MS");
        assertEquals(1.0, fair.get("1") + fair.get("X") + fair.get("2"), 1e-12);
        assertTrue(ev.get(0).source.contains("pinnacle"));
        // tercih edilen site yoksa en az 3 sitenin ortalaması
        String three = book("a", 2.0, 3.5, 3.8) + "," + book("b", 2.1, 3.5, 3.8) + "," + book("c", 2.2, 3.5, 3.8);
        assertTrue(api.parseOdds(Json.parse("[{" + EVENT + ",\"bookmakers\":[" + three + "]}]"), "x")
                .get(0).source.contains("ortalama(3)"));
        String two = book("a", 2.0, 3.5, 3.8) + "," + book("b", 2.1, 3.5, 3.8);
        assertTrue(api.parseOdds(Json.parse("[{" + EVENT + ",\"bookmakers\":[" + two + "]}]"), "x").isEmpty());
        Map<String, ScoreResult> sc = OddsApi.parseScores(Json.parse("[{" + EVENT + ",\"completed\":true,\"scores\":["
                + "{\"name\":\"Galatasaray\",\"score\":\"2\"},{\"name\":\"Fenerbahce\",\"score\":\"1\"}]},"
                + "{\"id\":\"live\",\"completed\":false,\"scores\":null}]"));
        assertTrue(sc.get("abc").completed);
        assertEquals(Integer.valueOf(2), sc.get("abc").home);
        assertFalse(sc.get("live").completed);
        assertFalse(Http.safe("https://x/v4?apiKey=SECRET&a=1").contains("SECRET"));
        try {
            new OddsApi(null, new Settings());
            fail();
        } catch (Http.ProviderException expected) {
            // anahtar yok
        }
    }

    // ---- günlük akış ---------------------------------------------------------
    @Test
    public void dailyFlowWithFakeSources() {
        ledger.deposit(1000000, "");
        final Instant ko = NOW.plusSeconds(6 * 3600);
        Daily.Sources src = new Daily.Sources() {
            @Override
            public List<BookEvent> book() {
                List<BookEvent> l = new ArrayList<>();
                l.add(new BookEvent("b1", "Beşiktaş", "Göztepe", ko, "Süper Lig", 1, ms(2.40, 3.3, 3.2), "1"));
                return l;
            }

            @Override
            public List<SharpEvent> sharp() {
                List<SharpEvent> l = new ArrayList<>();
                l.add(new SharpEvent("s1", "soccer_turkey_super_league", "Besiktas JK", "Goztepe", ko,
                        ms(0.52, 0.26, 0.22), "pinnacle"));
                return l;
            }

            @Override
            public Map<String, ScoreResult> scores(Set<String> keys) {
                return new HashMap<>();
            }
        };
        Daily.Result r = Daily.runDaily(ledger, src, false, false);
        assertEquals("KUPON", r.headline());
        Coupon c = ledger.coupon(r.couponId);
        assertTrue(c.suggestedStake > 0 && c.suggestedStake % 100 == 0 && c.suggestedStake <= 30000);
        String[] note = Texts.notification(ledger, r);
        assertTrue(note[0], note[0].startsWith("Günün kuponu"));
        assertTrue(note[1], note[1].contains("MS 1 @ 2,40"));
        assertTrue(Daily.runDaily(ledger, src, false, false).skipped); // aynı gün tekrar üretmez
        Map<String, Object> state = State.build(ledger, false, null);
        assertEquals(r.couponId, Json.obj(state.get("todayRun")).get("couponId"));
        Json.parse(Json.write(state)); // arayüze giden JSON geçerli
    }

    @Test
    public void demoSimulationRuns() {
        Ledger.MemoryStorage st = new Ledger.MemoryStorage(null);
        Map<String, Object> out = DemoSim.run(st, 30, 1000000, 7, NOW);
        assertEquals(30L, out.get("days"));
        long days = (Long) out.get("couponDays") + (Long) out.get("passDays") + (Long) out.get("guardDays");
        assertEquals(31L, days); // bugün dahil
        Ledger demo = new Ledger(st, clock);
        Ledger.Stats s = demo.stats();
        assertEquals(s.balance, s.deposited + s.bettingPl() - s.openStake);
        assertTrue((Long) out.get("baselineEnd") < 1000000); // "banko" kupon marj yüzünden eriyor
    }
}
