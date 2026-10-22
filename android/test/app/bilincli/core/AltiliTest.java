package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 2.21: altılı kupon planı (Kelly büyümesi, ganyan–AGF değeri, devir, kasa sınırı). */
public class AltiliTest {
    static List<String> six(String leg) {
        return new ArrayList<>(Collections.nCopies(6, leg));
    }

    static Altili.Inputs inputs(double kasa) {
        Altili.Inputs in = new Altili.Inputs();
        in.kasa = kasa;
        return in;
    }

    @SuppressWarnings("unchecked")
    static List<Object> horses(Map<String, Object> plan, int leg) {
        return (List<Object>) ((Map<String, Object>) ((List<Object>) plan.get("legs")).get(leg)).get("horses");
    }

    @Test
    public void ganyanUsesOddsAndDropsBook() {
        Altili.Leg l = Altili.leg("1:60:3 2:40:1.5");
        assertTrue(l.fromOdds);
        assertEquals("2", l.horses.get(0).no); // ganyana göre en olası
        assertEquals(2 / 3.0, l.horses.get(0).q, 1e-9);
        assertFalse(Altili.leg("1:60:9 2:40:9").fromOdds); // 1/oran toplamı %22: akla yatkın değil, AGF kullanılır
        assertFalse(Altili.leg("1:60 2:40:1.5").fromOdds); // her atta oran yok
        assertEquals(0.6, Altili.leg("1:60 2:40").horses.get(0).q, 1e-9);
    }

