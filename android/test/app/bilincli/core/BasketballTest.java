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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** 1.9: basketbol maç sonucu (EuroLeague, NBA). */
public class BasketballTest {
    static final Instant NOW = CoreTest.NOW;
    static final String LEAGUE = "basketball_euroleague";
    /** Ev sahibinin adil kazanma olasılıkları (uzatmalar dahil). */
    static final double[] P = {0.55, 0.72, 0.38, 0.61, 0.47, 0.83};

    static Map<String, Double> two(double a, double b) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("1", a);
        m.put("2", b);
        return m;
    }

    /** iddaa: %8 marjla adil fiyat; value maçında ev sahibine 2,10 verilir (adil 1,82). */
    static List<BookEvent> book(int value) {
        List<BookEvent> out = new ArrayList<>();
        for (int i = 0; i < P.length; i++) {
            Map<String, Map<String, Double>> odds = new LinkedHashMap<>();
            double o1 = i == value ? 2.10 : 1 / (P[i] * 1.08), o2 = 1 / ((1 - P[i]) * 1.08);
            odds.put(Calibration.RAW_BS + "20", two(o1, o2));
            odds.put(Calibration.RAW_BS + "31", two(1.87, 1.87)); // tek/çift gibi yanıltıcı pazar
            BookEvent b = new BookEvent("b" + i, "Ev " + i, "Dep " + i, NOW.plusSeconds(3600L * (8 + i)), "EuroLeague", 1, odds, "" + i);
            b.sport = Models.BASKETBALL;
            out.add(b);
        }
        return out;
    }

    static List<SharpEvent> sharp() {
        List<SharpEvent> out = new ArrayList<>();
        for (int i = 0; i < P.length; i++) {
            Map<String, Map<String, Double>> fair = new LinkedHashMap<>();
            fair.put("BS", two(P[i], 1 - P[i]));
            out.add(new SharpEvent("s" + i, LEAGUE, "Ev " + i, "Dep " + i, NOW.plusSeconds(3600L * (8 + i)), fair, "pinnacle"));
        }
        return out;
    }

    @Test
    public void nesineReadsBasketballMoneylineCandidates() throws Exception {
        String payload = "{\"sg\":{\"EA\":["
                + "{\"C\":1,\"HN\":\"A\",\"AN\":\"B\",\"D\":\"03.10.2026\",\"T\":\"20:00\",\"TYPE\":1,\"MBS\":1,"
                + "\"MA\":[{\"MTID\":1,\"OCA\":[{\"N\":1,\"O\":2.0},{\"N\":2,\"O\":3.2},{\"N\":3,\"O\":3.6}]}]},"
                + "{\"C\":2,\"HN\":\"Fenerbahçe Beko\",\"AN\":\"Real Madrid\",\"D\":\"03.10.2026\",\"T\":\"20:45\",\"TYPE\":2,\"MBS\":1,"
                + "\"MA\":[{\"MTID\":20,\"SOV\":0.0,\"MBS\":1,\"OCA\":[{\"N\":1,\"O\":1.65},{\"N\":3,\"O\":2.15}]},"
                + "{\"MTID\":21,\"SOV\":-4.5,\"OCA\":[{\"N\":1,\"O\":1.85},{\"N\":2,\"O\":1.85}]},"
                + "{\"MTID\":22,\"SOV\":161.5,\"OCA\":[{\"N\":1,\"O\":1.85},{\"N\":2,\"O\":1.85}]}]},"
                + "{\"C\":3,\"HN\":\"X\",\"AN\":\"Y\",\"D\":\"03.10.2026\",\"T\":\"21:00\",\"TYPE\":5,\"MBS\":1,"
                + "\"MA\":[{\"MTID\":7,\"OCA\":[{\"N\":1,\"O\":1.5},{\"N\":2,\"O\":2.5}]}]}]}}";
        List<BookEvent> ev = Nesine.parse(Json.parse(payload));
        assertEquals(2, ev.size()); // futbol + basketbol; diğer sporlar atlanır
        assertEquals(Models.FOOTBALL, ev.get(0).sport);
        BookEvent b = ev.get(1);
        assertEquals(Models.BASKETBALL, b.sport);
        assertEquals(Collections.singleton("BS#20"), b.odds.keySet()); // handikap ve toplam sayı (özel değerli) alınmaz
        assertEquals(1.65, b.odds.get("BS#20").get("1"), 0); // N sırası: ilk = ev sahibi
        assertEquals(2.15, b.odds.get("BS#20").get("2"), 0);
        assertEquals("futbol 1, basketbol 1, tür 5 1", Nesine.lastTypes);
        assertTrue(Nesine.lastBasketInventory, Nesine.lastBasketInventory.contains("MTID 22: 1 maç, seçenek [2], değer [161.5]"));
        assertFalse(Nesine.lastInventory.contains("MTID 22"));
    }

    @Test
    public void pinnacleTwoWayMoneylineAndOneCreditPerLeague() throws Exception {
        final List<String> urls = new ArrayList<>();
        Http http = new Http() {
            public Response get(String url, Map<String, String> headers) {
                urls.add(url);
                return new Response(200, "[{\"id\":\"e1\",\"commence_time\":\"2026-10-01T17:45:00Z\",\"home_team\":\"Fenerbahce Beko\","
                        + "\"away_team\":\"Real Madrid\",\"bookmakers\":[{\"key\":\"pinnacle\",\"markets\":[{\"key\":\"h2h\",\"outcomes\":["
                        + "{\"name\":\"Fenerbahce Beko\",\"price\":1.62},{\"name\":\"Real Madrid\",\"price\":2.42}]}]}]}]",
                        new LinkedHashMap<String, String>());
            }
        };
        Settings cfg = new Settings();
        cfg.oddsApiKey = "k";
        cfg.totals = true;
        List<SharpEvent> ev = new OddsApi(http, cfg).fetchEvents(Collections.singletonList(LEAGUE));
        assertTrue(urls.get(0), urls.get(0).contains("markets=h2h&")); // Alt/Üst açıkken bile yalnızca maç sonucu
        Map<String, Double> bs = ev.get(0).fair.get("BS");
        assertEquals(1.0, bs.get("1") + bs.get("2"), 1e-9);
        assertTrue(bs.get("1") > 0.59 && bs.get("1") < 0.61);
        assertNull(ev.get(0).fair.get("MS"));
        assertEquals(Models.BASKETBALL, ev.get(0).sport());
        assertEquals("h2h,totals", Settings.markets("soccer_epl", true));
        assertEquals(1, Settings.scanCost(LEAGUE, true));
        assertEquals(2, Settings.scanCost("soccer_epl", true));
    }

    @Test
    public void footballAndBasketballNeverMatchEachOther() {
        BookEvent basket = new BookEvent("b", "Fenerbahçe Beko", "Real Madrid", NOW.plusSeconds(3600), "EuroLeague", 1,
                new LinkedHashMap<String, Map<String, Double>>(), "1");
        basket.sport = Models.BASKETBALL;
        SharpEvent football = new SharpEvent("f", "soccer_uefa_champs_league", "Fenerbahce", "Real Madrid", NOW.plusSeconds(3600),
                new LinkedHashMap<String, Map<String, Double>>(), "pinnacle");
        SharpEvent hoops = new SharpEvent("h", LEAGUE, "Fenerbahce Beko Istanbul", "Real Madrid", NOW.plusSeconds(3600),
                new LinkedHashMap<String, Map<String, Double>>(), "pinnacle");
        assertTrue(Matching.match(Collections.singletonList(basket), Collections.singletonList(football)).isEmpty());
        assertEquals("h", Matching.match(Collections.singletonList(basket), Arrays.asList(football, hoops)).get(0).sharp.ref);
        assertEquals("crvena zvezda", Matching.normalize("Kızılyıldız"));
        assertEquals("crvena zvezda", Matching.normalize("Red Star Belgrade"));
    }

    @Test
    public void calibrationDiscoversBasketballMarketAndDropsMismatchedGame() {
        List<BookEvent> book = book(-1);
        List<SharpEvent> sharp = sharp();
        // 5 numaralı maç yanlış eşleşmiş gibi: iddaa favoriyi tersine fiyatlıyor
        book.get(5).odds.put(Calibration.RAW_BS + "20", two(1 / (0.17 * 1.08), 1 / (0.83 * 1.08)));
        Map<String, Object> memory = new LinkedHashMap<>();
        Map<String, Object> r = Calibration.apply(book, sharp, memory);
        String bs = String.valueOf(r.get("BS"));
        assertTrue(bs, bs.startsWith("doğrulandı (pazar 20") && bs.contains("1 uyumsuz maç ayıklandı"));
        assertEquals("BS#20|0", memory.get("BS"));
        assertEquals(1 / (P[0] * 1.08), book.get(0).odds.get("BS").get("1"), 1e-9);
        assertNull(book.get(5).odds.get("BS"));
        for (BookEvent b : book) for (String k : b.odds.keySet()) assertFalse(k, k.contains("#")); // ham pazarlar temizlendi
        assertTrue(Calibration.summary(r), Calibration.summary(r).contains("Basket MS ✓"));
        // bültende basketbol yoksa sorun sayılmaz
        assertEquals("bültende basketbol yok", Calibration.apply(new ArrayList<BookEvent>(), sharp).get("BS"));
        // az maçta son güvenilir eşleme kullanılır
        List<BookEvent> few = new ArrayList<>(book(-1).subList(0, 2));
        String again = String.valueOf(Calibration.apply(few, sharp, memory).get("BS"));
        assertTrue(again, again.startsWith("önceki eşleme kullanıldı (pazar 20"));
        assertEquals(1 / (P[1] * 1.08), few.get(1).odds.get("BS").get("1"), 1e-9);
    }

    @Test
    public void basketballValueBecomesPartOfTheDaysCouponAndSettles() throws Exception {
        CoreTest.TestClock clock = new CoreTest.TestClock();
        Ledger ledger = new Ledger(new Ledger.MemoryStorage(null), clock);
        ledger.deposit(500000, "");
        final List<BookEvent> book = book(0);
        final List<SharpEvent> sharp = sharp();
        Daily.Sources src = new Daily.Sources() {
            @Override
            public List<BookEvent> book() {
                return book;
            }

            @Override
            public List<SharpEvent> sharp() {
                return sharp;
            }

            @Override
            public Map<String, ScoreResult> scores(Set<String> k) {
                return new HashMap<>();
            }
        };
        Daily.Result r = Daily.runDaily(ledger, src, false, false);
        assertFalse(r.decision.reason, r.couponIds.isEmpty());
        Ledger.Coupon c = ledger.coupon(r.couponIds.get(0));
        Ledger.Leg leg = c.legs.get(0);
        assertEquals("BS", leg.market);
        assertEquals("1", leg.outcome);
        assertEquals(2.10, leg.odds, 0);
        assertEquals(LEAGUE, leg.sportKey);
        assertTrue(Texts.notification(ledger, r)[1], Texts.notification(ledger, r)[1].contains("Basket MS 1 @ 2,10"));
        assertTrue(Texts.statsLine(r.decision.stats), Texts.statsLine(r.decision.stats).contains("iddaa basket MS marjı"));
        // sonuç: uzatmalar dahil skor
        assertEquals(Models.WON, Settlement.legResult("BS", "1", 88, 80));
        assertEquals(Models.LOST, Settlement.legResult("BS", "2", 88, 80));
        assertEquals(Models.VOID, Settlement.legResult("BS", "1", 80, 80));
        assertEquals("Basket MS 2", Models.outcomeLabel("BS", "2"));
        assertEquals("Basketbol MS", Models.marketName("BS"));
    }

    @Test
    public void settingsAddBasketballOnceAndCountItsCredits() {
        Settings s = new Settings();
        assertTrue(s.leagues.containsAll(Settings.BASKETBALL_LEAGUES));
        Map<String, Object> v3 = s.toMap();
        v3.put("v", 3L);
        List<Object> mine = new ArrayList<Object>(Arrays.asList("soccer_epl", "soccer_spain_la_liga"));
        v3.put("leagues", mine);
        Settings migrated = Settings.fromMap(v3);
        assertEquals(Arrays.asList("soccer_epl", "soccer_spain_la_liga", "basketball_euroleague", "basketball_nba"), migrated.leagues);
        // kullanıcı sonradan kaldırırsa (v=4) geri eklenmez
        Map<String, Object> v4 = migrated.toMap();
        v4.put("leagues", new ArrayList<Object>(Arrays.asList("soccer_epl")));
        assertEquals(Collections.singletonList("soccer_epl"), Settings.fromMap(v4).leagues);
        // kredi: basketbol ligi Alt/Üst açıkken de tarama başına 1
        CreditPlan.Plan p = new CreditPlan.Plan();
        p.leagues = new ArrayList<>(Arrays.asList("soccer_epl", LEAGUE));
        p.totals = true;
        p.coupons = 1;
        Map<String, Integer> active = new LinkedHashMap<>();
        active.put("soccer_epl", 3);
        active.put(LEAGUE, 4);
        assertEquals(2 + 1 + CreditPlan.overhead(1, -1), CreditPlan.cost(p, active, -1), 1e-9);
        p.kgEvents = 12; // KG yalnızca futbol maçları için sayılır
        assertEquals(2 + 1 + 3 + CreditPlan.overhead(1, -1), CreditPlan.cost(p, active, -1), 1e-9);
    }
}
