package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

/** 2.1: promosyon (özel oran) kontrolü ve pas gününde en yakın seçim. */
public class PromoTest {
    static final Instant NOW = CoreTest.NOW;

    @Test
    public void passDayShowsNearestSelection() {
        // iddaa %10 marjlı: avantaj yok; en yakın seçim oran aralığındakilerden
        List<BookEvent> book = Arrays.asList(FeatureTest.book(1, 1.80, 1, 8), FeatureTest.book(2, 1.95, 1, 9));
        List<SharpEvent> sharp = Arrays.asList(FeatureTest.sharp(1, 0.50, 8), FeatureTest.sharp(2, 0.50, 9));
        Engine.Decision d = Engine.decide(book, sharp, NOW, new Settings());
        assertTrue(d.isPass());
        String near = String.valueOf(d.stats.get("en_yakin"));
        assertTrue(near, near.contains("Ev 2 – Dep 2 · MS 1 @ 1,95 (adil 2,00, −%2,5)"));
        String line = Texts.statsLine(d.stats);
        assertTrue(line, line.contains("\nEn yakın seçim: ") && line.startsWith("Bülten 2 maç"));
        // tutar: avantaj yok -> 0 TL; oynanır olacağı oran (1,03 / 0,50 = 2,06) ve o orandaki Kelly payı
        assertTrue(line, line.endsWith("\nÖnerilen tutar: 0 TL — avantaj yok, oynanmaz (yine de 100 TL oynanırsa beklenen kayıp ≈ 2,50 TL). "
                + "Oran 2,06 ya da üstüne çıkarsa (ör. kampanya, oran değişimi) önerilen kasa payı %0,7."));
        d.stats.put("kasa", 500000L); // 5.000 TL: 0,25 x (0,03 / 1,06) = %0,71 -> 35,4 TL, 10 TL'ye aşağı
        assertTrue(Texts.statsLine(d.stats), Texts.statsLine(d.stats).endsWith("önerilen 30,00 TL (kasa payı %0,7)."));
        // eşikte avantaj tam ayardaki kadar
        double[] t = Engine.targetOdds(0.5, "MS", new Settings());
        assertEquals(2.06, t[0], 1e-9);
        assertTrue(0.5 * t[0] - 1 >= 0.03 - 1e-9);
        // kanıt koruması devredeyse daha yüksek oran gerekir: r(p·o − 1) ≥ eşik -> (1 + 0,03/0,5) / 0,5 = 2,12
        Settings guarded = new Settings();
        guarded.edgeRatios = new LinkedHashMap<>();
        guarded.edgeRatios.put("*", 0.5);
        assertEquals(2.12, Engine.targetOdds(0.5, "MS", guarded)[0], 1e-9);
        guarded.edgeRatios.put("*", 0.0);
        assertNull(Engine.targetOdds(0.5, "MS", guarded));
        // avantajı var ama oran aralığı dışında
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("en_yakin_ev", 0.05);
        out.put("en_yakin_aralikta", false);
        assertEquals("0 TL — oranı ayarlardaki oran aralığının ya da tutma olasılığı şartının dışında, oynanmaz.", Texts.nearStake(out));
        // kupon gününde yok
        Engine.Decision value = Engine.decide(Collections.singletonList(FeatureTest.book(1, 2.30, 1, 8)),
                Collections.singletonList(FeatureTest.sharp(1, 0.50, 8)), NOW, new Settings());
        assertFalse(value.isPass());
        assertNull(value.stats.get("en_yakin"));
    }

    @Test
    public void numericNesineLeagueCodeShownAsLeagueName() {
        BookEvent coded = new BookEvent("b1", "Kazakistan", "Moldova", NOW.plusSeconds(3600), "10004", 1, CoreTest.ms(1.69, 3.03, 3.93), "1");
        SharpEvent s = new SharpEvent("s1", "soccer_epl", "Kazakhstan", "Moldova", NOW.plusSeconds(3600), CoreTest.ms(0.52, 0.28, 0.2), "pinnacle");
        assertEquals("İngiltere Premier Lig", Models.leagueLabel(coded, s)); // kod yerine lig adı
        BookEvent named = new BookEvent("b2", "A", "B", NOW, "Süper Lig", 1, CoreTest.ms(2, 3, 4), "2");
        assertEquals("Süper Lig", Models.leagueLabel(named, s));
        List<Object> table = Promo.table(Collections.singletonList(new Models.Pair(coded, s, 1)), NOW);
        assertEquals("İngiltere Premier Lig", Json.obj(table.get(0)).get("league"));
    }

