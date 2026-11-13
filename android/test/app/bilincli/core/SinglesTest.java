package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

/** 2.10: tek maç uzmanı — ek bahis türleri (gol modeli), pazar eşlemesi, yalnızca tekli kupon, %70+ katmanı. */
public class SinglesTest {
    static final Instant NOW = CoreTest.NOW;
    /** Maç başına {MS 1, MS 2, 2,5 Üst} adil olasılıkları. */
    static final double[][] GAMES = {
        {0.55, 0.22, 0.55}, {0.40, 0.33, 0.48}, {0.70, 0.12, 0.62}, {0.30, 0.45, 0.52},
        {0.48, 0.26, 0.42}, {0.62, 0.15, 0.58}, {0.35, 0.36, 0.45}, {0.25, 0.52, 0.60},
    };

    static SharpEvent sharp(int i) {
        Map<String, Map<String, Double>> fair = new LinkedHashMap<>(CoreTest.ms(GAMES[i][0], 1 - GAMES[i][0] - GAMES[i][1], GAMES[i][1]));
        Map<String, Double> au = new LinkedHashMap<>();
        au.put("ALT", 1 - GAMES[i][2]);
        au.put("UST", GAMES[i][2]);
        fair.put("AU25", au);
        return new SharpEvent("s" + i, "soccer_epl", "Ev " + i, "Dep " + i, NOW.plusSeconds(3600L * (6 + i)), fair, "pinnacle");
    }

    /** iddaa gibi: %8 marj ve biraz gürültü; first: bültenin 1. seçeneğine giden olasılık. */
    static Map<String, Double> two(double first, Random rng) {
        double p = Math.min(0.97, Math.max(0.03, first + 0.01 * rng.nextGaussian()));
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("1", Math.round(100 / (p * 1.08)) / 100.0);
        m.put("2", Math.round(100 / ((1 - p) * 1.08)) / 100.0);
        return m;
    }

