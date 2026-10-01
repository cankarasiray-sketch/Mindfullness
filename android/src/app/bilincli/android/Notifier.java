package app.bilincli.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import app.bilincli.R;
import app.bilincli.core.Daily;
import app.bilincli.core.Ledger;
import app.bilincli.core.Texts;
import java.util.List;

/** Bildirimler: günün kararı ve kupon sonuçları. */
final class Notifier {
    private static final String CH_DAILY = "kupon";
    private static final String CH_RESULT = "sonuc";
    /** Gece (00:00–08:00) gelen bildirimler: ses ve titreşim yok, ekranı açmaz. */
    private static final String CH_QUIET = "gece";
    private static final int ID_DAILY = 10;
    private static final int ID_RESULT = 11;

    private Notifier() {}

    static boolean allowed(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return ctx.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    private static NotificationManager channels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel d = new NotificationChannel(CH_DAILY, "Günün kuponu", NotificationManager.IMPORTANCE_HIGH);
        d.setDescription("Her sabah üretilen kupon ya da pas kararı");
        NotificationChannel r = new NotificationChannel(CH_RESULT, "Kupon sonuçları", NotificationManager.IMPORTANCE_DEFAULT);
        r.setDescription("Kupon tuttu / yattı bildirimleri");
        NotificationChannel q = new NotificationChannel(CH_QUIET, "Gece (sessiz)", NotificationManager.IMPORTANCE_LOW);
        q.setDescription("00:00–08:00 arası gelen bildirimler (gece maçlarının kontrolleri); sabah bildirim alanında durur");
        nm.createNotificationChannel(d);
        nm.createNotificationChannel(r);
        nm.createNotificationChannel(q);
        return nm;
    }

    static void show(Context ctx, String channel, int id, String title, String body) {
        if (!allowed(ctx)) return;
        NotificationManager nm = channels(ctx);
        if (quiet(ctx, java.time.Instant.now())) channel = CH_QUIET;
        Intent open = new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(ctx, channel)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFF0F6E56)
                .setContentTitle(title)
                .setContentText(body.split("\n")[0])
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }

    /** Gece sessizliği: ayar açık ve Türkiye saatiyle 00:00–08:00 arası. */
    static boolean quiet(Context ctx, java.time.Instant now) {
        int hour = now.atOffset(app.bilincli.core.Fmt.TR).getHour();
        return hour < 8 && Repo.get(ctx).real().settings().quietNights;
    }

    static void daily(Context ctx, Ledger ledger, Daily.Result r, boolean notifyErrors) {
        if (r.error != null && !notifyErrors) return;
        String[] t = Texts.notification(ledger, r);
        if (t != null) show(ctx, CH_DAILY, ID_DAILY, t[0], t[1]);
    }

    static void settled(Context ctx, List<String> messages) {
        if (messages == null || messages.isEmpty()) return;
        StringBuilder b = new StringBuilder();
        for (String m : messages) b.append(m).append('\n');
        String first = messages.get(0);
        show(ctx, CH_RESULT, ID_RESULT, first.length() > 60 ? "Kupon sonuçları" : first, b.toString().trim());
    }
}
