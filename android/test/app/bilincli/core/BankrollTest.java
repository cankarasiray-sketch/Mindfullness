package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Güncel kasaya göre tutar, otomatik kasa takibi ve kredi planlayıcı. */
public class BankrollTest {
    static final Instant NOW = CoreTest.NOW;

    @Test
    public void stakeFollowsCurrentBankroll() {
        Ledger ledger = new Ledger(new Ledger.MemoryStorage(null), new CoreTest.TestClock());
        ledger.deposit(1000000, "");
        Settings cfg = ledger.settings();
        FeatureTest f = new FeatureTest();
        f.ledger = ledger;
        long cid = f.twoLegCoupon();
        Ledger.Coupon c = ledger.coupon(cid);
        c.fraction = 0.02;
        c.scale = 0.5;
        assertEquals(10000, Daily.stakeNow(ledger, c, cfg)); // 10.000 TL x %2 x 0,5 = 100 TL
        ledger.deposit(1000000, "");
        assertEquals(20000, Daily.stakeNow(ledger, c, cfg)); // kasa büyüdü, tutar da büyüdü
    }

    @Test
    public void autoTrackPlaysWithCheckedOddsAndCanBeUndone() {
        Ledger ledger = new Ledger(new Ledger.MemoryStorage(null), new CoreTest.TestClock());
        ledger.deposit(1000000, "");
        FeatureTest f = new FeatureTest();
        f.ledger = ledger;
        long cid = f.twoLegCoupon();
        Settings cfg = new Settings();
        Map<String, Object> ok = Recheck.run(ledger.coupon(cid), Arrays.asList(FeatureTest.book(1, 2.40, 1, 10),
                FeatureTest.book(2, 2.25, 1, 12)), Arrays.asList(FeatureTest.sharp(1, 0.5, 10), FeatureTest.sharp(2, 0.5, 12)),
                NOW, cfg, ledger.balance());
        long stake = Daily.applyCheck(ledger, cid, ok, cfg);
        assertTrue(stake > 0);
        Ledger.Coupon c = ledger.coupon(cid);
        assertTrue(c.played && c.autoPlayed);
        assertEquals(2.40 * 2.25, c.totalOdds, 1e-12); // kasa güncel oranlarla güncellenir
        assertEquals(1000000 - stake, ledger.balance());
        // tutarsa ödeme güncel oranla
        ledger.setLegResult(cid, 1, Models.WON, "1-0");
        ledger.setLegResult(cid, 2, Models.WON, "2-0");
        ledger.settleCoupon(cid);
        assertEquals(1000000 - stake + Math.round(stake * 2.40 * 2.25), ledger.balance());

        // geri alma: sonuçlanmamış otomatik kupon
        long cid2 = f.twoLegCoupon();
        long s2 = Daily.applyCheck(ledger, cid2, Recheck.run(ledger.coupon(cid2), Arrays.asList(FeatureTest.book(1, 2.40, 1, 10),
                FeatureTest.book(2, 2.25, 1, 12)), Arrays.asList(FeatureTest.sharp(1, 0.5, 10), FeatureTest.sharp(2, 0.5, 12)),
                NOW, cfg, ledger.balance()), cfg);
        long before = ledger.balance();
        ledger.unmarkPlayed(cid2);
        assertEquals(before + s2, ledger.balance());
        assertFalse(ledger.coupon(cid2).played);

        // oynanamaz kontrol ya da elle mod: oynanmaz
        Map<String, Object> bad = new LinkedHashMap<>(ok);
        bad.put("playable", false);
        assertEquals(0, Daily.applyCheck(ledger, cid2, bad, cfg));
        cfg.autoTrack = false;
        assertEquals(0, Daily.applyCheck(ledger, cid2, ok, cfg));
    }

