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
        // tutar: kasanın %3'ü (2.15.2; önce %1), 10 TL'ye yuvarlı (5.000 TL -> 150 TL)
        // 2.15.1: beklenen −%3,6 sınırın içinde -> tam tutar (yarım kademe yok)
        assertEquals(15000L, p.get("stake"));
        assertEquals(23700L, p.get("win"));
        assertEquals(Math.round(15000 * (0.61 * 1.58 - 1)), p.get("expected"));
        assertEquals(Math.round(30 * 15000 * (0.61 * 1.58 - 1)), p.get("monthly"));
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
        assertTrue(line, line.startsWith("Günün seçimi: 01.10 14:00 Arsenal – Dep 1 · MS 1 @ 1,52 (Zirve Oran) · tutma %65 · adil 1,55 · beklenen −%2,0 · 150,00 TL (tutarsa 228,00 TL)"));
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
        assertTrue(t[1], t[1].contains("Beklenen sonuç " + Fmt.tl(Math.round(15000 * (0.61 * 1.58 - 1)))));
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
        cfg.pickMinOdds = 1.05; // düşük oranlı örnekler (2.15.6 varsayılanı 1,18 ayrı testte)
        long bal = 500000;
        // değerli -> Kelly (temel tutardan az değil), sınıra (−%6) kadar tam, daha kötüsü 0
        assertTrue(Pick.stakeFor(0.55, 2.0, cfg, bal) >= 15000);
        assertEquals(15000L, Pick.stakeFor(0.62, 1.58, cfg, bal)); // −%2,0
        assertEquals(15000L, Pick.stakeFor(0.60, 1.58, cfg, bal)); // −%5,2: 2.15.1'den beri tam
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
        long bal = 500000; // temel tutar 150 TL (kasanın %3'ü)
        assertEquals(15000L, Pick.stakeFor(0.645, 1.45, cfg, bal)); // −%6,5: tam
        assertEquals(15000L, Pick.stakeFor(0.50, 1.85, cfg, bal)); // −%7,5: tam
        assertEquals(15000L, Pick.stakeFor(0.50, 1.80, cfg, bal)); // −%10: tam (yarım kademe yok)
        assertEquals(15000L, Pick.stakeFor(0.50, 1.70, cfg, bal)); // −%15 (adil 2,00 x 0,85): tam
        assertEquals(0L, Pick.stakeFor(0.50, 1.68, cfg, bal)); // −%16: oynama
        // liste: −%6,5'lik Arsenal MS 1 artık tutarla
        Map<String, Object> ars = null;
        for (Object o : Pick.list(fairs(), null, cfg, bal, NOW)) if ("1".equals(Json.obj(o).get("ref")) && "MS".equals(Json.obj(o).get("m"))) ars = Json.obj(o);
        assertEquals(15000L, ars.get("stake"));
        // 2.15.2: kasanın %3'ü, 10 TL'ye aşağı yuvarlı (5.947 TL -> 170 TL); sabit tutar ayarı önceliklidir
        assertEquals(17000L, Pick.stake(cfg, 594700));
        cfg.pickStake = 100;
        assertEquals(10000L, Pick.stake(cfg, 594700));
        cfg.pickStake = 0;
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

    @Test
    public void profitableFirstThenBalanceScore() {
        // 2.15.4: önce kârlı (adil oranı geçen), sonra denge puanı (kâr + tutma), eşitlikte sık tutan
        double f = 0.03;
        assertTrue(Pick.score(0.60, 1.67, f) < Pick.score(0.90, 1.11, f)); // puanda −%0,1'lik sık tutan önde...
        assertTrue(Pick.rank(0.60 * 1.67 - 1, 0.60, 1.67, 0.90 * 1.11 - 1, 0.90, 1.11, f) < 0); // ...ama +%0,2 kârlı olan önce
        assertTrue(Pick.rank(0.85 * 1.15 - 1, 0.85, 1.15, 0.85 * 1.17 - 1, 0.85, 1.17, f) > 0); // ikisi de kayıpta: puan
        assertTrue(Pick.rank(0.0, 0.5, 2.0, 0.0, 0.5, 2.0, f) == 0);
        List<Object> fx = new ArrayList<>();
        fx.add(row("1", "Arsenal", 8, sel("MS", "1", 0.90, 1.11, 1)));
        fx.add(row("2", "Chelsea", 9, sel("MS", "1", 0.60, 1.67, 1)));
        fx.add(row("3", "Liverpool", 10, sel("CS", "1X", 0.85, 1.15, 1)));
        Settings cfg = new Settings();
        cfg.pickMinOdds = 1.05; // düşük oranlı örnekler
        List<Object> list = Pick.list(fx, null, cfg, 500000, NOW);
        assertEquals("2", Json.obj(list.get(0)).get("ref")); // kârlı
        assertEquals("1", Json.obj(list.get(1)).get("ref"));
        assertEquals("3", Json.obj(list.get(2)).get("ref"));
        assertEquals("2", Pick.choose(fx, null, cfg, 500000, NOW).get("ref")); // günün seçimi de kârlıyı alır
        // Zirve listesi: maçlar en iyi seçimine göre; oynanabilir maç, erken başlayan kayıptaki maçın önünde
        List<Map<String, Object>> z = new ArrayList<>();
        z.add(zrow("e1", "2026-10-01T12:00:00Z", "oynama", -0.03, 0.70, 1.38));
        z.add(zrow("e2", "2026-10-01T18:00:00Z", "oynama", -0.08, 0.60, 1.53));
        z.add(zrow("e2", "2026-10-01T18:00:00Z", "oyna", 0.04, 0.55, 1.89));
        z.add(zrow("e3", "2026-10-01T10:00:00Z", "mac", null, 0, 1.5));
        z.add(zrow("e1", "2026-10-01T12:00:00Z", "oynama", -0.01, 0.80, 1.26));
        Zirve.sortByValue(z);
        StringBuilder order = new StringBuilder();
        for (Map<String, Object> r : z) order.append(r.get("event")).append(":").append(r.get("status")).append(r.get("ev") == null ? "" : "@" + r.get("ev")).append(" ");
        assertEquals("e2:oyna@0.04 e2:oynama@-0.08 e1:oynama@-0.01 e1:oynama@-0.03 e3:mac ", order.toString());
    }

    static Map<String, Object> zrow(String event, String kickoff, String status, Double ev, double p, double tval) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("event", event);
        r.put("kickoff", kickoff);
        r.put("status", status);
        if (ev != null) {
            r.put("ev", ev);
            r.put("p", p);
        }
        r.put("tval", tval);
        return r;
    }

    @Test
    public void profitableSelectionsAreNotifiedOnce() {
        // 2.15.5: adil oranı geçen (beklenen ≥ 0) tek maç seçimleri bildirilir; aynı oranla bir kez
        List<Object> fx = new ArrayList<>();
        fx.add(row("1", "Arsenal", 8, sel("MS", "1", 0.60, 1.70, 1))); // +%2: kârlı
        fx.add(row("2", "Chelsea", 9, sel("MS", "1", 0.80, 1.20, 1))); // −%4
        Map<String, Object> zirve = new LinkedHashMap<>();
        List<Object> zr = new ArrayList<>();
        zr.add(zirveRow("3", "Liverpool", 10, "AU25", "UST", 0.55, 1.95, "oyna")); // Zirve bildirimi zaten gider
        zr.add(zirveRow("4", "Everton", 11, "KG", "VAR", 0.50, 2.02, "oynama")); // +%1: Zirve "oyna" değil ama kârlı
        zirve.put("rows", zr);
        Settings cfg = new Settings();
        List<Object> list = Pick.list(fx, zirve, cfg, 500000, NOW);
        List<Object> notified = new ArrayList<>();
        String[] n = Pick.profitNotice(list, notified);
        assertEquals("2 kârlı seçim", n[0]);
        assertTrue(n[1], n[1].contains("01.10 14:00 Arsenal – Dep 1 · MS 1 @ 1,70 · tutma %60 · adil 1,67 · beklenen +%2,0 · 150,00 TL (tutarsa 255,00 TL)"));
        assertTrue(n[1], n[1].contains("Everton – Dep 4 · KG Var @ 2,02 (Zirve Oran)"));
        assertFalse(n[1], n[1].contains("Liverpool") || n[1].contains("Chelsea"));
        assertTrue(n[1], n[1].endsWith("Oynamadan önce oranın Bilyoner'de hâlâ aynı olduğunu kontrol et."));
        assertEquals(2, notified.size());
        assertNull(Pick.profitNotice(list, notified)); // aynı oran: tekrar yok
        // oran yükselirse yeniden; tek seçimde başlıkta maç
        Json.obj(Json.arr(Json.obj(fx.get(0)).get("sel")).get(0)).put("i", 1.75);
        n = Pick.profitNotice(Pick.list(fx, zirve, cfg, 500000, NOW), notified);
        assertEquals("Kârlı seçim · Arsenal – Dep 1", n[0]);
        assertNull(Pick.profitNotice(new ArrayList<Object>(), notified));
    }

    static Map<String, Object> zirveRow(String ref, String home, long hours, String m, String o, double p, double tval, String status) {
        Map<String, Object> z = new LinkedHashMap<>(row(ref, home, hours));
        z.remove("sel");
        z.put("m", m);
        z.put("o", o);
        z.put("label", Models.outcomeLabel(m, o));
        z.put("p", p);
        z.put("tval", tval);
        z.put("val", tval - 0.05);
        z.put("mbs", 1L);
        z.put("status", status);
        return z;
    }

    @Test
    public void everyBetTypeKeepsItsBestInTheList() {
        // 2.15.6: ilk 100'ü Çifte Şans doldursa da MS 2 ve 1,5 Üst listede (bahis türü süzgeci için)
        List<Object> fx = new ArrayList<>();
        for (int k = 0; k < 110; k++) fx.add(row("c" + k, "Takım " + k, 2 + k % 18, sel("CS", "1X", 0.80, 1.22, 1)));
        fx.add(row("m", "Lazio", 5, sel("MS", "2", 0.55, 1.70, 1)));
        Map<String, Object> au = sel("AU@1.5", "UST", 0.72, 1.25, 1);
        au.put("model", true);
        fx.add(row("u", "Roma", 6, au));
        List<Object> list = Pick.list(fx, null, new Settings(), 500000, NOW);
        assertEquals(102, list.size()); // ilk 100 + MS 2 + 1,5 Üst
        int cs = 0;
        for (Object o : list) if ("CS".equals(Json.obj(o).get("m"))) cs++;
        assertEquals(100, cs);
        Map<String, Object> ms = Json.obj(list.get(100)), u = Json.obj(list.get(101));
        assertEquals("MS 2", ms.get("label"));
        assertEquals("1,5 Üst", u.get("label"));
        assertTrue(Json.bool(u, "model", false));
        assertEquals("MS", Pick.group("MS"));
        assertEquals("AU", Pick.group("AU25"));
        assertEquals("AU", Pick.group("AU@3.5"));
        assertEquals("TG", Pick.group("DEPG@0.5"));
        assertEquals("HMS", Pick.group("HMS@-1.0"));
        assertEquals("BASKET", Pick.group("BT@161.5"));
    }

    @Test
    public void minOddsDefault118() {
        // 2.15.6: kullanıcı isteği: tek maçta 1,18'in altındaki oranlar listelenmez, önerilmez, bildirilmez
        Settings cfg = new Settings();
        assertEquals(1.18, cfg.pickMinOdds, 0);
        List<Object> fx = new ArrayList<>();
        fx.add(row("1", "Arsenal", 8, sel("CS", "1X", 0.88, 1.17, 1))); // 1,17: alınmaz (+%3 kârlı olsa da)
        fx.add(row("2", "Chelsea", 9, sel("MS", "1", 0.82, 1.18, 1))); // 1,18: alınır
        List<Object> list = Pick.list(fx, null, cfg, 500000, NOW);
        assertEquals(1, list.size());
        assertEquals("2", Json.obj(list.get(0)).get("ref"));
        assertEquals("2", Pick.choose(fx, null, cfg, 500000, NOW).get("ref"));
        assertNull(Pick.profitNotice(list, new ArrayList<Object>())); // 1,17'lik kârlı seçim bildirilmez
        cfg.pickMinOdds = 1.10;
        assertEquals(2, Pick.list(fx, null, cfg, 500000, NOW).size());
        assertEquals(1.10, Settings.fromMap(cfg.toMap()).pickMinOdds, 0);
        cfg.pickMinOdds = 1.0;
        assertTrue(cfg.validate().contains("en düşük oran"));
        // eski kayıtta alan yok: 1,18 gelir
        Map<String, Object> old = new Settings().toMap();
        old.remove("pickMinOdds");
        assertEquals(1.18, Settings.fromMap(old).pickMinOdds, 0);
    }
}
