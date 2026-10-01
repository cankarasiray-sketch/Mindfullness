# Bilinçli Kupon

iddaa için değer odaklı kupon önerisi ve kasa takibi. Her sabah 06:00'da (Türkiye saati)
bülteni tarar, matematiksel olarak avantajlı bir kupon varsa önerir, yoksa **pas** der.
Kasanı tutar, oynadığın kuponların sonucunu kendisi kontrol eder, performansını dürüstçe raporlar.

> **Android uygulaması:** Aynı mantık telefonda da çalışıyor; bilgisayar kapalıyken bile
> 06:00'da bildirim gelir. Kurulum ve derleme için [android/README.md](android/README.md).

## Önce gerçek: garanti kazanç yok

Hiçbir uygulama iddaa'da garanti kazanç sağlayamaz. Bunun sebebi şans değil, aritmetik:

- iddaa her oranın içine bir marj koyar. Bir maçın üç sonucunun ima ettiği olasılıkların
  toplamı %100 değil, %100 + marjdır. Uygulama bu marjı her gün bültenden ölçüp gösterir.
- MBS kombine oynamayı zorunlu kılar, marj da her maçta çarpılarak büyür. Örneğin maç başına
  %10 marjla 3'lü kupon, uzun vadede yatırılan her 1 TL'nin yaklaşık 0,73 TL'sini geri verir
  (0,9 × 0,9 × 0,9). "Banko" favorilerle kurulan kupon, kasayı yavaş ama kesin eritir.

Uzun vadede kâr etmenin bilinen tek mantıklı yolu, **iddaa oranının gerçek olasılığın ima ettiği
orandan yüksek olduğu** nadir seçimleri bulmak ve sadece onları oynamaktır (*value betting*).
Bu uygulama tam olarak bunu yapar. Bu tür bir avantaj her gün çıkmaz, çıktığında da küçüktür.
Bu yüzden pas günleri normaldir ve uygulamanın en değerli çıktılarından biridir.

## Nasıl çalışır

1. **Adil olasılık.** Pinnacle dünyanın en keskin (en düşük marjlı, en iyi bilgilendirilmiş)
   bahis piyasasıdır. Oranları The Odds API'den alınır, marjı *üs yöntemiyle* arındırılır.
   Ortaya çıkan olasılık, sonucun gerçek olasılığının en iyi tahminidir.
2. **iddaa oranı.** Bülten Nesine'den (ya da senin hazırladığın bir JSON dosyasından) okunur,
   maçlar başlama saati ve takım adı benzerliğiyle eşleştirilir ("Bayern Münih" ⇄ "Bayern Munich").
3. **Avantaj.** `avantaj = adil olasılık × iddaa oranı − 1`. Eşiği (varsayılan +%3) geçen
   seçimler aday olur.
