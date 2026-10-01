# Bilinçli Kupon — Android

Masaüstü sürümüyle aynı mantığın telefona taşınmış hali. Bilgisayar açık olmasa da her sabah
06:00'da (Türkiye saati) bülteni tarar, kararı bildirim olarak gönderir, kasanı ve kuponlarını
telefonda tutar.

## Kurulum (telefon)

1. `BilincliKupon-1.0.apk` dosyasını telefona indir ve aç. Android "bilinmeyen kaynak" izni
   isterse bu kaynağa (tarayıcı ya da dosya yöneticisi) izin ver.
2. Uygulamayı aç, bildirim iznini onayla.
3. **Ayarlar** sekmesinde The Odds API anahtarını gir ([ücretsiz](https://the-odds-api.com)),
   **Kaynakları test et**'e bas. Kaç iddaa maçının okunduğu ve kaçının eşleştiği görünür.
4. **Kasa** sekmesinden kasanı oluştur.
5. Hazır. Karar her sabah gelir; istersen **Bugün** sekmesinden **Şimdi üret** diyebilirsin.

Anahtar girmeden denemek için **Önce demoyu gör**: hayali takımlarla 60 günlük simülasyon.

## Telefonda nasıl çalışır

- **06:00 alarmı:** Tam saatli alarm, ağ bağlantısı olduğunda hemen çalışan bir arka plan işi
  başlatır. Telefon kapalıysa açıldığında, uygulama açıldığında ya da 3 saatlik kontrolde
  kaçırılan karar tamamlanır. Aynı gün ikinci kupon üretilmez.
- **Sonuçlar:** 3 saatte bir biten maçlar kontrol edilir. Kupon tutunca ya da yatınca bildirim gelir.
- **Veri:** Her şey telefonda (`kasa.json`) durur, hiçbir sunucuya gönderilmez. Tek ağ trafiği
  Nesine bülteni ve The Odds API'dir.
- **Yedek:** Ayarlar → **Yedeği paylaş**. Uygulamayı silersen ya da imza anahtarı değişen bir
  sürüm kurman gerekirse veri bu yedekle geri yüklenir.

Bazı üreticiler (Xiaomi, Huawei, Samsung'un bazı modelleri) arka plan işlerini agresif biçimde
durdurur. Sabah bildirimi gelmezse: Ayarlar → Uygulamalar → Bilinçli Kupon → Pil → **Kısıtlama yok**.

## Derleme

Android SDK ya da Gradle gerekmez. Linux veya macOS'ta JDK 17+, python3 ve curl yeterli:

```bash
cd android
./build.sh          # testler + build/BilincliKupon-1.0.apk
./build.sh test     # yalnızca testler
```

Betik gereken araçları Maven Central'dan indirir ve SHA-256 ile doğrular:

| Araç | Kaynak | Ne için |
|---|---|---|
| aapt2, framework kaynakları | `org.apktool:apktool-lib:3.0.3` | manifest ve kaynakları derleme |
| Android API sınıfları | `org.robolectric:android-all:14` | Java derlemesi (yalnızca derleme anında; ~137 MB) |
| dx | `com.jakewharton.android.repackaged:dalvik-dx:16.0.1` | Java → Dalvik |
| apksig | `com.android.tools.build:apksig:2.3.0` | APK İmza Şeması v2 |
| JUnit | `junit:junit:4.13.2` | testler |

İmza anahtarı `android/.keystore/` altında ilk derlemede oluşturulur ve repoya girmez.
**Güncellemeleri aynı anahtarla imzala**; farklı anahtarla imzalanmış sürüm, eskisi
kaldırılmadan kurulamaz.

## Testler

- **Eşdeğerlik (ParityTest):** Python sürümü 150 motor senaryosu, 479 takım adı eşleştirmesi ve
  oran hesapları için referans üretir (`test/fixtures/make_fixtures.py`). Java çekirdeği aynı
  girdide aynı kuponu, aynı olasılığı ve aynı Kelly payını 1e-9 hassasiyetle vermek zorunda.
- **Çekirdek (CoreTest):** kasa, iade kuralı, haftalık limit, kovalama beklemesi, sonuçlandırma,
  Nesine/The Odds API ayrıştırma, kalıcılık ve yedek, Türkçe telefonda biçimlendirme.
- **Arayüz (test/ui):** jsdom üzerinde gerçek durum verisiyle oynadım akışı, ayarların Türkçe
  sayı biçimiyle okunması, XSS kaçışı, ilk kurulum ve demo ekranı.

## Yapı

```
android/
  AndroidManifest.xml
  assets/index.html            arayüz (WebView)
  res/                         ikon, tema, metinler
  src/app/bilincli/core/       Android'den bağımsız çekirdek (Python sürümünün birebir karşılığı)
  src/app/bilincli/android/    aktivite, JS köprüsü, alarm, arka plan işi, bildirimler
  test/                        JUnit, arayüz testleri, Python referans verisi
  tools/                       zip hizalama ve imzalama
  build.sh
```