    @Test
    public void creditPlanKeepsEverythingWhenBudgetAllows() {
        Settings s = new Settings(); // 6 lig, yalnız MS: günde 6 + 13 kredi
        CreditPlan.Plan p = CreditPlan.plan(s, 900L, 100L, LocalDate.of(2026, 10, 1), null);
        assertFalse(p.narrowed);
        assertEquals(6, p.leagues.size());
        assertEquals(31, p.daysLeft); // 1 Ekim yenilendi: sonraki 1 Kasım
        // ücretsiz plan (500): aynı ayarlar sığmaz, ligler daraltılır
        CreditPlan.Plan free = CreditPlan.plan(s, 450L, 50L, LocalDate.of(2026, 10, 1), null);
        assertTrue(free.narrowed);
        assertTrue(free.cost <= free.budget);
    }

    @Test
    public void creditPlanCutsLowestValueFirst() {
        Settings s = new Settings();
        s.totals = true;
        s.kgEvents = 8;
        s.radarScans = 2;
        Map<String, Double> yield = new LinkedHashMap<>();
        yield.put("soccer_turkey_super_league", 3.0);
        yield.put("soccer_epl", 2.0);
        yield.put("soccer_france_ligue_one", 0.1); // en az fırsat
        // kalan 400 kredi, 21 gün: günde ~18 kredi
        CreditPlan.Plan p = CreditPlan.plan(s, 400L, 100L, LocalDate.of(2026, 10, 11), yield);
        assertTrue(p.narrowed);
        assertEquals(0, p.kgEvents);
        assertEquals(0, p.radarScans);
        assertFalse(p.totals);
        assertTrue(p.cost <= p.budget);
        assertFalse(p.leagues.contains("soccer_france_ligue_one"));
        assertTrue(p.leagues.contains("soccer_turkey_super_league"));
        // her kalem için yalnızca son durum, okunur lig adıyla
        assertEquals(Arrays.asList("Karşılıklı Gol bugünlük kapatıldı", "Radar bugünlük kapatıldı",
                "2,5 Alt/Üst bugünlük kapatıldı", "Fransa Ligue 1 bugünlük çıkarıldı (en az fırsat çıkaran lig)"),
                p.notes.subList(0, 4));
        Settings eff = CreditPlan.effective(s, p);
        assertEquals(p.leagues, eff.leagues);
        assertFalse(eff.totals);
        assertTrue(s.totals); // kullanıcının ayarı değişmez
        // yalnızca gerektiği kadar kısar: bol kredide KG korunur
        CreditPlan.Plan rich = CreditPlan.plan(s, 2000L, 0L, LocalDate.of(2026, 10, 11), yield);
        assertFalse(rich.narrowed);
        assertEquals(8, rich.kgEvents);
        // otomatik kapalıysa daraltma yok
        s.creditAuto = false;
        assertFalse(CreditPlan.plan(s, 50L, 450L, LocalDate.of(2026, 10, 11), yield).narrowed);
    }

    @Test
    public void creditResetDayAndYield() {
        assertEquals(14, CreditPlan.daysLeft(LocalDate.of(2026, 10, 1), 15));
        assertEquals(1, CreditPlan.daysLeft(LocalDate.of(2026, 10, 14), 15));
        assertEquals(31, CreditPlan.daysLeft(LocalDate.of(2026, 10, 15), 15)); // bugün yenilendi: sonraki ay
        Map<String, Object> memory = new LinkedHashMap<>();
        Map<String, Object> view = new LinkedHashMap<>();
        List<Object> values = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("sport", "soccer_epl");
            values.add(v);
        }
        view.put("values", values);
        CreditPlan.updateYield(memory, view, Arrays.asList("soccer_epl", "soccer_italy_serie_a"));
        Map<String, Double> y = CreditPlan.yieldOf(memory);
        assertEquals(0.7 + 0.9, y.get("soccer_epl"), 1e-12);
        assertEquals(0.7, y.get("soccer_italy_serie_a"), 1e-12);
    }
}
