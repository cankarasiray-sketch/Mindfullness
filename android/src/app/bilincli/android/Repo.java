package app.bilincli.android;

import android.content.Context;
import android.content.SharedPreferences;
import app.bilincli.core.Daily;
import app.bilincli.core.DemoSim;
import app.bilincli.core.Fmt;
import app.bilincli.core.Http;
import app.bilincli.core.Ledger;
import app.bilincli.core.Settings;
import java.io.File;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Uygulama genelinde tek veri deposu. Arka plan işi ve arayüz aynı süreçte
 * çalıştığından tüm işlemler {@link #LOCK} ile sıraya sokulur.
 */
final class Repo {
    static final Object LOCK = new Object();
    private static Repo instance;

    private final Context app;
    private final SharedPreferences prefs;
    private final Ledger ledger;
    private Ledger demoLedger;
    Map<String, Object> demoSummary;

    private static final Ledger.Clock SYSTEM = new Ledger.Clock() {
        @Override
        public Instant now() {
            return Instant.now();
        }
    };

    private Repo(Context ctx) {
        app = ctx.getApplicationContext();
        prefs = app.getSharedPreferences("bilincli", Context.MODE_PRIVATE);
        ledger = new Ledger(new FileStorage(new File(app.getFilesDir(), "kasa.json")), SYSTEM);
    }

    /**
     * LOCK değil, ayrı bir kilit: arka plan işi ağ isteği sırasında LOCK'u tutarken
     * ana iş parçacığı burada beklerse uygulama donar (ANR).
     */
    static Repo get(Context ctx) {
        synchronized (Repo.class) {
            if (instance == null) instance = new Repo(ctx);
            return instance;
        }
    }

    /** Gerçek defter. Demo modunda bile arka plan işi bunun üzerinde çalışır. */
    Ledger real() {
        return ledger;
    }

    boolean isDemo() {
        return prefs.getBoolean("demo", false) && demoLedger() != null;
    }

    /** Arayüzün gösterdiği defter. */
    Ledger visible() {
        return isDemo() ? demoLedger() : ledger;
    }

    private Ledger demoLedger() {
        if (demoLedger == null) {
            File f = new File(app.getFilesDir(), "demo.json");
            if (!f.exists()) return null;
            demoLedger = new Ledger(new FileStorage(f), SYSTEM);
        }
        return demoLedger;
    }

    Map<String, Object> startDemo() {
        File f = new File(app.getFilesDir(), "demo.json");
        FileStorage st = new FileStorage(f);
        st.delete();
        OffsetDateTime local = Instant.now().atOffset(Fmt.TR);
        Instant today0600 = local.withHour(6).withMinute(0).withSecond(0).withNano(0).toInstant();
        if (today0600.isAfter(Instant.now())) today0600 = today0600.minusSeconds(86400);
        demoSummary = DemoSim.run(st, 60, 1000000, System.currentTimeMillis() % 1000, today0600);
        demoLedger = null;
        prefs.edit().putBoolean("demo", true).apply();
        return demoSummary;
    }

    void stopDemo() {
        prefs.edit().putBoolean("demo", false).apply();
        new FileStorage(new File(app.getFilesDir(), "demo.json")).delete();
        demoLedger = null;
        demoSummary = null;
    }

    Daily.LiveSources live() {
        return new Daily.LiveSources(new Http.UrlHttp(), ledger.settings());
    }

    /** Bugünün planlanan çalışma anı (Türkiye saati). */
    Instant todaysRunTime() {
        Settings s = ledger.settings();
        return Instant.now().atOffset(Fmt.TR).withHour(s.runHour).withMinute(s.runMinute).withSecond(0)
                .withNano(0).toInstant();
    }

    /** Arka plan: biten maçları sonuçlandır; gerekiyorsa bugünün kararını üret. */
    BackgroundResult background(boolean dailyTrigger) {
        BackgroundResult out = new BackgroundResult();
        synchronized (LOCK) {
            if (ledger.settings().oddsApiKey.isEmpty()) {
                out.setupNeeded = true; // anahtar yokken ağ isteği yapma
                return out;
            }
            Daily.LiveSources src = live();
            out.settleMessages = Daily.settle(ledger, src);
            String today = Fmt.dayKey(Instant.now());
            boolean due = !Instant.now().isBefore(todaysRunTime()) && ledger.runFor(today) == null;
            if (dailyTrigger || due) {
                Daily.Result r = Daily.generate(ledger, src, false, false);
                if (!r.skipped) out.daily = r;
            }
        }
        return out;
    }

    static final class BackgroundResult {
        List<String> settleMessages = new ArrayList<>();
        Daily.Result daily;
        boolean setupNeeded;
    }
}
