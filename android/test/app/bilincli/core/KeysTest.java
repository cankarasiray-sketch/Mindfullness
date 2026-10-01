package app.bilincli.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        s.oddsApiKey = "k1 k2 k3 k4 k5 k6";
        assertTrue(s.validate().contains("En fazla 5"));
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
        assertEquals(Arrays.asList("soccer_greece_super_league", "soccer_efl_champ"), p.expanded); // Belçika sığmadı
        assertTrue(p.cost <= CreditPlan.EXPAND_SHARE * p.budget);
        assertEquals(57, p.cost, 1e-9); // 4 oynayan lig x 2 pazar x 5 tarama + 12 KG + 5 gider
        assertTrue(CreditPlan.effective(s, p).leagues.contains("soccer_efl_champ"));
        assertTrue(p.notes.contains("Kredi bol: 2 ek lig tarandı"));
        // tek ücretsiz anahtar: genişleme yok (daraltma var)
        assertTrue(CreditPlan.plan(s, 450L, 50L, LocalDate.of(2026, 10, 1), yield, active, 1.0).expanded.isEmpty());
        // kapalıysa yok
        s.creditExpand = false;
        assertTrue(CreditPlan.plan(s, 2455L, 45L, LocalDate.of(2026, 10, 1), yield, active, 1.0).expanded.isEmpty());
        assertTrue(!Settings.fromMap(s.toMap()).creditExpand);
    }
}