    @Test
    public void noValueWithoutGanyanOrDevir() {
        Map<String, Object> p = Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), inputs(5000));
        assertEquals(Boolean.FALSE, p.get("play"));
        assertEquals(0.73, (Double) p.get("roi"), 1e-9);
        assertTrue((String) p.get("reason"), ((String) p.get("reason")).contains("Ganyan oranı okunamadı"));
        assertTrue((String) p.get("reason"), ((String) p.get("reason")).contains("73 TL'sini geri getirir. Ganyan"));
        assertEquals(null, p.get("mode")); // bütçe yok: tutma odaklı kupon kurulmaz
    }

    @Test
    public void noPlayDayTakesTheCheapestCouponThatMeetsTheLegRule() {
        // 2.30: bütçe yalnızca üst sınır; kâr yokken en kârlı kupon en az zarar eden: her ayağı %50'ye tamamlayan en ucuz kupon
        Altili.Inputs in = inputs(3124.67);
        in.budget = 210;
        in.legMin = 0.50;
        Map<String, Object> p = Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), in);
        assertEquals(Boolean.FALSE, p.get("play"));
        assertEquals("tutma", p.get("mode"));
        for (int k = 0; k < 6; k++) assertEquals(Arrays.<Object>asList("1", "2"), horses(p, k)); // %65 ≥ %50, tek at %40 yetmez
        assertEquals(64L, p.get("units"));
        assertEquals(64.0, (Double) p.get("cost"), 1e-9); // 210 TL'nin hepsi harcanmaz
        assertEquals(0.73, (Double) p.get("roi"), 1e-9);
        assertEquals(1L, p.get("kat"));
        assertEquals(0.0, (Double) p.get("stake"), 0);
        assertEquals(null, p.get("overBudget"));
        assertTrue((String) p.get("reason"), ((String) p.get("reason")).startsWith("Ortalamada zarar: bu kupon"));
        in.legMin = 0; // ayak sınırı yoksa en ucuz: her ayakta favori
        assertEquals(1L, Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), in).get("units"));
    }

    @Test
    public void learnedValuePicksBetterHorsesWithinTheLegRule() {
        Altili.Inputs in = inputs(3124.67);
        in.budget = 210;
        in.legMin = 0.50;
        in.alpha = 1.5; // öğrenilmiş: favoriler AGF'nin verdiğinden sık kazanıyor
        Map<String, Object> p = Altili.plan(six("1:45 2:30 3:15 4:10"), in);
        for (int k = 0; k < 6; k++) assertEquals("1", horses(p, k).get(0));
        assertTrue((Double) p.get("roi") > 0.73); // değeri yüksek atlarla getiri artar
    }

    @Test
    public void underbetHorsesGiveValueAndKellyMixesAccuracyAndPayout() {
        Map<String, Object> p = Altili.plan(six("1:60:3 2:40:1.5"), inputs(5000));
        assertEquals(Boolean.TRUE, p.get("play"));
        assertTrue((String) p.get("reason"), ((String) p.get("reason")).startsWith("Ganyan oranlarına göre"));
        // yalnızca az oynanmış at (ucuz, nadiren tutar) ile hepsi (her zaman tutar, getirisi düşük) arasında karışım
        int singles = 0;
        for (int k = 0; k < 6; k++) {
            List<Object> h = horses(p, k);
            assertEquals("2", h.get(0));
            if (h.size() == 1) singles++;
        }
        assertTrue("karışım: " + singles, singles > 0 && singles < 6);
        long kat = (Long) p.get("kat");
        double stake = (Double) p.get("stake");
        assertTrue(kat >= 1);
        assertTrue("kasanın %5'i sınırı: " + stake, stake <= 5000 * 0.05 + 1e-9);
        assertEquals(6L, p.get("oddsLegs"));
    }

    @Test
    public void devirDayWithoutGanyanCoversWithinKelly() {
        Altili.Inputs in = inputs(5000);
        in.pool = 1_000_000;
        in.devir = 500_000; // getiri (1 − 0,27 + 0,5) = %123
        Map<String, Object> small = Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), in);
        assertEquals(Boolean.FALSE, small.get("play")); // 5.000 TL kasada tek kombinasyonun bile Kelly payı 1 TL'nin altında
        assertTrue((String) small.get("reason"), ((String) small.get("reason")).contains("kasana göre büyük"));
        in.kasa = 100_000;
        Map<String, Object> p = Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), in);
        assertEquals(Boolean.TRUE, p.get("play"));
        assertEquals("Devir havuzu kesintiyi aşıyor; ortalamada kâr.", p.get("reason"));
        assertEquals(1.23, (Double) p.get("avg"), 1e-9);
        assertTrue((Double) p.get("stake") <= 5000 + 1e-9);
        assertTrue((Long) p.get("units") > 1); // devirde getiri her kombinasyonda aynı: Kelly tutmayı artırır
        in.devir = 100_000;
        Map<String, Object> q = Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), in);
        assertEquals(Boolean.FALSE, q.get("play"));
        assertEquals(270_000, (Double) q.get("needDevir"), 1); // P/(1−vergi) − P(1−kesinti)
    }

    @Test
    public void smallKasaOrCapBlocksPlay() {
        Altili.Inputs in = inputs(20); // 20 TL kasa: Kelly payı birim fiyatın altında
        in.unit = 10;
        Map<String, Object> p = Altili.plan(six("1:60:3 2:40:1.5"), in);
        assertEquals(Boolean.FALSE, p.get("play"));
        assertTrue((String) p.get("reason"), ((String) p.get("reason")).contains("kasana göre büyük"));
        Altili.Inputs cap = inputs(5000);
        cap.maxCost = 3;
        Map<String, Object> c = Altili.plan(six("1:60:3 2:40:1.5"), cap);
        assertTrue((Double) c.get("stake") <= 3 + 1e-9);
    }

    @Test
    public void exactPoolShareLowersSmallPoolReturn() {
        Altili.Inputs big = inputs(5000), small = inputs(5000);
        big.pool = 10_000_000;
        small.pool = 2_000;
        double rb = (Double) Altili.plan(six("1:60:3 2:40:1.5"), big).get("roi");
        double rs = (Double) Altili.plan(six("1:60:3 2:40:1.5"), small).get("roi");
        assertTrue(rs + " < " + rb, rs < rb); // küçük havuzda kendi kuponumuz ikramiyeyi böler
    }

    @Test
    public void missingLegsAndUnit() {
        assertEquals("6 ayağın AGF'si gerekli.", Altili.plan(Arrays.asList("1:50 2:50"), inputs(100)).get("error"));
        List<String> legs = six("1:50 2:50");
        legs.set(3, "yok");
        assertEquals("4. ayağın AGF'si okunamadı.", Altili.plan(legs, inputs(100)).get("error"));
        Altili.Inputs in = inputs(100);
        in.unit = 0;
        assertEquals("Birim fiyatı gir.", Altili.plan(six("1:50 2:50"), in).get("error"));
    }

    @Test
    public void bestAltiliOfTheDay() {
        Altili.Inputs in = inputs(2324.20);
        in.budget = 60;
        Map<String, Object> open = Altili.plan(six("1:20 2:20 3:20 4:20 5:20"), in); // açık koşular: tutması zor
        Map<String, Object> clear = Altili.plan(six("1:70 2:20 3:10"), in); // net favoriler
        Map<String, Object> value = Altili.plan(six("1:60:3 2:40:1.5"), inputs(5000)); // ganyana göre kârlı
        assertEquals(1, Altili.best(Arrays.asList(open, clear)));
        assertEquals(2, Altili.best(Arrays.asList(open, clear, value)));
        assertEquals(0, Altili.best(Arrays.asList(clear, null)));
        assertEquals(-1, Altili.best(Arrays.asList((Map<String, Object>) null)));
        Map<String, Object> err = Altili.plan(Arrays.asList("1:50"), in);
        assertEquals(1, Altili.best(Arrays.asList(err, open)));
    }

    @Test
    public void notificationText() {
        Map<String, Object> p = Altili.plan(six("1:60:3 2:40:1.5"), inputs(5000));
        String coupon = Texts.altiliCoupon(p), summary = Texts.altiliSummary(p);
        assertTrue(coupon, coupon.startsWith("1) 2"));
        assertTrue(coupon, coupon.contains(" · 6) "));
        assertTrue(summary, summary.matches("(?s)\\d+ kombinasyon(, misli \\d+)? = [\\d.,]+ TL · tutma %[\\d,]+ · beklenen getiri %\\d+\nGanyan oranlarına göre.*"));
    }

    @Test
    public void rankingWeighsProfitAndHitTogether() {
        Altili.Inputs in = inputs(3124.67);
        in.budget = 90;
        Map<String, Object> open = Altili.plan(six("1:20 2:20 3:20 4:20 5:20"), in), clear = Altili.plan(six("1:70 2:20 3:10"), in);
        Map<String, Object> value = Altili.plan(six("1:60:3 2:40:1.5"), inputs(3124.67));
        assertTrue((Double) open.get("growth") < (Double) clear.get("growth")); // aynı getiri: tutması kolay olan kasaya az zarar
        assertTrue((Double) value.get("growth") > 0); // kârlı kupon kasayı büyütür
        assertEquals(Arrays.asList(2, 1, 0, 3), Altili.order(Arrays.asList(open, clear, value, null)));
    }

    @Test
    public void unprofitableCouponsRankByHitNotByCheapness() {
        Altili.Inputs cheap = inputs(3124.67), full = inputs(3124.67);
        cheap.budget = 70;
        full.budget = 90;
        // 21.10: 71 TL'lik %16'lık kupon, 80 TL'lik %30'luk kuponun önüne geçiyordu
        Map<String, Object> a = Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), cheap), b = Altili.plan(six("1:70 2:20 3:10"), full);
        assertTrue((Double) a.get("hit") < (Double) b.get("hit"));
        assertEquals(Arrays.asList(1, 0), Altili.order(Arrays.asList(a, b)));
    }

    @Test
    public void sevenPercentBudgetAndHalfEveryLeg() {
        Altili.Inputs in = inputs(3124.67);
        in.budget = 210; // kasanın %7'si, 10 TL'ye yuvarlı
        in.maxFraction = 0.07;
        in.legMin = 0.50;
        List<String> legs = Arrays.asList("5:30 9:25 3:15 6:12 1:10 10:8", "4:40 2:30 7:20 9:10", "1:35 2:25 3:15 5:10 8:8 10:7", "3:45 1:25 4:15 6:10 9:5",
                "2:30 4:22 5:18 7:15 9:10 11:5", "1:20 2:15 3:12 4:10 5:9 6:8 7:7 8:6 9:5 10:4 12:2 14:1 15:1");
        Map<String, Object> p = Altili.plan(legs, in);
        assertEquals("tutma", p.get("mode"));
        @SuppressWarnings("unchecked")
        List<Object> ls = (List<Object>) p.get("legs");
        for (Object o : ls) {
            @SuppressWarnings("unchecked")
            double share = (Double) ((Map<String, Object>) o).get("share");
            assertTrue("ayak " + share, share >= 0.50 - 1e-9);
        }
        assertTrue((Double) p.get("cost") <= 210); // %50 kapsama bütçeye sığdı, kalan bütçe tutmayı artırdı
        assertEquals(null, p.get("overBudget"));
        assertEquals(0.5, (Double) p.get("legMin"), 1e-9);
        // çok açık koşular: %50 için bütçe yetmez, işaretlenir ve sıralamada sona kalır
        Map<String, Object> open = Altili.plan(six("1:9 2:9 3:9 4:9 5:8 6:8 7:8 8:8 9:8 10:8 11:8 12:8"), in);
        assertEquals(Boolean.TRUE, open.get("overBudget"));
        assertEquals(Arrays.asList(0, 1), Altili.order(Arrays.asList(p, open)));
        // kârlı kuponda da üst sınır kasanın %7'si ve her ayak en az %50
        Altili.Inputs v = inputs(3124.67);
        v.maxFraction = 0.07;
        v.legMin = 0.50;
        Map<String, Object> value = Altili.plan(six("1:60:3 2:40:1.5"), v);
        assertEquals(Boolean.TRUE, value.get("play"));
        assertTrue((Double) value.get("stake") <= 3124.67 * 0.07 + 1e-6);
        for (int k = 0; k < 6; k++) assertEquals("2", horses(value, k).get(0)); // her ayakta önce az oynanmış 2 numara (%67 ≥ %50)
    }

    @Test
    public void returnBelowHalfIsNotRecommended() {
        Altili.Inputs in = inputs(3124.67);
        in.budget = 210;
        in.pool = 150; // küçük havuz: kendi kuponumuz ikramiyeyi böler
        Map<String, Object> low = Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), in);
        assertTrue("getiri " + low.get("roi"), (Double) low.get("roi") < 0.5);
        assertEquals(Boolean.TRUE, low.get("lowReturn"));
        assertTrue((String) low.get("reason"), ((String) low.get("reason")).endsWith("Beklenen getiri %50'nin altında: önerilmez."));
        Altili.Inputs ok = inputs(3124.67);
        ok.budget = 210;
        Map<String, Object> normal = Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), ok);
        assertEquals(Arrays.asList(1, 0), Altili.order(Arrays.asList(low, normal)));
        assertEquals(1, Altili.best(Arrays.asList(low, normal)));
    }

    static Altili.Inputs rule() {
        Altili.Inputs in = inputs(3124.67);
        in.budget = 210;
        in.legMin = 0.50;
        in.minCost = 60;
        return in;
    }

    @Test
    public void reasonableDefaultsAndRevertOf2322And2323() {
        // 2.32.4: kupon en az 60 TL; 2.32.6: kullanıcı istedi, her ayak en az %70
        assertEquals(0.0, new Settings().altiliLegMin, 1e-9); // 2.33: %70 kuralı kalktı
        assertEquals(60.0, new Settings().altiliMinCost, 1e-9);
        Map<String, Object> v11 = new Settings().toMap();
        v11.put("v", 11L);
        v11.put("altiliLegMin", 0.80);
        v11.put("altiliMinCost", 120.0);
        Settings back = Settings.fromMap(v11);
        assertEquals(0.0, back.altiliLegMin, 1e-9);
        assertEquals(60.0, back.altiliMinCost, 1e-9);
        v11.put("altiliLegMin", 0.60); // kullanıcının kendi seçimi korunur
        v11.put("altiliMinCost", 90.0);
        assertEquals(0.60, Settings.fromMap(v11).altiliLegMin, 1e-9);
        assertEquals(90.0, Settings.fromMap(v11).altiliMinCost, 1e-9);
        Settings mine = new Settings();
        Map<String, Object> v12 = new Settings().toMap();
        v12.put("v", 12L);
        v12.put("altiliLegMin", 0.50); // 2.32.4–2.32.5 varsayılanı %70'e, 2.33'te sınırsıza taşınır
        assertEquals(0.0, Settings.fromMap(v12).altiliLegMin, 1e-9);
        Map<String, Object> v13 = new Settings().toMap();
        v13.put("v", 13L);
        v13.put("altiliLegMin", 0.70);
        assertEquals(0.0, Settings.fromMap(v13).altiliLegMin, 1e-9);
        mine.altiliLegMin = 0.80; // yeni sürümde bilerek %80'e çeken korunur
        mine.altiliMinCost = 120;
        assertEquals(0.80, Settings.fromMap(mine.toMap()).altiliLegMin, 1e-9);
        assertEquals(120.0, Settings.fromMap(mine.toMap()).altiliMinCost, 1e-9);
        // %80 kuralı: her ayak en az %80; bütçeyi aşarsa kupon yine kurulur, uyarı işaretlenir
        Altili.Inputs in = rule();
        in.legMin = 0.80;
        List<String> legs = Arrays.asList("1:40 2:25 3:15 4:10 5:10", "1:40 2:25 3:15 4:10 5:10", "1:30 2:15 3:15 4:15 5:15 6:10",
                "1:60 2:40", "1:55 2:25 3:20", "1:50 2:30 3:20");
        Map<String, Object> p = Altili.plan(legs, in);
        for (int k = 0; k < 6; k++) {
            double s = 0;
            for (Object h : horses(p, k)) s += share(legs.get(k), String.valueOf(h));
            assertTrue("ayak " + (k + 1) + " %" + s, s >= 80 - 1e-9);
        }
        assertEquals(true, p.get("overBudget"));
        assertEquals(0.80, (Double) p.get("legMin"), 1e-9);
        // ganyan yokken ikisi de paranın %73'ünü geri getirir; %50 / 60 TL kuponu kaybedilen TL başına daha çok tutar
        in.legMin = 0.50;
        Map<String, Object> lean = Altili.plan(legs, in);
        assertEquals((Double) p.get("roi"), (Double) lean.get("roi"), 1e-9);
        assertTrue((Double) lean.get("cost") < (Double) p.get("cost") / 3);
        assertTrue((Double) lean.get("hit") / -(Double) lean.get("ev") > (Double) p.get("hit") / -(Double) p.get("ev"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void maxHitFillsToCapAndFeedsWeakestLegs() {
        // 2.33: bütçeyle en yüksek tutma: kupon 320 TL'ye kadar, para önce en zayıf (açık) ayaklara
        Altili.Inputs in = rule();
        in.legMin = 0;
        in.maxCost = 320;
        in.maxHit = true;
        List<String> legs = Arrays.asList("1:85 2:10 3:5", "1:35 2:20 3:15 4:10 5:10 6:5 7:5", "1:60 2:25 3:15", "1:45 2:20 3:15 4:10 5:10",
                "1:50 2:30 3:20", "1:40 2:30 3:15 4:15");
        Map<String, Object> p = Altili.plan(legs, in);
        double cost = (Double) p.get("cost");
        assertTrue("kupon " + cost, cost <= 320 + 1e-9 && cost >= 240);
        assertEquals(true, p.get("maxHit"));
        assertTrue(horses(p, 0).size() < horses(p, 1).size()); // favorisi net ayakta az, açık ayakta çok at
        Altili.Inputs cheap = rule();
        cheap.legMin = 0;
        assertTrue((Double) p.get("hit") > 1.5 * (Double) Altili.plan(legs, cheap).get("hit"));
        assertEquals(320.0, cost, 1e-9); // tavana sığan en yüksek tutma (kesin arama)
        // en zayıf ayak (5. ayak, 1-2: %80) ve 3 numarayı eklemenin bedeli
        assertEquals(5L, p.get("weakLeg"));
        assertEquals("3", p.get("weakHorse"));
        assertEquals(480.0, (Double) p.get("weakCost"), 1e-9);
        assertEquals((Double) p.get("hit") / 0.8, (Double) p.get("weakHit"), 1e-9);
        // adım adım eklemenin tuzağı: tek atta bırakılan ayak yerine en iyi dağılım (en yüksek tutma) seçilir
        List<String> open = Arrays.asList("1:35 2:20 3:15 4:10 5:10 6:5 7:5", "1:45 2:20 3:15 4:10 5:10", "1:25 2:20 3:15 4:15 5:10 6:10 7:5",
                "1:50 2:20 3:15 4:15", "1:30 2:25 3:20 4:15 5:10", "1:40 2:30 3:15 4:15");
        Map<String, Object> o = Altili.plan(open, in);
        assertTrue((Double) o.get("cost") <= 320 + 1e-9);
        int[] n = Altili.bestHit(legsOf(open), 320, 0);
        double best = 0;
        for (int a = 1; a <= 7; a++) for (int b = 1; b <= 5; b++) for (int c = 1; c <= 7; c++) for (int d = 1; d <= 4; d++) for (int e = 1; e <= 5; e++)
            for (int f = 1; f <= 4; f++) {
                if (a * b * c * d * e * f > 320) continue;
                int[] m = {a, b, c, d, e, f};
                double h = 1;
                for (int k = 0; k < 6; k++) h *= top(open.get(k), m[k]);
                best = Math.max(best, h);
            }
        assertEquals(best, (Double) o.get("hit"), 1e-9); // bütün dağılımlar arasında en yüksek
        assertEquals(n.length, 6);
        // tavanın altındaki her eklemeden daha iyisi yok: ayakların en zayıfı en çok güçlenir
        double weakest = 1;
        for (Object l : (List<Object>) p.get("legs")) weakest = Math.min(weakest, (Double) ((Map<String, Object>) l).get("share"));
        assertTrue("en zayıf ayak %" + weakest * 100, weakest >= 0.6);
    }

    @Test
    public void profitRuleNeedsMeaningfulEdge() {
        // 2.45: kâr kuralı: beklenen dönüş en az %110; AGF'ye dayalı kupon (%73) ve küçük devir (%103) oynanmaz, büyük devir oynanır
        assertEquals(0.10, new Settings().altiliMinEdge, 1e-9);
        List<String> legs = Arrays.asList("1:85 2:10 3:5", "1:35 2:20 3:15 4:10 5:10 6:5 7:5", "1:60 2:25 3:15", "1:45 2:20 3:15 4:10 5:10",
                "1:50 2:30 3:20", "1:40 2:30 3:15 4:15");
        Map<String, Map<String, Object>> by = new LinkedHashMap<>();
        for (double devir : new double[] {0, 300000, 600000}) {
            Altili.Inputs in = rule();
            in.maxCost = 600;
            in.maxHit = true;
            in.minHit = 0.15;
            in.minEdge = 0.10;
            in.profitRule = true;
            in.devir = devir;
            in.pool = 1000000;
            by.put(String.valueOf((long) devir), Altili.plan(legs, in));
        }
        assertEquals(false, by.get("0").get("play"));
        assertEquals(true, by.get("0").get("noEdge"));
        assertEquals(false, by.get("300000").get("play")); // %3 kâr: anlamlı değil
        assertEquals(true, by.get("300000").get("noEdge"));
        assertTrue(String.valueOf(by.get("300000").get("reason")).startsWith("Beklenen kâr %3: anlamlı değil (kâr kuralı en az %10)"));
        assertEquals(true, by.get("600000").get("play"));
        assertEquals(null, by.get("600000").get("noEdge"));
        assertTrue((Double) by.get("600000").get("roi") >= 1.10);
        // kâr kuralına uymayan günün kuponu olarak önerilmez
        assertEquals(1, Altili.best(Arrays.asList(by.get("0"), by.get("600000"))));
        assertEquals(-1, Altili.best(Arrays.asList(by.get("0"), by.get("300000"))));
    }

    @Test
    public void unpredictableAltiliIsNotPlayed() {
        // 2.37: 600 TL ile bile 6/6 olasılığı %30'un altındaysa oynanmaz, önerilmez; sırada sonda
        assertEquals(0.20, new Settings().altiliMinHit, 1e-9); // 2.44: %30 → %15; 2.49: %20
        Map<String, Object> v15 = new Settings().toMap();
        v15.put("v", 15L);
        v15.put("altiliMinHit", 0.30);
        assertEquals(0.20, Settings.fromMap(v15).altiliMinHit, 1e-9);
        v15.put("altiliMinHit", 0.25); // kullanıcının kendi seçimi korunur
        assertEquals(0.25, Settings.fromMap(v15).altiliMinHit, 1e-9);
        // 2.49: kullanıcı istedi: kural 6/6 (kârsız günde de), eşik %20 ya da otomatik en iyi
        Map<String, Object> v16 = new Settings().toMap();
        v16.put("v", 16L);
        v16.put("altiliMinHit", 0.15);
        v16.put("altiliPlayNoEdge", false);
        v16.remove("altiliMinHitAuto");
        Settings m16 = Settings.fromMap(v16);
        assertEquals(0.20, m16.altiliMinHit, 1e-9);
        assertTrue(m16.altiliPlayNoEdge);
        assertTrue(m16.altiliMinHitAuto);
        Settings mine = new Settings();
        mine.altiliPlayNoEdge = false; // yeni sürümde bilerek kapatan korunur
        mine.altiliMinHitAuto = false;
        mine.altiliMinHit = 0.15;
        Settings back = Settings.fromMap(mine.toMap());
        assertFalse(back.altiliPlayNoEdge);
        assertFalse(back.altiliMinHitAuto);
        assertEquals(0.15, back.altiliMinHit, 1e-9);
        Altili.Inputs in = rule();
        in.maxCost = 320;
        in.maxHit = true;
        in.minHit = 0.30;
        Map<String, Object> open = Altili.plan(Arrays.asList("1:35 2:20 3:15 4:10 5:10 6:5 7:5", "1:45 2:20 3:15 4:10 5:10",
                "1:25 2:20 3:15 4:15 5:10 6:10 7:5", "1:50 2:20 3:15 4:15", "1:30 2:25 3:20 4:15 5:10", "1:40 2:30 3:15 4:15"), in);
        Map<String, Object> clear = Altili.plan(Arrays.asList("1:85 2:10 3:5", "1:35 2:20 3:15 4:10 5:10 6:5 7:5", "1:60 2:25 3:15",
                "1:45 2:20 3:15 4:10 5:10", "1:50 2:30 3:20", "1:40 2:30 3:15 4:15"), in);
        assertEquals(true, open.get("lowHit")); // %13,9
        assertEquals(null, clear.get("lowHit")); // %46,8
        assertEquals(1, Altili.best(Arrays.asList(open, clear)));
        assertEquals(-1, Altili.best(Arrays.asList(open)));
        assertEquals(Arrays.asList(1, 0), Altili.order(Arrays.asList(open, clear)));
        // 2.51: kupon kasaya göre küçükse tahmin edilebilirlik tavandaki kuponla ölçülür
        in.maxCost = 60;
        Map<String, Object> small = Altili.plan(Arrays.asList("1:85 2:10 3:5", "1:35 2:20 3:15 4:10 5:10 6:5 7:5", "1:60 2:25 3:15",
                "1:45 2:20 3:15 4:10 5:10", "1:50 2:30 3:20", "1:40 2:30 3:15 4:15"), in);
        assertEquals(true, small.get("lowHit")); // 60 TL ile 6/6 olasılığı %30'un altında
        in.refCost = 320;
        Map<String, Object> ref = Altili.plan(Arrays.asList("1:85 2:10 3:5", "1:35 2:20 3:15 4:10 5:10 6:5 7:5", "1:60 2:25 3:15",
                "1:45 2:20 3:15 4:10 5:10", "1:50 2:30 3:20", "1:40 2:30 3:15 4:15"), in);
        assertEquals(null, ref.get("lowHit"));
        assertEquals((Double) clear.get("hit"), (Double) ref.get("hitRef"), 1e-12);
        assertTrue((Double) ref.get("hit") < 0.30 && (Double) ref.get("cost") <= 60);
        assertEquals(320.0, (Double) ref.get("refCost"), 1e-9);
        in.maxCost = 320;
        in.refCost = 0;
        // 2.42: AGF'si doğrulanmış sonuçlarda tutmayan bölgenin altılısı önerilmez, sonda
        Map<String, Object> weak = new LinkedHashMap<>(clear);
        weak.put("regionWeak", true);
        assertEquals(1, Altili.best(Arrays.asList(weak, clear)));
        assertEquals(-1, Altili.best(Arrays.asList(weak)));
        assertEquals(Arrays.asList(1, 0), Altili.order(Arrays.asList(weak, clear)));
        // 2.53: kasa bu strateji için yetersiz: önerilmez, sonda
        Map<String, Object> poor = new LinkedHashMap<>(clear);
        poor.put("kasaLow", true);
        assertEquals(1, Altili.best(Arrays.asList(poor, clear)));
        assertEquals(-1, Altili.best(Arrays.asList(poor)));
        assertEquals(Arrays.asList(1, 0), Altili.order(Arrays.asList(poor, clear)));
    }

    @Test
    public void legMinAndCapTogetherOrNoPlay() {
        // 2.32.10: Belmont kuponunun kuralları: her ayak en az %70 ve kupon en fazla 320 TL; sığmazsa daraltılmaz, oynanmaz
        assertEquals(600.0, new Settings().altiliCap, 1e-9); // 2.35.1
        Map<String, Object> v14 = new Settings().toMap();
        v14.put("v", 14L);
        v14.put("altiliCap", 320.0);
        assertEquals(600.0, Settings.fromMap(v14).altiliCap, 1e-9); // eski varsayılan 600'e
        v14.put("altiliCap", 250.0);
        assertEquals(250.0, Settings.fromMap(v14).altiliCap, 1e-9); // kullanıcının kendi seçimi korunur
        Settings mine = new Settings();
        mine.altiliCap = 320; // yeni sürümde bilerek 320'ye çeken korunur
        assertEquals(320.0, Settings.fromMap(mine.toMap()).altiliCap, 1e-9);
        Altili.Inputs in = rule();
        in.legMin = 0.70;
        in.maxCost = 320;
        List<String> fits = Arrays.asList("1:40 2:25 3:15 4:10 5:10", "1:40 2:25 3:15 4:10 5:10", "1:30 2:15 3:15 4:15 5:15 6:10",
                "1:60 2:40", "1:55 2:25 3:20", "1:50 2:30 3:20");
        Map<String, Object> p = Altili.plan(fits, in);
        assertEquals(288.0, (Double) p.get("cost"), 1e-9); // her ayak %70+, 288 TL: kurala uyar
        assertEquals(null, p.get("overBudget"));
        assertEquals(320.0, (Double) p.get("cap"), 1e-9);
        for (int k = 0; k < 6; k++) {
            double s = 0;
            for (Object h : horses(p, k)) s += share(fits.get(k), String.valueOf(h));
            assertTrue("ayak " + (k + 1) + " %" + s, s >= 70 - 1e-9);
        }
        List<String> wide = Arrays.asList("1:35 2:20 3:15 4:10 5:10 6:5 7:5", "1:45 2:20 3:15 4:10 5:10", "1:25 2:20 3:15 4:15 5:10 6:10 7:5",
                "1:50 2:20 3:15 4:15", "1:30 2:25 3:20 4:15 5:10", "1:40 2:30 3:15 4:15");
        Map<String, Object> w = Altili.plan(wide, in);
        assertEquals(432.0, (Double) w.get("cost"), 1e-9); // ayaklar daraltılmaz
        assertEquals(true, w.get("overBudget"));
        // kurala sığmayan günün kuponu olarak önerilmez; hiçbiri sığmıyorsa öneri yok
        assertEquals(0, Altili.best(Arrays.asList(p, w)));
        assertEquals(1, Altili.best(Arrays.asList(w, p)));
        assertEquals(-1, Altili.best(Arrays.asList(w)));
    }

    static List<Altili.Leg> legsOf(List<String> texts) {
        List<Altili.Leg> out = new ArrayList<>();
        for (String t : texts) out.add(Altili.leg(t));
        return out;
    }

    /** Ayağın en olası n atının toplam payı (0–1). */
    static double top(String leg, int n) {
        double s = 0;
        String[] ts = leg.split(" ");
        for (int i = 0; i < n && i < ts.length; i++) s += Double.parseDouble(ts[i].split(":")[1]) / 100;
        return s;
    }

    static double share(String leg, String horse) {
        for (String t : leg.split(" ")) if (t.startsWith(horse + ":")) return Double.parseDouble(t.substring(horse.length() + 1));
        return 0;
    }

    @Test
    public void couponNeverBelowSixtyAndAboveOnlyProfitableHorses() {
        // her ayakta favori %55: %50 için tek at yeter (1 TL); 60 TL'ye kadar tutmayı en çok artıran atlar eklenir
        Map<String, Object> p = Altili.plan(six("1:55 2:25 3:20"), rule());
        long units = (Long) p.get("units");
        assertTrue("kupon " + units, units >= 60 && units < 120); // 60'ı geçince durur: değer yoksa kârlı at yok
        assertTrue((Double) p.get("hit") >= Math.pow(0.80, 6) - 1e-9);
        for (int k = 0; k < 6; k++) assertEquals("1", horses(p, k).get(0));
        assertEquals(60.0, (Double) p.get("minCost"), 1e-9);
        assertEquals(0.73, (Double) p.get("roi"), 1e-9);
        // %50 kapsaması zaten 60 TL'nin üstündeyse eklenmez
        assertEquals(64L, Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), rule()).get("units"));
        // %50 için 90 TL'den fazlası gerekiyorsa gerektiği kadar (kasanın %7'sine kadar)
        List<String> legs = Arrays.asList("1:40 2:25 3:20 4:15", "1:40 2:25 3:20 4:15", "1:30 2:15 3:15 4:15 5:15 6:10", "1:30 2:15 3:15 4:15 5:15 6:10",
                "1:30 2:15 3:15 4:15 5:15 6:10", "1:60 2:40");
        Map<String, Object> wide = Altili.plan(legs, rule());
        assertEquals(108L, wide.get("units"));
        assertEquals(null, wide.get("overBudget"));
    }

    @Test
    public void profitableHorsesAreAddedAboveTheMinimum() {
        // ganyana göre 2 numaranın kazanma şansı %40, AGF payı %20: değeri 2 (q/p); favori 1 numaranın değeri 1
        List<Altili.Leg> legs = new ArrayList<>();
        List<List<Altili.Horse>> pick = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            Altili.Leg l = Altili.leg("1:50:2 2:20:2.5 3:30:10");
            legs.add(l);
            pick.add(new ArrayList<>(l.horses.subList(0, 1)));
        }
        Altili.Inputs in = inputs(3124.67);
        Altili.fill(pick, legs, in, 1000); // en az tutar yok: yalnızca kârlı atlar
        for (List<Altili.Horse> hs : pick) {
            boolean two = false;
            for (Altili.Horse h : hs) two |= h.no.equals("2");
            assertTrue(two);
        }
        List<List<Altili.Horse>> plain = new ArrayList<>();
        List<Altili.Leg> plainLegs = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            Altili.Leg l = Altili.leg("1:50 2:20 3:30");
            plainLegs.add(l);
            plain.add(new ArrayList<>(l.horses.subList(0, 1)));
        }
        Altili.fill(plain, plainLegs, in, 1000);
        for (List<Altili.Horse> hs : plain) assertEquals(1, hs.size()); // değer yoksa hiç at eklenmez
    }
}
