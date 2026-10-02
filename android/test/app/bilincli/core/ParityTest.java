package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import app.bilincli.core.Engine.Decision;
import app.bilincli.core.Models.BookEvent;
import app.bilincli.core.Models.Candidate;
import app.bilincli.core.Models.Proposal;
import app.bilincli.core.Models.SharpEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;

/** Java çekirdeği, Python sürümüyle (referans) aynı girdide aynı sonucu vermeli. */
public class ParityTest {
    private static Map<String, Object> data;
    private static final double EPS = 1e-9;

    @BeforeClass
    public static void load() throws Exception {
        String path = System.getProperty("parity", "test/fixtures/parity.json");
        data = Json.parseObject(new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8));
    }

    private static double[] doubles(Object list) {
        List<Object> l = Json.arr(list);
        double[] out = new double[l.size()];
        for (int i = 0; i < out.length; i++) out[i] = ((Number) l.get(i)).doubleValue();
        return out;
    }

    @Test
    public void oddsMath() {
        for (Object o : Json.arr(data.get("odds"))) {
            Map<String, Object> c = Json.obj(o);
            double[] odds = doubles(c.get("odds"));
            double[] expected = doubles(c.get("power"));
            double[] actual = OddsMath.devigPower(odds);
            for (int i = 0; i < odds.length; i++) assertEquals(expected[i], actual[i], EPS);
            assertEquals(Json.dbl(c, "margin", 0), OddsMath.margin(odds), EPS);
            assertEquals(Json.dbl(c, "kelly", 0), OddsMath.kellyFraction(0.55, odds[0]), EPS);
            assertEquals(Json.dbl(c, "growth", 0), OddsMath.logGrowth(0.55, odds[0], 0.02), EPS);
        }
    }

    @Test
    public void matching() {
        for (Object o : Json.arr(data.get("matching"))) {
            Map<String, Object> c = Json.obj(o);
            String a = Json.str(c, "a"), b = Json.str(c, "b");
            assertEquals(a, c.get("na"), Matching.normalize(a));
            assertEquals(a, c.get("va"), Matching.variant(a));
            assertEquals(a + " ~ " + b, Json.dbl(c, "sim", -1), Matching.similarity(a, b), EPS);
        }
    }

    @Test
    public void parseTl() {
        for (Object o : Json.arr(data.get("tl"))) {
            List<Object> c = Json.arr(o);
            assertEquals((String) c.get(0), ((Number) c.get(1)).longValue(), Ledger.parseTl((String) c.get(0)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Double>> markets(Object o) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.obj(o).entrySet()) {
            Map<String, Double> inner = new LinkedHashMap<>();
            for (Map.Entry<String, Object> x : ((Map<String, Object>) e.getValue()).entrySet()) {
                inner.put(x.getKey(), ((Number) x.getValue()).doubleValue());
            }
            out.put(e.getKey(), inner);
        }
        return out;
    }

    private static void assertProposal(String ctx, Map<String, Object> exp, Proposal p) {
        List<Object> legs = Json.arr(exp.get("legs"));
        assertEquals(ctx + " bacak sayısı", legs.size(), p.legs.size());
        for (int i = 0; i < legs.size(); i++) {
            List<Object> l = Json.arr(legs.get(i));
            Candidate c = p.legs.get(i);
            assertEquals(ctx, l.get(0), c.book.ref);
            assertEquals(ctx, l.get(1), c.market);
            assertEquals(ctx, l.get(2), c.outcome);
        }
        assertEquals(ctx, Json.dbl(exp, "odds", 0), p.odds, EPS);
        assertEquals(ctx, Json.dbl(exp, "prob", 0), p.prob, EPS);
        assertEquals(ctx, Json.dbl(exp, "fraction", 0), p.stakeFraction, EPS);
        assertEquals(ctx, Json.dbl(exp, "growth", 0), p.growth, EPS);
    }

    @Test
    public void engineDecisions() {
        int n = 0;
        for (Object o : Json.arr(data.get("engine"))) {
            Map<String, Object> c = Json.obj(o);
            String ctx = "senaryo " + n++;
            List<BookEvent> book = new ArrayList<>();
            for (Object bo : Json.arr(c.get("book"))) {
                Map<String, Object> b = Json.obj(bo);
                book.add(new BookEvent(Json.str(b, "ref"), Json.str(b, "home"), Json.str(b, "away"),
                        Instant.parse(Json.str(b, "kickoff")), Json.str(b, "league"), (int) Json.lng(b, "mbs", 1),
                        markets(b.get("odds")), Json.str(b, "code")));
            }
            List<SharpEvent> sharp = new ArrayList<>();
            for (Object so : Json.arr(c.get("sharp"))) {
                Map<String, Object> s = Json.obj(so);
                sharp.add(new SharpEvent(Json.str(s, "ref"), Json.str(s, "sport"), Json.str(s, "home"),
                        Json.str(s, "away"), Instant.parse(Json.str(s, "kickoff")), markets(s.get("fair")), ""));
            }
            Map<String, Object> cfgMap = Json.obj(c.get("cfg"));
            Settings cfg = Settings.fromMap(cfgMap);
            // masaüstü (Python) sürümünün kuralları: maç başına olasılık şartı yok, kupon eşiği fikstürdeki gibi
            cfg.minLegProb = 0;
            cfg.minWinProb = Json.dbl(cfgMap, "minWinProb", 0.20);
            Decision d = Engine.decide(book, sharp, Instant.parse(Json.str(c, "now")), cfg);
            Map<String, Object> stats = Json.obj(c.get("stats"));
            for (String k : new String[] {"eslesen", "karsilastirilan_secim", "avantajli_secim"}) {
                assertEquals(ctx + " " + k, stats.get(k), d.stats.get(k));
            }
            assertEquals(ctx + " pas", Json.bool(c, "pass", false), d.isPass());
            assertEquals(ctx + " gerekçe", Json.str(c, "reason") == null ? "" : Json.str(c, "reason"), d.reason);
            if (d.isPass()) {
                assertNull(c.get("proposal"));
                continue;
            }
            assertNotNull(ctx, c.get("proposal"));
            assertProposal(ctx, Json.obj(c.get("proposal")), d.proposal);
            List<Object> alts = Json.arr(c.get("alternatives"));
            assertEquals(ctx + " alternatif", alts.size(), d.alternatives.size());
            for (int i = 0; i < alts.size(); i++) assertProposal(ctx + " alt" + i, Json.obj(alts.get(i)), d.alternatives.get(i));
        }
    }
}
