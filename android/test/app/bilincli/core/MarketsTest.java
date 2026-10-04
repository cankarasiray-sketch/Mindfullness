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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/** Veri doğrulama (Calibration), 2,5 Alt/Üst ve Karşılıklı Gol, günde birden fazla kupon. */
public class MarketsTest {
    static final Instant NOW = CoreTest.NOW;

    static Map<String, Double> two(String a, double x, String b, double y) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put(a, x);
        m.put(b, y);
        return m;
    }

    /** n maç: iddaa %8 marjla adil olasılıkları fiyatlar. msOrder bültendeki 1/X/2 sırasını bozmak için. */
    static List<Object[]> world(int n, long seed) {
        Random rng = new Random(seed);
        List<Object[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double ph = 0.25 + 0.4 * rng.nextDouble(), pd = 0.22 + 0.08 * rng.nextDouble(), pa = 1 - ph - pd;
            double over = 0.35 + 0.35 * rng.nextDouble(), kg = 0.35 + 0.35 * rng.nextDouble();
            out.add(new Object[] {ph, pd, pa, over, kg});
        }
        return out;
    }

    static double price(double p) {
        return Math.round(1 / (p * 1.08) * 100) / 100.0;
    }

    static List<BookEvent> book(List<Object[]> w, boolean swapXand2, boolean auN1IsOver, boolean withDecoy) {
        List<BookEvent> out = new ArrayList<>();
        Random rng = new Random(99);
        for (int i = 0; i < w.size(); i++) {
            Object[] t = w.get(i);
            double ph = (Double) t[0], pd = (Double) t[1], pa = (Double) t[2], over = (Double) t[3], kg = (Double) t[4];
            Map<String, Map<String, Double>> odds = new LinkedHashMap<>();
            Map<String, Double> ms = new LinkedHashMap<>();
            ms.put("1", price(ph));
            ms.put("X", swapXand2 ? price(pa) : price(pd));
            ms.put("2", swapXand2 ? price(pd) : price(pa));
            odds.put("MS", ms);
            odds.put(Calibration.RAW_AU25 + "14", auN1IsOver ? two("1", price(over), "2", price(1 - over))
                    : two("1", price(1 - over), "2", price(over)));
            odds.put(Calibration.RAW_KG + "38", two("1", price(kg), "2", price(1 - kg)));
            if (withDecoy) { // tek/çift gibi hep %50 civarı bir pazar
                double p = 0.48 + 0.04 * rng.nextDouble();
                odds.put(Calibration.RAW_KG + "77", two("1", price(p), "2", price(1 - p)));
            }
            BookEvent b = new BookEvent("b" + i, "Ev " + i, "Dep " + i, NOW.plusSeconds(3600L * (2 + i)), "Lig", 1, odds, "" + i);
            b.marketMbs.put(Calibration.RAW_AU25 + "14", 2);
            out.add(b);
        }
        return out;
    }

    static List<SharpEvent> sharp(List<Object[]> w, boolean withTotals, boolean withKg) {
        List<SharpEvent> out = new ArrayList<>();
        for (int i = 0; i < w.size(); i++) {
            Object[] t = w.get(i);
            Map<String, Map<String, Double>> fair = new LinkedHashMap<>();
            Map<String, Double> ms = new LinkedHashMap<>();
            ms.put("1", (Double) t[0]);
            ms.put("X", (Double) t[1]);
            ms.put("2", (Double) t[2]);
            fair.put("MS", ms);
            if (withTotals) fair.put("AU25", two("ALT", 1 - (Double) t[3], "UST", (Double) t[3]));
            if (withKg) fair.put("KG", two("VAR", (Double) t[4], "YOK", 1 - (Double) t[4]));
            out.add(new SharpEvent("s" + i, "lig", "Ev " + i, "Dep " + i, NOW.plusSeconds(3600L * (2 + i)), fair, "pinnacle"));
        }
        return out;
    }

    @Test
    public void verifiesMsAndDiscoversTwoWayMarkets() {
        List<Object[]> w = world(10, 1);
        List<BookEvent> book = book(w, false, false, false);
        Map<String, Object> r = Calibration.apply(book, sharp(w, true, true));
        assertTrue(String.valueOf(r.get("MS")), String.valueOf(r.get("MS")).startsWith("doğrulandı"));
        assertTrue(String.valueOf(r.get("AU25")), String.valueOf(r.get("AU25")).startsWith("doğrulandı (pazar 14"));
        assertTrue(String.valueOf(r.get("KG")), String.valueOf(r.get("KG")).startsWith("doğrulandı (pazar 38"));
        BookEvent b = book.get(0);
        // N1 = Alt, N2 = Üst doğru yönde eşlendi
        assertEquals(price(1 - (Double) w.get(0)[3]), b.odds.get("AU25").get("ALT"), 0);
        assertEquals(price((Double) w.get(0)[4]), b.odds.get("KG").get("VAR"), 0);
        assertEquals(2, b.mbsFor("AU25")); // pazarın MBS'i taşındı
        for (String k : b.odds.keySet()) assertFalse(k, k.contains("#")); // ham pazarlar temizlendi
        assertTrue(Calibration.summary(r).contains("2,5 A/Ü ✓") && Calibration.summary(r).contains("KG ✓"));
    }

    @Test
    public void reversedOverUnderOrientationDetected() {
        List<Object[]> w = world(10, 2);
        List<BookEvent> book = book(w, false, true, false);
        Calibration.apply(book, sharp(w, true, false));
        assertEquals(price((Double) w.get(3)[3]), book.get(3).odds.get("AU25").get("UST"), 0);
        assertNull(book.get(3).odds.get("KG")); // Pinnacle KG yoksa KG kullanılmaz
    }

    @Test
    public void swappedMatchResultOrderIsCorrected() {
        List<Object[]> w = world(10, 3);
        List<BookEvent> book = book(w, true, false, false);
        Map<String, Object> r = Calibration.apply(book, sharp(w, false, false));
        assertTrue(String.valueOf(r.get("MS")), String.valueOf(r.get("MS")).startsWith("sıra düzeltildi"));
        assertEquals(price((Double) w.get(0)[1]), book.get(0).odds.get("MS").get("X"), 0);
        assertEquals(price((Double) w.get(0)[2]), book.get(0).odds.get("MS").get("2"), 0);
    }

    @Test
    public void inconsistentMatchResultDisabled() {
        List<Object[]> w = world(10, 4);
        List<BookEvent> book = book(w, false, false, false);
        Random rng = new Random(7);
        for (BookEvent b : book) for (Map.Entry<String, Double> e : b.odds.get("MS").entrySet()) e.setValue(1.2 + 6 * rng.nextDouble());
        Map<String, Object> r = Calibration.apply(book, sharp(w, false, false));
        assertTrue(String.valueOf(r.get("MS")), String.valueOf(r.get("MS")).startsWith("tutarsız"));
        assertNull(book.get(0).odds.get("MS"));
    }

    @Test
    public void ambiguousCandidateMarketsAreNotUsed() {
        // KG olasılıkları hep %50 civarındaysa gerçek pazar ile "tek/çift" ayırt edilemez
        List<Object[]> w = world(10, 5);
        for (Object[] t : w) t[4] = 0.5;
        List<BookEvent> book = book(w, false, false, true);
        Map<String, Object> r = Calibration.apply(book, sharp(w, false, true));
        assertEquals("belirsiz, kullanılmadı", r.get("KG"));
        assertNull(book.get(0).odds.get("KG"));
    }

    @Test
    public void implausibleEdgeIsRemoved() {
        List<Object[]> w = world(6, 6);
        List<BookEvent> book = book(w, false, false, false);
        book.get(2).odds.get("MS").put("2", 9.0); // adil 1/0.3 civarında iken 9,00: veri hatası
        double pa = (Double) w.get(2)[2];
        Map<String, Object> r = Calibration.apply(book, sharp(w, false, false));
        assertTrue(pa * 9.0 - 1 > Calibration.MAX_PLAUSIBLE_EV);
        // tek fiyat bu kadar sapınca maçın tamamı tutarsız sayılır (1.7: maç bazında kontrol)
        assertEquals(1L, r.get("mismatched"));
        assertTrue(book.get(2).odds.isEmpty());
        assertFalse(book.get(1).odds.isEmpty());
    }

    @Test
    public void rememberedMappingUsedWhenDataIsThin() {
        List<Object[]> w = world(10, 8);
        Map<String, Object> memory = new LinkedHashMap<>();
        Calibration.apply(book(w, false, true, false), sharp(w, true, true), memory);
        assertEquals("AU25#14|1", memory.get("AU25"));
        // kontrol anında yalnızca bir maç: karar verilemez, önceki eşleme kullanılır
        List<BookEvent> one = book(w.subList(0, 1), false, true, false);
        Map<String, Object> r = Calibration.apply(one, sharp(w.subList(0, 1), true, true), memory);
        assertTrue(String.valueOf(r.get("AU25")), String.valueOf(r.get("AU25")).startsWith("önceki eşleme"));
        assertEquals(price((Double) w.get(0)[3]), one.get(0).odds.get("AU25").get("UST"), 0);
        assertTrue(one.get(0).odds.containsKey("KG"));
    }

    @Test
    public void kgSettlementAndLabels() {
        assertEquals(Models.WON, Settlement.legResult("KG", "VAR", 1, 2));
        assertEquals(Models.WON, Settlement.legResult("KG", "YOK", 0, 2));
        assertEquals(Models.LOST, Settlement.legResult("KG", "VAR", 3, 0));
        assertEquals("KG Var", Models.outcomeLabel("KG", "VAR"));
    }

    @Test
    public void oddsApiParsesBtts() {
        Settings cfg = new Settings();
        cfg.oddsApiKey = "k";
        String ev = "[{\"id\":\"e1\",\"commence_time\":\"2026-10-03T17:00:00Z\",\"home_team\":\"A\",\"away_team\":\"B\","
                + "\"bookmakers\":[{\"key\":\"pinnacle\",\"markets\":[{\"key\":\"btts\",\"outcomes\":["
                + "{\"name\":\"Yes\",\"price\":1.80},{\"name\":\"No\",\"price\":2.05}]}]}]}]";
        try {
            List<SharpEvent> s = new OddsApi(null, cfg).parseOdds(Json.parse(ev), "x");
            Map<String, Double> kg = s.get(0).fair.get("KG");
            assertEquals(1.0, kg.get("VAR") + kg.get("YOK"), 1e-12);
            assertTrue(kg.get("VAR") > kg.get("YOK"));
        } catch (Http.ProviderException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void nesineCollectsCandidateMarketsAndInventory() throws Exception {
        String payload = "{\"sg\":{\"EA\":[{\"C\":1,\"HN\":\"A\",\"AN\":\"B\",\"D\":\"03.10.2026\",\"T\":\"20:00\",\"TYPE\":1,\"MBS\":1,"
                + "\"MA\":[{\"MTID\":1,\"OCA\":[{\"N\":1,\"O\":2.0},{\"N\":2,\"O\":3.2},{\"N\":3,\"O\":3.6}]},"
                + "{\"MTID\":14,\"SOV\":2.5,\"MBS\":2,\"OCA\":[{\"N\":1,\"O\":1.9},{\"N\":2,\"O\":1.8}]},"
                + "{\"MTID\":14,\"SOV\":3.5,\"OCA\":[{\"N\":1,\"O\":1.3},{\"N\":2,\"O\":3.2}]},"
                + "{\"MTID\":38,\"OCA\":[{\"N\":1,\"O\":1.7},{\"N\":2,\"O\":2.0}]},"
                + "{\"MTID\":44,\"SOV\":9.5,\"OCA\":[{\"N\":1,\"O\":1.85},{\"N\":2,\"O\":1.85}]}]}]}}";
        List<BookEvent> ev = Nesine.parse(Json.parse(payload));
        Set<String> keys = ev.get(0).odds.keySet();
        assertTrue(keys.contains("MS") && keys.contains("AU25#14") && keys.contains("KG#38"));
        assertFalse(keys.contains("AU25#44")); // 9,5 çizgisi (ör. korner) 2,5 Alt/Üst değil
        assertEquals(2, ev.get(0).mbsFor("AU25#14"));
        assertTrue(Nesine.lastInventory, Nesine.lastInventory.contains("MTID 44: 1 maç, seçenek [2], değer [9.5]"));
    }

    @Test
    public void multipleCouponsPerDayWithinDailyCap() {
        CoreTest.TestClock clock = new CoreTest.TestClock();
        Ledger ledger = new Ledger(new Ledger.MemoryStorage(null), clock);
        ledger.deposit(1000000, "");
        Settings s = ledger.settings();
        s.applyProfile("yuksek");
        s.minWinProb = 0.30; // kombineler (olasılık 0,25) elensin, üç bağımsız tekli kalsın
        ledger.saveSettings(s);
        final List<BookEvent> book = new ArrayList<>();
        final List<SharpEvent> sharp = new ArrayList<>();
        for (int i = 0; i < 3; i++) { // üç bağımsız değerli tekli
            book.add(FeatureTest.book(i, 2.40, 1, 5 + i));
            sharp.add(FeatureTest.sharp(i, 0.50, 5 + i));
        }
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
        assertEquals(3, r.couponIds.size());
        assertEquals(r.couponIds, ledger.runFor(Fmt.dayKey(NOW)).ids());
        long total = 0;
        for (Long id : r.couponIds) {
            Ledger.Coupon c = ledger.coupon(id);
            assertEquals(1, c.legs.size());
            total += c.suggestedStake;
        }
        assertTrue("toplam " + total, total <= Math.round(0.20 * 1000000) + 300); // günlük %20 (+yuvarlama)
        String[] note = Texts.notification(ledger, r);
        assertTrue(note[0], note[0].startsWith("Günün kuponları · 3 kupon"));
        // kalıcılık
        Ledger again = new Ledger(new Ledger.MemoryStorage(ledger.export()), clock);
        assertEquals(r.couponIds, again.runFor(Fmt.dayKey(NOW)).ids());
        // temkinli: tek kupon sınırı ayarlanabilir
        s.maxCouponsPerDay = 1;
        s.maxDailyExposure = 0.10;
        ledger.saveSettings(s);
        assertEquals(1, Daily.generate(ledger, src, true, false).couponIds.size());
    }

    @Test
    public void profilesAndCreditEstimate() {
        Settings s = new Settings();
        assertEquals("temkinli", s.detectProfile());
        assertEquals(5, s.maxCouponsPerDay);
        // 2.6: basketbol handikabı açıkken 2 basketbol ligi tarama başına +1 kredi; 2.15.3: KG günde 12 maç açık
        assertEquals(12, s.kgEvents);
        assertEquals(Math.round(30 * (13 + 2) * Settings.ACTIVE_SHARE * 2) + 240 + 30 * 12, s.estimatedMonthlyCredits());
        s.kgEvents = 0; // aşağıdaki formüller KG'siz
        s.basketHandicap = false; // aşağıdaki formüller lig başına 1 kredi
        int base = s.estimatedMonthlyCredits();
        assertEquals(13, s.leagues.size()); // büyük 6 + Avrupa kupaları + Hollanda, Portekiz + EuroLeague, NBA
        assertEquals(Math.round(30 * 13 * Settings.ACTIVE_SHARE * 2) + 240, base); // sabah + kadro saati taraması
        s.totals = true;
        int totals = s.estimatedMonthlyCredits();
        assertEquals(Math.round(30 * 13 * 2 * Settings.ACTIVE_SHARE * 2) + 240, totals); // basketbolda da toplam sayı
        s.lineupScans = false;
        assertEquals(Math.round(30 * 13 * 2 * Settings.ACTIVE_SHARE) + 240, s.estimatedMonthlyCredits());
        s.lineupScans = true;
        s.kgEvents = 6;
        assertEquals(totals + 180, s.estimatedMonthlyCredits());
        // 1.7.2'ye taşıma: eski varsayılan 6 lig değiştirilmemişse yeni varsayılana geçer, özel seçim korunur
        Map<String, Object> v2 = new Settings().toMap();
        v2.put("v", 2L);
        v2.put("leagues", new ArrayList<Object>(Settings.OLD_DEFAULT_LEAGUES));
        assertEquals(13, Settings.fromMap(v2).leagues.size());
        List<Object> custom = new ArrayList<Object>(Settings.OLD_DEFAULT_LEAGUES.subList(0, 3));
        v2.put("leagues", custom);
        assertEquals(3 + 2, Settings.fromMap(v2).leagues.size()); // özel seçim korunur, 1.9'da basketbol eklenir
        s.maxDailyExposure = 0.02; // kupon başına sınırdan küçük olamaz
        assertTrue(s.validate() != null);
        Settings back = Settings.fromMap(Json.parseObject(Json.write(new Settings().toMap())));
        assertEquals(5, back.maxCouponsPerDay);
        assertFalse(back.totals);
        assertTrue(back.edgeGuard);
        // 1.4'ten taşıma: profil kullanıcısı 5 kupona geçer, özel ayar korunur
        Map<String, Object> old = new Settings().toMap();
        old.remove("v");
        old.put("maxCouponsPerDay", 3L);
        Settings migrated = Settings.fromMap(old);
        assertEquals(5, migrated.maxCouponsPerDay);
        assertEquals("temkinli", migrated.profile);
        old.put("kellyMultiplier", 0.3);
        assertEquals(3, Settings.fromMap(old).maxCouponsPerDay);
    }
}
