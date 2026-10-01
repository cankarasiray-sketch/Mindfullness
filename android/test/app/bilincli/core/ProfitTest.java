package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.Candidate;
import app.bilincli.core.Models.ScoreResult;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** 1.5: ortak Kelly, kanıt koruması, Çifte Şans, borsa uzlaşısı, boş lig atlama. */
public class ProfitTest {
    static final Instant NOW = CoreTest.NOW;

    static Candidate leg(int i, double odds, double prob, int mbs) {
        BookEvent b = new BookEvent("b" + i, "Ev " + i, "Dep " + i, NOW.plusSeconds(6 * 3600L), "Lig", mbs,
                CoreTest.ms(odds, 3.4, 4.0), String.valueOf(i));
        SharpEvent s = new SharpEvent("s" + i, "lig", "Ev " + i, "Dep " + i, NOW.plusSeconds(6 * 3600L),
                CoreTest.ms(prob, 0.25, 0.75 - prob), "pinnacle");
        return new Candidate(b, s, "MS", "1", odds, prob);
    }

    // ---- ortak Kelly ----

    @Test
    public void singleBetEqualsFractionalKelly() {
        Settings cfg = new Settings();
        Portfolio.Result r = Portfolio.optimize(Collections.singletonList(leg(1, 2.30, 0.50, 1)), cfg, 5, cfg.maxDailyExposure, null);
        assertEquals(1, r.picks.size());
        double kelly = OddsMath.kellyFraction(0.50, 2.30);
        assertEquals(cfg.kellyMultiplier * kelly, r.picks.get(0).fraction, 1e-4);
        assertEquals(1.0, r.picks.get(0).scale(), 1e-3);
        assertTrue(r.growth > 0);
    }

    @Test
    public void jointSolutionIsOptimal() {
        // iki bağımsız değerli seçim: tekler ve çiftli birlikte; tam Kelly çözümünde KKT koşulları
        List<Candidate> pool = Arrays.asList(leg(1, 2.30, 0.50, 1), leg(2, 2.10, 0.55, 1));
        double[] pi = Portfolio.stateProbs(pool);
        int[] mk = {1, 2, 3};
        double[] od = {2.30, 2.10, 2.30 * 2.10};
        double[] f = Portfolio.solve(2, pi, mk, od, 1.0, 0.95);
        double[] g = Portfolio.gradient(2, pi, mk, od, f);
        for (int b = 0; b < f.length; b++) {
            if (f[b] > 1e-6) assertEquals("bet " + b, 0, g[b], 1e-5);
            else assertTrue("bet " + b, g[b] < 1e-5);
        }
        // ortak çözüm, aynı bahisleri ayrı ayrı Kelly ile oynamaktan daha fazla büyür
        double[] separate = {OddsMath.kellyFraction(0.50, 2.30), OddsMath.kellyFraction(0.55, 2.10), OddsMath.kellyFraction(0.275, 2.30 * 2.10)};
        assertTrue(Portfolio.growth(2, pi, mk, od, f) > Portfolio.growth(2, pi, mk, od, separate));
    }

