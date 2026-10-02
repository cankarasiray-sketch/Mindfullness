package app.bilincli.preview;

import app.bilincli.core.DemoSim;
import app.bilincli.core.Json;
import app.bilincli.core.Ledger;
import app.bilincli.core.State;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Arayüz önizlemesi için demo durum JSON'u üretir (yalnızca geliştirme aracı). */
public final class PreviewState {
    public static void main(String[] args) {
        final Instant today = Instant.parse(args.length > 0 ? args[0] : "2026-10-01T03:00:00Z");
        Ledger.MemoryStorage st = new Ledger.MemoryStorage(null);
        // önizleme kupon ekranlarını da göstersin: 2.5 öncesi eşikler (yeni olasılık şartında demo günü pas olabilir)
        Ledger pre = new Ledger(st, new Ledger.Clock() {
            @Override
            public Instant now() {
                return today.minusSeconds(61 * 86400L);
            }
        });
        app.bilincli.core.Settings legacy = pre.settings();
        legacy.minWinProb = 0.20;
        legacy.minLegProb = 0;
        pre.saveSettings(legacy);
        Map<String, Object> summary = DemoSim.run(st, 60, 1000000, 11, today);
        Ledger ledger = new Ledger(st, new Ledger.Clock() {
            @Override
            public Instant now() {
                return today.plusSeconds(2 * 3600);
            }
        });
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("notifications", true);
        extra.put("exactAlarm", true);
        extra.put("nextRun", today.plusSeconds(86400).toString());
        extra.put("demoSummary", summary);
        extra.put("radar", summary.get("radar"));
        extra.put("version", "1.0");
        System.out.print(Json.write(State.build(ledger, args.length > 1, extra)));
    }
}
