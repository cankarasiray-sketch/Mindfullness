package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.bilincli.core.Models.SharpEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 1.9.1: birden fazla The Odds API anahtarı (her biri kendi hesabının kotası). */
public class KeysTest {
    static final String A = "aaaaaaaaaaaa1111", B = "bbbbbbbbbbbb2222", C = "cccccccccccc3333";

    /** Anahtar -> yanıt: "401", "429" ya da kalan kredi. */
    static Http fake(final Map<String, String> answers, final List<String> used) {
        return new Http() {
            public Response get(String url, Map<String, String> headers) throws ProviderException {
                String key = url.replaceAll(".*apiKey=([^&]*).*", "$1");
                used.add(key);
                String a = answers.get(key);
                if ("401".equals(a)) throw new ProviderException(Http.safe(url) + " -> HTTP 401: {\"message\":\"Usage quota has been reached\"}");
                if ("429".equals(a)) throw new ProviderException(Http.safe(url) + " -> HTTP 429: too many requests");
                Map<String, String> h = new LinkedHashMap<>();
                h.put("x-requests-remaining", a);
                h.put("x-requests-used", String.valueOf(500 - Long.parseLong(a)));
                h.put("x-requests-last", "1");
                return new Response(200, "[]", h);
            }
        };
    }

    static Settings cfg(String keys) {
        Settings s = new Settings();
        s.oddsApiKey = keys;
        return s;
    }

    @Test
    public void keysParsedDedupedAndValidated() {
        Settings s = Settings.fromMap(cfg("  " + A + " , " + B + "\n" + B + ";" + C + " ").toMap());
        assertEquals(Arrays.asList(A, B, C), s.apiKeys());
        assertEquals(A + "\n" + B + "\n" + C, s.oddsApiKey);
        assertEquals("…1111", Settings.keyLabel(A));
        assertNull(s.validate());
        s.oddsApiKey = "k1 k2 k3 k4 k5 k6 k7 k8 k9 k10 k11 k12 k13 k14 k15";
        assertNull(s.validate()); // 15 anahtar kabul (2.14)
        assertEquals(15, s.apiKeys().size());
        s.oddsApiKey = "k1 k2 k3 k4 k5 k6 k7 k8 k9 k10 k11 k12 k13 k14 k15 k16";
        assertTrue(s.validate().contains("En fazla 15"));
        assertEquals(Collections.singletonList("abc123"), cfg(" abc123 ").apiKeys()); // tek anahtar eskisi gibi
    }

    @Test
    public void exhaustedOrInvalidKeyIsSkippedAndCreditsAreSummed() throws Exception {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put(A, "401"); // kredisi bitmiş
        answers.put(B, "299");
        answers.put(C, "401"); // geçersiz (yanlış yazılmış)
        List<String> used = new ArrayList<>();
        OddsApi api = new OddsApi(fake(answers, used), cfg(A + "\n" + B + "\n" + C));
        long now = System.currentTimeMillis();
        api.keyCredits.put(Settings.keyId(A), new long[] {450, 50, now});
        api.keyCredits.put(Settings.keyId(B), new long[] {300, 200, now});
        api.fetchEvents(Collections.singletonList("soccer_epl"));
        assertEquals(Arrays.asList(C, A, B), used); // önce hiç ölçülmemiş, sonra kredisi en çok kalan
        assertEquals("299", api.remaining); // 0 + 299 + 0
        assertEquals(String.valueOf(50 + 201), api.used);
        assertEquals(1, api.spent);
        assertTrue(api.keySummary(), api.keySummary().contains("…2222 299 kalan") && api.keySummary().contains("…1111 geçersiz ya da kredisi bitti (401)"));
        // aynı çalışmada sorunlu anahtarlar bir daha denenmez
        used.clear();
        api.fetchEvents(Collections.singletonList("soccer_epl"));
        assertEquals(Collections.singletonList(B), used);
    }

