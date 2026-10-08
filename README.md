# Hava Uyarı

Yağış, fırtına, don, sis ve aşırı sıcak gibi **dikkat etmeniz ve önlem almanız gereken** hava olaylarını
önceden gösteren Android hava durumu uygulaması. Bulunduğunuz yerin yanında, aradığınız herhangi bir şehir
veya ilçenin hava durumuna da bakabilirsiniz.

## Özellikler

- **Dikkat edilmesi gerekenler** – önümüzdeki 48 saat saat saat taranır; yağmur, kuvvetli yağış/sel riski,
  kar, dondurucu yağmur, gizli buzlanma, gök gürültülü fırtına ve dolu, kuvvetli rüzgar/fırtına, sis,
  sıcak hava, don/ayaz, yüksek UV, ani sıcaklık düşüşü, kötü hava kalitesi ve çöl tozu için uyarı üretilir.
  - **Güven derecesi** – uyarılar ECMWF, DWD ICON ve NOAA GFS modelleriyle karşılaştırılır;
    "Güven: Yüksek · 3/3 model" gibi modellerin ne kadar hemfikir olduğu gösterilir.
  - **Fırtına riski** – atmosferin kararsızlığı (CAPE) yüksek ve sağanak olasılığı varsa, hava kodu henüz
    göstermese de gök gürültülü sağanak riski bildirilir.
  - **Sel ve taşkın riski** – dün dahil 3 günde biriken yağış (toprağın suya doyması) ayrıca değerlendirilir.
  - **Hava kalitesi ve çöl tozu** – Copernicus CAMS tahminiyle; toz taşınımında çamur yağmuru uyarısı.
  - Her uyarıda **ne zaman** (ör. "Bugün 15:00–21:00"), **ne kadar** (mm, km/sa, °C) ve
    MGM'nin kullandığı renk ölçeğiyle **seviye** (Bilgi · Sarı · Turuncu · Kırmızı) gösterilir.
  - **"Ne yapmalı?"** bölümü seviyeye göre somut önlemler sıralar.
  - 3–7 gün sonrası için "İleriki günler" ön uyarıları.
- **Yağış grafiği** – 24 saatlik yağış miktarı ve olasılığı; "Yağış bekleniyor · 15:00 – 21:00" gibi özet.
- **Yanınıza alın** – şemsiye, mont, güneş kremi, su şişesi gibi kısa öneriler.
- **Arama ve kayıtlı yerler** – Türkçe yer adı araması (il, ilçe, dünya genelinde şehirler); baktığınız
  yerler listede kalır, anlık sıcaklık ve varsa uyarı rozetiyle görünür.
- **Bildirimler** – seçtiğiniz konum için arka planda düzenli kontrol; sarı ve üzeri (veya yalnızca
  turuncu/kırmızı) uyarılarda bildirim. İsteğe bağlı 07:00 **sabah özeti**.
- **Yağmur başlamadan haber ver** – yağmur veya kar 1–2 saat içinde başlayacaksa kısa bir bildirim
  (saatlik kontrol, her yağış için bir kez).
- **Ana ekran widget'ı** – anlık sıcaklık, hava durumu, en yüksek/en düşük ve en önemli uyarı; bildirim
  konumunu (yoksa cihaz konumunu) gösterir, saatte bir güncellenir.
- **Uyarıyı paylaş** – uyarıyı önerileriyle birlikte mesajlaşma uygulamalarıyla paylaşın.
- Aşağı çekerek yenileme, hava kalitesi kutucuğu, "maske" önerisi.
- Saatlik (36 saat) ve 10 günlük tahmin, hissedilen sıcaklık, nem, rüzgar yönü/hamle, UV, görüş,
  basınç, gün doğumu/batımı.
- Çevrimdışı açılış: son başarılı tahmin önbellekte tutulur.
- Hava durumuna göre değişen arka plan ve elle çizilmiş renkli hava ikonları.

Veriler [Open-Meteo](https://open-meteo.com) üzerinden alınır (API anahtarı gerekmez, CC BY 4.0):
tahmin, ECMWF/ICON/GFS model karşılaştırması ve CAMS hava kalitesi.
Uyarılar tahmin modellerine dayalı otomatik değerlendirmelerdir; resmî uyarılar için MGM ve AFAD'ı takip edin.

## APK'yı indirme

Her push'ta GitHub Actions uygulamayı derler ve **Releases** sayfasına `HavaUyari-1.0.N.apk` olarak koyar
(varsayılan dal dışındaki dallar "pre-release" olarak yayımlanır):

1. Telefonda deponun **Releases** sayfasını açın, en yeni sürümdeki `.apk` dosyasını indirin.
2. Dosyayı açın; istenirse "Bilinmeyen kaynaklardan yükleme" iznini verin.
3. Sürüm numarası (`N`) commit sayısıdır; aynı anahtarla imzalanan her yeni sürüm eskisinin üzerine kurulur.

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
derlenebilir (JDK 17+ gerekir):

```bash
sudo apt install aapt dalvik-exchange apksigner zipalign
./gradlew -p tools/offline-apk assembleApk
# çıktı: tools/offline-apk/build/apk/app-release.apk
```

Bu yol Android 14 framework sınıflarını Maven Central'daki Robolectric `android-all` paketinden alır,
ProGuard ile Kotlin standart kütüphanesini küçültür, `dx` ile dex üretir ve depodaki anahtarla imzalar.
Sürüm numarası CI ile aynı şekilde commit sayısından hesaplanır.

Kod düzeni:

| Klasör | İçerik |
| --- | --- |
| `domain/` | Uyarı motoru (`AlertEngine`), model uyumu (`ModelAgreement`), öneriler (`Insights`), modeller, Türkçe tarih/saat metinleri |
| `data/` | Open-Meteo istemcisi, JSON ayrıştırma, önbellekli depo |
| `platform/` | Konum, ayarlar, bildirimler, `JobScheduler` görevleri, ana ekran widget'ı |
| `ui/` | Ekranlar (`HomeView`, `PlacesView`, `SettingsView`), durum yönetimi (`AppController`), çizimler |
| `tools/` | İkon üretici (`generate_icons.py`) ve çevrimdışı APK derleyici |

`app/signing/havauyari.jks` yalnızca sürümler arası güncellenebilirlik için depoya eklenmiş bir anahtardır.
Play Store'a yüklenecekse `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD` ortam değişkenleriyle gizli bir anahtar kullanın.
