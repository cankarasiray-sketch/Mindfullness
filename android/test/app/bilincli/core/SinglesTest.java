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
        Settings cfg = new Settings();
        Engine.Decision d = Engine.decide(book, sharpsOf(pairs), NOW, cfg);
        assertTrue((Long) d.stats.get("karsilastirilan_secim") > 3 * GAMES.length * 3);
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
        Settings combo = new Settings();
        combo.minLegProb = 0;
        combo.minWinProb = 0;
        Engine.Decision d = Engine.decide(book, sharp, NOW, combo);
        assertFalse(d.isPass());
        assertEquals(3, d.candidates.size());
        Settings singles = new Settings();
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
        Map<String, Object> old = new Settings().toMap();
        old.put("v", 6L);
        old.remove("singlesOnly");
        assertTrue(Settings.fromMap(old).singlesOnly);
        assertFalse(new Settings().singlesOnly);
        Settings off = Settings.fromMap(old);
        off.singlesOnly = false;
        assertFalse(Settings.fromMap(off.toMap()).singlesOnly);
        Settings bad = new Settings();
        bad.pickHighProb = 0.5;
        assertTrue(bad.validate().contains("Yüksek olasılık"));
    }

    @Test
    public void pickHasHighProbabilityTierAndAllOdds() {
        List<Object> fairs = PickTest.fairs();
        // %78 tutan 1,22: −%4,8 (yüksek katmanın en iyisi); 1,05 altı yine alınmaz
        fairs.add(PickTest.row("7", "City", 5, PickTest.sel("MS", "1", 0.78, 1.22, 1), PickTest.sel("CS", "1X", 0.97, 1.04, 1)));
        Settings cfg = new Settings();
        Map<String, Object> p = Pick.choose(fairs, null, cfg, 500000, NOW);
        assertEquals("3", p.get("ref")); // ana seçim: %61, −%3,6
        Map<String, Object> high = Json.obj(p.get("high"));
        assertNotNull(high);
        assertEquals("7", high.get("ref"));
        assertEquals("MS 1", high.get("label"));
        assertEquals(5000L, high.get("stake"));
        assertEquals(Math.round(5000 * 1.22), high.get("win"));
        assertEquals(1L, p.get("highCount"));
        // ana seçim zaten %70+ ise ayrı katman yok
        cfg.pickMinProb = 0.75;
        Map<String, Object> q = Pick.choose(fairs, null, cfg, 500000, NOW);
        assertEquals("7", q.get("ref"));
        assertTrue(Json.bool(q, "isHigh", false));
        assertNull(q.get("high"));
        // liste: en az %50, adil orana yakınlığa göre; model pazarları işaretli
        Map<String, Object> modelSel = PickTest.sel("AU@1.5", "UST", 0.74, 1.30, 1);
        modelSel.put("model", true);
        fairs.add(PickTest.row("8", "Brighton", 6, modelSel));
        List<Object> list = Pick.list(fairs, null, new Settings(), NOW);
        assertEquals(5, list.size()); // Arsenal MS 1, Liverpool Üst ve KG Var, City MS 1, Brighton 1,5 Üst
        double prev = 9;
        for (Object o : list) {
            double ev = Json.dbl(Json.obj(o), "ev", 0);
            assertTrue(ev <= prev);
            prev = ev;
        }
        boolean model = false;
        for (Object o : list) model |= Json.bool(Json.obj(o), "model", false) && "8".equals(Json.obj(o).get("ref"));
        assertTrue(model);
    }
}