    @Test
    public void spreadsLoadToKeyWithMostCredits() throws Exception {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put(A, "99");
        answers.put(B, "399");
        List<String> used = new ArrayList<>();
        OddsApi api = new OddsApi(fake(answers, used), cfg(A + "\n" + B));
        api.keyCredits.put(Settings.keyId(A), new long[] {100, 400, 0});
        api.keyCredits.put(Settings.keyId(B), new long[] {400, 100, 0});
        api.fetchEvents(Collections.singletonList("soccer_epl"));
        assertEquals(Collections.singletonList(B), used);
        assertEquals("499", api.remaining); // A'nın eski ölçümü 100 + B'nin yeni ölçümü 399
    }

    @Test
    public void allKeysFailingGivesOneClearError() throws Exception {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put(A, "401");
        answers.put(B, "429");
        OddsApi api = new OddsApi(fake(answers, new ArrayList<String>()), cfg(A + " " + B));
        try {
            api.fetchSports();
            fail();
        } catch (Http.ProviderException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Tüm API anahtarları kullanılamadı"));
            assertTrue(e.getMessage(), e.getMessage().contains("…2222 istek sınırı (429)"));
            assertTrue(!e.getMessage().contains(A) && !e.getMessage().contains(B)); // anahtar sızmaz
        }
        // tek anahtarda davranış ve hata metni değişmez
        try {
            new OddsApi(fake(answers, new ArrayList<String>()), cfg(A)).fetchSports();
            fail();
        } catch (Http.ProviderException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("HTTP 401"));
        }
    }

    @Test
    public void storedCreditsMergeExpireAndSum() {
        long reset = KeyCredits.lastResetMillis(LocalDate.of(2026, 10, 15), 1);
        assertEquals(java.time.Instant.parse("2026-09-30T21:00:00Z").toEpochMilli(), reset); // 1 Ekim 00:00 TR
        assertEquals(java.time.Instant.parse("2026-09-19T21:00:00Z").toEpochMilli(), KeyCredits.lastResetMillis(LocalDate.of(2026, 10, 15), 20));
        Map<String, long[]> old = new LinkedHashMap<>();
        old.put(Settings.keyId(A), new long[] {120, 380, reset + 1000});
        old.put(Settings.keyId(B), new long[] {0, 500, reset - 1000}); // yenilenmeden önce: atılır
        old.put("silinmis", new long[] {500, 0, reset + 1000});
        Map<String, long[]> parsed = KeyCredits.parse(KeyCredits.write(old), reset);
        assertEquals(2, parsed.size());
        assertNull(parsed.get(Settings.keyId(B)));
        Map<String, long[]> fresh = new LinkedHashMap<>();
        fresh.put(Settings.keyId(C), new long[] {480, 20, reset + 5000});
        Map<String, long[]> merged = KeyCredits.merge(parsed, fresh, Arrays.asList(A, B, C));
        assertEquals(2, merged.size()); // silinen anahtar kalmaz
        assertTrue(Arrays.equals(new long[] {600, 400}, KeyCredits.totals(merged)));
        List<Object> view = KeyCredits.view(merged, Arrays.asList(A, B, C));
        assertEquals("…2222", Json.obj(view.get(1)).get("label"));
        assertNull(Json.obj(view.get(1)).get("remaining")); // B: henüz ölçülmedi
        assertEquals(480L, Json.obj(view.get(2)).get("remaining"));
        // kredi planı toplam kotayla çalışır: 3 anahtar = 1500
        CreditPlan.Plan p = CreditPlan.plan(new Settings(), 1450L, 50L, LocalDate.of(2026, 10, 1), null);
        assertEquals(1500, p.quota);
        assertTrue(!p.narrowed);
    }

    @Test
    public void ampleCreditsExpandToLeaguesWithMatchesToday() {
        Settings s = new Settings();
        s.totals = true;
        s.radarScans = 4;
        s.kgEvents = 12;
        Map<String, Integer> active = new LinkedHashMap<>();
        for (String[] l : Settings.KNOWN_LEAGUES) active.put(l[0], 0);
        active.put("soccer_epl", 5); // seçili ve bugün oynayan
        active.put("soccer_italy_serie_a", 4);
        active.put("soccer_efl_champ", 12); // seçili değil, oynayan
        active.put("soccer_belgium_first_div", 4);
        active.put("soccer_greece_super_league", 3);
        Map<String, Double> yield = new LinkedHashMap<>();
        yield.put("soccer_greece_super_league", 3.0); // geçmişte en çok değerli seçim
        // 5 anahtar: 2455 kalan, 31 gün -> günde ~78,7; seçili ligler ~34 kredi
        CreditPlan.Plan p = CreditPlan.plan(s, 2455L, 45L, LocalDate.of(2026, 10, 1), yield, active, 1.0);
        assertTrue(!p.narrowed);
        assertTrue(p.activeKnown);
        assertTrue(!CreditPlan.plan(s, 2455L, 45L, LocalDate.of(2026, 10, 1), yield).activeKnown);
        assertEquals(Arrays.asList("soccer_greece_super_league", "soccer_efl_champ"), p.expanded); // Belçika sığmadı
        assertTrue(p.cost <= CreditPlan.EXPAND_SHARE * p.budget);
        assertEquals(65, p.cost, 1e-9); // 4 oynayan lig x 2 pazar x 6 tarama (sabah + 4 radar + kadro) + 12 KG + 5 gider
        assertTrue(CreditPlan.effective(s, p).leagues.contains("soccer_efl_champ"));
        assertTrue(p.notes.contains("Kredi bol: 2 ek lig tarandı"));
        // tek ücretsiz anahtar: genişleme yok (daraltma var)
        assertTrue(CreditPlan.plan(s, 450L, 50L, LocalDate.of(2026, 10, 1), yield, active, 1.0).expanded.isEmpty());
        // kapalıysa yok
        s.creditExpand = false;
        assertTrue(CreditPlan.plan(s, 2455L, 45L, LocalDate.of(2026, 10, 1), yield, active, 1.0).expanded.isEmpty());
        assertTrue(!Settings.fromMap(s.toMap()).creditExpand);
    }

    /** 2.16: paralel tarama için iş parçacığı güvenli sahte kaynak (anahtar A'nın kredisi bitmiş). */
    static Http parallelFake(final List<String> calls) {
        return new Http() {
            public Response get(String url, Map<String, String> headers) throws ProviderException {
                synchronized (calls) {
                    calls.add(url);
                }
                try {
                    Thread.sleep(3); // istekler gerçekten üst üste binsin
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                String key = url.replaceAll(".*apiKey=([^&]*).*", "$1");
                if (A.equals(key)) throw new ProviderException(Http.safe(url) + " -> HTTP 401: {\"message\":\"Usage quota has been reached\"}");
                String league = url.replaceAll(".*/sports/([^/]*)/.*", "$1");
                Map<String, String> h = new LinkedHashMap<>();
                h.put("x-requests-remaining", "400");
                h.put("x-requests-used", "100");
                if (url.contains("/events?")) return new Response(200, league.contains("bundesliga") ? "[]" : "[{\"id\":\"e\"}]", h);
                if (league.contains("serie_a")) throw new ProviderException(Http.safe(url) + " -> HTTP 500: sunucu hatası");
                h.put("x-requests-last", "1");
                return new Response(200, "[{\"id\":\"" + league + "-e\",\"commence_time\":\"2026-10-01T18:00:00Z\",\"home_team\":\"Ev " + league
                        + "\",\"away_team\":\"Dep\",\"bookmakers\":[{\"key\":\"pinnacle\",\"markets\":[{\"key\":\"h2h\",\"outcomes\":["
                        + "{\"name\":\"Ev " + league + "\",\"price\":2.0},{\"name\":\"Dep\",\"price\":3.8},{\"name\":\"Draw\",\"price\":3.4}]}]}]}]", h);
            }
        };
    }

    @Test
    public void parallelScanMatchesSequentialScan() throws Exception {
        // 2.16: ligler aynı anda çekilir; sonuç (maçlar, rapor, boş ligler, uyarılar, kredi) sırayla taramayla aynı
        List<String> leagues = Arrays.asList("soccer_epl", "soccer_spain_la_liga", "soccer_italy_serie_a", "soccer_germany_bundesliga",
                "soccer_france_ligue_one", "soccer_netherlands_eredivisie", "soccer_portugal_primeira_liga");
        List<List<Object>> results = new ArrayList<>();
        for (int threads : new int[] {1, 4}) {
            List<String> calls = new ArrayList<>();
            OddsApi api = new OddsApi(parallelFake(calls), cfg(A + " " + B));
            api.threads = threads;
            final List<String> steps = Collections.synchronizedList(new ArrayList<String>());
            final Thread caller = Thread.currentThread();
            api.progress = new Daily.Progress() {
                public void step(String text) {
                    assertTrue(text, Thread.currentThread() == caller); // ilerleme yalnızca çağıran iş parçacığından
                    steps.add(text);
                }
            };
            List<SharpEvent> ev = api.fetchEvents(leagues, CoreTest.NOW);
            List<String> refs = new ArrayList<>();
            for (SharpEvent e : ev) refs.add(e.ref);
            List<Object> r = new ArrayList<>();
            r.add(refs);
            r.add(new ArrayList<>(api.report));
            r.add(new ArrayList<>(api.idle));
            r.add(new ArrayList<>(api.warnings));
            r.add(api.spent);
            r.add(api.remaining);
            r.add(new ArrayList<>(api.keyProblems.keySet()));
            results.add(r);
            assertEquals(threads == 1 ? 7 : 8, steps.size());
        }
        assertEquals(results.get(0), results.get(1));
        List<Object> r = results.get(0);
        assertEquals(Arrays.asList("soccer_epl-e", "soccer_spain_la_liga-e", "soccer_france_ligue_one-e",
                "soccer_netherlands_eredivisie-e", "soccer_portugal_primeira_liga-e"), r.get(0));
        assertEquals(Collections.singletonList("soccer_germany_bundesliga"), r.get(2));
        assertEquals(1, ((List<?>) r.get(3)).size());
        assertTrue(String.valueOf(r.get(3)), String.valueOf(r.get(3)).contains("soccer_italy_serie_a atlandı"));
        assertEquals(5, r.get(4)); // 5 oran isteği x 1 kredi
        assertEquals(Collections.singletonList("…1111"), r.get(6)); // kredisi biten anahtar atlandı
    }

    @Test
    public void rateLimitedLeagueIsRetriedOnce() throws Exception {
        // 2.16: paralel taramada tek anahtar istek sınırına (429) takılırsa lig atlanmaz, bir kez yeniden çekilir
        OddsApi.RETRY_429_MS = 5;
        final List<String> calls = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicBoolean limited = new java.util.concurrent.atomic.AtomicBoolean();
        final Http inner = parallelFake(calls);
        Http http = new Http() {
            public Response get(String url, Map<String, String> headers) throws ProviderException {
                if (url.contains("soccer_epl/odds") && limited.compareAndSet(false, true)) {
                    throw new ProviderException(Http.safe(url) + " -> HTTP 429: too many requests");
                }
                return inner.get(url, headers);
            }
        };
        OddsApi api = new OddsApi(http, cfg(B));
        api.threads = 4;
        List<SharpEvent> ev = api.fetchEvents(Arrays.asList("soccer_epl", "soccer_spain_la_liga"), CoreTest.NOW);
        assertEquals(2, ev.size());
        assertTrue(api.warnings.toString(), api.warnings.isEmpty());
        OddsApi.RETRY_429_MS = 1500;
    }
}