    @Test
    public void limitsAndMbsRespected() {
        Settings cfg = new Settings();
        cfg.applyProfile("yuksek");
        List<Candidate> cands = new ArrayList<>();
        for (int i = 1; i <= 8; i++) cands.add(leg(i, 2.2 + 0.05 * i, 0.50, i == 3 ? 2 : 1));
        Portfolio.Result r = Portfolio.optimize(cands, cfg, 5, cfg.maxDailyExposure, null);
        assertFalse(r.picks.isEmpty());
        assertTrue(r.picks.size() <= 5);
        double total = 0, prev = 1;
        for (Portfolio.Pick p : r.picks) {
            assertTrue(p.fraction <= cfg.maxStakeFraction + 1e-9);
            assertTrue(p.fraction <= p.proposal.stakeFraction + 1e-9);
            assertTrue(p.fraction <= prev + 1e-12); // en büyük önce
            prev = p.fraction;
            total += p.fraction;
            // MBS 2 olan maç tek başına oynanamaz
            if (p.proposal.legs.size() == 1) assertFalse("b3".equals(p.proposal.legs.get(0).book.ref));
        }
        assertTrue(total <= cfg.maxDailyExposure + 1e-9);
        // bugünkü kuponlardaki maçlar dışarıda bırakılır, kalan sınır uygulanır
        Portfolio.Result rest = Portfolio.optimize(cands, cfg, 2, 0.03, Arrays.asList("b1", "b2", "b4"));
        double t2 = 0;
        for (Portfolio.Pick p : rest.picks) {
            t2 += p.fraction;
            for (Candidate c : p.proposal.legs) assertFalse(Arrays.asList("b1", "b2", "b4").contains(c.book.ref));
        }
        assertTrue(rest.picks.size() <= 2);
        assertTrue(t2 <= 0.03 + 1e-9);
        assertTrue(Portfolio.optimize(cands, cfg, 0, 0.1, null).picks.isEmpty());
    }

