# Bilinçli Kupon — Android

Masaüstü sürümüyle aynı mantığın telefona taşınmış hali. Bilgisayar açık olmasa da her sabah
06:00'da (Türkiye saati) bülteni tarar, kararı bildirim olarak gönderir, kasanı ve kuponlarını
telefonda tutar.

## Kurulum (telefon)

1. `BilincliKupon-2.7.0.apk` dosyasını telefona indir ve aç. Android "bilinmeyen kaynak" izni
   isterse bu kaynağa (tarayıcı ya da dosya yöneticisi) izin ver.
2. Uygulamayı aç, bildirim iznini onayla.
3. **Ayarlar** sekmesinde The Odds API anahtarını gir ([ücretsiz](https://the-odds-api.com)),
   **Kaynakları test et**'e bas. Kaç iddaa maçının okunduğu ve kaçının eşleştiği görünür.
4. **Kasa** sekmesinden kasanı oluştur.
5. Hazır. Karar her sabah gelir; istersen **Bugün** sekmesinden **Şimdi üret** diyebilirsin.

Anahtar girmeden denemek için **Önce demoyu gör**: hayali takımlarla 60 günlük simülasyon.

**the-odds-api.com açılmıyorsa ("Bağlantı sıfırlandı" / ERR_CONNECTION_RESET):** Bu, bulunduğun
ağdan siteye erişimin engellendiğinin tipik görüntüsüdür. Uygulamanın veri aldığı adres
(`api.the-odds-api.com`) de engelliyse uygulama Pinnacle oranlarını alamaz. Kontrol için telefonun
tarayıcısında `https://api.the-odds-api.com/v4/sports` adresini aç: kısa bir hata metni (anahtar
eksik) görürsen veri adresine erişim vardır, yalnızca kayıt sayfası açılmıyordur; "Bağlantı
sıfırlandı" görürsen veri adresi de engellidir. Başka bir ağ (Wi-Fi / mobil veri) dene.
"Kaynakları test et" de bu durumda "erişim engelleniyor olabilir" der. Not: Chrome adresi açsa
bile uygulamanın bağlantısı engele takılabilir (tarayıcı site adını şifreleyerek bağlanabiliyor,
standart Android bağlantısı bunu yapmıyor). Bu durumda uygulama ancak VPN açıkken veri alır;
sabah kararı ve maç öncesi kontroller için VPN'i "Her zaman açık" yap (Android: Ayarlar → Ağ →
VPN → uygulamanın yanındaki dişli → Her zaman açık).

**VPN açıkken Nesine "bağlantı sıfırlandı" derse (1.8.1):** Nesine bazı VPN sunucularını
engeller. Uygulama Nesine'yi önce VPN dışındaki ağdan (mobil veri / Wi-Fi) çekmeyi dener; bu,
VPN uygulaması atlamaya (bypass) izin veriyorsa çalışır. İzin vermiyorsa VPN'de Türkiye'ye yakın
başka bir sunucu seç. Kopan bağlantılar kısa aralıkla yeniden denenir.

**Milli maçlar (1.8):** Milli maç arasında lig maçı olmaz. Ayarlar → Milli maçlar açıkken
(varsayılan) The Odds API'de o an aktif milli turnuvalar (Uluslar Ligi, Dünya Kupası elemeleri,
hazırlık maçları...) günde bir kez ücretsiz spor listesinden bulunup taramaya eklenir. Bülten
Türkçe ("Almanya"), keskin piyasa İngilizce ("Germany") yazdığı için yaklaşık 100 ülke adı
eşlenir; U21 ve kadın milli takımları A takımıyla eşleşmez.

**"Keskin piyasa: 0 maç" görürsen:** Seçili liglerin hiçbirinde önümüzdeki 24 saatte maç yoktur
(ör. Perşembe günü yerli ligler oynamaz). Uygulama maç listesine ücretsiz bakar ve maçı olmayan
lig için kredi harcamaz; test çıktısı bu ligleri listeler. Varsayılan ligler 1.7.2'den itibaren
Avrupa kupalarını (hafta içi), Hollanda ve Portekiz'i de içerir; Ayarlar'dan 31 lig arasından
seçim yapılabilir. Seçili lig yalnızca oynadığı gün kredi harcar.

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

## Tutma olasılığı ve kârlılık dengesi (2.13)

- **Denge puanı:** günün seçimi ve tek maç listesi artık yalnızca beklenen değere göre değil, beklenen
  log büyümeye (Kelly ölçütü) göre sıralanır: kasanın oynanan payı f iken
  `[p·ln(1 + f(o − 1)) + (1 − p)·ln(1 − f)] / f`. Tutma olasılığı ile ödemeyi birlikte tartar: beklenen
  değer aynıysa sık tutan, tutma yakınsa ödemesi iyi olan öne geçer; uzun vadede kasayı en az küçülten
  (değerliyse en çok büyüten) seçim üstte.
- **Kademeli tutar:** beklenen kayıp sınırın (Ayarlar → Tek maç → En kötü beklenen kayıp, varsayılan %6)
  yarısına kadarsa tam tutar, sınıra kadarsa yarım tutar, daha kötüyse o gün **oynanmaz** (kart "Bugün
  oynama", sabah bildirimi de öyle; sanal takip o gün bahis saymaz). Değerli seçimde (beklenen ≥ maç
  başına eşik) Kelly tutarı, temel tutardan az olmamak üzere. iddaa marjı (~%22) yüzünden çoğu seçim eksi
  beklenir; kârlılığı artıran asıl şey kötü günleri oynamamak ve kayıp beklenen günlerde tutarı küçültmek.
  Örnek: 50 TL temel tutarla en iyi seçim −%12 olan gün eski kural ~6 TL kaybettirirdi, yenisi oynamaz;
  −%4'lük günde 25 TL ile ~1 TL.
- **Denge tablosu (Fırsatlar):** bugünün tek maç seçimleri tutma bandına göre (%50–60, 60–65, 65–70,
  70–80, 80+): her bantta seçim sayısı ve adil orana en yakın seçimin beklenen değeri; "en iyi denge"
  işaretli.

## Basketbol kapsamı (2.12)

- Basketbol için Pinnacle fiyatı oran kaynağındaki (The Odds API) liglerden gelir: EuroLeague, NBA ve
  dönemine göre NBA hazırlık, WNBA, Avustralya NBL gibi ligler. Türkiye BSL, İspanya, İtalya, Almanya,
  VTB, ABA gibi iddaa'daki diğer ligler kaynakta olmadığından değerlendirilemez.
- Önceden yalnızca EuroLeague ve NBA taranıyordu; kaynakta aktif ek basketbol ligleri elle
  işaretlenmedikçe taranmıyordu. Artık **kendiliğinden taranır** (Ayarlar → Basketbol; maçı olmayan lig
  kredi harcamaz, kredi yetmezse plan en az fırsat çıkaran ligi önce çıkarır).
- Özet satırı eşleşen basketbol maçı sayısını gösterir ("eşleşen 22 (basketbol 0)"): basketbol
  önerisi yoksa nedeni o gün kaynakta eşleşen maç olmamasıdır (ör. EuroLeague hafta içi oynar).

## Hız ve veri iyileştirmesi (2.11)

- **Gol modeli ~28 kat hızlı:** ek pazarların adil olasılığı için her maçta çözülen model, ızgara
  taraması (~6.700 hesap) yerine başlangıç tahmini + daralan adımlı aramayla (~150 hesap) çözülür.
  400 maçta 634 ms → 22 ms (masaüstü ölçümü; telefonda oran benzer). Sonuç eskisiyle aynı ya da daha
  isabetli (testte karşılaştırılır).
- Adil oran tablosunda olasılıklar 4 haneye yuvarlanır (daha küçük kayıt). 2.11.1: tablo yine her
  ekran yenilemesinde durumla birlikte gelir (Fırsatlar açılınca hazır); tembel yükleme geri alındı.
- Tek maç listesindeki her satır önerilen tutarı taşır. 2.11.2: günün seçimi ve tek maç listesi yine
  her ekran yenilemesinde baştan hesaplanır (önbellek geri alındı).
