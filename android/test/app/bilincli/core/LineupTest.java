package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 2.0: kadro saati taraması, gece maçları, çoklu anahtar (10) ve gece sessizliği ayarları. */
public class LineupTest {
    static final Instant NOW = CoreTest.NOW;

    static Instant tr(int day, int h, int m) {
        return LocalDate.of(2026, 10, day).atTime(h, m).atZone(Fmt.TR).toInstant();
    }

    @Test
    public void nightGamesGetNoScanAfterTheyAreOver() {
        LocalDate day = LocalDate.of(2026, 10, 3);
        List<Instant> ko = Arrays.asList(tr(3, 2, 0), tr(3, 3, 30), tr(3, 19, 0)); // NBA gece + akşam maçı
        assertEquals(Collections.singletonList(tr(3, 16, 30)), ScanPlan.times(ko, 2, day));
        assertNull(ScanPlan.times(Arrays.asList(tr(3, 2, 0), tr(3, 4, 0)), 2, day)); // yalnızca gece maçı: sabit saatler
    }

    @Test
    public void lineupSlotsGroupKickoffsAndCarryTheirLeagues() {
        LocalDate day = LocalDate.of(2026, 10, 3);
        List<Instant> ko = Arrays.asList(tr(3, 14, 0), tr(3, 16, 0), tr(3, 14, 15), tr(3, 19, 0), tr(3, 19, 0),
                tr(3, 21, 45), tr(3, 2, 0), tr(4, 13, 0), tr(3, 20, 0));
        List<String> lg = Arrays.asList("soccer_epl", "soccer_epl", "soccer_spain_la_liga", "soccer_italy_serie_a",
                "basketball_euroleague", "soccer_uefa_champs_league", "basketball_nba", "soccer_epl", null);
        List<ScanPlan.Slot> s = ScanPlan.lineupSlots(ko, lg, day);
        assertEquals(4, s.size()); // gece NBA, yarının maçı ve lig kodu bilinmeyen maç yok
        assertEquals(tr(3, 13, 15), s.get(0).at);
        assertEquals(new LinkedHashSet<>(Arrays.asList("soccer_epl", "soccer_spain_la_liga")), s.get(0).leagues); // 14:00 ve 14:15
        assertEquals(tr(3, 15, 15), s.get(1).at);
        assertEquals(Collections.singleton("soccer_epl"), s.get(1).leagues);
        assertEquals(tr(3, 18, 15), s.get(2).at);
        assertEquals(new LinkedHashSet<>(Arrays.asList("soccer_italy_serie_a", "basketball_euroleague")), s.get(2).leagues);
        assertEquals(tr(3, 21, 0), s.get(3).at);
        assertTrue(ScanPlan.lineupSlots(ko, lg.subList(0, 3), day).isEmpty()); // paralel listeler uyuşmuyor
        // karar, maçların lig kodlarını taşır
        Engine.Decision d = Engine.decide(Collections.singletonList(FeatureTest.book(1, 2.30, 1, 8)),
                Collections.singletonList(FeatureTest.sharp(1, 0.50, 8)), NOW, new Settings());
        assertEquals(Collections.singletonList("lig"), d.kickoffLeagues);
        assertEquals(d.kickoffs.size(), d.kickoffLeagues.size());
    }

    @Test
    public void partialScanAddsBesideWithoutReplacingThePlanOrValueList() {
        CoreTest.TestClock clock = new CoreTest.TestClock();
        Ledger ledger = new Ledger(new Ledger.MemoryStorage(null), clock);
        ledger.deposit(1000000, "");
        Radar radar = new Radar(new Ledger.MemoryStorage(null));
        // sabah/tam tarama: maç 1 değerli -> kupon (oynanmadı)
        Daily.Intraday first = Daily.intraday(ledger, Collections.singletonList(RadarTest.book(1, 2.30)),
                Collections.singletonList(RadarTest.sharp(1, 0.50)), radar);
        long c1 = first.newCouponId;
        Object valuesBefore = radar.view().get("values");
        // kadro saati: yalnızca maç 2'nin ligi çekildi, maç 2 değerli
        List<BookEvent> book = Arrays.asList(RadarTest.book(1, 2.30), RadarTest.book(2, 2.40));
        List<SharpEvent> sharp = Collections.singletonList(RadarTest.sharp(2, 0.50));
        Daily.Intraday part = Daily.intraday(ledger, book, sharp, radar, null, true);
        assertNotNull(part.newCouponId);
        Ledger.Run run = ledger.runFor(Fmt.dayKey(NOW));
        assertEquals(Arrays.asList(c1, part.newCouponId), run.ids()); // sabahki kupon duruyor
        assertFalse(ledger.coupon(c1).superseded);
        for (Ledger.Leg l : ledger.coupon(part.newCouponId).legs) assertEquals("b2", l.bookRef);
        assertEquals(valuesBefore, radar.view().get("values")); // değer listesi tam taramaya ait kalır
        // aynı kısmi veriyle tekrar: yeni kupon yok
        assertNull(Daily.intraday(ledger, book, sharp, radar, null, true).newCouponId);
    }

    @Test
    public void lineupScansCostOneMoreScanAndAreCutAfterKg() {
        Settings s = new Settings();
        s.totals = true;
        Map<String, Integer> active = new java.util.LinkedHashMap<>();
        for (String l : s.leagues) active.put(l, 0);
        active.put("soccer_epl", 5);
        CreditPlan.Plan on = CreditPlan.plan(s, 2000L, 0L, LocalDate.of(2026, 10, 1), null, active, 1.0);
        s.lineupScans = false;
        CreditPlan.Plan off = CreditPlan.plan(s, 2000L, 0L, LocalDate.of(2026, 10, 1), null, active, 1.0);
        assertEquals(2, on.cost - off.cost, 1e-9); // 1 oynayan lig x 2 pazar x 1 tarama
        assertTrue((Boolean) on.toMap().get("lineupScans"));
        // ayarlar kalıcı; varsayılanlar açık
        Settings d = new Settings();
        assertTrue(d.lineupScans && d.quietNights);
        Map<String, Object> m = d.toMap();
        m.put("lineupScans", false);
        m.put("quietNights", false);
        Settings back = Settings.fromMap(m);
        assertFalse(back.lineupScans);
        assertFalse(back.quietNights);
        // 15 anahtar (2.14'ten beri; önce 10)
        List<String> keys = new ArrayList<>();
        for (int i = 1; i <= 15; i++) keys.add("anahtar" + i);
        d.oddsApiKey = String.join("\n", keys);
        assertNull(d.validate());
        assertEquals(15, Settings.fromMap(d.toMap()).apiKeys().size());
    }
}
