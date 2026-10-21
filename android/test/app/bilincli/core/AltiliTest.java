package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    public void noPlayDayStillGivesAlternativesWithinBudget() {
        Altili.Inputs in = inputs(2324.20);
        in.budget = 60; // tek maçtaki gibi kasanın %3'ü (10 TL'ye yuvarlı)
        Map<String, Object> p = Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), in);
        assertEquals(Boolean.FALSE, p.get("play"));
        assertEquals("tutma", p.get("mode"));
        long units = (Long) p.get("units");
        assertTrue("bütçe: " + units, units <= 60 && units >= 32);
        int multi = 0;
        for (int k = 0; k < 6; k++) if (horses(p, k).size() > 1) multi++;
        assertTrue(multi >= 5); // ayakların çoğu alternatifli
        assertTrue((Double) p.get("hit") > Math.pow(0.4, 6) * 10); // tek favorili kupondan çok daha sık tutar
        assertEquals(0.73, (Double) p.get("roi"), 1e-9);
        assertEquals(1L, p.get("kat"));
        assertEquals(0.0, (Double) p.get("stake"), 0);
        assertTrue((String) p.get("reason"), ((String) p.get("reason")).startsWith("Ortalamada zarar: bu kupon"));
        in.maxCost = 8; // kupon sınırı bütçenin yerine geçer
        assertTrue((Long) Altili.plan(six("1:40 2:25 3:15 4:10 5:10"), in).get("units") <= 8);
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
}
