package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 2.6: basketbol handikap (Pinnacle handikabı iddaa'nın çizgisine sayı farkı modeliyle çevrilir). */
public class HandicapTest {
    static final Instant NOW = CoreTest.NOW;
    static final String LEAGUE = "basketball_euroleague";
    /** Pinnacle ev sahibi çizgileri ve ev sahibinin bu çizgiyle kazanma olasılığı. */
    static final double[] LINE = {-4.5, -6.5, 2.5, -1.5, -8.5, 3.5};
    static final double[] P = {0.52, 0.49, 0.51, 0.53, 0.50, 0.48};

    static SharpEvent sharp(int i) {
        Map<String, Map<String, Double>> fair = new LinkedHashMap<>();
        Map<String, Double> bh = new LinkedHashMap<>();
        bh.put("LINE", LINE[i]);
        bh.put("1", P[i]);
        bh.put("2", 1 - P[i]);
        fair.put("BH", bh);
        return new SharpEvent("s" + i, LEAGUE, "Ev " + i, "Dep " + i, NOW.plusSeconds(3600L * (8 + i)), fair, "pinnacle");
    }

    /**
     * iddaa bülteni (%8 marj): handikap pazarı 144, her maçta Pinnacle çizgisi ve ±2 sayı. awaySign: değer
     * deplasmana uygulanır; firstIsAway: 1. seçenek deplasman. Ayrıca toplam sayı (149) ve ilk yarı
     * handikabı (148, Pinnacle'la uyuşmaz) gürültü olarak.
     */
    static BookEvent book(int i, SharpEvent s, boolean awaySign, boolean firstIsAway) {
        Map<String, Map<String, Double>> odds = new LinkedHashMap<>();
        for (double d : new double[] {0, -2, 2}) {
            double homeLine = LINE[i] + d;
            double cover = Models.coverProb(s, homeLine);
            double sov = awaySign ? -homeLine : homeLine;
            Map<String, Double> raw = new LinkedHashMap<>();
            double home = 1 / (cover * 1.08), away = 1 / ((1 - cover) * 1.08);
            raw.put("1", firstIsAway ? away : home);
            raw.put("2", firstIsAway ? home : away);
            odds.put(Calibration.RAW_BT + "144@" + sov, raw);
        }
        Map<String, Double> half = new LinkedHashMap<>(); // ilk yarı handikabı: Pinnacle'ın tam maç fiyatıyla uyuşmaz
        half.put("1", 1.70);
        half.put("2", 2.05);
        odds.put(Calibration.RAW_BT + "148@" + (LINE[i] / 2 + 0.25 > 0 ? 1.5 : -2.5), half);
        Map<String, Double> total = new LinkedHashMap<>();
        total.put("1", 1.85);
        total.put("2", 1.85);
        odds.put(Calibration.RAW_BT + "149@160.5", total);
        BookEvent b = new BookEvent("b" + i, "Ev " + i, "Dep " + i, s.kickoff, "EuroLeague", 1, odds, "" + i);
        b.sport = Models.BASKETBALL;
        return b;
    }

    static List<Models.Pair> pairs(boolean awaySign, boolean firstIsAway, List<BookEvent> book) {
        List<Models.Pair> out = new ArrayList<>();
        for (int i = 0; i < LINE.length; i++) {
            SharpEvent s = sharp(i);
            BookEvent b = book(i, s, awaySign, firstIsAway);
            book.add(b);
            out.add(new Models.Pair(b, s, 1));
        }
        return out;
    }

    @Test
    public void coverProbabilityFromPinnacleLine() {
        SharpEvent s = sharp(0); // ev −4,5 ile %52
        assertEquals(0.52, Models.coverProb(s, -4.5), 1e-6); // ters normal yaklaşık
        assertTrue(Models.coverProb(s, -2.5) > 0.52); // ev daha az sayı veriyor: daha olası
        assertTrue(Models.coverProb(s, -6.5) < 0.52);
        assertEquals(OddsMath.normCdf((11 * OddsMath.normInv(0.52) + 4.5 - 2.5) / 11), Models.coverProb(s, -2.5), 1e-9);
        assertNull(Models.coverProb(s, -4.0)); // tam sayı çizgi: iade ihtimali
        assertNull(Models.coverProb(s, 0.5)); // 5 sayı uzak
        Map<String, Double> f = Models.fair(s, "BH@-2.5");
        assertEquals(1.0, f.get("1") + f.get("2"), 1e-12);
        assertEquals("Basket H. Ev −2,5", Models.outcomeLabel("BH@-2.5", "1"));
        assertEquals("Basket H. Dep +2,5", Models.outcomeLabel("BH@-2.5", "2"));
        assertEquals("Basket H. Ev +3,5", Models.outcomeLabel("BH@3.5", "1"));
        assertEquals("Basketbol Handikap", Models.marketName("BH@3.5"));
        assertEquals("BH", Models.family("BH@3.5"));
    }

    @Test
    public void discoversMarketSignAndOrder() {
        // dört varsayımın hepsi doğru bulunur
        for (int k = 0; k < 4; k++) {
            boolean awaySign = k / 2 == 1, firstIsAway = k % 2 == 1;
            List<BookEvent> book = new ArrayList<>();
            List<Models.Pair> pairs = pairs(awaySign, firstIsAway, book);
            Map<String, Object> memory = new LinkedHashMap<>();
            String r = Calibration.basketHandicap(book, pairs, new ArrayList<String>(), memory);
            assertTrue(k + ": " + r, r.startsWith("doğrulandı (pazar 144, 18 çizgi"));
            assertEquals("144|" + (awaySign ? 1 : 0) + "|" + (firstIsAway ? 1 : 0), memory.get("BH"));
            // dönüştürülen çizgi: ev sahibinin çizgisi, "1" ev sahibi; olasılık Pinnacle'a yakın
            Map<String, Double> o = book.get(0).odds.get("BH@-4.5");
            assertTrue(k + " " + book.get(0).odds.keySet(), o != null);
            double first = Calibration.devig(o.get("1"), o.get("2"))[0];
            assertEquals(0.52, first, 0.01);
            assertTrue(book.get(0).odds.containsKey("BH@-2.5"));
        }
    }

    @Test
    public void unknownConventionWithoutEnoughEvidenceIsNotUsed() {
        // iddaa yalnızca Pinnacle'ın çizgisini sunuyor: olasılıklar ~%50, yön ayırt edilemez
        List<BookEvent> book = new ArrayList<>();
        List<Models.Pair> pairs = new ArrayList<>();
        for (int i = 0; i < LINE.length; i++) {
            SharpEvent s = sharp(i);
            Map<String, Map<String, Double>> odds = new LinkedHashMap<>();
            Map<String, Double> raw = new LinkedHashMap<>();
            raw.put("1", 1.85);
            raw.put("2", 1.85);
            odds.put(Calibration.RAW_BT + "144@" + LINE[i], raw);
            BookEvent b = new BookEvent("b" + i, "Ev " + i, "Dep " + i, s.kickoff, "L", 1, odds, "" + i);
            b.sport = Models.BASKETBALL;
            book.add(b);
            pairs.add(new Models.Pair(b, s, 1));
        }
        Map<String, Object> memory = new LinkedHashMap<>();
        String r = Calibration.basketHandicap(book, pairs, new ArrayList<String>(), memory);
        assertFalse(r, r.startsWith("doğrulandı"));
        assertNull(memory.get("BH"));
        assertFalse(book.get(0).odds.containsKey("BH@-4.5"));
        // önceki güvenilir eşleme varsa o kullanılır
        memory.put("BH", "144|0|0");
        assertTrue(Calibration.basketHandicap(book, pairs, new ArrayList<String>(), memory).startsWith("önceki eşleme kullanıldı"));
        assertTrue(book.get(0).odds.containsKey("BH@-4.5"));
    }

    @Test
    public void pinnacleSpreadsParsedAndSettled() throws Exception {
        Settings cfg = new Settings();
        cfg.oddsApiKey = "k";
        String ev = "[{\"id\":\"e1\",\"commence_time\":\"2026-10-03T17:00:00Z\",\"home_team\":\"A\",\"away_team\":\"B\",\"bookmakers\":["
                + "{\"key\":\"pinnacle\",\"last_update\":\"2026-10-01T10:00:00Z\",\"markets\":["
                + "{\"key\":\"h2h\",\"outcomes\":[{\"name\":\"A\",\"price\":1.60},{\"name\":\"B\",\"price\":2.45}]},"
                + "{\"key\":\"spreads\",\"outcomes\":[{\"name\":\"A\",\"price\":1.95,\"point\":-4.5},{\"name\":\"B\",\"price\":1.87,\"point\":4.5}]}]}]}]";
        List<SharpEvent> s = new OddsApi(null, cfg).parseOdds(Json.parse(ev), LEAGUE);
        assertEquals(1, s.size());
        Map<String, Double> bh = s.get(0).fair.get("BH");
        assertEquals(-4.5, bh.get("LINE"), 0);
        double[] p = OddsMath.devigPower(new double[] {1.95, 1.87});
        assertEquals(p[0], bh.get("1"), 1e-9);
        assertTrue(s.get(0).fair.containsKey("BS"));
        // pazar sorgusu ve kredi: basketbolda handikap açıkken +1
        assertEquals("h2h,totals,spreads", Settings.markets(LEAGUE, true, true));
        assertEquals("h2h,totals", Settings.markets("soccer_epl", true, true));
        assertEquals(3, Settings.scanCost(LEAGUE, true, true));
        assertEquals(2, Settings.scanCost("soccer_epl", true, true));
        assertTrue(new Settings().basketHandicap);
        Map<String, Object> m = new Settings().toMap();
        m.put("basketHandicap", false);
        assertFalse(Settings.fromMap(m).basketHandicap);
        // sonuç: ev −4,5 -> 90-85 ev kazanır, 88-85 deplasman
        assertEquals(Models.WON, Settlement.legResult("BH@-4.5", "1", 90, 85));
        assertEquals(Models.LOST, Settlement.legResult("BH@-4.5", "1", 88, 85));
        assertEquals(Models.WON, Settlement.legResult("BH@-4.5", "2", 88, 85));
        assertEquals(Models.WON, Settlement.legResult("BH@3.5", "1", 80, 82)); // ev +3,5
        assertEquals(Models.VOID, Settlement.legResult("BH@-3.0", "1", 88, 85));
    }
}
