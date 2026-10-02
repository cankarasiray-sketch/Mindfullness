package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

/** Düşen oran radarı, değer listesi, gün içi tarama ve ROI analizi. */
public class RadarTest {
    static final Instant NOW = CoreTest.NOW;

    CoreTest.TestClock clock;
    Ledger ledger;
    Ledger.MemoryStorage ledgerStore, radarStore;
    Radar radar;

    @Before
    public void setUp() {
        clock = new CoreTest.TestClock();
        ledgerStore = new Ledger.MemoryStorage(null);
        ledger = new Ledger(ledgerStore, clock);
        ledger.deposit(1000000, "");
        radarStore = new Ledger.MemoryStorage(null);
        radar = new Radar(radarStore);
    }

    static BookEvent book(int i, double o1) {
        return FeatureTest.book(i, o1, 1, 10);
    }

    static SharpEvent sharp(int i, double p1) {
        return FeatureTest.sharp(i, p1, 10);
    }

    @Test
    public void valueListSortedAndMarked() {
        // maç 1: 0,50 x 2,30 = +%15; maç 2: 0,50 x 2,10 = +%5; maç 3: avantaj yok
        radar.update(Arrays.asList(book(1, 2.30), book(2, 2.10), book(3, 1.80)),
                Arrays.asList(sharp(1, 0.50), sharp(2, 0.50), sharp(3, 0.50)), NOW, new Settings(), true);
        List<Object> values = Json.arr(radar.view().get("values"));
        assertEquals(2, values.size());
        Map<String, Object> top = Json.obj(values.get(0));
        assertEquals("b1", top.get("bookRef"));
        assertEquals(0.15, Json.dbl(top, "ev", 0), 1e-12);
        assertEquals(Boolean.TRUE, top.get("inRange"));
        assertEquals(NOW.toString(), radar.view().get("scannedAt"));
    }

    @Test
    public void droppingOddsDetectedOnceWhileIddaaLags() {
        Settings cfg = new Settings();
        radar.update(Collections.singletonList(book(1, 2.00)), Collections.singletonList(sharp(1, 0.48)), NOW, cfg, true);
        assertTrue(Json.arr(radar.view().get("moves")).isEmpty()); // tek ölçüm: hareket yok
        // Pinnacle'da ev sahibi 0,48 -> 0,55 (oran 2,08 -> 1,82); iddaa hâlâ 2,00 => +%10 değer
        Instant later = NOW.plusSeconds(4 * 3600);
        List<Map<String, Object>> fresh = radar.update(Collections.singletonList(book(1, 2.00)),
                Collections.singletonList(sharp(1, 0.55)), later, cfg, true);
        assertEquals(1, fresh.size());
        Map<String, Object> m = fresh.get(0);
        assertEquals("1", m.get("outcome"));
        assertEquals(0.48, Json.dbl(m, "from", 0), 1e-12);
        assertEquals(0.55, Json.dbl(m, "to", 0), 1e-12);
        assertEquals(0.55 * 2.00 - 1, Json.dbl(m, "ev", 0), 1e-12);
        String[] note = Texts.moves(fresh);
        assertTrue(note[1], note[1].contains("Pinnacle 2,08 → 1,82") && note[1].contains("iddaa hâlâ 2,00"));
        // aynı hareket ikinci kez bildirilmez ama listede kalır
        assertTrue(radar.update(Collections.singletonList(book(1, 2.00)), Collections.singletonList(sharp(1, 0.55)),
                later.plusSeconds(600), cfg, true).isEmpty());
        assertEquals(1, Json.arr(radar.view().get("moves")).size());
        // durum kalıcı
        Radar again = new Radar(new Ledger.MemoryStorage(radarStore.read()));
        assertEquals(1, Json.arr(again.view().get("moves")).size());
        // maç geçtikten sonra eski kayıtlar silinir
        radar.update(new ArrayList<BookEvent>(), new ArrayList<SharpEvent>(), NOW.plusSeconds(3 * 86400), cfg, true);
        assertTrue(Json.arr(radar.view().get("moves")).isEmpty());
    }

    @Test
    public void smallMovesIgnored() {
        Settings cfg = new Settings();
        radar.update(Collections.singletonList(book(1, 2.00)), Collections.singletonList(sharp(1, 0.50)), NOW, cfg, true);
        radar.update(Collections.singletonList(book(1, 2.00)), Collections.singletonList(sharp(1, 0.52)),
                NOW.plusSeconds(3600), cfg, true);
        assertTrue(Json.arr(radar.view().get("moves")).isEmpty());
    }