- Tarama tabloyu güncellerken okuyan ekranın eşzamanlı değişiklik hatası almaması için görünüm kopyası.
- Pas gününde "Günün seçimi" kartı Bugün sekmesinin en üstünde.

## Tek maç uzmanı (2.10)

- **Yalnızca tek maç:** değerli seçimler de yalnızca tek oynanabilen (MBS 1) maçlardan, tekli kupon
  olarak önerilir (Ayarlar → Tek maç; güncellemede bir kez açılır). Kombinede iddaa marjı her bacakta
  yeniden ödenir. Demo simülasyonunda (24 piyasa × 180 gün, En yüksek kazanç ayarı) tutma oranı
  %38'den %46'ya çıktı, aylık büyüme +%6,7'den +%5,4'e indi: değerli bacakların birleşimi ve MBS 2–3
  maçlar dışarıda kalıyor. Kombineye izin vermek için ayar kapatılabilir.
- **Her bahis türü:** maç sonucu, çifte şans, 2,5 Alt/Üst, karşılıklı gol, basketbol (maç sonucu,
  toplam, handikap) yanında artık **Alt/Üst 0,5 / 1,5 / 3,5 / 4,5**, **ev ve deplasman gol Alt/Üst**
  ve **handikaplı maç sonucu**. Pinnacle bu pazarlara fiyat vermediği için adil olasılık, Pinnacle'ın
  maç sonucu ve 2,5 Alt/Üst fiyatına uyan gol modelinden (bağımsız Poisson, skor matrisi) gelir ve
  kararda **4 puan güvenlik payı** düşülür; ekranda "model" diye işaretlenir.
- **Pazar eşlemesi:** Nesine bülteninde bu pazarların kodu ve seçenek sırası bilinmediğinden her
  taramada gol modeliyle karşılaştırılarak bulunur: her çizgili iki seçenekli pazar için toplam / ev /
  deplasman golü / ilk yarı toplamı ve iki yön, çizgili üç seçenekli pazar için handikabın iki işareti
  denenir; maç başına ortalama olasılık farkı %6'nın altında ve ikinci adaydan belirgin ayrılan kabul
  edilir. Korner ve kart gibi gol dışı pazarlar uymaz. İlk yarı pazarları tanınır ama kullanılmaz (sonuç
  kaynağında ilk yarı skoru yok, otomatik sonuçlandırılamaz). Durum "Kaynakları test et"te: "Ek tek maç
  pazarları".
- **Günün seçimi her oranı alır** (1,05 üstü; önce 1,25–4,50) ve iki katmanlıdır: en az %60 tutanlar
  arasında adil orana en yakın seçim ve ayrıca **%70+ seçimi** (daha sık tutar, ödemesi küçük). Eşikler
  Ayarlar → Tek maç'tan değişir.
- **Tek maç fırsatları (Fırsatlar):** son taramanın bütün tek oynanabilen seçimleri (her pazar, Zirve
  dahil), adil orana yakınlığa göre; %50+ / %60+ / %65+ / %70+ / %80+ süzgeci (2.12.1: varsayılan %65+,
  liste 100 seçime kadar) ve her satırda "Oynadım".
  Yeşil beklenen değer gerçek fırsattır.
- Bütün yeni pazarlar maç sonucuna göre otomatik sonuçlanır (x,5 çizgide iade yok; handikapta eşitlik X).

## Günün seçimi (2.9)

iddaa oranları neredeyse her zaman adil oranın altında kaldığından çoğu gün değerli seçim çıkmıyor.
Yine de oynamak isteyen için her gün tek bir öneri:

- **Kural:** tek maç oynanabilen (MBS 1), tutma olasılığı en az %60 (Ayarlar → Günün seçimi), oranı
  1,25–maç oranı üst sınırı arasında, en az 30 dk sonra ve 24 saat içinde başlayan seçimler arasında
  **adil orana en yakın** (beklenen değeri en yüksek) olan. Normal iddaa oranları ve Bilyoner Zirve Oran
  birlikte karşılaştırılır; Zirve oranı daha iyiyse o önerilir.
- **Tutar:** Kelly değil, sabit ve küçük: ayar 0 ise kasanın %1'i (10 TL'ye yuvarlı, en az 10 TL).
  Kart tutarı, tutarsa ödemeyi, beklenen sonucu ve her gün oynanırsa aylık beklenen sonucu yazar.
  Seçim adil oranı geçiyorsa "değerli" diye işaretlenir.
- **Neden böyle:** rastgele bir tek maç bahsinde iddaa marjı (~%22) yüzünden 100 TL'de ortalama ~18 TL
  kaybedilir; adil orana en yakın, sık tutan seçimde kayıp tipik olarak 2–8 TL'ye iner. Sık tutması
  kaybı ortadan kaldırmaz, dalgalanmayı azaltır.
- **Nerede:** pas günlerinde Bugün sekmesinde "Günün seçimi" kartı (diğer 3 aday açılır listede) ve
  sabah bildiriminde başlık. Haftalık kayıp limiti dolduysa öneri gösterilmez. "Oynadım" seçimi
  kasaya "günün seçimi" kuponu olarak yazar (avantaj ölçümüne girmez); Geçmiş'te ayrı sonuç kartı
  gerçekleşen ve beklenen tutma oranı ile kâr/zararı karşılaştırır.
- **Sanal takip:** günün seçimi oynansın oynanmasın 100 TL'lik sanal bahis olarak kaydedilir ve
  sonuçlanır (seçim maç başlamadan değişirse yenisi geçer); strateji para riske atmadan sınanır.

## Uzun işlemlerde bekleme ekranı (2.8.1)

- "Güncel oranlarla yeni kupon" ve "Şimdi tara" kredi bolken dakikalarca sürebiliyordu: taramadan önce
  bugün oynayan ligler (~40 lig, ücretsiz maç listesi) her seferinde tek tek, sırayla soruluyordu.
  Artık bu liste 45 dakika boyunca yeniden kullanılır ve 4 lig aynı anda sorulur ("Kaynakları test et"
  ve "Bugün oynayanları öğren" her zaman yeniden sorar).
- Bekleme ekranı ne yapıldığını yazar (maç listesi 12/40, iddaa bülteni, Pinnacle oranları 5/18, maç
  sonuçları, Zirve Oran) ve geçen süreyi gösterir. Arka planda bir tarama sürüyorsa önce onun bitmesi
  beklenir; bekleme ekranında "Arka plandaki işlem sürüyor · …" diye görünür.
- 10 saniyeden sonra "Arka planda sürsün" ile ekran kapatılabilir; sonuç bitince bildirim balonu
  olarak gelir. Sırada başka işlem varsa "Sırada" yazar.
- Ağ: yanıtın tamamı en fazla 90 sn (Nesine/Bilyoner 180 sn) sürebilir; çok yavaş akan bağlantıda
  işlem sonsuza dek beklemez, hata mesajı VPN sunucusunu değiştirmeyi önerir.

## En yüksek kâr ayarları, günde 8 radar, saatlik Zirve (2.8)

