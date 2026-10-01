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
import java.util.Set;
import org.junit.Test;

/** 1.9.2: basketbol toplam sayı Alt/Üst (iddaa çizgisi Pinnacle'ın ana çizgisinden farklı olabilir). */
public class BasketTotalsTest {
    static final Instant NOW = CoreTest.NOW;
    static final String LEAGUE = "basketball_euroleague";
    /** Pinnacle ana çizgileri ve iddaa'nın çizgi farkı. */
    static final double[] LINE = {160.5, 158.5, 166.5, 171.5, 155.5, 162.5};
    static final double[] GAP = {-2, 2, -3, 3, -2, 2};

    static SharpEvent sharp(int i, double line, double over) {
        Map<String, Map<String, Double>> fair = new LinkedHashMap<>();
        Map<String, Double> bt = new LinkedHashMap<>();
        bt.put("LINE", line);
        bt.put("ALT", 1 - over);
        bt.put("UST", over);
        fair.put("BT", bt);
        return new SharpEvent("s" + i, LEAGUE, "Ev " + i, "Dep " + i, NOW.plusSeconds(3600L * (8 + i)), fair, "pinnacle");
    }

    /** iddaa: %8 marjla, bültende 1. seçenek ÜST (yön 1); value maçında Üst adilin %15 üstünde. */
    static BookEvent book(int i, SharpEvent s, double[] lines, int value) {
        Map<String, Map<String, Double>> odds = new LinkedHashMap<>();
        for (double line : lines) {
            Double model = Models.overProb(s, line);
            double over = model == null ? 0.5 : model; // tam sayı çizgi: dönüşüm yok, bülten yine sunar
            Map<String, Double> raw = new LinkedHashMap<>();
            raw.put("1", (i == value ? 1.15 : 1.0) / (over * 1.08));
            raw.put("2", 1 / ((1 - over) * 1.08));
            odds.put(Calibration.RAW_BT + "22@" + line, raw);
        }
        Map<String, Double> half = new LinkedHashMap<>(); // ilk yarı toplamı: çizgi çok uzak, elenir
        half.put("1", 1.85);
        half.put("2", 1.85);
        odds.put(Calibration.RAW_BT + "30@80.5", half);
        Map<String, Double> handicap = new LinkedHashMap<>();
        handicap.put("1", 1.9);
        handicap.put("2", 1.8);
        odds.put(Calibration.RAW_BT + "21@-4.5", handicap);
        BookEvent b = new BookEvent("b" + i, "Ev " + i, "Dep " + i, s.kickoff, "EuroLeague", 1, odds, "" + i);
        b.sport = Models.BASKETBALL;
        return b;
    }

    @Test
    public void lineConversionFromPinnacleMainLine() {
        SharpEvent s = sharp(0, 160.5, 0.5);
        assertEquals(0.5, Models.overProb(s, 160.5), 1e-6);
        assertEquals(OddsMath.normCdf(3 / 16.0), Models.overProb(s, 157.5), 1e-9); // EuroLeague: SS 16
        assertTrue(Models.overProb(s, 163.5) < 0.5);
        assertNull(Models.overProb(s, 160.0)); // tam sayı çizgi: iade ihtimali
        assertNull(Models.overProb(s, 165.5)); // 5 sayı uzak: varsayım güvenilmez
        // Pinnacle %55 Üst diyorsa ortalama çizginin üstündedir
        assertTrue(Models.overProb(sharp(0, 160.5, 0.55), 160.5) > 0.549);
        assertEquals(1.96, OddsMath.normInv(OddsMath.normCdf(1.96)), 1e-5);
        Map<String, Double> f = Models.fair(s, "BT@157.5");
        assertEquals(1.0, f.get("ALT") + f.get("UST"), 1e-12);
        assertEquals("Basket 157,5 Üst", Models.outcomeLabel("BT@157.5", "UST"));
        assertEquals("Basketbol Alt/Üst", Models.marketName("BT@157.5"));
    }