    @Test
    public void intradayRebuildsUnplayedPlanAndAddsBesidePlayed() {
        // sabah: avantaj yok -> pas
        ledger.recordRun(Fmt.dayKey(NOW), "pas", "avantaj yok", (Long) null, "");
        List<BookEvent> book = Collections.singletonList(book(1, 2.30));
        List<SharpEvent> sharp = Collections.singletonList(sharp(1, 0.50));
        Daily.Intraday r = Daily.intraday(ledger, book, sharp, radar);
        assertNotNull(r.newCouponId);
        assertEquals("kupon", ledger.runFor(Fmt.dayKey(NOW)).decision);
        assertTrue(ledger.runFor(Fmt.dayKey(NOW)).reason.contains("Gün içi"));
        // aynı seçimler: yeni kupon yok
        assertNull(Daily.intraday(ledger, book, sharp, radar).newCouponId);
        // oynanmış kupon değiştirilmez; kalan günlük sınır içinde başka maçtan ek kupon kurulur
        ledger.markPlayed(r.newCouponId, 10000, null);
        List<BookEvent> book2 = Arrays.asList(book(1, 2.30), book(2, 2.40));
        List<SharpEvent> sharp2 = Arrays.asList(sharp(1, 0.50), sharp(2, 0.50));
        Daily.Intraday more = Daily.intraday(ledger, book2, sharp2, radar);
        assertNotNull(more.newCouponId);
        Ledger.Run run = ledger.runFor(Fmt.dayKey(NOW));
        assertEquals(Arrays.asList(r.newCouponId, more.newCouponId), run.ids());
        assertTrue(run.reason, run.reason.contains("1 ek kupon"));
        assertTrue(ledger.coupon(r.newCouponId).played);
        for (Ledger.Leg l : ledger.coupon(more.newCouponId).legs) assertEquals("b2", l.bookRef);
        // aynı veriyle tekrar: yeni kupon yok (iki maç da bugünün kuponlarında)
        assertNull(Daily.intraday(ledger, book2, sharp2, radar).newCouponId);
    }

    @Test
    public void replacedPlanIsNeverAutoPlayed() {
        // sabah planı: maç 1 (oynanmadı); gün içinde oranlar değişti, plan maç 2 ile yenilendi
        CoreTest.TestClock c = clock;
        Daily.Intraday first = Daily.intraday(ledger, Collections.singletonList(book(1, 2.30)),
                Collections.singletonList(sharp(1, 0.50)), radar);
        long old = first.newCouponId;
        Daily.Intraday next = Daily.intraday(ledger, Collections.singletonList(book(2, 2.40)),
                Collections.singletonList(sharp(2, 0.50)), radar);
        assertNotNull(next.newCouponId);
        assertTrue(ledger.coupon(old).superseded);
        assertFalse(ledger.coupon(next.newCouponId).superseded);
        // eski kupon maç öncesi kontrole, kapanışa ve sonuçlandırmaya girmez
        c.now = NOW.plusSeconds(10 * 3600 - 80 * 60); // maçtan 80 dk önce
        for (Ledger.Coupon due : Recheck.allDueForPrecheck(ledger, c.now)) assertTrue(due.id != old);
        for (Ledger.Coupon open : ledger.openCoupons()) assertTrue(open.id != old);
        java.util.Map<String, Object> ok = new java.util.LinkedHashMap<>();
        ok.put("playable", true);
        ok.put("stake", 10000L);
        ok.put("legs", new ArrayList<Object>());
        assertEquals(0, Daily.applyCheck(ledger, old, ok, ledger.settings()));
        // elle "oynadım" denirse yeniden açık kupon olur (Bilyoner'de oynanmış olabilir)
        ledger.markPlayed(old, 10000, null);
        assertFalse(ledger.coupon(old).superseded);
        boolean open = false;
        for (Ledger.Coupon o : ledger.openCoupons()) open |= o.id == old;
        assertTrue(open);
        // kalıcı
        Ledger again = new Ledger(new Ledger.MemoryStorage(ledgerStore.read()), clock);
        assertFalse(again.coupon(old).superseded);
        assertTrue(again.coupon(next.newCouponId).played == false);
    }

