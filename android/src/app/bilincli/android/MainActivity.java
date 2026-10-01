package app.bilincli.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Tek ekran: arayüz assets/index.html, Java ile köprü üzerinden konuşur. */
public final class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setTextZoom(100);
        web.setBackgroundColor(0xFF0B3D31);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                if ("https".equals(u.getScheme())) {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                }
                return true; // uygulama içinde dış sayfa açılmaz
            }
        });
        web.addJavascriptInterface(new Bridge(this, web), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Scheduler.ensureScheduled(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Telefon 06:00'da kapalıydıysa: açılınca bugünün kararını tamamla.
        Repo repo = Repo.get(this);
        if (!java.time.Instant.now().isBefore(repo.todaysRunTime())
                && repo.real().runFor(app.bilincli.core.Fmt.dayKey(java.time.Instant.now())) == null) {
            Scheduler.runDailyNow(this);
        }
        web.evaluateJavascript("window.refresh && window.refresh()", null);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        web.evaluateJavascript("window.refresh && window.refresh()", null);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.goBack ? window.goBack() : false", new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String handled) {
                if (!"true".equals(handled)) MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