- **"En yüksek kazanç" profili güncellendi:** Kelly çarpanı 0,50 → **0,60**, maç oranı üst sınırı
  3,50 → **4,50**, maç başına en az avantaj %3 → **%2**. Kupon başına %10, günlük toplam %20, maç
  başına %30 / kupon için %20 tutma olasılığı aynı. Değerler uygulamanın kendi motoruyla, demo
  piyasasında (24 piyasa × 180 gün, aynı tohumlar) ve iki zorlaştırılmış dünyada ölçüldü:

  | Ayar (normal dünya: marj %8, model hatası %2) | Ortanca kasa | Aylık büyüme | Kötü %10 | Tutma |
  |---|---|---|---|---|
  | Temkinli | ×1,12 | +%1,5 | ×0,93 | %50 |
  | 2.7 En yüksek kazanç | ×1,27 | +%4,6 | ×0,90 | %40 |
  | yalnız üst oran 5,0 | ×1,40 | +%5,2 | ×1,04 | %40 |
  | yalnız maç başına %2 | ×1,42 | +%5,2 | ×0,94 | %38 |
  | üst oran 4,5 + %2 | ×1,53 | +%5,8 | ×1,03 | %38 |
  | **2.8: üst oran 4,5 + %2 + Kelly 0,60** | **×1,63** | **+%6,7** | **×1,03** | %38 |
  | aynısı, Kelly 0,65, %12 / gün %25 | ×1,70 | +%7,0 | ×1,03 (en kötü ×0,75) | %37 |

  | Aylık büyüme (kötü %10) | 2.7 | üst 4,5 + %2 | **2.8** | Kelly 0,65 |
  |---|---|---|---|---|
  | Zor dünya: marj %12, model hatası %4 | +%1,9 (×0,88) | +%2,2 (×0,91) | **+%2,5 (×0,88)** | — |
  | Çok hatalı model: marj %10, hata %8 | +%1,7 (×0,77) | +%1,8 (×0,80) | **+%2,0 (×0,74)** | +%2,1 (×0,64) |

  Üst oran 4,5 ile 5,0 aynı sonucu verdi (%30 tutma şartıyla 4,5 üstü oranda değer pek çıkmaz).
  Kupon eşiğini %5'ten %3'e indirmek, en az maç oranı, haftalık limit, aday sayısı ve en fazla maç
  sayısı sonucu değiştirmedi ya da zor dünyada düşürdü; değiştirilmedi. Kelly 0,65 her dünyada biraz
  daha büyüme verdi ama çok hatalı modelde kötü %10'u ×0,74'ten ×0,64'e indirdi; gerçekte avantaj
  tahminleri abartılı çıkma eğiliminde olduğundan 0,60'ta kalındı. Bu dünyalarda avantaj tasarım
  gereği vardır; rakamlar ayarları karşılaştırmak içindir, gerçek getiri vaadi değildir.
- **Günde 6 ya da 8 radar:** iddaa, Pinnacle'ın fiyat değişikliğini geç yansıttığında değer doğar;
  daha sık tarama bu aralıkları daha çok yakalar. En yüksek kazanç profili günde 8'e kadar tarar
  (maç saatlerine göre, taramalar arasında en az 90 dk). Kredi yetmezse kredi planı önce Karşılıklı
  Gol'ü, sonra kadro saatini, sonra radarı 8 → 6 → 4 → 2 → 1 diye azaltır; 10 anahtarla 8 tarama korunur.
- **Zirve Oran saatlik:** Zirve okuması kredi harcamadığı için arka planda saatte bir yapılır
  (01:00–08:00 arası hariç); yeni ya da yükselen artırılmış oran 3 saatlik işi beklemeden bildirilir.
- **Geçiş:** 2.7'nin En yüksek kazanç değerleri ayar dosyasında 2.8 değerlerine taşınır. Kullanıcı
  en yüksek kârı yeniden istediği için profil Temkinli ya da özelse güncellemede bir kez En yüksek
  kazanç'a geçirilir; Bugün sekmesi bir hafta boyunca yeni ayarları ve Temkinli'ye nasıl dönüleceğini
  gösterir. Sonradan elle seçilen profile dokunulmaz.

## En yüksek kazanç profili (2.7)

- Profiller artık tutma olasılığı eşiklerini de kurar. **En yüksek kazanç**: ortak yarım Kelly,
  kupon başına en fazla %10, günlük toplam %20; maç başına en az %30, kupon için en az %20 tutma
  olasılığı; günde 4 radar, kadro saati taraması, kredi bolsa ek ligler, Alt/Üst ve basketbol
  handikabı açık (kredi yetmezse kredi planı kendiliğinden daraltır). **Temkinli (sık tutsun)**:
  çeyrek Kelly, %3; maç başına %40, kupon için %30.
- Eşikler uygulamanın kendi motoruyla, demo piyasasında (20 piyasa × 180 gün, günlük karar) ölçüldü:

  | Ayar | Ortanca kasa | Aylık büyüme | Kötü %10 | Tutma |
  |---|---|---|---|---|
  | Temkinli, %40/%30 | ×1,14 | +%1,7 | ×0,93 | %51 |
  | Yarım Kelly, %50/%40 | ×1,21 | +%3,3 | ×0,91 | %62 |
  | Yarım Kelly, %40/%30 | ×1,27 | +%3,3 | ×0,86 | %50 |
  | Yarım Kelly, %35/%25 | ×1,25 | +%3,8 | ×0,95 | %45 |
  | **Yarım Kelly, %30/%20** | **×1,39** | **+%5,0** | **×0,99** | %40 |
  | Yarım Kelly, şart yok | ×1,39 | +%5,0 | ×0,98 | %40 |
  | Tam Kelly, %20, %30/%20 | ×1,39 | +%6,7 | ×0,83 | %39 |

  Tam Kelly ortalamayı artırdı ama ortancayı değiştirmedi ve kötü %10'u ×0,99'dan ×0,83'e indirdi;
  bu dünyada olasılıklar neredeyse doğru bilinir. Gerçekte avantaj tahminleri abartılı çıkma
  eğilimindedir (yalnızca iddaa'nın adilden yüksek olduğu seçimler oynanır); önceki 200 aylık
  testte tahmin hatası %6 olunca tam Kelly yarım Kelly'nin gerisinde kaldı. Bu yüzden profil yarım
  Kelly kullanır.

  Olasılık şartı sıkılaştıkça tutma oranı artar ama değerli seçimlerin bir kısmı dışarıda kalır ve
  büyüme düşer. Bu dünyada avantaj tasarım gereği vardır; rakamlar ayarları karşılaştırmak içindir,
  gerçek getiri vaadi değildir.
- Kullanıcı en yüksek kazancı istediği için, profili hiç değiştirilmemiş (Temkinli) kasa güncellemede
  bir kez En yüksek kazanç'a geçirilir; Bugün sekmesi bir hafta boyunca bunu ve Temkinli'ye nasıl
  dönüleceğini gösterir. Yeni kurulumların varsayılanı Temkinli'dir.
- Demo: kalibrasyonun bültenden çıkardığı seçenek yüzünden "banko" kıyasında oluşan boş değer hatası
  düzeltildi.

## Basketbol handikap (2.6)

- Pinnacle'ın handikap (spreads) fiyatı çekilir (basketbol taramasına +1 kredi; Ayarlar → Basketbol'dan
  kapatılabilir) ve ev sahibinin çizgisiyle "BH" olarak saklanır. iddaa'nın çizgisine sayı farkı
  normal dağılımlı kabul edilerek çevrilir (SS: NBA 12,5, diğerleri 11; en fazla 3 sayı fark; tam sayı
  çizgiler kullanılmaz). Seçimler "Basket H. Ev −4,5" / "Basket H. Dep +4,5"; sonuç uzatmalar dahil skorla.
- iddaa'da hangi pazarın maç handikabı olduğu, değerin hangi takıma uygulandığı ve 1. seçeneğin hangi
  takım olduğu (dört varsayım) Pinnacle'a karşı sınanır: yalnızca bilgi taşıyan ölçümler (olasılık
  %50'den uzak) kullanılır; en iyi varsayım belirgin değilse son güvenilir eşleme, o da yoksa pazar
  kullanılmaz. Toplam sayı, yarı/takım toplamları ve ilk yarı handikabı çizgi farkıyla ya da büyük
  sapmayla elenir. Maç bazında Pinnacle'dan 15 puandan fazla sapan çizgi kullanılmaz.
  "Kaynakları test et" özetinde "Basketbol Handikap" satırı ve "Basket H. ✓".
- Tutma olasılığı şartı, kanıt koruması (pazar ailesi "BH"), marj özeti ("basket handikap") ve
  sonuçlandırma diğer pazarlarla aynıdır.

## Tutma olasılığı şartı, MBS, ek basketbol ligleri (2.5)