    @Test
    public void discoversMarketAndReversedOrderFromLinesThatDiffer() {
        List<BookEvent> book = new ArrayList<>();
        List<SharpEvent> sharp = new ArrayList<>();
        for (int i = 0; i < LINE.length; i++) {
            SharpEvent s = sharp(i, LINE[i], 0.5);
            sharp.add(s);
            // 0. maçta ek olarak tam sayı çizgi (158,0): iade ihtimali, kullanılmaz
            book.add(book(i, s, i == 0 ? new double[] {LINE[i] + GAP[i], 158.0} : new double[] {LINE[i] + GAP[i]}, -1));
        }
        Map<String, Object> memory = new LinkedHashMap<>();
        Map<String, Object> r = Calibration.apply(book, sharp, memory);
        String bt = String.valueOf(r.get("BT"));
        assertTrue(bt, bt.startsWith("doğrulandı (pazar 22, 6 çizgi, sapma"));
        assertEquals("22|1", memory.get("BT")); // 1. seçenek Üst
        assertNull(book.get(0).odds.get("BT@158.0"));
        assertTrue(book.get(0).odds.containsKey("BT@158.5"));
        Map<String, Double> o1 = book.get(1).odds.get("BT@160.5");
        double over = Models.overProb(sharp.get(1), 160.5);
        assertEquals(1 / (over * 1.08), o1.get("UST"), 1e-9);
        for (BookEvent b : book) for (String k : b.odds.keySet()) assertFalse(k, k.contains("#"));
        assertTrue(Calibration.summary(r).contains("Basket A/Ü ✓"));
    }

    @Test
    public void sameLinesAsPinnacleGiveNoDirectionUnlessSeveralLinesOrMemory() {
        List<BookEvent> book = new ArrayList<>();
        List<SharpEvent> sharp = new ArrayList<>();
        for (int i = 0; i < LINE.length; i++) {
            SharpEvent s = sharp(i, LINE[i], 0.5);
            sharp.add(s);
            book.add(book(i, s, new double[] {LINE[i]}, -1)); // iddaa çizgisi = Pinnacle çizgisi
        }
        Map<String, Object> memory = new LinkedHashMap<>();
        String r = String.valueOf(Calibration.apply(book, sharp, memory).get("BT"));
        assertTrue(r, r.startsWith("yön belirlenemedi"));
        for (BookEvent b : book) for (String k : b.odds.keySet()) assertFalse(k, k.startsWith("BT"));
        // aynı maçta birden fazla çizgi: oranların çizgiyle değişimi yönü gösterir
        book.clear();
        for (int i = 0; i < LINE.length; i++) book.add(book(i, sharp.get(i), new double[] {LINE[i] - 1, LINE[i], LINE[i] + 1}, -1));
        // ±1 sayı fark %50'ye çok yakın (bilgi taşımaz); yön çizgiler arası oran değişiminden
        String two = String.valueOf(Calibration.apply(book, sharp, memory).get("BT"));
        assertTrue(two, two.startsWith("doğrulandı (pazar 22"));
        assertEquals("22|1", memory.get("BT"));
        // kanıt yoksa son güvenilir eşleme
        book.clear();
        for (int i = 0; i < LINE.length; i++) book.add(book(i, sharp.get(i), new double[] {LINE[i]}, -1));
        String again = String.valueOf(Calibration.apply(book, sharp, memory).get("BT"));
        assertTrue(again, again.startsWith("önceki eşleme kullanıldı (pazar 22"));
        assertTrue(book.get(0).odds.containsKey("BT@" + LINE[0]));
    }

    @Test
    public void totalsValueReachesCouponAndSettlesOnLine() throws Exception {
        CoreTest.TestClock clock = new CoreTest.TestClock();
        Ledger ledger = new Ledger(new Ledger.MemoryStorage(null), clock);
        ledger.deposit(500000, "");
        final List<BookEvent> book = new ArrayList<>();
        final List<SharpEvent> sharp = new ArrayList<>();
        for (int i = 0; i < LINE.length; i++) {
            SharpEvent s = sharp(i, LINE[i], 0.5);
            sharp.add(s);
            book.add(book(i, s, new double[] {LINE[i] + GAP[i]}, 1)); // 1: 160,5 Üst değerli
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
        assertFalse(r.decision.reason, r.couponIds.isEmpty());
        Ledger.Leg leg = ledger.coupon(r.couponIds.get(0)).legs.get(0);
        assertEquals("BT@160.5", leg.market);
        assertEquals("UST", leg.outcome);
        assertTrue(Texts.notification(ledger, r)[1], Texts.notification(ledger, r)[1].contains("Basket 160,5 Üst"));
        assertTrue(Texts.statsLine(r.decision.stats), Texts.statsLine(r.decision.stats).contains("iddaa basket A/Ü marjı"));
        assertEquals(Models.WON, Settlement.legResult("BT@160.5", "UST", 82, 79));
        assertEquals(Models.LOST, Settlement.legResult("BT@160.5", "ALT", 82, 79));
        assertEquals(Models.VOID, Settlement.legResult("BT@160.0", "ALT", 81, 79));
        // kanıt koruması tüm çizgileri tek "BT" segmentinde ölçer
        Map<String, Double> ratios = new LinkedHashMap<>();
        ratios.put("BT", 0.4);
        assertEquals(0.4, EdgeCalibration.ratio(ratios, "BT@160.5"), 0);
    }
}