    @Test
    public void fairTableStoredOnFullScanAndSearchable() {
        Radar radar = new Radar(new Ledger.MemoryStorage(null));
        List<BookEvent> book = Arrays.asList(FeatureTest.book(1, 1.80, 1, 8), FeatureTest.book(2, 1.95, 1, 200));
        List<SharpEvent> sharp = Arrays.asList(FeatureTest.sharp(1, 0.50, 8), FeatureTest.sharp(2, 0.50, 200));
        radar.update(book, sharp, NOW, new Settings(), true);
        List<Object> fairs = Json.arr(radar.view().get("fairs"));
        assertEquals(1, fairs.size()); // 200 saat sonraki maç ufkun dışında
        Map<String, Object> row = Json.obj(fairs.get(0));
        assertEquals("b1", row.get("ref"));
        assertEquals("s1", row.get("sref"));
        assertEquals(3, Json.arr(row.get("sel")).size()); // MS 1, X, 2
        assertEquals(NOW.toString(), radar.view().get("fairsAt"));
        Map<String, Object>[] f = Promo.find(fairs, "b1", "MS", "1");
        assertNotNull(f);
        assertEquals(0.5, Json.dbl(f[1], "p", 0), 1e-12);
        assertEquals("MS 1", f[1].get("label"));
        assertNull(Promo.find(fairs, "b1", "MS", "Y"));
        // kısmi tarama tabloyu değiştirmez
        radar.update(Collections.<BookEvent>emptyList(), Collections.<SharpEvent>emptyList(), NOW.plusSeconds(60), new Settings(), false);
        assertEquals(1, Json.arr(radar.view().get("fairs")).size());
    }

    @Test
    public void boostedOddsEvaluatedWithKellyStake() {
        Settings cfg = new Settings(); // çeyrek Kelly, kupon başına en fazla %3
        Promo.Check play = Promo.evaluate(0.50, "MS", 2.40, cfg, 500000); // 5.000 TL
        assertTrue(play.play);
        assertEquals(0.20, play.ev, 1e-9);
        assertEquals(0.03, play.fraction, 1e-9); // Kelly 0,25 x 0,143 = 0,036 -> üst sınır %3
        assertEquals(15000, play.stake); // 150 TL
        assertTrue(play.message, play.message.startsWith("Kampanya oranı 2,40 · adil 2,00 · avantaj +%20,0. Oyna: önerilen tutar 150,00 TL"));
        Promo.Check no = Promo.evaluate(0.50, "MS", 2.02, cfg, 500000);
        assertFalse(no.play);
        assertEquals(0, no.stake);
        assertTrue(no.message, no.message.contains("Oynama"));
        // kanıt koruması devredeyse olasılık motordaki gibi küçültülür
        cfg.edgeRatios = new LinkedHashMap<>();
        cfg.edgeRatios.put("*", 0.2);
        assertTrue(Promo.evaluate(0.50, "MS", 2.40, cfg, 500000).ev < 0.20);
        // çok yüksek avantaj: şartları kontrol et uyarısı
        cfg.edgeRatios = null;
        assertTrue(Promo.evaluate(0.50, "MS", 3.00, cfg, 500000).message.contains("kampanya şartlarını"));
    }

    @Test
    public void playedPromoIsTrackedButNotUsedForEdgeMeasurement() throws Exception {
        CoreTest.TestClock clock = new CoreTest.TestClock();
        Ledger ledger = new Ledger(new Ledger.MemoryStorage(null), clock);
        ledger.deposit(500000, "");
        Radar radar = new Radar(new Ledger.MemoryStorage(null));
        radar.update(Collections.singletonList(FeatureTest.book(1, 1.80, 1, 8)),
                Collections.singletonList(FeatureTest.sharp(1, 0.50, 8)), NOW, new Settings(), true);
        Map<String, Object>[] f = Promo.find(Json.arr(radar.view().get("fairs")), "b1", "MS", "1");
        long id = Promo.record(ledger, f[0], f[1], 2.40, 15000, Fmt.dayKey(NOW));
        Ledger.Coupon c = ledger.coupon(id);
        assertTrue(c.promo && c.played);
        assertEquals(15000, c.stake);
        assertEquals(500000 - 15000, ledger.balance());
        assertEquals(2.40, c.legs.get(0).odds, 0);
        assertEquals("lig", c.legs.get(0).sportKey);
        assertEquals("s1", c.legs.get(0).sharpRef);
        assertTrue(ledger.openCoupons().contains(c)); // sonuçlandırmaya girer
        // kalıcı
        Ledger again = new Ledger(new Ledger.MemoryStorage(ledger.export()), clock);
        assertTrue(again.coupon(id).promo);
        // kapanış ölçülmüş olsa bile kanıt koruması kampanya bahsini saymaz
        c.legs.get(0).closingFair = 0.52;
        assertEquals(0, EdgeCalibration.fromLedger(ledger).segments.get("*").n);
    }
}
