# Bilinçli Kupon — Android

Masaüstü sürümüyle aynı mantığın telefona taşınmış hali. Bilgisayar açık olmasa da her sabah
06:00'da (Türkiye saati) bülteni tarar, kararı bildirim olarak gönderir, kasanı ve kuponlarını
telefonda tutar.

## Kurulum (telefon)

1. `BilincliKupon-1.3.apk` dosyasını telefona indir ve aç. Android "bilinmeyen kaynak" izni
   isterse bu kaynağa (tarayıcı ya da dosya yöneticisi) izin ver.
2. Uygulamayı aç, bildirim iznini onayla.
3. **Ayarlar** sekmesinde The Odds API anahtarını gir ([ücretsiz](https://the-odds-api.com)),
   **Kaynakları test et**'e bas. Kaç iddaa maçının okunduğu ve kaçının eşleştiği görünür.
4. **Kasa** sekmesinden kasanı oluştur.
5. Hazır. Karar her sabah gelir; istersen **Bugün** sekmesinden **Şimdi üret** diyebilirsin.

Anahtar girmeden denemek için **Önce demoyu gör**: hayali takımlarla 60 günlük simülasyon.

## Bilyoner'de oynarken: bayat oranla oynama

iddaa oranları tüm bayilerde (Bilyoner, Nesine, Misli) aynıdır; uygulama bülteni Nesine'den
okur. Sabah 06:00'da kurulan kuponun oranları gün içinde değişir, bu yüzden:

1. **Oynamadan önce kontrol et:** Kupondaki butona bas. Güncel iddaa ve Pinnacle oranları
   yeniden çekilir, her maç için "güncel, hâlâ avantajlı" ya da "avantaj kayboldu / başladı /
   bültende yok / MBS değişti" yazar.
2. **OYNANABİLİR** çıkarsa: **Güncel oranlarla oynadım**. Bilyoner'de aynı oranları gördüğünden
   emin ol; farklıysa "Oynadım (oranları ben gireyim)" ile gerçek oranları gir. Kontrol 30 dakika
   sonra bayatlar ve yeniden ister.
3. **OYNAMA** çıkarsa: o kuponu oynama. **Güncel oranlarla yeni kupon** o anki oranlardan
   baştan kupon kurar.

Ayrıca oynanmamış öneri, ilk maçtan **90 dakika önce otomatik kontrol edilir** ve sonuç
bildirim olarak gelir.

## Daha fazla ölçüm, daha az tahmin

- **Kapanış oranı testi (CLV):** Her maçtan 10 dk önce Pinnacle'ın son oranı kaydedilir.
  Seçimlerin kapanış oranını yenip yenmediği, avantajın gerçek olup olmadığının kâr/zarardan
  çok daha hızlı ve güvenilir göstergesidir. 30+ maçtan sonra ortalama negatifse uygulama
  "avantaj görünmüyor" uyarısı verir.
- **Bu ay kutusu:** Bu ayın gerçekleşen kâr/zararı, modelin beklediği kâr/zarar ve açık
  kuponlarla ay sonu için kötü / olası / iyi senaryo (%10 / %50 / %90).
- **Strateji profili:** *Temkinli* (çeyrek Kelly, kupon başına en fazla %3) ya da
  *En yüksek getiri* (yarım Kelly, en fazla %10). Demo simülasyonunda (200 ay) en yüksek tipik
  aylık getiriyi yarım Kelly verdi; daha büyük bahis tipik getiriyi düşürdü, Kelly'nin iki
  katında ortanca ay zarara döndü. Hiçbir profil kazanç garantisi vermez.

## Daha çok fırsat: pazarlar, günde 3 kupon, veri doğrulama (1.3)

- **Günde en fazla 3 bağımsız kupon:** Motor, maç paylaşmayan en iyi kuponları seçer; tutarlar
  Kelly'ye göre, toplamı günlük üst sınırı (Temkinli %9, En yüksek getiri %20) aşarsa orantılı
  küçültülür. Demo simülasyonunda (200 ay) ortanca ayı Temkinli'de +%1,7 → +%2,3, En yüksek
  getiri'de +%3,2 → +%4,1 yükseltti; en kötü ay biraz derinleşti.
- **2,5 Alt/Üst** (Ayarlar → Pazarlar): Pinnacle "totals" ile karşılaştırılır; lig başına kredi ×2.
- **Karşılıklı Gol:** Pinnacle KG oranı maç bazında çekilir (maç başına 1 kredi), günde en fazla
  4/8/12 maç.
- **Veri doğrulama:** Nesine'nin pazar kodları ve seçenek sıraları Pinnacle'la karşılaştırılarak
  otomatik bulunur. Maç Sonucu sırası tutarsızsa düzeltilir ya da kapatılır, Alt/Üst ve KG için
  eşleşme belirsizse pazar kullanılmaz, %25'ten büyük "avantajlar" veri hatası sayılıp ayıklanır.
  Son güvenilir eşleme hatırlanır (kontrol anında az veri olsa da çalışır).
- **Kredi takibi:** Kalan The Odds API kredisi Ayarlar'da görünür; 40'ın altına inince radar
  durur, kalan kredi günlük karar için saklanır.
- **Korner:** Bilinçli olarak eklenmedi. Değer hesabı keskin bir referans fiyat gerektirir; korner
  için bu verinin kaynağı ve Nesine'deki pazar biçimi doğrulanamadı. "Kaynakları test et"
  çıktısındaki **pazar envanteri** (pazar kodları, çizgi değerleri, örnek maç) paylaşılırsa
  gerçek veriyle eşlenebilir.

## Fırsatlar sekmesi: değerli oranlar ve düşen oran radarı

- **Değerli oranlar:** Son taramada iddaa oranının, Pinnacle'ın marjı arındırılmış adil
  oranından yüksek olduğu tüm seçimler, avantaja göre sıralı. Örnek: gerçek olasılığı %50 olan
  sonuca 2,20 oran → 0,50 × 2,20 − 1 = +%10.
- **Düşen oranlar:** Gün içinde Pinnacle'da olasılığı en az 3 puan artan (oranı düşen)
  sonuçlar. iddaa oranı henüz düşmediyse değer fırsatıdır ve bildirim gelir.
- **Otomatik radar** (Ayarlar → Düşen oran radarı): günde 1, 2 ya da 4 tarama. Bugün oynanmış
  kupon yoksa ve güncel oranlarla daha iyi bir kupon kurulabiliyorsa onu önerir. Her tarama lig
  sayısı kadar kredi harcar; ayarlar ekranı aylık tahmini gösterir.

**ROI analizi** (Geçmiş sekmesi): maç sayısı, kupon oranı, lig, pazar ve aya göre kâr/zarar,
ROI ve kapanış avantajı; ayrıca kasanın zirveden en büyük düşüşü.

**Bilinçli olarak eklenmeyenler:**
- *Arbitraj:* tüm iddaa bayileri aynı oranı verir ve her maçta marj vardır; tek kaynakta risksiz
  kâr imkânsızdır. Yabancı sitelerle arbitraj Türkiye'de yasa dışıdır.
- *Kendi "derin veri" modeli:* Pinnacle'ın fiyatı xG, sakatlık, hakem ve hava bilgisini zaten
  içerir. Kamuya açık veriyle kurulan bir model bu fiyatı yenmekten çok gürültü ekler.

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
./build.sh          # testler + build/BilincliKupon-1.3.apk
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
