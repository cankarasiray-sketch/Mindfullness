package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.bilincli.core.Ledger.Coupon;
import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.Candidate;
import app.bilincli.core.Models.Proposal;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/** Oynamadan önce kontrol, kapanış oranı (CLV), aylık beklenti ve risk profilleri. */
public class FeatureTest {
    static final Instant NOW = CoreTest.NOW;

    CoreTest.TestClock clock;
    Ledger ledger;

    @Before
    public void setUp() {
        clock = new CoreTest.TestClock();
        ledger = new Ledger(new Ledger.MemoryStorage(null), clock);
        ledger.deposit(1000000, "");
    }

    /** 2.5 öncesi kupon eşiği (%20): iki maçlık örnek kupon (0,5 x 0,5) bu eşiğe göre kurulu. */
    static Settings legacy() {
        Settings s = new Settings();
        s.minWinProb = 0.20;
        return s;
    }

    static BookEvent book(int i, double odds, int mbs, int hours) {
        return new BookEvent("b" + i, "Ev " + i, "Dep " + i, NOW.plusSeconds(hours * 3600L), "Lig", mbs,
                CoreTest.ms(odds, 3.4, 4.0), String.valueOf(i));
    }

    static SharpEvent sharp(int i, double p1, int hours) {
        return new SharpEvent("s" + i, "lig", "Ev " + i, "Dep " + i, NOW.plusSeconds(hours * 3600L),
                CoreTest.ms(p1, 0.25, 1 - p1 - 0.25), "pinnacle");
    }

    /** İki maçlık kupon: sabah oranları 2,30 ve 2,20, adil olasılık 0,50. */
    long twoLegCoupon() {
        List<Candidate> legs = new ArrayList<>();
        legs.add(new Candidate(book(1, 2.30, 1, 10), sharp(1, 0.50, 10), "MS", "1", 2.30, 0.50));
        legs.add(new Candidate(book(2, 2.20, 1, 12), sharp(2, 0.50, 12), "MS", "1", 2.20, 0.50));
        return ledger.addCoupon(new Proposal(legs, 2.30 * 2.20, 0.25, 0.02, 0.001), "2026-10-01", 20000);
    }

    @Test
    public void recheckStillValuable() {
        long cid = twoLegCoupon();
        Instant later = NOW.plusSeconds(4 * 3600);
        Map<String, Object> r = Recheck.run(ledger.coupon(cid), Arrays.asList(book(1, 2.35, 1, 10), book(2, 2.20, 1, 12)),
                Arrays.asList(sharp(1, 0.50, 10), sharp(2, 0.49, 12)), later, legacy(), ledger.balance());
        assertTrue(Json.str(r, "verdict"), Json.bool(r, "playable", false));
        assertEquals(2.35 * 2.20, Json.dbl(r, "odds", 0), 1e-12);
        assertEquals(0.50 * 0.49, Json.dbl(r, "prob", 0), 1e-12);
        assertTrue(Json.lng(r, "stake", 0) > 0);
        assertTrue(Recheck.isFresh(r, later.plusSeconds(60)));
        assertFalse(Recheck.isFresh(r, later.plusSeconds(Recheck.FRESH_S + 1)));
    }

    @Test
    public void recheckDetectsLostEdgeStartedMissingAndMbs() {
        long cid = twoLegCoupon();
        Coupon c = ledger.coupon(cid);
        Settings cfg = new Settings();
        // oran düştü: 0,50 x 1,95 = 0,975 -> avantaj yok
        Map<String, Object> lost = Recheck.run(c, Arrays.asList(book(1, 1.95, 1, 10), book(2, 2.20, 1, 12)),
                Arrays.asList(sharp(1, 0.50, 10), sharp(2, 0.50, 12)), NOW, cfg, ledger.balance());
        assertFalse(Json.bool(lost, "playable", true));
        assertEquals("edge_lost", Json.obj(Json.arr(lost.get("legs")).get(0)).get("status"));
        assertTrue(Json.str(lost, "verdict").contains("avantaj kayboldu"));
        // maç başladı
        Map<String, Object> started = Recheck.run(c, Arrays.asList(book(1, 2.30, 1, 10), book(2, 2.20, 1, 12)),
                Arrays.asList(sharp(1, 0.50, 10), sharp(2, 0.50, 12)), NOW.plusSeconds(10 * 3600), cfg, ledger.balance());
        assertEquals("started", Json.obj(Json.arr(started.get("legs")).get(0)).get("status"));
        // bültenden kalkmış
        Map<String, Object> missing = Recheck.run(c, Collections.singletonList(book(2, 2.20, 1, 12)),
                Arrays.asList(sharp(1, 0.50, 10), sharp(2, 0.50, 12)), NOW, cfg, ledger.balance());
        assertEquals("not_found", Json.obj(Json.arr(missing.get("legs")).get(0)).get("status"));
        // MBS 3'e çıktı, kupon 2 maçlık
        Map<String, Object> mbs = Recheck.run(c, Arrays.asList(book(1, 2.30, 3, 10), book(2, 2.20, 1, 12)),
                Arrays.asList(sharp(1, 0.50, 10), sharp(2, 0.50, 12)), NOW, cfg, ledger.balance());
        assertEquals("mbs", Json.obj(Json.arr(mbs.get("legs")).get(0)).get("status"));
        assertFalse(Json.bool(mbs, "playable", true));
    }