4. **Kupon.** MBS kuralına uyan (kupon büyüklüğü ≥ bacakların en yüksek MBS'i), aynı maçtan iki
   seçim içermeyen tüm kombinasyonlar denenir. Kasanın beklenen logaritmik büyümesini en çok
   artıran kupon seçilir. Bu ölçüt avantajı ve riski birlikte tartar. Tutma olasılığı %20'nin
   altındaki "piyango" kuponlar elenir.
5. **Tutar.** Çeyrek Kelly. Kasanın en fazla %3'ü yatırılır, tutar tam TL'ye yuvarlanır.
6. **Pas.** Eşikleri sağlayan kupon yoksa bugün oynanmaz. Gerekçesi panelde yazar.

## Kurulum (Windows)

1. [python.org](https://www.python.org/downloads/) üzerinden Python 3.11 veya daha yenisini kur.
   Kurulumda **Add python.exe to PATH** seçeneğini işaretle.
2. Projeyi indir ve kur:
   ```powershell
   git clone https://github.com/cankarasiray-sketch/Mindfullness.git
   cd Mindfullness
   pip install -e .
   bilincli kur
   ```
3. [the-odds-api.com](https://the-odds-api.com) üzerinden ücretsiz API anahtarı al ve
   `bilincli.toml` içindeki `odds_api_key` satırına yaz. Ücretsiz plan ayda 500 kredi verir;
   varsayılan 6 lig ve yalnızca maç sonucu pazarıyla günde yaklaşık 6 kredi harcanır, sonuç
   kontrolü de buna eklenir. Ay boyunca yeter.
4. Veri kaynaklarını test et:
   ```powershell
   bilincli kontrol
   ```
   Kaç iddaa maçı okunduğunu, kaçının keskin piyasayla eşleştiğini ve iddaa'nın o günkü marjını
   gösterir.
5. Kasanı oluştur ve ilk kararı al:
   ```powershell
   bilincli yatir 5.000
   bilincli gunluk
   bilincli panel
   ```

### Her sabah 06:00'da otomatik çalıştırma

```powershell
powershell -ExecutionPolicy Bypass -File scripts\windows-gorev-kur.ps1
```

Bu komut Windows Görev Zamanlayıcı'ya günlük bir görev ekler. Bilgisayar 06:00'da kapalı ya da
uykudaysa görev açılınca hemen çalışır. Aynı gün ikinci kez kupon üretilmez. Veri kaynağına
ulaşılamazsa görev 20 dakika arayla 3 kez daha dener. Kayıtlar `veri\gunluk.log` dosyasına yazılır.

Alternatif olarak açık bir terminalde `bilincli zamanla` komutunu çalıştırabilirsin.

### Kupon telefonuna gelsin (Telegram, isteğe bağlı)

Telegram'da @BotFather ile bir bot oluştur ve token'ı al, ardından bota bir mesaj yaz.
@userinfobot sana chat id'ni söyler. İkisini `bilincli.toml` dosyasındaki `[bildirim]` bölümüne
yaz. Günün kuponu ya da pas kararı her sabah mesaj olarak gelir.

## Günlük kullanım

| Komut | Ne yapar |
|---|---|
| `bilincli gunluk` | Biten maçları sonuçlandırır, bugünün kararını üretir, paneli günceller, bildirim gönderir |
| `bilincli kupon` | Bugünün kuponunu gösterir (`bilincli kupon 12` belirli bir kuponu gösterir) |
| `bilincli oynadim 12` | Öneriyi oynadığını kaydeder, tutarı kasadan düşer |
| `bilincli oynadim 12 --tutar 150 --oranlar "1.85 2.10"` | Farklı tutarla oynadıysan ya da oranlar değiştiyse |
| `bilincli sonuclandir` | Sonuçları hemen kontrol eder |
| `bilincli sonuc 12 2 iade` | Ertelenen/iptal edilen maçın sonucunu elle girer (iddaa kuralı: oran 1,00) |
| `bilincli durum` | Kasa, kâr/zarar, ROI, tutan/beklenen |
| `bilincli yatir 1000` / `bilincli cek 500` | Kasaya para ekler / çeker |
| `bilincli panel` | Yerel web panelini açar (http://127.0.0.1:8765). Bütün işlemler butonlarla yapılabilir |
| `bilincli demo --ac` | Sentetik veriyle 60 günlük simülasyon yapar, paneli açar |

Öneriyi oynamasan da uygulama sonucunu takip eder. Böylece modelin performansı, senin
oynayıp oynamadığından bağımsız olarak ölçülür.

## Kasa koruması

- **Bahis sınırı:** Bir kupona kasanın en fazla %3'ü yatırılır (`max_bahis_orani`).
- **Haftalık kayıp limiti:** Pazartesi 06:00'dan bu yana sonuçlanan kuponların zararı, hafta başı
  kasanın %15'ini geçerse hafta sonuna kadar kupon üretilmez.
- **Kovalama beklemesi:** Zarardayken kasaya para eklenirse 48 saat boyunca kupon üretilmez.
  Kaybı geri almak için bahsi büyütmek, kasayı en hızlı eriten davranıştır. Ana parayı
  artırmak istiyorsan bunu kâr ederken yap, zarar ederken değil.

Hepsi `bilincli.toml` içinden değiştirilebilir. Kumar kontrolünü zorlaştırmaya başladıysa
Yeşilay Danışmanlık Merkezi (YEDAM) 115 numaralı hattan ücretsiz destek veriyor.

## Sonuçları nasıl okumalı

Panelde **Beklenen tutan** ile gerçekleşen tutan sayısı yan yana durur. Model doğru çalışıyorsa
bu ikisi uzun vadede birbirine yaklaşır. 20–30 kuponluk bir seri hiçbir şey kanıtlamaz; varyans
çok yüksektir. Avantajlı bir strateji bile 30 kuponluk bir dönemde zarar edebilir, avantajsız
bir strateji de kâr edebilir. En az 100–200 kupon biriktikten sonra hâlâ zarardaysan ve
gerçekleşen tutan sayısı beklenenin belirgin şekilde altındaysa, piyasada avantaj yok demektir.
O noktada en mantıklı karar durmaktır.

## Ayarlar

`bilincli.toml` dosyası açıklamalıdır. Öne çıkanlar:

| Ayar | Varsayılan | Anlamı |
|---|---|---|
| `min_bacak_avantaji` | 0.03 | Her maçta iddaa oranı adil oranı en az %3 geçmeli |
| `min_kupon_avantaji` | 0.05 | Kuponun toplam beklenen değeri en az +%5 |
| `min_kazanma_olasiligi` | 0.20 | Kuponun tutma şansı en az %20 |
| `min_kupon_orani` | 1.50 | Anlamlı kazanç için alt sınır |
| `max_mac` | 4 | Kupondaki en fazla maç sayısı |
| `kelly_carpani` | 0.25 | Çeyrek Kelly |
| `min_kupon_tutari` | 50 | iddaa'nın güncel asgari kupon bedeline göre güncelle |
| `pazarlar` | `["h2h"]` | `"totals"` eklenirse 2,5 Alt/Üst de değerlendirilir (kredi iki katına çıkar) |

Eşikleri düşürmek daha çok kupon üretir ama her birinin avantajı daha belirsiz olur.

## Bilinen sınırlar

- **Nesine bülteni resmi bir API değil.** Sitenin kendi kullandığı JSON verisi okunuyor.
  Biçim değişirse `bilincli kontrol` sıfır maç gösterir. O durumda `iddaa_kaynagi = "dosya"`
  ayarıyla oranları `veri/iddaa.json` dosyasından verebilirsin (biçimi
  `bilincli/providers/filesource.py` içinde yazılı). `bilincli kontrol --ham` ham yanıtı
  kaydeder; ayrıştırıcıyı düzeltmek için bu dosya yeterli.
- Nesine'den şimdilik yalnızca **Maç Sonucu (1-X-2)** pazarı okunuyor. Alt/Üst için dosya
  kaynağı kullanılabilir.
- Oranlar 06:00 ile kuponu oynadığın an arasında değişebilir. Oynamadan önce iddaa'daki oranı
  kontrol et. Düştüyse avantaj kaybolmuş olabilir. Değişen oranla oynadıysan `--oranlar` ile kaydet.
- Sonuçlar The Odds API'den alınır ve en fazla 3 gün geriye gidilebilir. Ertelenen maçlar için
  uygulama elle giriş ister. Sonuçlar normal süreye göre değerlendirilir; kupa maçlarında
  uzatma olursa kontrol et.
- Takım adı eşleştirmesi bulanık eşleştirmedir. `bilincli kontrol` eşleşmeleri listeler;
  yanlış eşleşme görürsen `bilincli/matching.py` içindeki `_ALIASES` sözlüğüne ekleme yap.

## Gizlilik

Repo herkese açık. Kasa veritabanın, `bilincli.toml` (API anahtarları) ve `veri/` klasörü
`.gitignore` ile dışarıda tutulur; bunları asla commitleme.

## Geliştirme

```bash
pip install -e ".[dev]" ruff
pytest -q
ruff check bilincli tests
```

Dış bağımlılık yoktur, yalnızca Python standart kütüphanesi kullanılır.
