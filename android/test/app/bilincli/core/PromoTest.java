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
        // kupon gününde yok
        Engine.Decision value = Engine.decide(Collections.singletonList(FeatureTest.book(1, 2.30, 1, 8)),
                Collections.singletonList(FeatureTest.sharp(1, 0.50, 8)), NOW, new Settings());
        assertFalse(value.isPass());
        assertNull(value.stats.get("en_yakin"));
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

    @Test
    public void bilyonerProbeSummarizesPageAndScripts() {
        final java.util.List<String> urls = new java.util.ArrayList<>();
        Http http = new Http() {
            public Response get(String url, Map<String, String> headers) throws ProviderException {
                urls.add(url);
                assertTrue(headers.get("User-Agent").contains("Mozilla"));
                if (url.equals("https://www.bilyoner.com/iddaa")) {
                    return new Response(200, "<html><script id=\"__NEXT_DATA__\">{}</script>"
                            + "<script src=\"/_next/static/app.js\"></script><script src=\"https://cdn.other.com/x.js\"></script>"
                            + "<a>Süper Oran</a> fetch('https://www.bilyoner.com/api/v3/mobile/bulletin?x=1')</html>", new LinkedHashMap<String, String>());
                }
                if (url.equals("https://www.bilyoner.com/_next/static/app.js")) {
                    return new Response(200, "var a='/api/v2/super-odds/list';var b='superOdds';", new LinkedHashMap<String, String>());
                }
                throw new ProviderException(url + " -> bağlantı hatası");
            }
        };
        String r = BilyonerProbe.report(http);
        assertTrue(r, r.contains("https://www.bilyoner.com/iddaa -> HTTP 200"));
        assertTrue(r, r.contains("gömülü veri: __NEXT_DATA__ var"));
        assertTrue(r, r.contains("\"süper oran\" geçen yer: 1"));
        assertTrue(r, r.contains("https://www.bilyoner.com/api/v3/mobile/bulletin?x=1"));
        assertTrue(r, r.contains("/api/v2/super-odds/list"));
        assertFalse(urls.contains("https://cdn.other.com/x.js")); // yalnızca sitenin kendi betikleri
        assertFalse(urls.contains("https://m.bilyoner.com/iddaa")); // ilk açılan sayfa yeterli
        // site açılmazsa çıktı yine üretilir (test çökmez)
        Http down = new Http() {
            public Response get(String url, Map<String, String> headers) throws ProviderException {
                throw new ProviderException("bağlantı hatası");
            }
        };
        String d = BilyonerProbe.report(down);
        assertTrue(d, d.contains("www.bilyoner.com/iddaa -> alınamadı") && d.contains("m.bilyoner.com/iddaa -> alınamadı"));
    }
}