    @Test
    public void playWithCheckedValuesUpdatesOddsAndProbability() {
        long cid = twoLegCoupon();
        Map<String, Object> r = Recheck.run(ledger.coupon(cid), Arrays.asList(book(1, 2.40, 1, 10), book(2, 2.25, 1, 12)),
                Arrays.asList(sharp(1, 0.48, 10), sharp(2, 0.50, 12)), NOW, legacy(), ledger.balance());
        ledger.saveCheck(cid, r);
        List<List<Double>> v = Recheck.checkedValues(ledger.coupon(cid).lastCheck);
        ledger.markPlayed(cid, Json.lng(r, "stake", 0), v.get(0), v.get(1));
        Coupon c = ledger.coupon(cid);
        assertEquals(2.40 * 2.25, c.totalOdds, 1e-12);
        assertEquals(0.48 * 0.50, c.winProb, 1e-12);
        assertEquals(0.48, c.legs.get(0).fairProb, 1e-12);
        // kontrol sonucu kalıcı
        Ledger again = new Ledger(new Ledger.MemoryStorage(ledger.export()), clock);
        assertEquals(Json.str(r, "verdict"), Json.str(again.coupon(cid).lastCheck, "verdict"));
    }

    @Test
    public void closingLineCaptureAndClv() {
        long cid = twoLegCoupon();
        ledger.markPlayed(cid, 20000, null);
        // 10. saatteki maç için pencere: maçtan önceki 40 dk
        assertTrue(Closing.dueLeagues(ledger, NOW).isEmpty());
        assertEquals(NOW.plusSeconds(10 * 3600 - Closing.LEAD_S), Closing.nextCaptureTime(ledger, NOW));
        Instant t = NOW.plusSeconds(10 * 3600 - 15 * 60);
        assertEquals(Collections.singleton("lig"), Closing.dueLeagues(ledger, t));
        assertEquals(1, Closing.capture(ledger, Arrays.asList(sharp(1, 0.46, 10), sharp(2, 0.5, 12)), t));
        assertEquals(0, Closing.capture(ledger, Collections.singletonList(sharp(1, 0.40, 10)), t)); // ikinci kez yazılmaz
        assertEquals(0.46, ledger.coupon(cid).legs.get(0).closingFair, 1e-12);
        assertNull(ledger.coupon(cid).legs.get(1).closingFair);
        double[] clv = ledger.clvSummary();
        assertEquals(2.30 * 0.46 - 1, clv[0], 1e-12); // oynanan ortalama
        assertEquals(1, (int) clv[1]);
        assertEquals(NOW.plusSeconds(12 * 3600 - Closing.LEAD_S), Closing.nextCaptureTime(ledger, t));
    }

    @Test
    public void monthlyOutlook() {
        long a = twoLegCoupon();
        ledger.markPlayed(a, 20000, null);
        ledger.setLegResult(a, 1, Models.LOST, "0-1");
        ledger.settleCoupon(a);
        Map<String, Object> only = Outlook.month(ledger, NOW);
        assertEquals("2026-10", only.get("month"));
        assertEquals(-20000L, only.get("realized"));
        assertEquals(-20000L, only.get("p50"));
        long b = twoLegCoupon();
        ledger.markPlayed(b, 10000, null);
        Map<String, Object> o = Outlook.month(ledger, NOW);
        long p10 = (Long) o.get("p10"), p50 = (Long) o.get("p50"), p90 = (Long) o.get("p90");
        assertTrue(p10 <= p50 && p50 <= p90);
        assertEquals(-30000L, p10); // açık kupon kaybederse
        assertEquals(Math.round(-20000 + 10000 * (2.30 * 2.20) - 10000), p90); // tutarsa
        assertEquals(10000L, o.get("openStake"));
        assertEquals(2L, o.get("played"));
        // geçen ayın kuponları sayılmaz
        assertEquals(0L, Outlook.month(ledger, NOW.plusSeconds(40L * 86400)).get("played"));
    }

    @Test
    public void riskProfiles() {
        Settings s = new Settings();
        assertEquals("temkinli", s.detectProfile());
        s.applyProfile("yuksek");
        assertEquals(0.6, s.kellyMultiplier, 0);
        assertEquals(0.10, s.maxStakeFraction, 0);
        assertNull(s.validate());
        ledger.saveSettings(s);
        assertEquals("yuksek", ledger.settings().profile);
        assertEquals("yuksek", Json.str(ledger.settings().toMap(), "profile"));
        s.kellyMultiplier = 0.4;
        assertEquals("ozel", s.detectProfile());
    }

    @Test
    public void demoRecordsClosingLines() {
        Ledger.MemoryStorage st = new Ledger.MemoryStorage(null);
        DemoSim.run(st, 20, 1000000, 3, NOW);
        Ledger demo = new Ledger(st, clock);
        double[] clv = demo.clvSummary();
        assertTrue(clv[3] > 0);
        Map<String, Object> state = State.build(demo, true, null);
        assertNotNull(state.get("clv"));
        assertNotNull(state.get("outlook"));
        Json.parse(Json.write(state));
    }
}
