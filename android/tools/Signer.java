import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * APK İmza Şeması v2 ile imzalar ve doğrular. minSdk 26 olduğundan v1 (JAR)
 * imzası gerekmez. Kullanım: Signer girdi.apk çıktı.apk anahtar.p12 parola
 */
public class Signer {
    public static void main(String[] a) throws Exception {
        File in = new File(a[0]), out = new File(a[1]), ks = new File(a[2]);
        char[] pass = a[3].toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream f = new FileInputStream(ks)) {
            store.load(f, pass);
        }
        String alias = store.aliases().nextElement();
        PrivateKey key = (PrivateKey) store.getKey(alias, pass);
        X509Certificate cert = (X509Certificate) store.getCertificate(alias);
        ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder("bilincli", key,
                Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(cfg)).setInputApk(in).setOutputApk(out)
                .setMinSdkVersion(26).setV1SigningEnabled(false).setV2SigningEnabled(true).build().sign();
        ApkVerifier.Result r = new ApkVerifier.Builder(out).setMinCheckedPlatformVersion(26).build().verify();
        for (Object e : r.getErrors()) System.out.println("HATA " + e);
        if (!r.isVerified() || !r.isVerifiedUsingV2Scheme()) {
            System.out.println("imza doğrulanamadı");
            System.exit(1);
        }
        System.out.println("imza doğrulandı (v2), sertifika: " + cert.getSubjectX500Principal());
    }
}
