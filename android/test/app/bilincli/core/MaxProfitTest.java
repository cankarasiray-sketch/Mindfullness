package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.Test;

/** 2.8: en yüksek kâr — kredi yettikçe günde 8 radar, saatlik ücretsiz Zirve kontrolü. */
public class MaxProfitTest {
    @Test
    public void radarUpToEightScansWhenCreditsAllow() {
        Settings s = new Settings();
        for (int n : new int[] {0, 1, 2, 4, 6, 8}) {
            s.radarScans = n;
            assertNull(n + "", s.validate());
            assertEquals(n, s.radarHours().length);
        }
        for (int n : new int[] {3, 5, 7, 12}) {
            s.radarScans = n;
            assertNotNull(n + "", s.validate());
        }
        Settings y = new Settings();
        y.applyProfile("yuksek");
        assertEquals(8, y.radarScans);
        LocalDate day = LocalDate.of(2026, 10, 2);
        // 10 anahtar (5000 kredi): 8 radar korunur
        CreditPlan.Plan rich = CreditPlan.plan(y, 4900L, 100L, day, null);
        assertEquals(8, rich.radarScans);
        assertTrue(rich.lineupScans);
        assertTrue(rich.cost <= rich.budget);
        // tek anahtar: plan önce kadro taramasını, sonra radarı 8 -> 6 -> 4 ... diye azaltır
        // (KG plan tarafından kısılmaz, 2.15.3; tek anahtarda kullanıcı Ayarlar'dan kapatır)
        y.kgEvents = 0;
        CreditPlan.Plan one = CreditPlan.plan(y, 450L, 50L, day, null);
        assertTrue(one.narrowed);
        assertTrue(one.radarScans < 8);
        assertTrue(Settings.isRadarStep(one.radarScans));
        assertTrue(one.cost <= one.budget);
        assertEquals(8, y.radarScans); // kullanıcının ayarı değişmez
        // maç saatlerine göre: 8 tarama birbirine 90 dk'dan yakın düşmez
        java.util.List<Instant> ko = new java.util.ArrayList<>();
        for (int h = 13; h <= 22; h++) ko.add(day.atTime(h, 0).atZone(Fmt.TR).toInstant());
        java.util.List<Instant> t = ScanPlan.times(ko, 8, day);
        assertTrue(t.size() >= 4 && t.size() <= 8);
        for (int i = 1; i < t.size(); i++) assertTrue(t.get(i).getEpochSecond() - t.get(i - 1).getEpochSecond() >= ScanPlan.MIN_GAP_S);
    }

    @Test
    public void maxProfitProfileWidensOddsAndEdgeAndMigratesFrom27() {
        Settings y = new Settings();
        y.applyProfile("yuksek");
        assertEquals(4.50, y.maxLegOdds, 0);
        assertEquals(0.02, y.minLegEv, 0);
        assertEquals(0.60, y.kellyMultiplier, 0); // README 2.8: 0,65 kötü senaryoyu bozdu
        assertEquals(0.10, y.maxStakeFraction, 0);
        assertEquals(0.20, y.maxDailyExposure, 0);
        assertNull(y.validate());
        Settings back = Settings.fromMap(y.toMap());
        assertEquals("yuksek", back.profile);
        assertEquals(4.50, back.maxLegOdds, 0);
        // Temkinli'ye dönüş varsayılan aralığa döner
        y.applyProfile("temkinli");
        assertEquals(3.50, y.maxLegOdds, 0);
        assertEquals(0.03, y.minLegEv, 0);
        assertEquals(new Settings().maxLegOdds, y.maxLegOdds, 0);
        // 2.7 kaydı: En yüksek kazanç (4 radar, 3,50, %3) -> 2.8 değerleri
        Settings old = new Settings();
        old.kellyMultiplier = 0.50;
        old.maxStakeFraction = 0.10;
        old.maxDailyExposure = 0.20;
        old.minLegProb = 0.30;
        old.minWinProb = 0.20;
        old.radarScans = 4;
        java.util.Map<String, Object> m = old.toMap();
        m.put("v", 5L);
        Settings migrated = Settings.fromMap(m);
        assertEquals("yuksek", migrated.profile);
        assertEquals(0.60, migrated.kellyMultiplier, 0);
        assertEquals(8, migrated.radarScans);
        assertEquals(4.50, migrated.maxLegOdds, 0);
        assertEquals(0.02, migrated.minLegEv, 0);
        assertEquals(0.20, migrated.minWinProb, 0);
        // Temkinli ya da özel kayıtlar ayar dosyasında değişmez (bir kerelik geçişi uygulama yapar)
        java.util.Map<String, Object> t = new Settings().toMap();
        t.put("v", 5L);
        Settings tm = Settings.fromMap(t);
        assertEquals("temkinli", tm.profile);
        assertEquals(3.50, tm.maxLegOdds, 0);
        assertEquals(0, tm.radarScans);
        // 2.8 kaydı yeniden taşınmaz: kullanıcının sonradan değiştirdiği değer korunur
        Settings custom = new Settings();
        custom.applyProfile("yuksek");
        custom.maxLegOdds = 3.80;
        assertEquals(3.80, Settings.fromMap(custom.toMap()).maxLegOdds, 0);
    }

    @Test
    public void zirveCheckedHourlyExceptLateNight() {
        LocalDate day = LocalDate.of(2026, 10, 2);
        assertTrue(Zirve.backgroundHour(day.atTime(8, 0).atZone(Fmt.TR).toInstant()));
        assertTrue(Zirve.backgroundHour(day.atTime(23, 30).atZone(Fmt.TR).toInstant()));
        assertTrue(Zirve.backgroundHour(day.atTime(0, 40).atZone(Fmt.TR).toInstant()));
        assertFalse(Zirve.backgroundHour(day.atTime(1, 0).atZone(Fmt.TR).toInstant()));
        assertFalse(Zirve.backgroundHour(day.atTime(7, 59).atZone(Fmt.TR).toInstant()));
        assertEquals(3600000L, Zirve.BACKGROUND_PERIOD_MS);
    }
}