    static List<BookEvent> book(List<Models.Pair> pairs, int n) {
        Random rng = new Random(7);
        List<BookEvent> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            SharpEvent s = sharp(i);
            double[][] m = Models.goalMatrix(s);
            Map<String, Map<String, Double>> odds = new LinkedHashMap<>(CoreTest.ms(1 / (GAMES[i][0] * 1.08), 3.3, 1 / (GAMES[i][1] * 1.08)));
            odds.put(Calibration.RAW_X2 + "20@1.5", two(GoalModel.market(m, "AU@1.5").get("UST"), rng)); // 1. seçenek Üst
            odds.put(Calibration.RAW_X2 + "21@3.5", two(GoalModel.market(m, "AU@3.5").get("ALT"), rng)); // 1. seçenek Alt
            odds.put(Calibration.RAW_X2 + "30@0.5", two(GoalModel.market(m, "EVG@0.5").get("ALT"), rng));
            odds.put(Calibration.RAW_X2 + "31@1.5", two(GoalModel.market(m, "DEPG@1.5").get("UST"), rng));
            odds.put(Calibration.RAW_X2 + "50@0.5", two(GoalModel.market(m, "IYAU@0.5").get("ALT"), rng)); // ilk yarı
            odds.put(Calibration.RAW_X2 + "40@9.5", two(0.45 + 0.1 * rng.nextDouble(), rng)); // korner: gol modeline uymaz
            Map<String, Double> h = GoalModel.market(m, "HMS@-1.0"); // ev sahibi −1 (Nesine özel değeri −1)
            Map<String, Double> hms = new LinkedHashMap<>();
            hms.put("1", Math.round(100 / (h.get("1") * 1.1)) / 100.0);
            hms.put("2", Math.round(100 / (h.get("X") * 1.1)) / 100.0);
            hms.put("3", Math.round(100 / (h.get("2") * 1.1)) / 100.0);
            odds.put(Calibration.RAW_X3 + "60@-1.0", hms);
            BookEvent b = new BookEvent("b" + i, "Ev " + i, "Dep " + i, s.kickoff, "Premier Lig", 1, odds, "" + (100 + i));
            out.add(b);
            pairs.add(new Models.Pair(b, s, 1));
        }
        return out;
    }

    @Test
    public void goalModelMarketsAreConsistent() {
        SharpEvent s = sharp(0);
        double[][] m = Models.goalMatrix(s);
        assertNotNull(m);
        assertEquals(0.55, GoalModel.market(m, "AU@2.5").get("UST"), 0.01); // modelin 2,5'i Pinnacle'a uyar
        double u15 = GoalModel.market(m, "AU@1.5").get("ALT"), u25 = GoalModel.market(m, "AU@2.5").get("ALT"), u35 = GoalModel.market(m, "AU@3.5").get("ALT");
        assertTrue(u15 < u25 && u25 < u35);
        assertEquals(1.0, GoalModel.market(m, "EVG@0.5").get("ALT") + GoalModel.market(m, "EVG@0.5").get("UST"), 1e-9);
        assertTrue(GoalModel.market(m, "EVG@0.5").get("ALT") < GoalModel.market(m, "DEPG@0.5").get("ALT")); // ev favori
        Map<String, Double> h0 = GoalModel.market(m, "HMS@0.0");
        assertEquals(0.55, h0.get("1"), 0.02); // handikapsız = maç sonucu
        Map<String, Double> h = GoalModel.market(m, "HMS@-1.0");
        assertEquals(1.0, h.get("1") + h.get("X") + h.get("2"), 1e-9);
        assertTrue(h.get("1") < h0.get("1"));
        assertNull(GoalModel.market(m, "AU@2.0")); // tam sayı çizgi: iade ihtimali
        assertNull(GoalModel.market(m, "HMS@0.5"));
        // karar olasılığı: güvenlik payı düşülmüş
        Map<String, Double> f = Models.fair(s, "AU@1.5");
        assertEquals(1 - u15 - GoalModel.MARGIN, f.get("UST"), 1e-9);
        assertNull(Models.fair(s, "IYAU@0.5"));
        // etiketler ve sonuçlandırma
        assertEquals("1,5 Üst", Models.outcomeLabel("AU@1.5", "UST"));
        assertEquals("Ev 0,5 Alt", Models.outcomeLabel("EVG@0.5", "ALT"));
        assertEquals("Dep 1,5 Üst", Models.outcomeLabel("DEPG@1.5", "UST"));
        assertEquals("H.MS (0:1) 1", Models.outcomeLabel("HMS@-1.0", "1"));
        assertEquals("H.MS (1:0) X", Models.outcomeLabel("HMS@1.0", "X"));
        assertEquals("Handikaplı Maç Sonucu", Models.marketName("HMS@-1.0"));
        assertEquals(Models.WON, Settlement.legResult("AU@1.5", "UST", 1, 1));
        assertEquals(Models.WON, Settlement.legResult("AU@3.5", "ALT", 2, 1));
        assertEquals(Models.LOST, Settlement.legResult("EVG@0.5", "ALT", 1, 0));
        assertEquals(Models.WON, Settlement.legResult("DEPG@1.5", "UST", 0, 2));
        assertEquals(Models.WON, Settlement.legResult("HMS@-1.0", "X", 2, 1)); // 2−1+(−1)=0
        assertEquals(Models.WON, Settlement.legResult("HMS@-1.0", "1", 3, 1));
        assertEquals(Models.WON, Settlement.legResult("HMS@1.0", "1", 1, 1));
        // basketbol maçında gol modeli yok
        Map<String, Map<String, Double>> bs = new LinkedHashMap<>();
        Map<String, Double> two = new LinkedHashMap<>();
        two.put("1", 0.6);
        two.put("2", 0.4);
        bs.put("BS", two);
        assertNull(Models.goalMatrix(new SharpEvent("x", "basketball_nba", "A", "B", NOW, bs, "pinnacle")));
    }

    @Test
    public void extraMarketsDiscoveredWithDirectionAndNonGoalMarketsRejected() {
        List<Models.Pair> pairs = new ArrayList<>();
        List<BookEvent> book = book(pairs, GAMES.length);
        Map<String, Object> memory = new LinkedHashMap<>();
        String r = Calibration.extraMarkets(book, pairs, new ArrayList<String>(), memory);
        assertTrue(r, r.startsWith("doğrulandı"));
        BookEvent b = book.get(0);
        double[][] m = Models.goalMatrix(pairs.get(0).sharp);
        for (String k : new String[] {"AU@1.5", "AU@3.5", "EVG@0.5", "DEPG@1.5"}) {
            Map<String, Double> o = b.odds.get(k);
            assertNotNull(k + " " + b.odds.keySet(), o);
            double alt = Calibration.devig(o.get("ALT"), o.get("UST"))[0];
            assertEquals(k, GoalModel.market(m, k).get("ALT"), alt, 0.04); // yön doğru
        }
        Map<String, Double> h = b.odds.get("HMS@-1.0");
        assertNotNull(b.odds.keySet().toString(), h);
        assertEquals(new java.util.HashSet<>(Arrays.asList("1", "X", "2")), h.keySet());
        assertFalse(b.odds.containsKey("AU@9.5"));
        assertFalse(b.odds.containsKey("EVG@9.5"));
        assertFalse(b.odds.containsKey("IYAU@0.5")); // ilk yarı tanınır ama sonuçlandırılamaz
        assertFalse(b.odds.containsKey("AU@0.5")); // ilk yarı 0,5 toplam 0,5 sanılmadı
        Map<String, Object> ek = Json.obj(memory.get("EK"));
        assertEquals("AU@1.5|1", ek.get(Calibration.RAW_X2 + "20@1.5"));
        assertEquals("IYAU@0.5|0", ek.get(Calibration.RAW_X2 + "50@0.5"));
        assertNull(ek.get(Calibration.RAW_X2 + "40@9.5"));
        assertTrue(Calibration.summary(reportWith(r)).contains("ek pazarlar ✓"));
        // az maçta son güvenilir eşleme kullanılır
        List<Models.Pair> few = new ArrayList<>();
        List<BookEvent> small = book(few, 2);
        Calibration.extraMarkets(small, few, new ArrayList<String>(), memory);
        assertTrue(small.get(0).odds.containsKey("AU@1.5"));
        // motor: model pazarı aday olarak karşılaştırılır (payı düşülmüş olasılıkla)
        Settings cfg = PickTest.legacy();
        Engine.Decision d = Engine.decide(book, sharpsOf(pairs), NOW, cfg);
        assertTrue((Long) d.stats.get("karsilastirilan_secim") > 3 * GAMES.length * 3);
    }

    /** Bültendeki gibi altı seçenekli pazar: order[i] = i. konumdaki seçeneğin COMBO_OUTCOMES sırası; %12 marj, biraz gürültü. */
    static Map<String, Double> six(Map<String, Double> f, int[] order, Random rng) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) {
            double p = Math.max(0.004, f.get(GoalModel.COMBO_OUTCOMES[order[i]]) + 0.004 * rng.nextGaussian());
            m.put(String.valueOf(i + 1), Math.max(1.01, Math.round(100 / (p * 1.12)) / 100.0));
        }
        return m;
    }

    @Test
    public void matchResultAndTotalsComboIsPricedLabelledAndSettled() {
        // 2.69: "Maç Sonucu ve Alt/Üst" (MS 2 ve 4,5 Alt, MS 1 ve 1,5 Üst…): skor matrisi Pinnacle'ın MS ve 2,5 A/Ü'süne oturtulur
        SharpEvent s0 = sharp(0); // MS 1 %55, MS 2 %22, 2,5 Üst %55
        double[][] j = Models.jointMatrix(s0);
        Map<String, Double> c15 = GoalModel.market(j, "MSAU@1.5"), c25 = GoalModel.market(j, "MSAU@2.5"), c45 = GoalModel.market(j, "MSAU@4.5");
        assertEquals(Arrays.asList(GoalModel.COMBO_OUTCOMES), new ArrayList<>(c25.keySet()));
        double sum = 0;
        for (double v : c25.values()) sum += v;
        assertEquals(1.0, sum, 1e-9);
        assertEquals(0.55, c25.get("1A") + c25.get("1U"), 1e-6); // kenar olasılıklar piyasanınkiyle aynı
        assertEquals(0.23, c25.get("XA") + c25.get("XU"), 1e-6);
        assertEquals(0.22, c45.get("2A") + c45.get("2U"), 1e-6);
        assertEquals(0.55, c25.get("1U") + c25.get("XU") + c25.get("2U"), 1e-6);
        assertTrue(c15.get("1A") < c25.get("1A") && c25.get("1A") < c45.get("1A")); // çizgi yükseldikçe Alt kolaylaşır
        assertEquals(0.434, c45.get("1A"), 0.01); // ev kazanır (%55) ve en çok 4 gol
        assertEquals(0.448, c15.get("1U"), 0.01); // ev kazanır ve en az 2 gol (yalnızca 1–0 dışarıda)
        assertNull(GoalModel.market(j, "MSAU@2.0")); // tam sayı çizgi yok
        // karar olasılığı güvenlik payı düşülmüş; kapanışta (CLV) düşülmez
        assertEquals(c45.get("1A") - GoalModel.MARGIN, Models.fair(s0, "MSAU@4.5").get("1A"), 1e-9);
        assertEquals(c45.get("1A"), Models.closingFair(s0, "MSAU@4.5").get("1A"), 1e-9);
        // etiketler
        assertEquals("MS 2 ve 4,5 Alt", Models.outcomeLabel("MSAU@4.5", "2A"));
        assertEquals("MS 1 ve 1,5 Üst", Models.outcomeLabel("MSAU@1.5", "1U"));
        assertEquals("MS X ve 2,5 Alt", Models.outcomeLabel("MSAU@2.5", "XA"));
        assertEquals("Maç Sonucu ve Alt/Üst", Models.marketName("MSAU@2.5"));
        assertEquals("MSAU", Pick.group("MSAU@2.5"));
        // sonuç: Çekya 0–2 İspanya (kullanıcının örneği): 2,5 Üst yatar; MS 2 ve 4,5 Alt, MS 2 ve 1,5 Üst tutar
        assertEquals(Models.LOST, Settlement.legResult("AU25", "UST", 0, 2));
        assertEquals(Models.WON, Settlement.legResult("MSAU@4.5", "2A", 0, 2));
        assertEquals(Models.WON, Settlement.legResult("MSAU@1.5", "2U", 0, 2));
        assertEquals(Models.LOST, Settlement.legResult("MSAU@2.5", "2U", 0, 2)); // sonuç tuttu, Üst tutmadı
        assertEquals(Models.LOST, Settlement.legResult("MSAU@4.5", "1A", 0, 2)); // Alt tuttu, sonuç tutmadı
        assertEquals(Models.LOST, Settlement.legResult("MSAU@4.5", "2A", 1, 4));
        assertEquals(Models.WON, Settlement.legResult("MSAU@1.5", "XA", 0, 0));
        assertEquals(Models.WON, Settlement.legResult("MSAU@1.5", "XU", 1, 1));
        assertNull(Settlement.liveResult("MSAU@1.5", "2U", 0, 2)); // maç bitmeden kesinleşmez (sonuç değişebilir)
    }

    @Test
    public void nesineKeepsSixWayCandidates() throws Exception {
        // 2.69: altı seçenekli pazar aday; seçenek numaraları ne olursa olsun küçükten büyüğe 1–6
        String json = "{\"sg\":{\"EA\":[{\"TYPE\":1,\"HN\":\"A\",\"AN\":\"B\",\"D\":\"03.10.2026\",\"T\":\"20:00\",\"C\":123,\"MBS\":1,"
                + "\"MA\":[{\"MTID\":1,\"MBS\":1,\"OCA\":[{\"N\":1,\"O\":2.1},{\"N\":2,\"O\":3.3},{\"N\":3,\"O\":3.4}]},"
                + "{\"MTID\":342,\"SOV\":0.0,\"MBS\":1,\"OCA\":[{\"N\":23,\"O\":9.0},{\"N\":21,\"O\":3.2},{\"N\":22,\"O\":7.5},{\"N\":24,\"O\":4.1},{\"N\":25,\"O\":12.0},{\"N\":26,\"O\":6.3}]},"
                + "{\"MTID\":343,\"SOV\":4.5,\"OCA\":[{\"N\":1,\"O\":2.4},{\"N\":2,\"O\":4.0},{\"N\":3,\"O\":4.6},{\"N\":4,\"O\":14.0},{\"N\":5,\"O\":60.0},{\"N\":6,\"O\":25.0}]},"
                + "{\"MTID\":344,\"SOV\":0.0,\"OCA\":[{\"N\":1,\"O\":2.4},{\"N\":2,\"O\":4.0},{\"N\":3,\"O\":4.6},{\"N\":4,\"O\":14.0},{\"N\":5,\"O\":60.0}]}]}]}}";
        BookEvent b = Nesine.parse(Json.parse(json)).get(0);
        Map<String, Double> a = b.odds.get(Calibration.RAW_X6 + "342@0.0"); // özel değer yok: çizgi pazar kodunda
        assertEquals(Arrays.asList("1", "2", "3", "4", "5", "6"), new ArrayList<>(a.keySet()));
        assertEquals(3.2, a.get("1"), 0);
        assertEquals(7.5, a.get("2"), 0);
        assertEquals(9.0, a.get("3"), 0);
        assertEquals(1, b.mbsFor(Calibration.RAW_X6 + "342@0.0"));
        assertNotNull(b.odds.get(Calibration.RAW_X6 + "343@4.5")); // çizgi özel değerde
        assertNull(b.odds.get(Calibration.RAW_X6 + "344@0.0")); // beş seçenek: aday değil
        // 2.70: veri testi raporu için altı seçenekli pazarın gerçek örneği (seçenek alanları, bültendeki sırayla numara=oran)
        String inv = Nesine.lastInventory;
        assertTrue(inv, inv.contains("MTID 342: 1 maç, seçenek [6]"));
        assertTrue(inv, inv.contains("seçenekler [N, O] 23=9.0 21=3.2 22=7.5 24=4.1 25=12.0 26=6.3"));
        assertFalse(inv, inv.contains("MTID 344: 1 maç, seçenek [5], değer [0.0], alanlar [MTID, OCA, SOV], örnek A - B, seçenekler"));
    }

    /** Bir olasılık dizisinden (COMBO_OUTCOMES sırası) altı seçenekli bülten pazarı; six gibi. */
    static Map<String, Double> sixOf(double[] probs, int[] order, Random rng) {
        Map<String, Double> f = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) f.put(GoalModel.COMBO_OUTCOMES[i], probs[i]);
        return six(f, order, rng);
    }

    /** iddaa gibi fiyat: marj b, kesinti küçük olasılıklara daha çok biner (üs a < 1), %3 gürültü. */
    static Map<String, Double> priced(double[] p, int[] order, double b, double a, Random rng) {
        double[] w = new double[6];
        double s = 0;
        for (int i = 0; i < 6; i++) s += w[i] = Math.pow(Math.max(0.002, p[i] * (1 + 0.03 * rng.nextGaussian())), a);
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) m.put(String.valueOf(i + 1), Math.max(1.01, Math.round(100 * s / (b * w[order[i]])) / 100.0));
        return m;
    }

    @Test
    public void comboOptionOrderNeedsSeveralLinesWhenOutcomesAreClose() {
        // 2.70: hep ev favorisi olan bültende 1,5 çizgisinde "X ve Alt" (0–0) ile "2 ve Alt" (0–1) hep birbirine yakın: sıra tek
        // çizgiyle ayırt edilemez; öteki çizgilerle birlikte (ortak sıra) bulunur. 2.69 bu yüzden 1,5 çizgisini çoğu zaman
        // tanıyamıyordu ("MS 1 ve 1,5 Üst" görünmüyordu).
        double[][] games = {{0.55, 0.20, 0.50}, {0.60, 0.17, 0.55}, {0.50, 0.23, 0.47}, {0.58, 0.19, 0.52}, {0.53, 0.21, 0.49}, {0.62, 0.16, 0.56}};
        int[] order = {3, 0, 4, 1, 5, 2}; // bülten sırası: 1U,1A,XU,XA,2U,2A
        for (boolean alone : new boolean[] {true, false}) {
            Random rng = new Random(5);
            List<Models.Pair> pairs = new ArrayList<>();
            List<BookEvent> book = new ArrayList<>();
            for (int i = 0; i < games.length; i++) {
                double[] g = games[i];
                Map<String, Map<String, Double>> fair = new LinkedHashMap<>(CoreTest.ms(g[0], 1 - g[0] - g[1], g[1]));
                Map<String, Double> au = new LinkedHashMap<>();
                au.put("ALT", 1 - g[2]);
                au.put("UST", g[2]);
                fair.put("AU25", au);
                SharpEvent s = new SharpEvent("h" + i, "soccer_epl", "Ev " + i, "Dep " + i, NOW.plusSeconds(3600L * (6 + i)), fair, "pinnacle");
                double[][] m = Models.jointMatrix(s);
                Map<String, Map<String, Double>> odds = new LinkedHashMap<>(CoreTest.ms(1 / (g[0] * 1.08), 1 / ((1 - g[0] - g[1]) * 1.08), 1 / (g[1] * 1.08)));
                odds.put(Calibration.RAW_X6 + "73@0.0", priced(GoalModel.comboProbs(m, "1.5"), order, 1.16, 0.92, rng));
                if (!alone) {
                    odds.put(Calibration.RAW_X6 + "70@0.0", priced(GoalModel.comboProbs(m, "2.5"), order, 1.16, 0.92, rng));
                    odds.put(Calibration.RAW_X6 + "74@0.0", priced(GoalModel.comboProbs(m, "3.5"), order, 1.16, 0.92, rng));
                }
                BookEvent b = new BookEvent("h" + i, "Ev " + i, "Dep " + i, s.kickoff, "Premier Lig", 1, odds, "" + (200 + i));
                book.add(b);
                pairs.add(new Models.Pair(b, s, 1));
            }
            List<String> notes = new ArrayList<>();
            String r = Calibration.comboMarkets(book, pairs, notes, new LinkedHashMap<String, Object>());
            if (alone) {
                assertEquals("seçenek sırası belirsiz, kullanılmadı", r);
                assertTrue(notes.toString(), notes.toString().contains("(kod 73): seçenek sırası ayırt edilemedi"));
                assertFalse(book.get(0).odds.containsKey("MSAU@1.5"));
            } else {
                assertEquals("doğrulandı (çizgiler 1,5 · 2,5 · 3,5; 6 maç, gol modeli)", r);
                Map<String, Double> raw = book.get(0).odds.get(Calibration.RAW_X6 + "73@0.0"), c15 = book.get(0).odds.get("MSAU@1.5");
                assertEquals(raw.get("1"), c15.get("1U")); // MS 1 ve 1,5 Üst
                assertEquals(raw.get("2"), c15.get("1A"));
                assertEquals(raw.get("4"), c15.get("XA"));
                assertEquals(raw.get("6"), c15.get("2A"));
                assertEquals(raw.get("5"), c15.get("2U"));
            }
        }
    }

    @Test
    public void comboMarketIsDiscoveredWithLineAndOptionOrder() {
        // 2.69–2.70: bültende seçenek sırası ve (özel değer yoksa) çizgi bilinmiyor: gol modeliyle bulunur. Aynı pazar türünün
        // çizgileri bültende aynı sırayla gelir (ortak sıra): burada 1A,1U,XA,XU,2A,2U
        List<Models.Pair> pairs = new ArrayList<>();
        List<BookEvent> book = book(pairs, GAMES.length);
        Random rng = new Random(11);
        int[] order = {0, 3, 1, 4, 2, 5};
        for (Models.Pair p : pairs) {
            double[][] m = Models.jointMatrix(p.sharp);
            p.book.odds.put(Calibration.RAW_X6 + "70@2.5", six(GoalModel.market(m, "MSAU@2.5"), order, rng)); // çizgi özel değerde
            p.book.odds.put(Calibration.RAW_X6 + "71@0.0", six(GoalModel.market(m, "MSAU@4.5"), order, rng)); // çizgi pazar kodunda
            p.book.odds.put(Calibration.RAW_X6 + "73@0.0", six(GoalModel.market(m, "MSAU@1.5"), order, rng));
            Map<String, Double> other = new LinkedHashMap<>();
            for (int i = 1; i <= 6; i++) other.put("" + i, 5.0 + rng.nextDouble()); // başka altı seçenekli pazar: modele uymaz
            p.book.odds.put(Calibration.RAW_X6 + "72@0.0", other);
            // Maç Sonucu ve Karşılıklı Gol: altı seçenekli, özel değersiz; 2,5 çizgisine benzer ama ayırt edilir
            p.book.odds.put(Calibration.RAW_X6 + "80@0.0", sixOf(GoalModel.comboProbs(m, "KG"), new int[] {3, 4, 5, 0, 1, 2}, rng));
            p.book.marketMbs.put(Calibration.RAW_X6 + "71@0.0", 1);
        }
        Map<String, Object> memory = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        String r = Calibration.comboMarkets(book, pairs, notes, memory);
        assertEquals("doğrulandı (çizgiler 1,5 · 2,5 · 4,5; 8 maç, gol modeli)", r);
        BookEvent b = book.get(0);
        Map<String, Double> c15 = b.odds.get("MSAU@1.5"), c25 = b.odds.get("MSAU@2.5"), c45 = b.odds.get("MSAU@4.5");
        assertEquals(Arrays.asList(GoalModel.COMBO_OUTCOMES), new ArrayList<>(c25.keySet()));
        assertEquals(b.odds.get(Calibration.RAW_X6 + "70@2.5").get("2"), c25.get("1U")); // bültenin 2. seçeneği: MS 1 ve Üst
        assertEquals(b.odds.get(Calibration.RAW_X6 + "70@2.5").get("5"), c25.get("2A"));
        assertEquals(b.odds.get(Calibration.RAW_X6 + "71@0.0").get("1"), c45.get("1A"));
        assertEquals(b.odds.get(Calibration.RAW_X6 + "71@0.0").get("6"), c45.get("2U"));
        assertEquals(b.odds.get(Calibration.RAW_X6 + "73@0.0").get("2"), c15.get("1U")); // MS 1 ve 1,5 Üst
        assertEquals(b.odds.get(Calibration.RAW_X6 + "73@0.0").get("3"), c15.get("XA"));
        assertEquals(1, b.mbsFor("MSAU@4.5"));
        assertFalse(b.odds.containsKey("MSAU@3.5"));
        Map<String, Object> mem = Json.obj(memory.get("MSAU"));
        assertEquals("2.5|031425", mem.get(Calibration.RAW_X6 + "70@2.5"));
        assertEquals("4.5|031425", mem.get(Calibration.RAW_X6 + "71@0.0"));
        assertEquals("1.5|031425", mem.get(Calibration.RAW_X6 + "73@0.0"));
        assertNull(mem.get(Calibration.RAW_X6 + "72@0.0"));
        assertNull(mem.get(Calibration.RAW_X6 + "80@0.0"));
        String why = notes.toString(); // kullanılmayan adayların nedeni (veri testi raporunda)
        assertTrue(why, why.contains("(kod 80): Maç Sonucu ve Karşılıklı Gol olarak tanındı"));
        assertTrue(why, why.contains("(kod 72): Maç Sonucu ve Alt/Üst'e uymuyor"));
        Map<String, Object> rep = reportWith("eşleşen ek pazar yok");
        rep.put("MSAU", r);
        assertTrue(Calibration.summary(rep).contains("MS ve A/Ü ✓"));
        // az maçta son güvenilir eşleme kullanılır
        List<Models.Pair> few = new ArrayList<>();
        List<BookEvent> small = book(few, 2);
        for (Models.Pair p : few) p.book.odds.put(Calibration.RAW_X6 + "71@0.0", six(GoalModel.market(Models.jointMatrix(p.sharp), "MSAU@4.5"), order, rng));
        assertEquals("doğrulandı (çizgiler 4,5; önceki eşleme, gol modeli)", Calibration.comboMarkets(small, few, new ArrayList<String>(), memory));
        assertEquals(small.get(0).odds.get(Calibration.RAW_X6 + "71@0.0").get("1"), small.get(0).odds.get("MSAU@4.5").get("1A"));
        // hafıza yoksa az maçta kullanılmaz
        List<Models.Pair> few2 = new ArrayList<>();
        List<BookEvent> small2 = book(few2, 2);
        for (Models.Pair p : few2) p.book.odds.put(Calibration.RAW_X6 + "71@0.0", six(GoalModel.market(Models.jointMatrix(p.sharp), "MSAU@4.5"), order, rng));
        List<String> fewNotes = new ArrayList<>();
        assertEquals("eşleşen pazar yok", Calibration.comboMarkets(small2, few2, fewNotes, new LinkedHashMap<String, Object>()));
        assertFalse(small2.get(0).odds.containsKey("MSAU@4.5"));
        assertTrue(fewNotes.toString(), fewNotes.toString().contains("(kod 71): veri az (2 maç; en az 5)"));
        // seçenek sırası çizgiden çizgiye farklıysa (beklenmez) ortak sıra yok: yalnızca kendi sırası açıkça kesin olan çizgi
        List<Models.Pair> odd = new ArrayList<>();
        List<BookEvent> oddBook = book(odd, GAMES.length);
        for (Models.Pair p : odd) {
            double[][] m = Models.jointMatrix(p.sharp);
            p.book.odds.put(Calibration.RAW_X6 + "70@2.5", six(GoalModel.market(m, "MSAU@2.5"), order, rng));
            p.book.odds.put(Calibration.RAW_X6 + "71@0.0", six(GoalModel.market(m, "MSAU@4.5"), new int[] {5, 4, 3, 2, 1, 0}, rng));
        }
        String oddResult = Calibration.comboMarkets(oddBook, odd, new ArrayList<String>(), new LinkedHashMap<String, Object>());
        assertTrue(oddResult, oddResult.startsWith("doğrulandı (çizgiler 2,5"));
        assertEquals(oddBook.get(0).odds.get(Calibration.RAW_X6 + "70@2.5").get("2"), oddBook.get(0).odds.get("MSAU@2.5").get("1U"));
        if (oddBook.get(0).odds.containsKey("MSAU@4.5")) { // kabul edildiyse kendi (ters) sırasıyla, doğru
            assertEquals(oddBook.get(0).odds.get(Calibration.RAW_X6 + "71@0.0").get("6"), oddBook.get(0).odds.get("MSAU@4.5").get("1A"));
        }
        // tek maç listesi: güvenlik payı düşülmüş olasılıkla, "model" işaretli; düşük olasılıklı seçenekler tabloya girmez
        List<Object> fairs = Promo.table(pairs, NOW);
        Map<String, Object> strong = null; // 3. maç: ev %70 favori
        int combos = 0;
        for (Object ro : fairs) {
            for (Object so : Json.arr(Json.obj(ro).get("sel"))) {
                Map<String, Object> sel = Json.obj(so);
                if (!String.valueOf(sel.get("m")).startsWith("MSAU@")) continue;
                combos++;
                assertTrue(Json.dbl(sel, "p", 0) >= 0.25);
                assertEquals(Boolean.TRUE, sel.get("model"));
                if ("b2".equals(Json.obj(ro).get("ref")) && "MSAU@4.5".equals(sel.get("m")) && "1A".equals(sel.get("o"))) strong = sel;
            }
        }
        assertTrue(combos >= GAMES.length);
        assertEquals("MS 1 ve 4,5 Alt", strong.get("label"));
        double want = GoalModel.market(Models.jointMatrix(pairs.get(2).sharp), "MSAU@4.5").get("1A") - GoalModel.MARGIN;
        assertEquals(want, Json.dbl(strong, "p", 0), 1e-4);
        assertEquals(0.472, want, 0.01); // %70 favori: MS 1 ve 4,5 Alt %51, payla %47
        // tek maç seçimleri arasında (en az %45 tutanlar): MS 1 ve 4,5 Alt, kendi oranıyla
        Map<String, Object> single = null;
        for (Map<String, Object> e : Pick.singles(fairs, null, PickTest.legacy(), 0.45, NOW)) {
            if ("b2".equals(e.get("ref")) && "MSAU@4.5".equals(e.get("m")) && "1A".equals(e.get("o"))) single = e;
        }
        assertNotNull(single);
        assertEquals("MS 1 ve 4,5 Alt", single.get("label"));
        assertEquals(book.get(2).odds.get("MSAU@4.5").get("1A"), Json.dbl(single, "odds", 0), 1e-9);
        assertEquals(Boolean.TRUE, single.get("model"));
    }

    static Map<String, Object> reportWith(String ek) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("MS", "doğrulandı");
        r.put("EK", ek);
        return r;
    }

    static List<SharpEvent> sharpsOf(List<Models.Pair> pairs) {
        List<SharpEvent> out = new ArrayList<>();
        for (Models.Pair p : pairs) out.add(p.sharp);
        return out;
    }

    @Test
    public void singlesOnlyBuildsOneLegCouponsFromSinglePlayableMatches() {
        // üç değerli MS 2 seçimi: ikisi tek oynanabilir, biri MBS 2
        List<BookEvent> book = Arrays.asList(ProbabilityTest.book(1, 1.70, 2.40, 1), ProbabilityTest.book(2, 1.70, 2.40, 1),
                ProbabilityTest.book(3, 1.70, 2.40, 2));
        List<SharpEvent> sharp = Arrays.asList(ProbabilityTest.sharp(1, 0.40, 0.46), ProbabilityTest.sharp(2, 0.40, 0.46),
                ProbabilityTest.sharp(3, 0.40, 0.46));
        Settings combo = PickTest.legacy();
        combo.minLegProb = 0;
        combo.minWinProb = 0;
        Engine.Decision d = Engine.decide(book, sharp, NOW, combo);
        assertFalse(d.isPass());
        assertEquals(3, d.candidates.size());
        Settings singles = PickTest.legacy();
        singles.minLegProb = 0;
        singles.minWinProb = 0;
        singles.singlesOnly = true;
        d = Engine.decide(book, sharp, NOW, singles);
        assertFalse(d.isPass());
        assertEquals(1, d.proposal.legs.size());
        assertEquals(2, d.candidates.size()); // MBS 2 maç alınmadı
        for (Models.Proposal p : d.alternatives) assertEquals(1, p.legs.size());
        for (Models.Candidate c : d.candidates) assertEquals(1, c.mbs());
        // ayar: eski kayıt bir kez tek maça geçer, yeni kurulum ve sonradan kapatma korunur
        Map<String, Object> old = PickTest.legacy().toMap();
        old.put("v", 6L);
        old.remove("singlesOnly");
        assertTrue(Settings.fromMap(old).singlesOnly);
        assertFalse(PickTest.legacy().singlesOnly);
        Settings off = Settings.fromMap(old);
        off.singlesOnly = false;
        assertFalse(Settings.fromMap(off.toMap()).singlesOnly);
        Settings bad = PickTest.legacy();
        bad.pickHighProb = 0.5;
        assertTrue(bad.validate().contains("Yüksek olasılık"));
    }

    @Test
    public void pickHasHighProbabilityTierAndAllOdds() {
        List<Object> fairs = PickTest.fairs();
        // %78 tutan 1,22: −%4,8 (yüksek katmanın en iyisi); 1,05 altı yine alınmaz
        fairs.add(PickTest.row("7", "City", 5, PickTest.sel("MS", "1", 0.78, 1.22, 1), PickTest.sel("CS", "1X", 0.97, 1.04, 1)));
        Settings cfg = PickTest.legacy();
        cfg.pickMaxLoss = 0.06; // 2.13 kademeleri
        Map<String, Object> p = Pick.choose(fairs, null, cfg, 500000, NOW);
        assertEquals("3", p.get("ref")); // ana seçim: %61, −%3,6
        Map<String, Object> high = Json.obj(p.get("high"));
        assertNotNull(high);
        assertEquals("7", high.get("ref"));
        assertEquals("MS 1", high.get("label"));
        assertEquals(15000L, high.get("stake")); // −%4,8: tam tutar (2.15.1), kasanın %3'ü (2.15.2)
        assertEquals(Math.round(15000 * 1.22), high.get("win"));
        assertEquals(1L, p.get("highCount"));
        // ana seçim zaten %70+ ise ayrı katman yok
        cfg.pickMinProb = 0.75;
        Map<String, Object> q = Pick.choose(fairs, null, cfg, 500000, NOW);
        assertEquals("7", q.get("ref"));
        assertTrue(Json.bool(q, "isHigh", false));
        assertNull(q.get("high"));
        // liste: en az %50, önce kârlı sonra denge puanı; model pazarları işaretli
        Map<String, Object> modelSel = PickTest.sel("AU@1.5", "UST", 0.74, 1.30, 1);
        modelSel.put("model", true);
        fairs.add(PickTest.row("8", "Brighton", 6, modelSel));
        Settings tiers = PickTest.legacy();
        tiers.pickMaxLoss = 0.06;
        List<Object> list = Pick.list(fairs, null, tiers, 500000, NOW);
        assertEquals(15000L, Json.obj(list.get(0)).get("stake"));
        assertEquals(6, list.size()); // Arsenal MS 1, Liverpool Üst ve KG Var, City MS 1, Brighton 1,5 Üst, Fulham MS 1 (30 saat sonra; 2.15.7: 48 saat)
        double prev = 9;
        for (Object o : list) { // 2.13: denge puanına göre (tutma ve ödeme birlikte)
            double sc = Json.dbl(Json.obj(o), "score", 0);
            assertTrue(sc <= prev);
            prev = sc;
        }
        boolean model = false;
        for (Object o : list) model |= Json.bool(Json.obj(o), "model", false) && "8".equals(Json.obj(o).get("ref"));
        assertTrue(model);
    }

    @Test
    public void fastGoalFitIsAsGoodAsTheOldGrid() {
        Random r = new Random(11);
        long fast = 0, grid = 0;
        for (int i = 0; i < 60; i++) {
            double[] o = GoalModel.outcomes(0.4 + 2.4 * r.nextDouble(), 0.3 + 2.2 * r.nextDouble());
            double p1 = o[0] + 0.02 * r.nextGaussian(), p2 = o[2] + 0.02 * r.nextGaussian(), ov = o[3] + 0.02 * r.nextGaussian();
            long t = System.nanoTime();
            double[] n = GoalModel.fit(p1, p2, ov);
            fast += System.nanoTime() - t;
            t = System.nanoTime();
            double[] g = GoalModel.gridFit(p1, p2, ov);
            grid += System.nanoTime() - t;
            assertTrue(i + "", GoalModel.fitError(n, p1, p2, ov) <= GoalModel.fitError(g, p1, p2, ov) + 1e-7);
            assertEquals(g[0], n[0], 0.01);
            assertEquals(g[1], n[1], 0.01);
        }
        assertTrue(fast * 5 < grid); // en az 5 kat hızlı (ölçülen ~28 kat)
    }

    @Test
    public void fairsTableTravelsOnlyWhenChanged() {
        Radar radar = new Radar(new Ledger.MemoryStorage(null));
        String k0 = radar.fairsKey();
        List<Models.Pair> pairs = new ArrayList<>();
        book(pairs, 3);
        radar.mergeFairs(pairs, NOW);
        String k1 = radar.fairsKey();
        assertFalse(k0.equals(k1));
        assertEquals(k1, radar.fairsKey()); // değişmedikçe aynı
        // olasılıklar 4 haneye yuvarlı; görünüm kopyası tabloyu değiştirmez
        List<Object> f = Json.arr(radar.view().get("fairs"));
        assertEquals(3, f.size());
        double p = Json.dbl(Json.obj(Json.arr(Json.obj(f.get(0)).get("sel")).get(0)), "p", 0);
        assertEquals(Math.round(p * 10000) / 10000.0, p, 0);
        f.clear();
        assertEquals(3, Json.arr(radar.view().get("fairs")).size());
    }

    @Test
    public void summaryCountsBasketballMatchesAndExtrasAreOnByDefault() {
        List<Models.Pair> pairs = new ArrayList<>();
        List<BookEvent> book = new ArrayList<>(book(pairs, 2));
        List<SharpEvent> sharp = sharpsOf(pairs);
        SharpEvent bs = HandicapTest.sharp(3);
        book.add(HandicapTest.book(3, bs, false, false));
        sharp.add(bs);
        Engine.Decision d = Engine.decide(book, sharp, NOW, PickTest.legacy());
        assertEquals(3L, d.stats.get("eslesen"));
        assertEquals(1L, d.stats.get("eslesen_basket"));
        assertTrue(Texts.statsLine(d.stats), Texts.statsLine(d.stats).contains("eşleşen 3 (basketbol 1)"));
        Settings s = PickTest.legacy();
        assertTrue(s.basketExtras);
        s.basketExtras = false;
        assertFalse(Settings.fromMap(s.toMap()).basketExtras);
    }
}
