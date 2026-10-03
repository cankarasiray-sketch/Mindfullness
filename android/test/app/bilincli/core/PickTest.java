package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 2.9: günün seçimi — tek maç, sık tutan, adil orana en yakın (normal oran ve Zirve Oran). */
public class PickTest {
    static final Instant NOW = CoreTest.NOW; // 01.10 06:00 Türkiye

    static Map<String, Object> sel(String m, String o, double p, double i, long mbs) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("m", m);
        s.put("o", o);
        s.put("label", Models.outcomeLabel(m, o));
        s.put("p", p);
        s.put("i", i);
        s.put("mbs", mbs);
        return s;
    }

    static Map<String, Object> row(String ref, String home, long hours, Map<String, Object>... sels) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ref", ref);
        r.put("sref", "s" + ref);
        r.put("sport", "soccer_epl");
        r.put("code", "1" + ref);
        r.put("home", home);
        r.put("away", "Dep " + ref);
        r.put("kickoff", NOW.plusSeconds(hours * 3600).toString());
        r.put("league", "İngiltere Premier Lig");
        List<Object> s = new ArrayList<>();
        for (Map<String, Object> x : sels) s.add(x);
        r.put("sel", s);
        return r;
    }

    @SuppressWarnings("unchecked")
    static List<Object> fairs() {
        List<Object> f = new ArrayList<>();
        // MS 1: %64,5, 1,45 -> −%6,5 (aday)
        f.add(row("1", "Arsenal", 8, sel("MS", "1", 0.645, 1.45, 1), sel("MS", "2", 0.15, 5.50, 1)));
        // MS 1: %62, 1,55 -> −%3,9 (en iyi) ama MBS 2: tek oynanamaz
        f.add(row("2", "Chelsea", 9, sel("MS", "1", 0.62, 1.55, 2)));
        // 2,5 Üst: %61, 1,58 -> −%3,6 ama %60 şartını geçer; KG Var %55 (şart altı)
        f.add(row("3", "Liverpool", 10, sel("AU25", "UST", 0.61, 1.58, 1), sel("KG", "VAR", 0.55, 1.75, 1)));
        // 20 dk sonra başlıyor: oynamaya vakit yok
        f.add(row("4", "Everton", 0, sel("MS", "1", 0.70, 1.45, 1)));
        // pencere dışı (30 saat sonra)
        f.add(row("5", "Fulham", 30, sel("MS", "1", 0.70, 1.48, 1)));
        // 1,05'in altındaki oran anlamsız
        f.add(row("6", "Spurs", 7, sel("MS", "1", 0.97, 1.04, 1)));
        Json.obj(f.get(3)).put("kickoff", NOW.plusSeconds(20 * 60).toString());
        return f;
    }

    @Test
    public void choosesClosestToFairAmongFrequentSingles() {
        Settings cfg = new Settings();
        cfg.pickMaxLoss = 0.06; // 2.13 kademeleri (2.15 varsayılanı %15: maxLoss15Default)
        Map<String, Object> p = Pick.choose(fairs(), null, cfg, 500000, NOW);
        assertEquals("3", p.get("ref"));
        assertEquals("AU25", p.get("m"));
        assertEquals("UST", p.get("o"));
        assertEquals("2,5 Üst", p.get("label"));
        assertEquals("iddaa", p.get("source"));
        assertEquals(0.61 * 1.58 - 1, Json.dbl(p, "ev", 0), 1e-12);
        assertEquals(1 / 0.61, Json.dbl(p, "fair", 0), 1e-12);
        assertEquals(2L, p.get("candidates")); // Arsenal MS 1 ve Liverpool 2,5 Üst
        assertFalse(Json.bool(p, "value", true));
        // tutar: kasanın %1'i, 10 TL'ye yuvarlı (5.000 TL -> 50 TL)
        // 2.15.1: beklenen −%3,6 sınırın içinde -> tam tutar (yarım kademe yok)
        assertEquals(5000L, p.get("stake"));
        assertEquals(7900L, p.get("win"));
        assertEquals(Math.round(5000 * (0.61 * 1.58 - 1)), p.get("expected"));
        assertEquals(Math.round(30 * 5000 * (0.61 * 1.58 - 1)), p.get("monthly"));
        assertFalse(Json.bool(p, "skip", false));
        assertEquals(1, Json.arr(p.get("alternatives")).size());
        assertEquals("soccer_epl", p.get("sport"));
        assertEquals("s3", p.get("sref"));
        // sabit tutar ayarı ve daha sık tutma şartı
        cfg.pickStake = 25;
        cfg.pickMinProb = 0.63;
        p = Pick.choose(fairs(), null, cfg, 500000, NOW);
        assertEquals("1", p.get("ref"));
        assertEquals(0L, p.get("stake")); // −%6,5: sınırı aşıyor, oynanmaz
        assertTrue(Json.bool(p, "skip", false));
        cfg.pickMaxLoss = 0.10;
        assertEquals(2500L, Pick.choose(fairs(), null, cfg, 500000, NOW).get("stake")); // sınır −%10: tam (25 TL)
        cfg.pickMaxLoss = 0.06;
        assertEquals(0L, Json.arr(p.get("alternatives")).size());
        // kapalı ya da aday yok
        cfg.dailyPick = false;
        assertNull(Pick.choose(fairs(), null, cfg, 500000, NOW));
        cfg.dailyPick = true;
        cfg.pickMinProb = 0.95;
        assertNull(Pick.choose(fairs(), null, cfg, 500000, NOW));
        // kasa küçükken en az 10 TL
        assertEquals(1000L, Pick.stake(new Settings(), 30000));
        assertEquals(0L, Pick.stake(new Settings(), 0));
    }

    @Test
    public void zirveOddsCountWhenBetterAndConsistent() {
        Settings cfg = new Settings();
        Map<String, Object> zirve = new LinkedHashMap<>();
        List<Object> rows = new ArrayList<>();
        // Arsenal MS 1 Zirve'de 1,52 (normal 1,45): %64,5 x 1,52 − 1 = −%2,0 -> en iyi
        Map<String, Object> z = new LinkedHashMap<>(Json.obj(fairs().get(0)));
        z.remove("sel");
        z.put("m", "MS");
        z.put("o", "1");
        z.put("label", "MS 1");
        z.put("p", 0.645);
        z.put("tval", 1.52);
        z.put("val", 1.45);
        z.put("mbs", 1L);
        z.put("status", "oynama");
        rows.add(z);
        // oranı değişmiş satır yok sayılır
        Map<String, Object> bad = new LinkedHashMap<>(z);
        bad.put("ref", "3");
        bad.put("m", "AU25");
        bad.put("o", "UST");
        bad.put("tval", 1.90);
        bad.put("p", 0.61);
        bad.put("status", "degisti");
        rows.add(bad);
        zirve.put("rows", rows);
        Map<String, Object> p = Pick.choose(fairs(), zirve, cfg, 500000, NOW);
        assertEquals("zirve", p.get("source"));
        assertEquals("1", p.get("ref"));
        assertEquals(1.52, Json.dbl(p, "odds", 0), 0);
        assertEquals(1.45, Json.dbl(p, "normalOdds", 0), 0);
        String line = Pick.line(p);
        assertTrue(line, line.startsWith("Günün seçimi: 01.10 14:00 Arsenal – Dep 1 · MS 1 @ 1,52 (Zirve Oran) · tutma %65 · adil 1,55 · beklenen −%2,0 · 50,00 TL (tutarsa 76,00 TL)"));
        // gerçekten değerli: eşik geçilirse işaretlenir
        z.put("tval", 1.62);
        assertTrue(Json.bool(Pick.choose(fairs(), zirve, cfg, 500000, NOW), "value", false));
    }

    @Test
    public void passNotificationLeadsWithPick() {
        Settings cfg = new Settings();
        cfg.pickMaxLoss = 0.06;
        Map<String, Object> p = Pick.choose(fairs(), null, cfg, 500000, NOW);
        Engine.Decision d = Engine.decide(new ArrayList<Models.BookEvent>(), new ArrayList<Models.SharpEvent>(), NOW, cfg);
        Daily.Result r = new Daily.Result();
        r.day = "2026-10-01";
        r.decision = d;
        String[] t = Texts.notification(null, r, p);
        assertEquals("Günün seçimi · Liverpool – Dep 3 · 2,5 Üst @ 1,58", t[0]);
        assertTrue(t[1], t[1].contains("Değerli seçim yok"));
        assertTrue(t[1], t[1].contains("Beklenen sonuç " + Fmt.tl(Math.round(5000 * (0.61 * 1.58 - 1)))));
        assertTrue(Texts.notification(null, r, null)[0].startsWith("Bugün pas"));
    }

    @Test
    public void playedPickIsTrackedSeparately() {
        CoreTest.TestClock clock = new CoreTest.TestClock();
        Ledger.MemoryStorage st = new Ledger.MemoryStorage(null);
        Ledger l = new Ledger(st, clock);
        l.deposit(100000, "t");
        @SuppressWarnings("unchecked")
        Map<String, Object>[] f = Promo.find(fairs(), "3", "AU25", "UST");
        long id = Promo.recordPick(l, f[0], f[1], 1.58, 5000, "2026-10-01");
        Ledger.Coupon c = new Ledger(st, clock).coupon(id);
        assertTrue(c.pick);
        assertFalse(c.promo);
        assertEquals(5000, c.stake);
        assertEquals(95000, l.balance());
        // sanal takipte ayrı tür
        Virtual v = new Virtual(new Ledger.MemoryStorage(null));
        assertTrue(v.record("2026-10-01", Virtual.PICK, Pick.selection(Pick.choose(fairs(), null, new Settings(), 500000, NOW)), NOW));
        assertEquals(1L, Json.obj(v.view().get("secim")).get("open"));
        assertEquals(0L, Json.obj(v.view().get("normal")).get("open"));
        // ayar doğrulama ve kalıcılık
        Settings s = new Settings();
        assertTrue(s.dailyPick);
        assertEquals(0.60, s.pickMinProb, 0);
        s.pickMinProb = 0.2;
        assertTrue(s.validate().contains("Günün seçimi"));
        s.pickMinProb = 0.7;
        s.pickStake = 30;
        Settings back = Settings.fromMap(s.toMap());
        assertEquals(0.7, back.pickMinProb, 0);
        assertEquals(30, back.pickStake, 0);
    }

    @Test
    public void balanceScoreTieredStakeSkipAndBands() {
        Settings cfg = new Settings();
        cfg.pickMaxLoss = 0.06;
        long bal = 500000;
        // değerli -> Kelly (temel tutardan az değil), sınıra (−%6) kadar tam, daha kötüsü 0
        assertTrue(Pick.stakeFor(0.55, 2.0, cfg, bal) >= 5000);
        assertEquals(5000L, Pick.stakeFor(0.62, 1.58, cfg, bal)); // −%2,0
        assertEquals(5000L, Pick.stakeFor(0.60, 1.58, cfg, bal)); // −%5,2: 2.15.1'den beri tam
        assertEquals(0L, Pick.stakeFor(0.80, 1.09, cfg, bal)); // −%12,8
        // denge puanı: aynı beklenen değerde sık tutan önde; tutma farkı küçükse beklenen değer belirler
        double f = 0.01;
        assertTrue(Pick.score(0.80, 1.20, f) > Pick.score(0.32, 3.0, f)); // ikisi de −%4
        assertTrue(Pick.score(0.60, 1.63, f) > Pick.score(0.75, 1.25, f)); // −%2,2 > −%6,3
        assertEquals(0.80 * 1.20 - 1, Pick.score(0.80, 1.20, 0.002), 0.001); // küçük payda ≈ beklenen değer
        // bütün seçimler sınırın altında: kart "oynama", bildirim de öyle
        List<Object> bad = new ArrayList<>();
        bad.add(row("9", "Belarus", 8, sel("DEPG@0.5", "ALT", 0.81, 1.09, 1), sel("MS", "1", 0.76, 1.16, 1)));
        Map<String, Object> p = Pick.choose(bad, null, cfg, bal, NOW);
        assertTrue(Json.bool(p, "skip", false));
        assertEquals(0L, p.get("stake"));
        assertEquals(0L, p.get("monthly"));
        Daily.Result r = new Daily.Result();
        r.day = "2026-10-01";
        r.decision = Engine.decide(new ArrayList<Models.BookEvent>(), new ArrayList<Models.SharpEvent>(), NOW, cfg);
        String[] t = Texts.notification(null, r, p);
        assertTrue(t[0], t[0].startsWith("Bugün oynama · en iyi tek maç bile −%"));
        assertTrue(t[1], t[1].contains("sınır −%6,0"));
        // bant tablosu: her tutma aralığında sayı ve en iyi beklenen
        List<Object> bands = Pick.bands(fairs(), null, cfg, bal, NOW);
        assertEquals(5, bands.size());
        Map<String, Object> b60 = Json.obj(bands.get(1)); // %60–65
        assertEquals(0.60, Json.dbl(b60, "from", 0), 0);
        assertEquals(2L, b60.get("n")); // Arsenal MS 1 %64,5, Liverpool Üst %61
        assertEquals(0.61 * 1.58 - 1, Json.dbl(b60, "bestEv", 0), 1e-9);
        assertEquals(0L, Json.obj(bands.get(3)).get("n")); // %70–80 boş
        assertNull(Json.obj(bands.get(3)).get("bestEv"));
        // ayar
        Settings s = new Settings();
        assertEquals(0.15, s.pickMaxLoss, 0);
        s.pickMaxLoss = 0.5;
        assertTrue(s.validate().contains("en kötü beklenen"));
        s.pickMaxLoss = 0.08;
        assertEquals(0.08, Settings.fromMap(s.toMap()).pickMaxLoss, 0);
    }
    @Test
    public void maxLoss15Default() {
        // 2.15: adil oranın %15 altına kadar tutar verilir (beklenen −%15); 2.15.1: hep tam tutar
        Settings cfg = new Settings();
        assertEquals(0.15, cfg.pickMaxLoss, 0);
        long bal = 500000; // temel tutar 50 TL
        assertEquals(5000L, Pick.stakeFor(0.645, 1.45, cfg, bal)); // −%6,5: tam
        assertEquals(5000L, Pick.stakeFor(0.50, 1.85, cfg, bal)); // −%7,5: tam
        assertEquals(5000L, Pick.stakeFor(0.50, 1.80, cfg, bal)); // −%10: tam (yarım kademe yok)
        assertEquals(5000L, Pick.stakeFor(0.50, 1.70, cfg, bal)); // −%15 (adil 2,00 x 0,85): tam
        assertEquals(0L, Pick.stakeFor(0.50, 1.68, cfg, bal)); // −%16: oynama
        // liste: −%6,5'lik Arsenal MS 1 artık tutarla
        Map<String, Object> ars = null;
        for (Object o : Pick.list(fairs(), null, cfg, bal, NOW)) if ("1".equals(Json.obj(o).get("ref")) && "MS".equals(Json.obj(o).get("m"))) ars = Json.obj(o);
        assertEquals(5000L, ars.get("stake"));
        assertFalse(Json.bool(ars, "skip", false));
        // eski kayıt (%6 ya da elle girilmiş başka değer) bir kez %15'e taşınır; sonradan değiştirilen korunur
        Map<String, Object> old = new Settings().toMap();
        old.put("v", 7L);
        old.put("pickMaxLoss", 0.06);
        Settings moved = Settings.fromMap(old);
        assertEquals(0.15, moved.pickMaxLoss, 0);
        moved.pickMaxLoss = 0.10;
        assertEquals(0.10, Settings.fromMap(moved.toMap()).pickMaxLoss, 0);
    }
}
