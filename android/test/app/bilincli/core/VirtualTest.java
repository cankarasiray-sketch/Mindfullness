package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.ScoreResult;
import app.bilincli.core.Models.SharpEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** 2.3: sanal takip (pas günlerinde en yakın seçimin 100 TL'lik sanal bahis sonucu). */
public class VirtualTest {
    static final Instant NOW = CoreTest.NOW;
    static final Instant KO = NOW.plusSeconds(8 * 3600);

    static Map<String, Object> sel(String sref, String market, String outcome, double odds, double p, Instant ko) {
        return Engine.selection("Macaristan", "Gürcistan", ko.toString(), "soccer_uefa_nations_league", sref, market, outcome, odds, p);
    }

    static Map<String, ScoreResult> score(String sref, int home, int away) {
        Map<String, ScoreResult> m = new LinkedHashMap<>();
        m.put(sref, new ScoreResult(true, home, away));
        return m;
    }

    @Test
    public void passDayNearestCarriesWhatSettlementNeeds() {
        Engine.Decision d = Engine.decide(Arrays.asList(FeatureTest.book(1, 1.80, 1, 8), FeatureTest.book(2, 1.95, 1, 9)),
                Arrays.asList(FeatureTest.sharp(1, 0.50, 8), FeatureTest.sharp(2, 0.50, 9)), NOW, new Settings());
        assertTrue(d.isPass());
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) d.stats.get("en_yakin_secim");
        assertEquals("s2", s.get("sref"));
        assertEquals("lig", s.get("sport"));
        assertEquals("MS", s.get("market"));
        assertEquals("1", s.get("outcome"));
        assertEquals(1.95, (Double) s.get("odds"), 0);
        assertEquals(0.50, (Double) s.get("p"), 0);
        // Zirve'deki en yakın seçim de aynı biçimde
        List<Object> fairs = ZirveTest.fairs();
        Json.obj(Json.arr(Json.obj(fairs.get(0)).get("sel")).get(0)).put("p", 0.70);
        Map<String, Object> near = Json.obj(Zirve.evaluate(Zirve.parse(ZirveTest.body()), fairs, null, new Settings(), 500000, NOW).get("nearest"));
        Map<String, Object> zs = Json.obj(near.get("selection"));
        assertEquals("sb1", zs.get("sref"));
        assertEquals("soccer_uefa_nations_league", zs.get("sport"));
        assertEquals(1.40, Json.dbl(zs, "odds", 0), 0); // Zirve oranı
        assertEquals(0.70, Json.dbl(zs, "p", 0), 0);
    }

    @Test
    public void recordsOncePerDayAndKindReplacingOnlyBeforeKickoff() {
        Ledger.MemoryStorage store = new Ledger.MemoryStorage(null);
        Virtual v = new Virtual(store);
        assertTrue(v.record("2026-10-02", Virtual.NORMAL, sel("e1", "MS", "1", 1.99, 0.444, KO), NOW));
        assertFalse(v.record("2026-10-02", Virtual.NORMAL, sel("e1", "MS", "1", 1.99, 0.444, KO), NOW)); // aynısı
        assertTrue(v.record("2026-10-02", Virtual.ZIRVE, sel("e2", "MS", "2", 1.74, 0.535, KO), NOW)); // ayrı tür
        // gün içinde seçim değişti, eski maç başlamadı: yenisi geçer
        assertTrue(v.record("2026-10-02", Virtual.NORMAL, sel("e3", "AU25", "UST", 1.35, 0.66, KO.plusSeconds(3600)), NOW.plusSeconds(60)));
        Map<String, Object> view = v.view();
        assertEquals(2L, view.get("open"));
        assertEquals(2, Json.arr(view.get("recent")).size());
        assertEquals("2,5 Üst", Json.obj(Json.arr(view.get("recent")).get(0)).get("label")); // yeniden eskiye
        // eski seçimin maçı başladıysa kayıt sabit kalır
        assertFalse(v.record("2026-10-02", Virtual.NORMAL, sel("e4", "MS", "X", 3.4, 0.25, KO.plusSeconds(7200)), KO.plusSeconds(3700)));
        // eksik ya da başlamış seçim kaydedilmez
        assertFalse(v.record("2026-10-03", Virtual.NORMAL, null, NOW));
        assertFalse(v.record("2026-10-03", Virtual.NORMAL, sel("e5", "MS", "1", 2.0, 0.5, NOW.minusSeconds(60)), NOW));
        Map<String, Object> noRef = sel("e6", "MS", "1", 2.0, 0.5, KO);
        noRef.remove("sref");
        assertFalse(v.record("2026-10-03", Virtual.NORMAL, noRef, NOW));
        // kalıcı
        assertEquals(2L, new Virtual(store).view().get("open"));
    }

    @Test
    public void settlesWithScoresAndSumsVirtualProfit() {
        Virtual v = new Virtual(new Ledger.MemoryStorage(null));
        v.record("2026-10-02", Virtual.NORMAL, sel("e1", "MS", "1", 1.40, 0.645, KO), NOW);
        v.record("2026-10-02", Virtual.ZIRVE, sel("e2", "MS", "2", 1.74, 0.535, KO), NOW);
        v.record("2026-10-03", Virtual.NORMAL, sel("e3", "MS", "1", 1.40, 0.645, KO.plusSeconds(86400)), NOW);
        Instant after = KO.plusSeconds(3 * 3600);
        assertEquals(new java.util.LinkedHashSet<>(Collections.singletonList("soccer_uefa_nations_league")), v.dueSports(after));
        assertTrue(v.dueSports(NOW).isEmpty()); // maçlar bitmedi
        Map<String, ScoreResult> s = score("e1", 2, 0);
        s.putAll(score("e2", 1, 1));
        assertEquals(2, v.resolve(s, after));
        Map<String, Object> view = v.view();
        assertEquals(2L, view.get("n"));
        assertEquals(1L, view.get("won"));
        assertEquals(1L, view.get("lost"));
        assertEquals(1L, view.get("open"));
        assertEquals(4000L - 10000L, view.get("pl")); // +40 TL, −100 TL
        assertEquals(0.5, (Double) view.get("hitRate"), 1e-12);
        assertEquals((0.645 + 0.535) / 2, (Double) view.get("expectedHitRate"), 1e-12);
        assertEquals(Math.round(10000 * (0.645 * 1.40 - 1) + 10000 * (0.535 * 1.74 - 1)), view.get("expectedPl"));
        assertEquals(1L, Json.obj(view.get("normal")).get("won"));
        assertEquals(4000L, Json.obj(view.get("normal")).get("pl"));
        assertEquals(-10000L, Json.obj(view.get("zirve")).get("pl"));
        Map<String, Object> last = Json.obj(Json.arr(view.get("recent")).get(1));
        assertEquals(Models.LOST, last.get("result"));
        assertEquals("1-1", last.get("score"));
        assertEquals(-10000L, last.get("pl"));
        // 3 günde sonucu bulunamayan kayıt iade sayılır, ölçüye girmez
        assertEquals(1, v.resolve(new LinkedHashMap<String, ScoreResult>(), KO.plusSeconds(86400 + 4 * 86400)));
        assertEquals(1L, v.view().get("voids"));
        assertEquals(2L, v.view().get("n"));
    }

    @Test
    public void settlementFetchesScoresForVirtualEvenWithoutOpenCoupons() {
        CoreTest.TestClock clock = new CoreTest.TestClock();
        Ledger ledger = new Ledger(new Ledger.MemoryStorage(null), clock);
        Virtual v = new Virtual(new Ledger.MemoryStorage(null));
        v.record("2026-10-02", Virtual.NORMAL, sel("e1", "MS", "1", 1.40, 0.645, clock.now().plusSeconds(3600)), clock.now());
        final List<Set<String>> asked = new ArrayList<>();
        Daily.Sources src = new Daily.Sources() {
            public List<BookEvent> book() {
                return new ArrayList<>();
            }

            public List<SharpEvent> sharp() {
                return new ArrayList<>();
            }

            public Map<String, ScoreResult> scores(Set<String> sportKeys) {
                asked.add(sportKeys);
                return score("e1", 3, 1);
            }
        };
        assertTrue(Daily.settle(ledger, src, null, v).isEmpty());
        assertTrue(asked.isEmpty()); // maç bitmedi: skor sorulmaz
        clock.now = clock.now.plusSeconds(5 * 3600);
        Daily.settle(ledger, src, null, v);
        assertEquals(1, asked.size());
        assertEquals(Collections.singleton("soccer_uefa_nations_league"), asked.get(0));
        assertEquals(1L, v.view().get("won"));
        Daily.settle(ledger, src, null, v);
        assertEquals(1, asked.size()); // sonuçlanmış: tekrar sorulmaz
        assertNull(Json.obj(v.view().get("zirve")).get("hitRate"));
        assertNotNull(v.view().get("recent"));
    }
}
