# Hava Uyarı

Yağış, fırtına, don, sis ve aşırı sıcak gibi **dikkat etmeniz ve önlem almanız gereken** hava olaylarını
önceden gösteren Android hava durumu uygulaması. Bulunduğunuz yerin yanında, aradığınız herhangi bir şehir
veya ilçenin hava durumuna da bakabilirsiniz.

## Özellikler

- **Dikkat edilmesi gerekenler** – önümüzdeki 48 saat saat saat taranır; yağmur, kuvvetli yağış/sel riski,
  kar, dondurucu yağmur, gizli buzlanma, gök gürültülü fırtına ve dolu, kuvvetli rüzgar/fırtına, sis,
  sıcak hava, don/ayaz, yüksek UV ve ani sıcaklık düşüşü için uyarı üretilir.
  - Her uyarıda **ne zaman** (ör. "Bugün 15:00–21:00"), **ne kadar** (mm, km/sa, °C) ve
    MGM'nin kullandığı renk ölçeğiyle **seviye** (Bilgi · Sarı · Turuncu · Kırmızı) gösterilir.
  - **"Ne yapmalı?"** bölümü seviyeye göre somut önlemler sıralar.
  - 3–7 gün sonrası için "İleriki günler" ön uyarıları.
- **Yağış grafiği** – 24 saatlik yağış miktarı ve olasılığı; "Yağış bekleniyor · 15:00 – 21:00" gibi özet.
- **Yanınıza alın** – şemsiye, mont, güneş kremi, su şişesi gibi kısa öneriler.
- **Arama ve kayıtlı yerler** – Türkçe yer adı araması (il, ilçe, dünya genelinde şehirler); baktığınız
  yerler listede kalır, anlık sıcaklık ve varsa uyarı rozetiyle görünür.
- **Bildirimler** – seçtiğiniz konum için ~3 saatte bir arka planda kontrol; sarı ve üzeri (veya yalnızca
  turuncu/kırmızı) uyarılarda bildirim. İsteğe bağlı 07:00 **sabah özeti**.
- Saatlik (36 saat) ve 10 günlük tahmin, hissedilen sıcaklık, nem, rüzgar yönü/hamle, UV, görüş,
  basınç, gün doğumu/batımı.
- Çevrimdışı açılış: son başarılı tahmin önbellekte tutulur.
- Hava durumuna göre değişen arka plan ve elle çizilmiş renkli hava ikonları.

Veriler [Open-Meteo](https://open-meteo.com) üzerinden alınır (API anahtarı gerekmez, CC BY 4.0).
Uyarılar tahmin modellerine dayalı otomatik değerlendirmelerdir; resmî uyarılar için MGM ve AFAD'ı takip edin.

## APK'yı indirme

Her push'ta GitHub Actions uygulamayı derler ve **Releases** sayfasına `HavaUyari-1.0.N.apk` olarak koyar:

1. Telefonda deponun **Releases** sayfasını açın, en son sürümdeki `.apk` dosyasını indirin.
2. Dosyayı açın; istenirse "Bilinmeyen kaynaklardan yükleme" iznini verin.
3. Uygulama her sürümde aynı anahtarla imzalandığı için yeni sürümler eskisinin üzerine kurulur.

## Geliştirme

- Kotlin, yalnızca Android framework'ü (View sistemi) ve Kotlin standart kütüphanesi — AndroidX, Compose
  veya başka bağımlılık yok. Arka plan kontrolleri `JobScheduler`, veri ayrıştırma `org.json` ile yapılır.
- `minSdk 26` (Android 8.0), `targetSdk 34`

```bash
./gradlew testReleaseUnitTest   # uyarı motoru testleri
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk
```

### Google depolarına erişim olmadan derleme

Android SDK'nın ve Google Maven'ın indirilemediği ortamlarda APK, Ubuntu paketlerindeki araçlarla
derlenebilir (`apt install aapt dalvik-exchange apksigner zipalign`):

```bash
gradle -p tools/offline-apk assembleApk -PversionCode=3
# çıktı: tools/offline-apk/build/apk/app-release.apk
```

Bu yol Android 14 framework sınıflarını Maven Central'daki Robolectric `android-all` paketinden alır,
ProGuard ile Kotlin standart kütüphanesini küçültür, `dx` ile dex üretir ve depodaki anahtarla imzalar.

Kod düzeni:

| Klasör | İçerik |
| --- | --- |
| `domain/` | Uyarı motoru (`AlertEngine`), öneriler (`Insights`), modeller, Türkçe tarih/saat metinleri |
| `data/` | Open-Meteo istemcisi, JSON ayrıştırma, önbellekli depo |
| `platform/` | Konum, ayarlar, bildirimler, `JobScheduler` görevleri |
| `ui/` | Ekranlar (`HomeView`, `PlacesView`, `SettingsView`), durum yönetimi (`AppController`), çizimler |
| `tools/` | İkon üretici (`generate_icons.py`) ve çevrimdışı APK derleyici |

`app/signing/havauyari.jks` yalnızca sürümler arası güncellenebilirlik için depoya eklenmiş bir anahtardır.
Play Store'a yüklenecekse `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD` ortam değişkenleriyle gizli bir anahtar kullanın.
