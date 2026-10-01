# Bilinçli Kupon — Android

Masaüstü sürümüyle aynı mantığın telefona taşınmış hali. Bilgisayar açık olmasa da her sabah
06:00'da (Türkiye saati) bülteni tarar, kararı bildirim olarak gönderir, kasanı ve kuponlarını
telefonda tutar.

## Kurulum (telefon)

1. `BilincliKupon-1.9.4.apk` dosyasını telefona indir ve aç. Android "bilinmeyen kaynak" izni
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

**Birden fazla anahtar (1.9.1):** Ayarlar → Veri kaynağı'na en fazla 5 anahtar girilebilir (ör.
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
./build.sh          # testler + build/BilincliKupon-1.9.4.apk
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