    @Test
    public void dailyCouponsUsePortfolio() {
        Ledger ledger = new Ledger(new Ledger.MemoryStorage(null), new CoreTest.TestClock());
        ledger.deposit(1000000, ""); // 10.000 TL
        final List<BookEvent> book = new ArrayList<>();
        final List<SharpEvent> sharp = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            book.add(FeatureTest.book(i, 2.25 + 0.05 * i, 1, 8 + i));
            sharp.add(FeatureTest.sharp(i, 0.50, 8 + i));
        }
        Daily.Sources src = new Daily.Sources() {
            public List<BookEvent> book() { return book; }
            public List<SharpEvent> sharp() { return sharp; }
            public Map<String, ScoreResult> scores(Set<String> k) { return new LinkedHashMap<>(); }
        };
        Daily.Result r = Daily.generate(ledger, src, false, false);
        Settings cfg = ledger.settings();
        assertTrue(r.couponIds.size() > 1);
        assertTrue(r.couponIds.size() <= cfg.maxCouponsPerDay);
        double exposure = 0;
        long prevStake = Long.MAX_VALUE;
        for (Long id : r.couponIds) {
            Ledger.Coupon c = ledger.coupon(id);
            assertTrue(c.scale > 0 && c.scale <= 1.0);
            exposure += c.fraction * c.scale;
            assertTrue(c.suggestedStake >= 5000); // asgari kupon bedeli 50 TL
            assertTrue(c.suggestedStake <= prevStake);
            prevStake = c.suggestedStake;
            assertEquals(c.suggestedStake, Daily.stakeNow(ledger, c, cfg));
        }
        assertTrue(exposure <= cfg.maxDailyExposure + 1e-9);
    }

    // ---- kanıt koruması ----

    static List<EdgeCalibration.Obs> obs(String market, int n, double pred, double real) {
        List<EdgeCalibration.Obs> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new EdgeCalibration.Obs(market, 2.0, (1 + pred) / 2.0, (1 + real) / 2.0));
        return out;
    }

    @Test
    public void edgeGuardOnlyActsOnWeakEvidence() {
        assertEquals(1.0, EdgeCalibration.compute(new ArrayList<EdgeCalibration.Obs>()).ratios().get("*"), 1e-12);
        // avantajın çoğu kapanışta korunuyor: dokunulmaz
        assertEquals(1.0, EdgeCalibration.compute(obs("MS", 100, 0.10, 0.06)).ratios().get("*"), 1e-12);
        // kapanışta avantaj yok: (0 + 30·0,10) / (100·0,10 + 30·0,10) = 0,23 -> çarpan 0,46
        EdgeCalibration.Calib none = EdgeCalibration.compute(obs("MS", 100, 0.10, 0.0));
        assertEquals(3.0 / 13.0, none.segments.get("*").ratio, 1e-9);
        assertEquals(6.0 / 13.0, none.ratios().get("*"), 1e-9);
        // kapanış aleyhte: en az FLOOR
        assertEquals(EdgeCalibration.FLOOR, EdgeCalibration.compute(obs("MS", 300, 0.10, -0.03)).ratios().get("*"), 1e-12);
        // pazar bazında: KG zayıf, MS sağlam
        List<EdgeCalibration.Obs> mixed = new ArrayList<>(obs("MS", 200, 0.08, 0.07));
        mixed.addAll(obs("KG", 80, 0.08, -0.02));
        Map<String, Double> r = EdgeCalibration.compute(mixed).ratios();
        assertEquals(1.0, r.get("MS"), 1e-12);
        assertTrue(r.get("KG") < 0.6);
        assertEquals(r.get("*"), EdgeCalibration.ratio(r, "AU25"), 0); // ölçümü olmayan pazar: genel
        // p' = r·p + (1-r)/oran  =>  avantaj r ile küçülür
        double p = EdgeCalibration.adjust(0.5, 2.3, 0.4);
        assertEquals(0.4 * (0.5 * 2.3 - 1), p * 2.3 - 1, 1e-12);
    }

    @Test
    public void guardShrinksDecisionButLedgerKeepsRawFair() {
        Ledger ledger = new Ledger(new Ledger.MemoryStorage(null), new CoreTest.TestClock());
        Settings cfg = new Settings();
        cfg.edgeRatios = new LinkedHashMap<>();
        cfg.edgeRatios.put("*", 0.5);
        List<Models.Pair> pairs = Matching.match(Collections.singletonList(FeatureTest.book(1, 2.40, 1, 6)),
                Collections.singletonList(FeatureTest.sharp(1, 0.50, 6)));
        List<Candidate> good = Engine.buildCandidates(pairs, NOW, cfg).get(0);
        assertEquals(1, good.size());
        Candidate c = good.get(0);
        assertEquals(0.50, c.rawProb, 1e-12);
        assertEquals(0.5 * 0.50 + 0.5 / 2.40, c.prob, 1e-12);
        assertEquals(0.5 * (0.5 * 2.4 - 1), c.ev(), 1e-12);
        long id = ledger.addCoupon(new Models.Proposal(Collections.singletonList(c), 2.40, c.prob, 0.02, 0.001), "2026-10-01", 10000);
        assertEquals(0.50, ledger.coupon(id).legs.get(0).fairProb, 1e-12); // kapanış ölçümü ham tahminle
        // zayıf kanıt: eşiği ancak geçen seçim elenir
        cfg.edgeRatios.put("*", 0.25);
        List<Models.Pair> weak = Matching.match(Collections.singletonList(FeatureTest.book(2, 2.15, 1, 6)),
                Collections.singletonList(FeatureTest.sharp(2, 0.50, 6)));
        assertTrue(Engine.buildCandidates(weak, NOW, cfg).get(0).isEmpty());
    }

    // ---- Çifte Şans ----

    @Test
    public void doubleChanceDiscoveredAndSettled() {
        double[][] ms = {{0.50, 0.27, 0.23}, {0.30, 0.30, 0.40}, {0.60, 0.22, 0.18}, {0.45, 0.28, 0.27}, {0.35, 0.25, 0.40}};
        List<BookEvent> book = new ArrayList<>();
        List<SharpEvent> sharp = new ArrayList<>();
        for (int i = 0; i < ms.length; i++) {
            double[] p = ms[i];
            Map<String, Map<String, Double>> fair = CoreTest.ms(p[0], p[1], p[2]);
            fair.put("CS", OddsApi.doubleChance(fair.get("MS")));
            sharp.add(new SharpEvent("s" + i, "lig", "Ev " + i, "Dep " + i, NOW.plusSeconds(6 * 3600L), fair, "pinnacle"));
            Map<String, Map<String, Double>> odds = CoreTest.ms(1 / (p[0] * 1.08), 1 / (p[1] * 1.08), 1 / (p[2] * 1.08));
            // bültende sıra: N1 = X2, N2 = 1X, N3 = 12 (Çifte Şans)
            Map<String, Double> raw = new LinkedHashMap<>();
            raw.put("1", 1 / ((p[1] + p[2]) * 1.06));
            raw.put("2", 1 / ((p[0] + p[1]) * 1.06));
            raw.put("3", 1 / ((p[0] + p[2]) * 1.06));
            odds.put(Calibration.RAW_CS + "7", raw);
            // yanıltıcı üç seçenekli pazar (ör. İlk Yarı Sonucu)
            Map<String, Double> iy = new LinkedHashMap<>();
            iy.put("1", 2.6);
            iy.put("2", 2.1);
            iy.put("3", 3.8);
            odds.put(Calibration.RAW_CS + "9", iy);
            book.add(new BookEvent("b" + i, "Ev " + i, "Dep " + i, NOW.plusSeconds(6 * 3600L), "Lig", 1, odds, String.valueOf(i)));
        }
        Map<String, Object> memory = new LinkedHashMap<>();
        Map<String, Object> rep = Calibration.apply(book, sharp, memory);
        assertTrue(String.valueOf(rep.get("CS")), String.valueOf(rep.get("CS")).startsWith("doğrulandı (pazar 7"));
        Map<String, Double> cs = book.get(0).odds.get("CS");
        assertEquals(1 / ((0.50 + 0.27) * 1.06), cs.get("1X"), 1e-12);
        assertEquals(1 / ((0.50 + 0.23) * 1.06), cs.get("12"), 1e-12);
        assertEquals(1 / ((0.27 + 0.23) * 1.06), cs.get("X2"), 1e-12);
        for (BookEvent b : book) for (String k : b.odds.keySet()) assertFalse(k.startsWith(Calibration.RAW_CS));
        assertTrue(Calibration.summary(rep).contains("ÇŞ ✓"));
        assertEquals(Models.WON, Settlement.legResult("CS", "1X", 1, 1));
        assertEquals(Models.LOST, Settlement.legResult("CS", "12", 1, 1));
        assertEquals(Models.WON, Settlement.legResult("CS", "X2", 0, 2));
        assertEquals(Models.LOST, Settlement.legResult("CS", "1X", 0, 2));
        assertEquals("ÇŞ 1-X", Models.outcomeLabel("CS", "1X"));
        Map<String, Double> dc = OddsApi.doubleChance(CoreTest.ms(0.5, 0.3, 0.2).get("MS"));
        assertEquals(0.8, dc.get("1X"), 1e-12);
        assertEquals(0.7, dc.get("12"), 1e-12);
        assertEquals(0.5, dc.get("X2"), 1e-12);
    }

    @Test
    public void nesineKeepsThreeWayCandidates() throws Exception {
        String json = "{\"sg\":{\"EA\":[{\"TYPE\":1,\"HN\":\"A\",\"AN\":\"B\",\"D\":\"03.10.2026\",\"T\":\"20:00\",\"C\":123,\"MBS\":1,"
                + "\"MA\":[{\"MTID\":1,\"MBS\":1,\"OCA\":[{\"N\":1,\"O\":2.1},{\"N\":2,\"O\":3.3},{\"N\":3,\"O\":3.4}]},"
                + "{\"MTID\":7,\"MBS\":2,\"OCA\":[{\"N\":1,\"O\":1.3},{\"N\":2,\"O\":1.32},{\"N\":3,\"O\":1.65}]},"
                + "{\"MTID\":11,\"SOV\":-1,\"OCA\":[{\"N\":1,\"O\":3.1},{\"N\":2,\"O\":3.5},{\"N\":3,\"O\":2.0}]}]}]}}";
        BookEvent b = Nesine.parse(Json.parse(json)).get(0);
        assertNotNull(b.odds.get(Calibration.RAW_CS + "7"));
        assertEquals(2, b.mbsFor(Calibration.RAW_CS + "7"));
        assertNull(b.odds.get(Calibration.RAW_CS + "11")); // handikaplı (SOV) pazar aday değil
    }

    // ---- Pinnacle + borsa ----

    static String bk(String key, double h, double d, double a) {
        return "{\"key\":\"" + key + "\",\"markets\":[{\"key\":\"h2h\",\"outcomes\":[{\"name\":\"A\",\"price\":" + h
                + "},{\"name\":\"Draw\",\"price\":" + d + "},{\"name\":\"B\",\"price\":" + a + "}]}]}";
    }

    static List<SharpEvent> parse(String... books) throws Exception {
        Settings cfg = new Settings();
        cfg.oddsApiKey = "k";
        return new OddsApi(null, cfg).parseOdds(Json.parse("[{\"id\":\"e1\",\"commence_time\":\"2026-10-03T17:00:00Z\","
                + "\"home_team\":\"A\",\"away_team\":\"B\",\"bookmakers\":[" + String.join(",", books) + "]}]"), "x");
    }

    @Test
    public void pinnacleAndExchangeConsensus() throws Exception {
        String pin = bk("pinnacle", 2.00, 3.60, 3.90);
        SharpEvent alone = parse(pin).get(0);
        // likit borsa (toplam ima olasılığı ~%101): ağırlıklı ortalama
        SharpEvent both = parse(pin, bk("betfair_ex_eu", 2.10, 3.70, 4.10)).get(0);
        assertTrue(both.source, both.source.contains("pinnacle+borsa"));
        double[] ex = OddsMath.devigPower(new double[] {2.10, 3.70, 4.10});
        assertEquals(0.6 * alone.fair.get("MS").get("1") + 0.4 * ex[0], both.fair.get("MS").get("1"), 1e-9);
        assertEquals(1.0, both.fair.get("MS").get("1") + both.fair.get("MS").get("X") + both.fair.get("MS").get("2"), 1e-12);
        assertNotNull(both.fair.get("CS")); // Çifte Şans türetilir
        // likit olmayan borsa (toplam %110): yok sayılır
        SharpEvent thin = parse(pin, bk("betfair_ex_eu", 1.80, 3.20, 3.60)).get(0);
        assertEquals(alone.fair.get("MS").get("1"), thin.fair.get("MS").get("1"), 1e-12);
        // belirgin ayrışma (biri bayat): pazar kullanılmaz
        assertTrue(parse(pin, bk("betfair_ex_eu", 2.60, 3.60, 2.95)).isEmpty());
    }

    // ---- boş lig atlama ----

    @Test
    public void idleLeaguesSkippedWithoutSpendingCredits() throws Exception {
        final List<String> calls = new ArrayList<>();
        Http http = new Http() {
            public Response get(String url, Map<String, String> headers) throws ProviderException {
                calls.add(url);
                Map<String, String> h = new LinkedHashMap<>();
                if (url.contains("/sports/bos/events")) return new Response(200, "[]", h);
                if (url.contains("/sports/hata/events")) throw new ProviderException("503");
                if (url.contains("/events")) return new Response(200, "[{\"id\":\"e\"}]", h);
                h.put("x-requests-remaining", "480");
                return new Response(200, "[]", h);
            }
        };
        Settings cfg = new Settings();
        cfg.oddsApiKey = "k";
        OddsApi api = new OddsApi(http, cfg);
        api.fetchEvents(Arrays.asList("dolu", "bos", "hata"), NOW);
        assertEquals(Collections.singletonList("bos"), api.idle);
        int odds = 0;
        for (String u : calls) {
            if (u.contains("/odds")) odds++;
            if (u.contains("/events")) assertTrue(u, u.contains("commenceTimeFrom=2026-") && u.contains("commenceTimeTo=2026-"));
        }
        assertEquals(2, odds); // dolu + hata (liste alınamadıysa temkinli: oran çekilir)
        assertFalse(calls.toString().contains("/sports/bos/odds"));
        assertEquals("480", api.remaining);
        // kontrol ve kapanış çekimleri maç listesine bakmaz
        calls.clear();
        api.fetchEvents(Collections.singletonList("bos"));
        assertEquals(1, calls.size());
    }
}