- **Maç başına en az tutma olasılığı** (Ayarlar → Strateji, varsayılan %40): adil olasılığı bundan
  düşük seçim avantajlı olsa da kupona girmez; "Değerli oranlar"da "tutma %32, seyrek" rozetiyle
  görünür. Kupon için en az tutma olasılığının varsayılanı %20'den %30'a çıktı (eski varsayılanı
  kullananlar taşınır, kendi eşiğini girenlerinki korunur).
- Aynı şart promosyon kontrolünde ("avantajlı ama tutma olasılığı düşük"), Zirve Oran'da ("avantajlı
  ama seyrek tutar", bildirilmez), en yakın seçimde (önce şartı sağlayanlar) ve kampanya
  hesaplayıcıda (en az tutma olasılığı alanı) uygulanır.
- **MBS:** adil oran tablosu ve Zirve oranları seçimin MBS'sini taşır. Kampanya hesaplayıcı tek
  maçlık kampanyada MBS'si 1'den büyük maçları listelemez (istenirse gösterilir); Zirve'de değerli ama
  MBS'si 1'den büyük oran "tek oynanamaz" olarak işaretlenir ve bildirilmez. En yakın seçim metninde
  MBS 1'den büyükse yazılır.
- **Kampanya hesaplayıcı:** her satırda tutma olasılığı; oynanacak tutar ile sağdaki değer (bedava
  bahsin gerçek değeri ya da beklenen kâr/zarar) ayrı yazılır; "en sık tutan" sıralaması (değeri en
  iyinin en az yarısı olanlar arasından); yalnızca gerçekten böyle bir kampanya varsa geçerli olduğu
  açıkça yazılır.
- **Ek basketbol ligleri:** oran kaynağının ücretsiz lig listesinden EuroLeague ve NBA dışındaki aktif
  basketbol ligleri (ör. WNBA, Avustralya NBL; ABD üniversite ligleri hariç) Ayarlar → Basketbol'da
  seçilebilir; liste günde bir kez güncellenir. iddaa'daki diğer ligler (İspanya, İtalya, Almanya,
  VTB, ABA...) kaynakta yoksa Pinnacle fiyatı olmadan değerlendirilemez.

## Kampanya hesaplayıcı (2.4)

- Fırsatlar → "Kampanya hesaplayıcı": tek seçimli kampanyanın gerçek değeri, son taramanın adil
  oranlarıyla (kredi harcamaz); en değerli 5 seçim ve özet ("Oyna" / "Oynama").
  - **Bedava bahis:** değer = tutar x p x (oran − 1) (kazanınca tutar da ödeniyorsa p x oran).
    Bedava bahis her zaman artıdır; hesaplayıcı en değerli seçimi ve TL karşılığını gösterir.
  - **Kayıp iadesi:** beklenen = tutar x (p x oran − 1) + (1 − p) x min(iade % x tutar, üst sınır) x k;
    iade nakitse k = 1, bedava bahis olarak geliyorsa k = o anki en iyi bedava bahis dönüşümü.
  - **Kazanç artışı:** yeni oran = 1 + (oran − 1)(1 + artış).
  - **Erken ödeme** (takım maç içinde x fark öne geçerse kazanmış sayılır; yalnızca MS 1 / MS 2):
    Pinnacle'ın MS ve 2,5 Üst adil oranlarına uyan Poisson gol beklentileriyle dakika dakika
    "x farka ulaşma" olasılığı; artış Pinnacle'ın kazanma olasılığına eklenir. Model tahminidir.
  - Kampanyanın en düşük oranı girilir; kombine ve çevrim şartları hesaba katılmaz.
- Nesine bazı turnuvalarda lig adı yerine sayısal kod veriyor ("10004"); o maçlarda oran sorgusundaki
  lig adı gösterilir.

## Sanal takip (2.3)

- Pas günlerinde "en yakın seçim" (normal iddaa oranlarında ve Zirve Oran'da ayrı ayrı, günde tür
  başına bir tane) 100 TL'lik sanal bahis olarak kaydedilir ve maç bitince skorla sonuçlanır. Seçim gün
  içinde değişirse, önceki seçimin maçı başlamadıysa yenisi yerine geçer (sonuç bilinmeden; seçim
  yanlılığı yok). Kasaya, kupon istatistiklerine ve kanıt korumasına dokunmaz (`sanal.json`).
- Geçmiş → "Sanal takip": sonuçlanan / tutan / yatan, tutma oranı ile adil olasılığa göre beklenen
  tutma oranı, sanal kâr/zarar ve ROI, beklenen kâr/zarar, normal ve Zirve ayrı, son kayıtlar.
  "Avantajı yok ama tutuyor" düşüncesini para riske atmadan gerçek sonuçlarla sınar.
- Sonuçlar kupon sonuçlandırmasının skorlarından gelir; kuponların liginde olmayan sanal kayıtlar
  için yalnızca o liglerin skoru ayrıca çekilir (açık kupon yokken de). 3 günde sonucu bulunamayan
  kayıt iade sayılır.

## Bilyoner Zirve Oran (2.2)

- **Otomatik okuma:** Bilyoner'in "Zirve Oran" kampanyası (seçilen maçlarda oranlar ~%2–5
  artırılmış) herkese açık bültenin Zirve sekmesinden okunur
  (`/api/v3/mobile/aggregator/gamelist/all/v1?tabType=160&bulletinType=2`; her oranda `val` normal
  iddaa oranı, `tval` Zirve oranı). Hesap ya da oturum gerekmez, kredi harcamaz. Okuma zamanları:
  uygulama açılınca (10 dakikada bir), sabah kararından, radar taramasından ve "Kaynakları test
  et"ten sonra, 3 saatlik periyodik işte ve (2.8) arka planda saatte bir.
- **Değerlendirme:** Zirve maçı son tam taramanın adil oran tablosundaki maçla (aynı saat, benzer
  adlar) eşleştirilir; seçim uygulamanın pazarına çevrilir (MS, KG, ÇŞ, 2,5 Alt/Üst; basketbolda
  uzatmalar dahil maç sonucu ve iddaa çizgisindeki toplam sayı). Avantaj = adil olasılık x Zirve oranı
  − 1; eşik, kanıt koruması ve Kelly tutarı promosyon kontrolüyle aynıdır.
- **Koruma:** Bilyoner'in normal oranı son taramadaki iddaa oranıyla aynı olmalı (iki site de resmi
  programı verir); %8'den büyük fark oranın değiştiğini ya da eşlemenin şüpheli olduğunu gösterir,
  seçim önerilmez ("oran değişmiş, tara"). İlk yarı, takım, korner ve kart pazarları alınmaz.
- **Gösterim:** Fırsatlar'da "Zirve Oran (Bilyoner)" kartı maç maç Zirve oranını, normal oranı,
  adil oranı ve avantajı gösterir; adil oranı geçenlerde "Oyna" önerilen tutarla açılır ve "Bu
  tutarla oynadım" bahsi promosyon kuponu olarak kasaya yazar. Adil oranı geçen olursa bildirim gelir
  (aynı oran bir kez; Zirve oranı yükselirse yeniden) ve Bugün sekmesinde uyarı görünür. Ayarlar →
  Radar'dan kapatılabilir.
