package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 2.5: avantajlı ama seyrek tutan seçimler önerilmez (tutma olasılığı şartı) ve MBS. */
public class ProbabilityTest {
    static final Instant NOW = CoreTest.NOW;

    /** Deplasman (MS 2) adil olasılığı p2, iddaa oranı o2 olan maç. */
    static BookEvent book(int i, double o1, double o2, int mbs) {
        return new BookEvent("b" + i, "Ev " + i, "Dep " + i, NOW.plusSeconds(8 * 3600L), "Lig", mbs, CoreTest.ms(o1, 3.6, o2), String.valueOf(i));
    }

    static SharpEvent sharp(int i, double p1, double p2) {
        return new SharpEvent("s" + i, "lig", "Ev " + i, "Dep " + i, NOW.plusSeconds(8 * 3600L), CoreTest.ms(p1, 1 - p1 - p2, p2), "pinnacle");
    }

    @Test
    public void valuableButRareSelectionIsNotRecommended() {
        // MS 2: adil olasılık %32, iddaa 3,40 -> 0,32 x 3,40 − 1 = +%8,8 avantaj ama %40 şartının altında
        Engine.Decision d = Engine.decide(Collections.singletonList(book(1, 1.60, 3.40, 1)),
                Collections.singletonList(sharp(1, 0.50, 0.32)), NOW, new Settings());
        assertTrue(d.isPass());
        assertEquals(0L, d.stats.get("avantajli_secim"));
        // şart kapatılırsa (0) aynı seçim kupona girer
        Settings off = new Settings();
        off.minLegProb = 0;
        assertFalse(Engine.decide(Collections.singletonList(book(1, 1.60, 3.40, 1)),
                Collections.singletonList(sharp(1, 0.50, 0.32)), NOW, off).isPass());
        // sık tutan avantajlı seçim (MS 1: %50, 2,15 -> +%7,5) önerilir
        Engine.Decision ok = Engine.decide(Collections.singletonList(book(1, 2.15, 3.10, 1)),
                Collections.singletonList(sharp(1, 0.50, 0.30)), NOW, new Settings());
        assertFalse(ok.isPass());
        assertEquals("1", ok.proposal.legs.get(0).outcome);
    }

    @Test
    public void nearestPrefersSelectionsThatOftenWin() {
        // MS 2 (%30) avantaja daha yakın (−%3,1) ama seyrek tutar; MS 1 (%50, −%5) gösterilir
        Engine.Decision d = Engine.decide(Collections.singletonList(book(1, 1.90, 3.23, 1)),
                Collections.singletonList(sharp(1, 0.50, 0.30)), NOW, new Settings());
        assertTrue(d.isPass());
        assertTrue(String.valueOf(d.stats.get("en_yakin")), String.valueOf(d.stats.get("en_yakin")).contains("MS 1 @ 1,90"));
        assertEquals(Boolean.TRUE, d.stats.get("en_yakin_aralikta"));
    }

    @Test
    public void radarFlagsRareValueAndPromoRefusesIt() {
        Radar radar = new Radar(new Ledger.MemoryStorage(null));
        radar.update(Collections.singletonList(book(1, 1.60, 3.60, 1)), Collections.singletonList(sharp(1, 0.50, 0.30)), NOW, new Settings(), true);
        Map<String, Object> v = Json.obj(Json.arr(radar.view().get("values")).get(0));
        assertEquals("2", v.get("outcome"));
        assertEquals(Boolean.TRUE, v.get("lowProb"));
        // kampanya oranı da: avantajlı ama seyrek tutuyorsa "oynama"
        Promo.Check c = Promo.evaluate(0.30, "MS", 3.60, new Settings(), 500000);
        assertFalse(c.play);
        assertTrue(c.lowProb);
        assertTrue(c.message, c.message.contains("Oynama: avantajlı ama tutma olasılığı düşük (%30,0; ayar en az %40,0)"));
        assertTrue(Promo.evaluate(0.50, "MS", 2.40, new Settings(), 500000).play);
    }

