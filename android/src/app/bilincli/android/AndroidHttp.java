package app.bilincli.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import app.bilincli.core.Http;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Ağ seçimi: Türkiye'de The Odds API için VPN gerekebilirken Nesine (ve Bilyoner) bazı VPN
 * sunucularını engeller. Bu sitelere istekler önce VPN dışındaki ağdan (mobil veri / Wi-Fi) denenir; VPN uygulaması
 * buna izin vermiyorsa ya da başarısız olursa normal yoldan (VPN) denenir.
 */
final class AndroidHttp extends Http.UrlHttp {
    private final ConnectivityManager cm;

    AndroidHttp(Context ctx) {
        cm = ctx.getSystemService(ConnectivityManager.class);
    }

    private static boolean direct(URL u) {
        return u.getHost().endsWith("nesine.com") || u.getHost().endsWith("bilyoner.com"); // Türk siteleri VPN'siz
    }

    @Override
    protected int attempts(URL u) {
        return direct(u) ? 4 : 2; // Nesine bülteni büyük: VPN üzerinden yarıda kesilebiliyor
    }

    @Override
    protected long maxTransferMs(URL u) {
        return direct(u) ? 180_000 : 90_000; // Nesine bülteni birkaç MB olabilir
    }

    @Override
    protected boolean alwaysRetry(URL u, int attempt) {
        return direct(u) && attempt == 0 && usedDirect;
    }

    private volatile boolean usedDirect;

    @Override
    protected HttpURLConnection open(URL u, int attempt) throws IOException {
        usedDirect = false;
        if (direct(u) && attempt == 0) {
            Network n = nonVpnNetwork();
            if (n != null) {
                try {
                    HttpURLConnection c = (HttpURLConnection) n.openConnection(u);
                    c.setConnectTimeout(6000); // VPN atlamaya izin vermiyorsa uzun beklemeden normal yola geç
                    usedDirect = true;
                    return c;
                } catch (IOException | RuntimeException e) {
                    // bu ağa bağlanılamıyor: normal yol
                }
            }
        }
        return super.open(u, attempt);
    }

    /** VPN olmayan, internete çıkan ilk ağ (yoksa ya da VPN yoksa null). */
    @SuppressWarnings("deprecation")
    private Network nonVpnNetwork() {
        if (cm == null) return null;
        try {
            boolean vpn = false;
            Network plain = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true;
                else if (plain == null) plain = n;
            }
            return vpn ? plain : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