- **Hedefli tarama (2.2.1):** Zirve maçı son taramada yoksa (ör. ligi o gün kredi planında değildi
  ya da sabah taraması olmadı), maçın ligi kendiliğinden ayrıca taranır: iddaa bülteni (ücretsiz) ve
  yalnızca o ligin Pinnacle oranı (lig başına ~2 kredi; Zirve'de Karşılıklı Gol varsa maç başına +1).
  Lig son 3 saatte tarandıysa tekrar taranmaz; 2 günden ilerideki maçlar maç gününe bırakılır.
  Lig adı bilinen iddaa adlarından, lig listesinden ve daha önce eşleşen Zirve maçlarından bulunur.
  Kısmi taramalar (kadro saati, hedefli) adil oran tablosunu satır satır tazeler. Hâlâ eşleşmeyen
  maçta kart nedenini tek satırda yazar (ör. "son taramada bu saatte Fransa – İtalya var; bu maçla
  eşleşmedi" ya da "ligi 07:50'de tarandı; Pinnacle'da bu maç yok").
- **Okuma durumu (2.2.2):** okuma ve hedefli tarama sürerken kart hangi adımda olduğunu yazar ve
  birkaç saniyede bir kendiliğinden yenilenir; ilk değerlendirme tarama bitmeden görünür. Adil
  oranların alındığı tam tarama zamanı kartın üstünde. Güncellemeden sonra eski sürümün kaydı
  10 dakika beklenmeden yeniden okunur.
- **Nesine'siz hedefli tarama (2.2.3):** Zirve yanıtı maçların tüm normal iddaa oranlarını da
  içerdiğinden hedefli taramada iddaa tarafı Bilyoner'den kurulur; yalnızca Pinnacle oranı çekilir
  (Nesine bültenine ulaşılamasa da çalışır, daha hızlı). Eşleme koruması: her pazarda Bilyoner
  oranlarının marjı arındırılmış olasılığı Pinnacle'ın adil olasılığından 10 puandan fazla saparsa
  (ör. Alt/Üst ters okunmuş) ya da karşılaştırılacak ikinci seçenek yoksa pazar atılır. Başarısız
  hedefli tarama 3 saat değil 15 dakika sonra yeniden denenir.
- **Karşılıklı Gol ve yeniden okuma (2.2.4):** tam tarama KG adil oranını yalnızca birkaç maç için
  çeker; Zirve maçında KG oranı varken adil oranı yoksa Pinnacle KG oranı maç başına çekilir (1 kredi,
  maç başına 3 saatte bir) ve iddaa tarafı Bilyoner'in normal KG oranlarıyla (eşleme korumasıyla)
  tabloya eklenir. Son okuma ya da hedefli tarama başarısızsa uygulama açılınca 10 dakika değil 2
  dakika sonra yeniden okunur. Pinnacle'da 2,5 çizgisi olmayan maçlarda (ana çizgi 2,75/3 ya da pazar
  henüz açılmamış) 2,5 Alt/Üst "adil oran yok" kalır; model tahmini kullanılmaz.
- **En yakın Zirve seçimi (2.2.7):** değerli Zirve oranı yokken kartın üstünde avantaja en yakın
  Zirve oranı (oynanabilir oran aralığındakiler arasından) ve pas kartındaki gibi tutar satırı:
  0 TL ve 100 TL'de beklenen kayıp; Zirve oranı hangi seviyeye çıkarsa oynanır ve o zaman önerilecek
  tutar. Örn. "Hırvatistan – İngiltere · MS 2 @ 1,74 (adil 1,87, −%7,0)". 2.2.8: uygulama
  güncellenince Zirve Oran 10 dakika beklenmeden yeniden okunur; önceki sürümün kaydında en yakın
  seçim kayıtlı satırlardan hesaplanır. 2.2.9: pas kartı Zirve Oran'daki en yakın seçimi de
  karşılaştırır; daha yakınsa "En yakın seçim (Zirve Oran)" olarak tutarıyla öne geçer, normal
  oranlardaki ikinci sıraya iner (değilse Zirve'deki ikinci satırda görünür).
- **Gerçekçi beklenti:** iddaa marjı ~%22, Zirve artışı ~%2–5; marj ~%17'ye iner ama çoğu zaman
  sıfırın altına inmez. Zirve Oran'lı seçimlerin çoğu "değer yok" çıkar; değerli olanı ara sıra görülür.
- Keşif aracı (2.1.2–2.1.6) kaldırıldı; "Kaynakları test et" artık Zirve okumasının özetini yazar.

## Promosyon kontrolü ve en yakın seçim (2.1)

- **Promosyon / özel oran kontrolü (Fırsatlar):** iddaa'nın normal oranları ~%22 marjlı olduğundan
  çoğu gün pas çıkar; Bilyoner'in "Süper Oran" gibi kampanyaları ise bazen adil oranın üstüne
  çıkar. Yaklaşan maçların seçimleri adil oran ve iddaa'nın normal oranıyla kendiliğinden
  listelenir (istersen takım ya da lig adıyla süzülür); bir seçime dokunup kampanya oranını
  yazdığın anda, düğmeye basmadan, son tam taramadaki Pinnacle adil olasılığıyla avantaj ve Kelly
  tutarı görünür (kredi harcamaz; kanıt koruması devredeyse olasılık motordaki gibi küçültülür). "Bu tutarla oynadım"
  bahsi tekli kupon olarak kasaya yazar; sonuç maçtan sonra otomatik işlenir. Kampanya bahisleri
  kanıt koruması ölçümüne (kapanış avantajı) girmez: kampanya oranı piyasa oranı değildir.
  Yalnızca tek seçimli kampanyalar; birleşik seçimler ("X kazanır ve 2,5 Üst") hesaplanamaz.
  Adil oranlar son tam taramadan (sabah kararı, radar ya da "Şimdi tara"); 3 saatten eskiyse uyarır.
- **Zirve Oran keşfi (2.1.4):** Bilyoner'in kampanyası "Zirve Oran" adını taşıyor (seçilen maçlarda
  tüm oranlar ~%2–4 artırılmış; ekrandaki örneklerde marj ~%17'den ~%13'e iniyor, Pinnacle'da ~%2).
  Keşif artık Zirve Oran sayfasından başlar; betiklerde "zirve", "specialOdds" ve "tabType" geçen
  kod parçalarını, bülten veri yollarını (gamelist/aggregator) ve istek başlığı adaylarını listeler;
  kullanıcıya özel olmayan aday veri adreslerini dener ve yanıtların yapısını (alan adları, örnek
  değerler) özetler. Okuyucu bu çıktıya göre yazılacak. 2.1.4 çıktısı: başlıksız açılan tek adres
  `/api/v3/mobile/aggregator/gamelist/all/v1?tabType=1&bulletinType=2` (HTTP 200, ~4 MB bülten:
  events → marketGroups → odds); diğer adresler uygulamaya özel başlık istiyor (HTTP 400, kod 4021).
  Zirve Oran kodda "topWin" (topWinOdds, topWinEnabled, günlük üst sınır). 2.1.5 bu bülteni indirip
  bir maçın ve Zirve alanı taşıyan bir maçın tam yapısını yazar. 2.1.5 çıktısı: sekme listesinde
  "Zirve Oran" tabType 160; futbol bülteninde Zirve alanı yok; oranlar `{"id":"maç:pazar:sonuç",
  "n":"MS 1","val":"1.79","mrt":1,"ocn":1,...}` biçiminde. 2.1.6 Zirve sekmesini (tabType 160)
  indirir; futbol bülteninde olmayan maç/oran alanlarını ve aynı oran kimliğinin bültenden farklı
  olduğu yerleri (artırılmış oran) yazar; futbol maçının takım adı/başlama saati alanlarını ve
  betikteki kısa ad tablosunu (topWinOdds'un API'deki adı) gösterir.
- **Bilyoner Süper Oran keşfi (2.1.2):** Bilyoner'in kampanya oranlarını hangi adresten ve hangi
  biçimde verdiği bilinmiyor; körlemesine okuma yanlış oran okutabilir. "Kaynakları test et"
  artık telefonda herkese açık Bilyoner iddaa sayfasını ve sitenin ilk birkaç betiğini indirip
  yapılarını özetler (durum, boyut, gömülü veri, "süper oran" geçen yerler, API'ye benzeyen
  adresler). Bu bölüm paylaşılınca Süper Oranlar gerçek veriye göre otomatik okunacak ve
  promosyon kontrolüne kendiliğinden düşecek. Oturum ya da hesap kullanılmaz, kredi harcanmaz;
  Bilyoner istekleri Nesine gibi önce VPN dışı ağdan denenir. 2.1.3'ten beri sunucu adları, sürüm
  numaralı veri yolları, oran/bülten/kampanya yolları ve kampanya alanı adayları da listelenir;
  test çıktısı "Çıktıyı paylaş" ile metin olarak gönderilebilir.
- **Bugünün maçları kendiliğinden (2.1.1):** Uygulama açıldığında bugün hangi liglerin oynadığı
  bilinmiyorsa (ör. güncellemeden sonra) arka planda ücretsiz maç listesinden öğrenilir; kredi
  planı ve ek ligler düğmeye basmadan güncellenir.
- **Pas kartında en yakın seçim:** Pas günlerinde, oynanabilir oran aralığındaki seçimler arasından
  avantaja en çok yaklaşan ve ne kadar uzak olduğu gösterilir (ör. "Ev – Dep · MS 1 @ 1,95 (adil
  2,00, −%2,5)"); bildirimde de yer alır.
- **En yakın seçimin tutarı (2.2.6):** altında "Önerilen tutar" satırı: avantaj eksi olduğu için
  Kelly tutarı 0 TL (oynanmaz) ve yine de 100 TL oynanırsa beklenen kayıp; seçimin oynanır olacağı en
  düşük oran (avantaj eşiği, kanıt koruması devredeyse onun küçültmesiyle) ve o oranda önerilecek
  tutar (kasa bakiyesine göre, 10 TL'ye aşağı yuvarlanmış). Ör. "0 TL — avantaj yok, oynanmaz (yine de
  100 TL oynanırsa beklenen kayıp ≈ 2,50 TL). Oran 2,06 ya da üstüne çıkarsa önerilen 30,00 TL".

## Kadro saati taraması, gece sessizliği, 7 anahtar (2.0)

- **Kadro saati taraması:** İlk 11'ler maçtan ~60 dk önce açıklanır; Pinnacle hemen tepki verir,
  iddaa çoğu zaman gecikir. Sabahki kararın maç saatlerinden gruplar çıkarılır (30 dk içinde
  başlayanlar bir grup) ve her grubun ilk maçından 45 dk önce yalnızca o grupta oynayan ligler
  taranır. Avantaj çıkarsa bugünkü kuponlara dokunulmaz, yenileri kalan günlük sınır içinde
  eklenir ve bildirilir; Fırsatlar'daki değer listesi tam taramaya ait kalır. Normal radar
  taraması 10 dk içindeyse onunla birleşir. Kredi: her lig günde bir tarama daha; kredi yetmezse
  plan bunu Karşılıklı Gol'den hemen sonra kapatır. Ayarlar → Düşen oran radarı'ndan kapatılabilir.
- **Gece maçları:** Sabah 09:00'dan önce başlayan maçlar (ör. NBA 02:00) radar planına girmez;
  önceden bunlar için 09:00'a, maç bittikten sonraya tarama konuyordu.
- **Gece sessizliği:** 00:00–08:00 arası bildirimler sessiz kanaldan gelir (ses, titreşim, ekran
  yok; sabah bildirim alanında durur). Ayarlar → Zamanlama'dan kapatılabilir.
- **7 anahtar (2.2.5'te 10):** Veri kaynağı'na en fazla 10 The Odds API anahtarı girilebilir
  (10 × 500 = ayda 5.000 kredi).

## Basketbol (1.9)

EuroLeague ve NBA maçlarının **maç sonucu** (uzatmalar dahil, beraberlik yok) Pinnacle'ın marjı
arındırılmış fiyatıyla karşılaştırılır. Avantajlı seçimler futbolla aynı havuza girer; günün
kuponlarında "Basket MS 1/2" olarak görünür, futbol maçlarıyla aynı kupona da girebilir. Bilyoner'de
basketbol → Maç Sonucu pazarından oynanır.

- **Kredi:** Basketbol ligi yalnızca maçı olduğu gün kredi harcar: tarama başına 1, Alt/Üst
  açıkken 2 (futbolla aynı). Kredi planı gerekirse en az fırsat çıkaran ligi (basketbol dahil) o
  gün çıkarır. Ayarlar → Basketbol kartından kapatılabilir.
- **Alt/Üst (toplam sayı, 1.9.2):** Pazarlar'daki Alt/Üst açıksa basketbolda toplam sayı da
  karşılaştırılır (uzatmalar dahil). Pinnacle tek (ana) çizgi verir, iddaa çoğu zaman başka bir
  çizgi: toplam sayı normal dağılımlı kabul edilir (standart sapma NBA'de 19, diğerlerinde 16),
  ortalama Pinnacle'ın çizgisi ve Üst olasılığından bulunur, iddaa çizgisindeki olasılık buradan
  okunur. Yalnızca en fazla 3 sayı farklı çizgiler (2 sayı farkta sapma varsayımının hatası ~1
  puan) ve buçuklu çizgiler (tam sayıda iade ihtimali) kullanılır. Kuponda "Basket 161,5 Üst".
  Nesine'nin pazar kodu, Pinnacle çizgisine yakın çizgi sunan pazardan bulunur (yarı ve takım
  toplamları, handikaplar çizgi farkıyla elenir). Seçenek yönü ana çizgide anlaşılamaz (iki taraf
  da ~%50); yalnızca çizgisi Pinnacle'dan farklı maçlardan ya da aynı maçın birden fazla
  çizgisindeki oran değişiminden belirlenir, iki kanıt çelişirse kullanılmaz; kanıt yoksa son
  güvenilir eşleme, o da yoksa pazar kullanılmaz. Dönüştürülmüş Pinnacle olasılığından 15
  puandan uzak çizgi ayıklanır.
- **Veri doğrulama:** Nesine'nin basketbol pazar kodu sabit kabul edilmez. Özel değersiz iki
  seçenekli pazarlar arasından, seçenek yönü dahil, Pinnacle'la en iyi örtüşen bulunur; yanlış
  eşleşen tek maç kararı bozmasın diye sapma ortancayla ölçülür. Ardından maç bazında tutarlılık:
  iddaa ve Pinnacle olasılıkları 15 puandan fazla ayrışan (yanlış eşleşme ya da ev/deplasman
  ters) maç kullanılmaz. Eşleme hatırlanır; az maçlı kontrollerde son güvenilir eşleme kullanılır.
  "Kaynakları test et" özetinde **Basketbol MS ✓** görünmeli; çıktıda bültenin spor dağılımı ve
  basketbol pazar envanteri de yer alır.
- **Eşleştirme:** Futbol ve basketbol maçları birbiriyle asla eşlenmez (Fenerbahçe, Real Madrid
  gibi iki sporda da oynayan kulüpler). Türkçe ve sponsorlu adlar (Kızılyıldız = Crvena Zvezda,
  Armani Milano = Olimpia Milano) eşleştirme sözlüğünde.
- **Sonuç:** Uzatmalar dahil skordan. Handikap pazarları kullanılmaz.
- **Dikkat:** NBA maçları Türkiye saatiyle gece 02:00–05:00. Sabah bulunan avantaj, maç saatine
  kadar sakatlık haberleriyle kaybolabilir; kanıt koruması (CLV) basketbolu ayrı ölçer ve
  avantaj kapanışta tutmuyorsa basketbol seçimlerini küçültür. Tahmin isabeti kartı yalnızca
  futbolu ölçer. Masaüstü (Python) sürümünde basketbol yok.

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

## Tahmin doğruluğu (1.7)

Uygulamanın "tahmini", Pinnacle'ın (ve likit bahis borsasının) marjı arındırılmış olasılığıdır.
Bu fiyat sakatlık, kadro, hava, xG gibi her bilgiyi zaten içerir ve futbolda bilinen en isabetli
tahminler arasındadır; kamuya açık veriyle kurulacak bir model onu yenmekten çok gürültü ekler.
Bu yüzden 1.7, tahmini değiştirmek yerine onu bozan veri hatalarını ayıklar ve isabeti gerçek
sonuçlarla ölçer:

- **Kadın, genç ve rezerv takımları:** "Arsenal (K)", "Fenerbahçe U19", "Barcelona B", "Jong Ajax"
  ana takımla eşleşmez. Yanlış maçın oranı sahte avantaj üretir. (Python ve Java aynı kuralı
  uygular; eşdeğerlik testi 491 ad çiftinde doğrular.)
- **Maç bazında oran tutarlılığı:** Aynı maçta iddaa ile Pinnacle'ın marjsız olasılıkları
  0,15'ten fazla farklı olamaz (%25 avantaj bile ~0,13 farktır). Daha büyük fark yanlış eşleşme
  demektir; ev/deplasman ters görünüyorsa da maç kullanılmaz.
- **Maç Sonucu sıra doğrulaması ortancayla:** Birkaç yanlış eşleşme tüm bültenin Maç Sonucu'nu
  kapatmaz; sıra gerçekten farklıysa her maç etkilendiği için yine yakalanır.
- **Bayat Pinnacle çizgisi:** Maça 6 saatten az kala Pinnacle'ın son güncellemesi aynı pazardaki en
  yeni güncellemeden 3 saatten eskiyse kullanılmaz (pazar askıda olabilir; haber anındaki sahte
  avantajlar böyle ayıklanır).
- **Tahmin defteri ve isabet kartı** (Geçmiş → Tahmin isabeti): Eşleşen her maçın olasılıkları
  kaydedilir ve sonuçla karşılaştırılır; skorlar kupon sonuçlandırmasında zaten çekildiği için
  ek kredi yok. Uygulamanın tahmini (ilk ve maç öncesi son) ile iddaa'nın kendi olasılıkları
  log kayıp ve Brier skoruyla kıyaslanır; fark 2 standart hatadan büyük değilse "henüz anlamlı
  değil" yazar. Olasılık dilimlerine göre kalibrasyon tablosu (tahmin %30 dediğinde gerçekte ne
  sıklıkla oldu) en az 20 sonuçlu dilimleri gösterir. Demo dünyasında 480 maçta iki tahmin
  arasındaki fark anlamlı çıkmadı: isabet farkını görmek yüzlerce, çoğu zaman binlerce maç ister.

## Para akışı düzeltmeleri ve maç saatine göre radar (1.6)

- **Yenilenen plandaki eski kupon artık oynanmış sayılmaz.** Gün içi tarama ya da "Güncel oranlarla
  yeni kupon" planı yenilediğinde, oynanmamış eski kuponlar açık kalıyor ve otomatik kasa takibi
  onları da (aynı maçta yeni kuponla birlikte) oynanmış sayabiliyordu: çift bahis. Artık "Yenilendi"
  olarak işaretlenir; kontrol, otomatik oynama, kapanış ve sonuçlandırma dışında kalır. Gerçekten
  oynadıysan "Oynadım" ile yeniden açılır.
- **Yeniden üretim oynanmış kuponlara dokunmaz.** Oynanmış kupon varken "Güncel oranlarla yeni
  kupon" planı silmez; yeni kuponlar kalan günlük sınır içinde, başka maçlardan kurulur. Pas ya da
  kayıp limiti sonucu da oynanmış günün kaydını silmez.
- **Maça 90 dakikadan az kala kurulan kupon hemen kontrol edilir.** Önceden kontrol saati geçmiş
  sayılıp atlanıyor, otomatik oynama kaçabiliyordu. Kapanış oranı alınamazsa 10 dakikada bir
  yeniden denenir (kredi harcayan döngüye girmez).
- **Radar maç saatine göre:** Günün maçları tarama sayısı kadar gruba bölünür, her tarama grubun ilk
  maçından ~2,5 saat önce yapılır (en erken 09:00). Kredi aynıdır; maç bilgisi yoksa sabit saatler.

## Kâr için: ortak Kelly, kanıt koruması, Çifte Şans, borsa uzlaşısı (1.5)

Her değişiklik uygulamanın kendi kodu üzerinde, sentetik iddaa piyasasında 200 aylık
simülasyonla ölçüldü (model hatası %2 ve %6). Bu dünyada avantaj tasarım gereği vardır; rakamlar
yöntemleri karşılaştırmak içindir, gerçek getiri vaadi değildir.

- **Ortak Kelly:** Günün kuponları ayrı ayrı değil birlikte tutarlandırılır. Değerli seçimlerden
  (maç başına bir, en çok 10) MBS'ye uyan tüm tekli ve kombine kuponlar aday olur; kuponlar maç
  paylaşabilir. Kasanın beklenen log büyümesini en çok artıran tutarlar 2^n sonuç durumu
  üzerinden tam hesaplanır, en büyük 5 kupon tutulur. Tipik ay: Temkinli +%1,1/+%1,4 →
  +%1,7/+%2,4; En yüksek getiri +%1,8/+%2,3 → +%2,9/+%4,2. 5'ten fazla kupon fark yaratmadı.
- **Kanıt koruması:** Oynama anındaki avantaj, maç öncesi Pinnacle kapanışıyla karşılaştırılır.
  Gerçekleşen avantaj öngörülenin yarısının altına düşerse olasılıklar iddaa fiyatına doğru
  küçültülür (pazar bazında; 30 maçlık ön bilgiyle). Gerçek avantaj varken kârı değiştirmedi;
  "avantajların" çoğu gürültü olduğunda (model hatası %12) aylık −%9'luk kaybı ~%0'a, en kötü ayı
  −%78'den −%14'e indirdi. Tüm avantajları her zaman küçültmek ise simülasyonda kârı azalttı; bu
  yüzden yalnızca kanıt zayıfsa devreye girer.
- **Çifte Şans:** Adil olasılık Maç Sonucu'ndan türetilir (ek kredi yok). Nesine'deki pazar
  kodu ve seçenek sırası Pinnacle'la karşılaştırılarak bulunur; belirsizse kullanılmaz.
- **Pinnacle + bahis borsası:** The Odds API yanıtında likit bir borsa (Betfair, Matchbook,
  Smarkets) varsa adil olasılık %60 Pinnacle + %40 borsa ortalamasıdır (ek kredi yok). İkisi
  5 puandan fazla ayrışıyorsa (biri bayat) o pazar o maçta kullanılmaz.
- **Gün içi ek kupon:** Sabahki kuponlar oynandıysa dokunulmaz; radar, kalan günlük sınır ve
  kupon hakkı içinde başka maçlardan ek kupon kurar.
- **Kredi tasarrufu:** Maç listesi sorgusu kredi harcamaz; karar penceresinde maçı olmayan lig için
  oran çekilmez. Aynı anda vadesi gelen kuponlar tek çekimle kontrol edilir. Kredi planı gerekirse
  günlük kuponu 5'ten 3'e indirir (3 kupon, 5'in getirisinin çoğunu verdi).
- **Profiller:** Kelly çarpanları değişmedi (çeyrek / yarım). ¾ ve tam Kelly tipik ayı biraz
  artırdı ama en kötü ayı −%60/−%70'e indirdi; model hatası %6 olunca tam Kelly yarım Kelly'nin
  gerisinde kaldı.

### Kredi nedir, limit gerekli mi?

Pinnacle oranlarını The Odds API verir. Her oran sorgusu, API anahtarının **aylık kotasından**
kredi düşer; bu, uygulamayı kaç kişinin kullandığından bağımsız, anahtar başınadır. Ücretsiz
anahtar ayda 500 kredidir; ücretli planlar çok daha fazlasını verir (güncel fiyat:
the-odds-api.com). Uygulama kotayı yanıt başlıklarından okur: kota büyükse kredi planı hiçbir şeyi
kısmaz. Ücretsiz anahtarda daraltma, kredinin ay ortasında bitip uygulamanın körleşmemesi
içindir. İstersen Ayarlar → Kredi planı'ndan kapatabilirsin; kredi biterse oranlar alınamaz ve yenilenene kadar
kupon üretilemez. Nesine bülteni ücretsizdir ve kredi harcamaz.

**Birden fazla anahtar (1.9.1; 2.0'da 7, 2.2.5'ten beri 10):** Ayarlar → Veri kaynağı'na en fazla 10 anahtar girilebilir (ör.
arkadaşlarının kendi hesaplarından, kendi rızalarıyla verdikleri). Her sorguda önce hiç ölçülmemiş,
sonra kalan kredisi en çok olan anahtar kullanılır; böylece yük anahtarlara yayılır. Kredisi biten
ya da geçersiz anahtar (HTTP 401) ve istek sınırına takılan anahtar (429) o çalışmada atlanır,
sorgu sıradakiyle tekrarlanır. Kalan kredi anahtar başına saklanır (anahtarın kendisi değil,
özetinden türetilen kimlikle); kredi planı toplamı tek kota gibi kullanır, ör. 3 ücretsiz anahtar =
ayda 1.500 kredi. Ayarlar'da anahtar başına kalan kredi, "Kaynakları test et" çıktısında anahtar
durumu görünür (yalnızca son 4 karakter). Son kota yenilenmesinden (Ayarlar → yenilenme günü)
eski ölçümler atılır ve anahtar yeniden ölçülür. Anahtarlar yalnızca telefonda saklanır. Sağlayıcı
kötüye kullanım şüphesinde anahtarları iptal edebilir; bu durumda o anahtar atlanır, diğerleriyle
devam edilir.

**Kredi bolsa genişleme (1.9.3):** Daraltma gerekmiyor ve günlük kredi (kalan / yenilenmeye
kalan gün) 40'ın üstündeyse, seçmediğin bilinen ligler de o gün taranır. Önce hepsinin bugünkü
maç sayısı ücretsiz listeden öğrenilir; yalnızca bugün maçı olanlar, geçmiş taramalarda en çok
değerli seçim çıkaran önce, tahmini gider günlük bütçenin %85'ini geçmeyecek kadar eklenir
(kalan pay elle tarama, kontrol ve tahmin hatası için). Ayarlar → Kredi planı'nda "Kredi bol, ek
ligler" satırında görünür; "Kredi bolsa ek lig tara" ile kapatılır. Tek ücretsiz anahtarda hiçbir
şey eklenmez.

Bir çalıştırmanın maliyeti: maçı olan her lig 1 kredi (2,5 Alt/Üst açıkken 2; Maç Sonucu ve
Alt/Üst aynı sorguda gelir), sorulan her Karşılıklı Gol maçı 1 kredi. Maçı olmayan lig ve maç
listesi sorguları ücretsizdir. Ör. 3 aktif lig + 4 KG maçı = 3×2 + 4 = 10 kredi. 1.8.9'dan beri
"Güncel oranlarla yeni kupon" ve elle radar taraması sonucu harcanan ve kalan krediyi yazar.

## Ana paraya göre oyun planı, otomatik kasa, kredi planı (1.4)

- **Bugünün oyun planı** (Bugün sekmesinin en üstü): bugünkü her kupon için ne kadar
  oynanacağı (TL), bunun ana paranın yüzde kaçı olduğu ve tutarsa ne döneceği; altta toplam.
  Ana para = kasadaki para + oyundaki kuponlar. Tutar sabah sabitlenmez: kuponun Kelly payı
  saklanır ve **güncel kasadan** yeniden hesaplanır (dün kazandıysan bugünkü tutar büyür,
  kaybettiysen küçülür). Güncel kontrol varsa onun oranı ve tutarı gösterilir; avantajı kaybolan
  ya da başlamış kupona "–" yazılır.
- **Otomatik kasa takibi** (Ayarlar → Kasa takibi, varsayılan açık): ilk maçtan 90 dk önceki
  kontrolde güncel iddaa ve Pinnacle oranlarıyla hâlâ avantajlı çıkan kupon, **o anki oranlar ve
  o anki kasaya göre tutarla** oynanmış sayılır ve bildirim gelir ("Kupon #12 oyna: 180,00 TL ·
  oran 2,90"). Maç bitince kupon bu oranlarla sonuçlandırılır, kasa kendiliğinden güncellenir.
  Avantajı kaybolan kupon oynanmış sayılmaz. Oynamadıysan kupondaki **Oynamadım, geri al**
  tutarı kasaya iade eder (sonuçlanmadan önce). Kapatırsan yalnızca "Oynadım" dediğin kuponlar
  kasadan düşer.
- **Kredi planı** (Ayarlar → Kredi planı, varsayılan açık): The Odds API kredisi yenilenme
  gününe kadar yetsin diye günlük bütçe = (kalan kredi − 15 yedek) / kalan gün. Tahmini gider
  bütçeyi aşarsa kredi başına en az fırsat getirenden başlayarak kısar: Karşılıklı Gol (12→8→4→0
  maç) → radar (4→2→1→0) → 2,5 Alt/Üst → en az değerli fırsat çıkaran lig (son taramalardaki
  değerli seçim sayısının hareketli ortalaması). Senin seçmediğin hiçbir şey eklenmez; plan her
  gün gerçek kalan krediden yeniden hesaplanır, daraltma olursa Bugün sekmesinde yazar.
  1.8.6'dan itibaren plan, o gün gerçekten oynayan ligleri ücretsiz maç listesinden önceden öğrenir
  ve kupon giderini son 14 günün ortalama kupon sayısından hesaplar; hafif günlerde Alt/Üst ve KG
  gereksiz yere kapanmaz, maçı olmayan lig "çıkarılmaz" (zaten kredi harcamaz).

## Daha çok fırsat: pazarlar, günde 3 kupon, veri doğrulama (1.3)

- **Günde en fazla 3 bağımsız kupon:** Motor, maç paylaşmayan en iyi kuponları seçer; tutarlar
  Kelly'ye göre, toplamı günlük üst sınırı (Temkinli %9, En yüksek getiri %20) aşarsa orantılı
  küçültülür. Demo simülasyonunda (200 ay) ortanca ayı Temkinli'de +%1,7 → +%2,3, En yüksek
  getiri'de +%3,2 → +%4,1 yükseltti; en kötü ay biraz derinleşti.
- **2,5 Alt/Üst** (Ayarlar → Pazarlar): Pinnacle "totals" ile karşılaştırılır; lig başına kredi ×2.
- **Karşılıklı Gol:** Pinnacle KG oranı maç bazında çekilir (maç başına 1 kredi), günde en fazla
  4/8/12 maç. Nesine'deki KG pazarının hangisi olduğunu bulmak için az maçta Pinnacle KG fiyatı
  varsa (1.8.8), Maç Sonucu ve 2,5 Alt/Üst'ten Poisson gol modeliyle hesaplanan KG olasılığı
  kullanılır ve eşleme hatırlanır. Model yalnızca eşleme içindir; bahis kararı her zaman
  Pinnacle'ın gerçek KG fiyatıyla verilir.
  1.8.9'dan beri aynı model kredi harcamadan **ön eleme** yapar: modelin KG olasılığı 8 puan
  iyimser alınsa bile iddaa'nın KG oranı en az %3 avantaj veremiyorsa o maç Pinnacle'a sorulmaz.
  Kalan maçlar en umutludan başlayarak sorulur. Payın dayanağı: bağımsız Poisson modelinin KG
  hatası, beraberlik düzeltmeli ve aşırı yayılımlı skor dağılımlarına karşı ortalama 2,8, en kötü
  6,8 puandı. iddaa'nın KG marjı ~%22 olduğundan maçların çoğu elenir. "Kaynakları test et"
  çıktısı kaç maçın elendiğini ve modelin sorulan maçlarda Pinnacle'dan gerçekte ne kadar
  saptığını gösterir.
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
- **Otomatik radar** (Ayarlar → Düşen oran radarı): günde 1, 2 ya da 4 tarama, maç saatlerine göre. Bugün oynanmış
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
./build.sh          # testler + build/BilincliKupon-2.7.0.apk
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
- **Kâr (ProfitTest):** ortak Kelly'nin optimallik (KKT) koşulları, sınırlar ve MBS, kanıt
  koruması, Çifte Şans eşlemesi ve sonuçlandırma, Pinnacle + borsa uzlaşısı, boş lig atlama,
  yenilenen planın oynanmaması, geç kurulan kuponun hemen kontrolü, maç saatine göre radar.
- **Doğruluk (AccuracyTest):** takım varyantları, tutarsız ve ters eşleşmeler, bayat çizgi, tahmin
  defterinin Brier/log kayıp/standart hata hesapları, demo isabet raporu.
- **Kasa ve kredi (BankrollTest):** güncel kasadan tutar, otomatik oynama ve geri alma,
  sonuçla kasanın güncellenmesi, kredi planının daraltma sırası ve yenilenme günü.
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