    @Test
    public void zirveMarksRareValueAndMultiMatchOdds() {
        // Zirve MS 2: adil %30, Zirve 3,60 -> +%8 ama seyrek: "dusuk"
        List<Object> fairs = ZirveTest.fairs();
        Map<String, Object> ms2 = Json.obj(Json.arr(Json.obj(fairs.get(0)).get("sel")).get(2));
        ms2.put("p", 0.30);
        ms2.put("i", 3.50); // taramadaki iddaa oranı Bilyoner'in normal oranıyla aynı
        String body = ZirveTest.body().replace("\"val\":\"6.50\",\"tval\":\"6.80\"", "\"val\":\"6.50\",\"tval\":\"3.60\"").replace("6.50", "3.50");
        Map<String, Object> v = Zirve.evaluate(Zirve.parse(body), fairs, null, new Settings(), 500000, NOW);
        Map<String, Object> row = ZirveTest.rowFor(v, "MS 2");
        assertEquals("dusuk", row.get("status"));
        // değerli ama MBS 3: tek oynanamaz, bildirilmez
        String mbs3 = ZirveTest.body().replace("{\"id\":\"3172993:83175177:1\",\"mbs\":1", "{\"id\":\"3172993:83175177:1\",\"mbs\":3");
        Map<String, Object> v3 = Zirve.evaluate(Zirve.parse(mbs3), ZirveTest.fairs(), null, new Settings(), 500000, NOW);
        Map<String, Object> ms1 = ZirveTest.rowFor(v3, "MS 1");
        assertEquals("mbs", ms1.get("status"));
        assertEquals(3L, ms1.get("mbs"));
        assertEquals(0L, v3.get("play"));
        assertEquals(null, Zirve.notice(v3, new java.util.ArrayList<Object>()));
    }

    @Test
    public void fairTableCarriesMbsPerSelection() {
        BookEvent b = book(1, 1.60, 3.60, 3);
        List<Object> t = Promo.table(Collections.singletonList(new Models.Pair(b, sharp(1, 0.5, 0.3), 1)), NOW);
        for (Object o : Json.arr(Json.obj(t.get(0)).get("sel"))) assertEquals(3L, Json.obj(o).get("mbs"));
    }

    @Test
    public void maxProfitProfileWidensChoicesAndScansMore() {
        Settings s = new Settings();
        s.applyProfile("yuksek");
        assertEquals("yuksek", s.detectProfile());
        assertEquals(0.60, s.kellyMultiplier, 0);
        assertEquals(0.10, s.maxStakeFraction, 0);
        assertEquals(Settings.MAX_PROFIT_LEG_PROB, s.minLegProb, 0);
        assertEquals(Settings.MAX_PROFIT_WIN_PROB, s.minWinProb, 0);
        assertEquals(8, s.radarScans);
        assertTrue(s.lineupScans && s.creditExpand && s.totals && s.basketHandicap);
        assertEquals(null, s.validate());
        // geri dönüş: Temkinli sık tutan eşiklere döner (tarama ayarları korunur)
        s.applyProfile("temkinli");
        assertEquals("temkinli", s.detectProfile());
        assertEquals(0.40, s.minLegProb, 0);
        assertEquals(0.30, s.minWinProb, 0);
        assertEquals(0.25, s.kellyMultiplier, 0);
        // kalıcı
        Settings y = new Settings();
        y.applyProfile("yuksek");
        Settings back = Settings.fromMap(y.toMap());
        assertEquals("yuksek", back.profile);
        assertEquals(Settings.MAX_PROFIT_LEG_PROB, back.minLegProb, 0);
    }

    @Test
    public void settingsMigrateOldCouponThresholdAndKeepCustomOnes() {
        Settings d = new Settings();
        assertEquals(0.40, d.minLegProb, 0);
        assertEquals(0.30, d.minWinProb, 0);
        Map<String, Object> old = new LinkedHashMap<>(d.toMap());
        old.put("v", 4L);
        old.put("minWinProb", 0.20);
        old.remove("minLegProb");
        Settings migrated = Settings.fromMap(old);
        assertEquals(0.30, migrated.minWinProb, 0);
        assertEquals(0.40, migrated.minLegProb, 0);
        old.put("minWinProb", 0.25); // kullanıcının kendi eşiği korunur
        assertEquals(0.25, Settings.fromMap(old).minWinProb, 0);
        Map<String, Object> now = d.toMap();
        now.put("minLegProb", 0.55);
        assertEquals(0.55, Settings.fromMap(now).minLegProb, 0);
        d.minLegProb = 1.2;
        assertTrue(d.validate().contains("tutma olasılığı"));
        assertTrue(Arrays.asList(Settings.fromMap(now).toMap().keySet().toArray()).contains("minLegProb"));
    }
}