    @Test
    public void radarSettings() {
        Settings s = new Settings();
        s.basketHandicap = false; // lig başına tarama başına 1 kredi
        assertEquals(0, s.radarHours().length);
        int base = s.estimatedMonthlyCredits();
        s.radarScans = 2;
        assertEquals(2, s.radarHours().length);
        // her tarama, o gün oynayan ligler kadar kredi (lig başına ~%40 gün)
        int n = s.leagues.size();
        assertEquals(base - Math.round(30 * n * Settings.ACTIVE_SHARE) + Math.round(30 * n * Settings.ACTIVE_SHARE * 3),
                s.estimatedMonthlyCredits());
        assertNull(s.validate());
        s.radarScans = 3;
        assertNotNull(s.validate());
        s.radarScans = 4;
        ledger.saveSettings(s);
        assertEquals(4, ledger.settings().radarScans);
    }

    private long played(int legs, double odds, boolean won, String league) {
        List<Candidate> cs = new ArrayList<>();
        for (int i = 0; i < legs; i++) {
            BookEvent b = new BookEvent("x" + i, "E" + i, "D" + i, NOW.plusSeconds(3600), league, 1,
                    CoreTest.ms(odds, 3.4, 4.0), "");
            cs.add(new Candidate(b, FeatureTest.sharp(i, 0.5, 1), "MS", "1", odds, 0.5));
        }
        long id = ledger.addCoupon(new Proposal(cs, Math.pow(odds, legs), Math.pow(0.5, legs), 0.02, 0.001), "2026-10-01", 10000);
        ledger.markPlayed(id, 10000, null);
        for (int i = 1; i <= legs; i++) ledger.setLegResult(id, i, won ? Models.WON : Models.LOST, null);
        clock.now = clock.now.plusSeconds(3600);
        ledger.settleCoupon(id);
        return id;
    }

    @Test
    public void roiAnalysisGroupsAndDrawdown() {
        played(1, 2.0, true, "Süper Lig");   // +100 TL
        played(1, 2.0, false, "Süper Lig");  // -100 TL
        played(2, 2.0, false, "Premier Lig"); // -100 TL (oran 4,00)
        Map<String, Object> a = Analysis.build(ledger);
        Map<String, Object> one = Json.obj(Json.arr(a.get("byLegs")).get(0));
        assertEquals("1 maç", one.get("key"));
        assertEquals(2L, one.get("n"));
        assertEquals(1L, one.get("won"));
        assertEquals(0L, one.get("pl"));
        Map<String, Object> two = Json.obj(Json.arr(a.get("byLegs")).get(1));
        assertEquals(-10000L, two.get("pl"));
        assertEquals(-1.0, Json.dbl(two, "roi", 0), 1e-12);
        Map<String, Object> band = Json.obj(Json.arr(a.get("byOdds")).get(2)); // 3,00–4,99
        assertEquals(1L, band.get("n"));
        boolean premier = false;
        for (Object o : Json.arr(a.get("byLeague"))) {
            Map<String, Object> l = Json.obj(o);
            if ("Premier Lig".equals(l.get("key"))) {
                premier = true;
                assertEquals(2L, l.get("n")); // iki maç, K/Z eşit bölünür
                assertEquals(-10000L, l.get("pl"));
            }
        }
        assertTrue(premier);
        Map<String, Object> dd = Json.obj(a.get("drawdown"));
        // K/Z: +100, 0, -100 TL; zirve +100 TL, kasa zirvede 10.100 TL -> düşüş 200 / 10.100
        assertEquals(20000L, dd.get("maxAmount"));
        assertEquals(20000.0 / 1010000, Json.dbl(dd, "max", 0), 1e-12);
        // para çekme düşüş sayılmaz
        clock.now = clock.now.plusSeconds(3600);
        ledger.withdraw(500000, "");
        assertEquals(20000.0 / 1010000, Json.dbl(Json.obj(Analysis.build(ledger).get("drawdown")), "max", 0), 1e-12);
        assertFalse(Json.arr(a.get("byMonth")).isEmpty());
    }

    @Test
    public void demoSummaryHasValueList() {
        Map<String, Object> out = DemoSim.run(new Ledger.MemoryStorage(null), 10, 1000000, 5, NOW);
        assertNotNull(Json.obj(out.get("radar")));
        Json.parse(Json.write(out));
    }
}
