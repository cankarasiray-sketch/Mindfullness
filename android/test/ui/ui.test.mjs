// Arayüz (assets/index.html) için uçtan uca testler: sahte Android köprüsüyle jsdom'da çalışır.
// Kullanım: node ui.test.mjs <durum.json>   (durum, PreviewState ile Java çekirdeğinden üretilir)
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { JSDOM } from "jsdom";

const html = readFileSync(new URL("../../assets/index.html", import.meta.url), "utf8");
const baseState = JSON.parse(readFileSync(process.argv[2], "utf8"));
const clone = (o) => JSON.parse(JSON.stringify(o));

function boot(state, hash = "") {
  const calls = [];
  const errors = [];
  const dom = new JSDOM(html, {
    url: "file:///android_asset/index.html" + hash,
    runScripts: "dangerously",
    pretendToBeVisual: true,
    beforeParse(win) {
      win.scrollTo = () => {};
      win.addEventListener("error", (e) => errors.push(e.message));
      win.MockAndroid = {
        state: () => JSON.stringify(state),
        act: (action, payload, id) => {
          calls.push({ action, payload: JSON.parse(payload) });
          setTimeout(() => win.onActResult(id, { ok: true, message: "tamam" }), 0);
        },
      };
    },
  });
  const w = dom.window;
  const $ = (sel) => w.document.querySelector(sel);
  const $$ = (sel) => [...w.document.querySelectorAll(sel)];
  const button = (text) => {
    const b = $$("button").find((x) => x.textContent.trim() === text);
    assert.ok(b, `buton yok: ${text}`);
    return b;
  };
  const tick = () => new Promise((r) => setTimeout(r, 5));
  return { w, $, $$, button, calls, errors, tick, text: () => w.document.body.textContent };
}

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

test("günün kuponu ve özet görünür", async () => {
  const t = boot(clone(baseState));
  assert.match(t.text(), /Günün kuponu/);
  assert.match(t.$("#hdrBal").textContent, /TL$/);
  assert.equal(t.$$("nav button").length, 5);
  assert.deepEqual(t.errors, []);
});

test("takım adları HTML olarak çalıştırılmaz", async () => {
  const s = clone(baseState);
  const c = s.coupons.find((x) => x.id === s.todayRun.couponId);
  c.legs[0].home = '<img src=x onerror="window.pwned=1">';
  const t = boot(s);
  assert.equal(t.$$("main img").length, 0);
  assert.match(t.text(), /<img src=x/);
  assert.equal(t.w.pwned, undefined);
});

test("oynadım: tutar ve değişen oranlar köprüye gider", async () => {
  const s = clone(baseState);
  const c = s.coupons.find((x) => x.id === s.todayRun.couponId);
  c.played = false; c.stake = 0; c.suggestedStake = 15000; c.result = null;
  const t = boot(s);
  t.button("Oynadım (oranları ben gireyim)").click();
  assert.equal(t.$("#pAmount").value, "150");
  t.$("#pAmount").value = "200";
  t.$("#pOdds").value = "2,70";
  t.button("Kaydet").click();
  await t.tick();
  assert.deepEqual(t.calls.at(-1), { action: "played", payload: { coupon: c.id, amount: "200", odds: "2,70" } });
});

test("pas günü gerekçesiyle gösterilir", async () => {
  const s = clone(baseState);
  s.todayRun = { day: s.today, decision: "pas", reason: "48 seçim karşılaştırıldı, hiçbirinde avantaj yok. Bugün pas.", summary: "Bülten 32 maç", couponId: null };
  const t = boot(s);
  assert.match(t.text(), /Pas/);
  assert.match(t.text(), /hiçbirinde avantaj yok/);
});

test("ilk kurulum: anahtar ve kasa uyarıları, şimdi üret", async () => {
  const s = clone(baseState);
  Object.assign(s, { coupons: [], runs: [], todayRun: null, series: [], transactions: [], notifications: false, exactAlarm: false });
  s.stats = Object.fromEntries(Object.keys(s.stats).map((k) => [k, k === "roi" ? null : 0]));
  s.settings.oddsApiKey = "";
  const t = boot(s);
  assert.match(t.text(), /Kurulumu tamamla/);
  assert.match(t.text(), /Kasan boş/);
  assert.match(t.text(), /Bildirim izni kapalı/);
  assert.match(t.text(), /Tam saatli alarm/);
  t.button("Şimdi üret").click();
  await t.tick();
  assert.equal(t.calls.at(-1).action, "generate");
  t.w.show("gecmis");
  assert.match(t.text(), /Henüz kupon yok/);
  assert.match(t.text(), /Grafik ilk kupon/);
});

test("ayarlar: Türkçe ondalık ve yüzdeler doğru dönüştürülür", async () => {
  const t = boot(clone(baseState), "#ayarlar");
  t.$("#sKey0").value = "  abc123 ";
  t.$$(".lg").find((x) => x.value === "soccer_epl").checked = false;
  t.$("#f_minLegEv").value = "2,5";
  t.$("#f_maxLegOdds").value = "3,2";
  t.$("#f_minCouponAmount").value = "1.000";
  t.$("#sTime").value = "07:30";
  t.button("Ayarları kaydet").click();
  await t.tick();
  const st = t.calls.at(-1).payload.settings;
  assert.equal(t.calls.at(-1).action, "saveSettings");
  assert.equal(st.oddsApiKey, "abc123");
  assert.ok(!st.leagues.includes("soccer_epl"));
  assert.ok(Math.abs(st.minLegEv - 0.025) < 1e-12);
  assert.equal(st.maxLegOdds, 3.2);
  assert.equal(st.minCouponAmount, 1000);
  assert.equal(st.runHour, 7);
  assert.equal(st.runMinute, 30);
});

test("kasa: yatırma ve boş tutar uyarısı", async () => {
  const t = boot(clone(baseState), "#kasa");
  t.button("Yatır").click();
  assert.equal(t.calls.length, 0);
  assert.match(t.$("#toast").textContent, /Tutar gir/);
  t.$("#mAmount").value = "5.000";
  t.button("Yatır").click();
  await t.tick();
  assert.deepEqual(t.calls.at(-1), { action: "deposit", payload: { amount: "5.000" } });
});

test("geri tuşu: önce sayfayı kapatır, sonra ana sekmeye döner", async () => {
  const t = boot(clone(baseState), "#gecmis");
  t.w.detail(t.w.S.coupons[0].id);
  assert.equal(t.w.goBack(), true);
  assert.ok(!t.$("#sheetBg").classList.contains("show"));
  assert.equal(t.w.goBack(), true);
  assert.equal(t.w.tab, "bugun");
  assert.equal(t.w.goBack(), false);
});

test("demo modunda işlem butonları gizli", async () => {
  const s = clone(baseState);
  s.demo = true;
  s.demoSummary = { start: 1000000, end: 1100000, baselineEnd: 700000 };
  const c = s.coupons.find((x) => x.id === s.todayRun.couponId);
  c.played = false; c.result = null;
  const t = boot(s);
  assert.match(t.text(), /Demo modu/);
  assert.ok(!t.$$("button").some((b) => /Oynadım|kontrol et/.test(b.textContent)));
  assert.ok(!t.$$("button").some((b) => b.textContent.trim() === "Sonuç kontrolü"));
});

function unplayed(s) {
  const c = s.coupons.find((x) => x.id === s.todayRun.couponId);
  Object.assign(c, { played: false, stake: 0, suggestedStake: 15000, result: null, lastCheck: null });
  return c;
}
function checkFor(c, at, playable) {
  return {
    at, playable, odds: 2.9, prob: 0.38, ev: 0.10, stake: 18000,
    verdict: playable ? "Güncel oranlarla hâlâ avantajlı" : "1. maç: avantaj kayboldu. Bu kuponu oynama",
    legs: c.legs.map((l) => ({ position: l.position, status: playable ? "ok" : "edge_lost", oddsThen: l.odds,
      oddsNow: playable ? 2.9 : 2.3, fairNow: 0.38, evNow: playable ? 0.10 : -0.13 })),
  };
}
const minus = (iso, min) => new Date(Date.parse(iso) - min * 60000).toISOString();

test("bayat oran: önce kontrol et, kontrol isteği köprüye gider", async () => {
  const s = clone(baseState);
  const c = unplayed(s);
  c.createdAt = minus(s.now, 150);
  const t = boot(s);
  assert.match(t.text(), /oynamadan önce kontrol et/);
  t.button("Oynamadan önce kontrol et").click();
  await t.tick();
  assert.deepEqual(t.calls.at(-1), { action: "recheck", payload: { coupon: c.id } });
});

test("taze ve oynanabilir kontrol: güncel oranlarla oyna", async () => {
  const s = clone(baseState);
  const c = unplayed(s);
  c.lastCheck = checkFor(c, minus(s.now, 5), true);
  const t = boot(s);
  assert.match(t.text(), /OYNANABİLİR/);
  assert.match(t.text(), /Şimdi 2,90/);
  t.button("Güncel oranlarla oynadım").click();
  assert.equal(t.$("#pAmount").value, "180");
  assert.match(t.$("#sheet").textContent, /2,90/);
  t.button("Kaydet").click();
  await t.tick();
  assert.deepEqual(t.calls.at(-1), { action: "playChecked", payload: { coupon: c.id, amount: "180" } });
});

test("avantaj kaybolduysa: oynama ve yeni kupon öner", async () => {
  const s = clone(baseState);
  const c = unplayed(s);
  c.lastCheck = checkFor(c, minus(s.now, 5), false);
  const t = boot(s);
  assert.match(t.text(), /OYNAMA/);
  assert.match(t.text(), /avantaj kayboldu/);
  assert.ok(!t.$$("button").some((b) => b.textContent.trim() === "Güncel oranlarla oynadım"));
  t.$$("button").find((b) => b.textContent.trim() === "Güncel oranlarla yeni kupon").click();
  await t.tick();
  assert.deepEqual(t.calls.at(-1), { action: "generate", payload: { force: true } });
});

test("eski kontrol yeniden kontrol ister", async () => {
  const s = clone(baseState);
  const c = unplayed(s);
  c.lastCheck = checkFor(c, minus(s.now, 120), true);
  const t = boot(s);
  assert.match(t.text(), /KONTROL ESKİ/);
  assert.ok(!t.$$("button").some((b) => b.textContent.trim() === "Güncel oranlarla oynadım"));
  t.button("Tekrar kontrol et").click();
  await t.tick();
  assert.equal(t.calls.at(-1).action, "recheck");
});

test("aylık beklenti ve CLV uyarısı", async () => {
  const s = clone(baseState);
  s.outlook = { month: "2026-10", played: 4, settled: 3, won: 1, realized: -12000, expected: 3400, openStake: 10000, p10: -22000, p50: -22000, p90: 9000 };
  s.clv = { played: -0.031, playedN: 42, all: -0.02, allN: 60 };
  const t = boot(s);
  assert.match(t.text(), /BU AY · 4 kupon/);
  assert.match(t.text(), /Kötü \(%10\)-220,00 TL/);
  assert.match(t.text(), /Avantaj görünmüyor/);
  t.w.show("gecmis");
  assert.match(t.text(), /Kapanış oranı testi/);
  assert.match(t.text(), /−%3,1 \(42 maç\)/);
});

test("risk profili seçimi", async () => {
  const t = boot(clone(baseState), "#ayarlar");
  assert.match(t.text(), /Temkinli/);
  assert.match(t.text(), /En yüksek getiri/);
  t.button("Bu profili kullan").click();
  await t.tick();
  assert.deepEqual(t.calls.at(-1), { action: "profile", payload: { name: "yuksek" } });
});

test("fırsatlar: değer listesi, düşen oranlar ve elle tarama", async () => {
  const s = clone(baseState);
  assert.ok(s.radar.values.length > 0, "demo durumunda değer listesi olmalı");
  s.radar.moves = [{ key: "s1:1", home: "Ev <b>", away: "Dep", kickoff: s.now, outcome: "1", from: 0.48, to: 0.55,
    since: s.now, iddaa: 2.0, ev: 0.1 }];
  const t = boot(s, "#firsat");
  assert.match(t.text(), /Değerli oranlar/);
  assert.match(t.text(), /Pinnacle 2,08 → 1,82/);
  assert.match(t.text(), /\+7,0 puan/);
  assert.match(t.text(), /Ev <b>/);
  assert.equal(t.$$("main b").filter((b) => b.textContent === "Ev ").length, 0);
  t.button("Şimdi tara").click();
  await t.tick();
  assert.equal(t.calls.at(-1).action, "radarScan");
});

test("düşen oranlarda yalnızca fırsatlar; gizlenenler sayılır", async () => {
  const s = clone(baseState);
  s.settings.minLegEv = 0.03;
  const base = { kickoff: s.now, since: s.now, outcome: "1", from: 0.56, to: 0.61 };
  s.radar.moves = [
    Object.assign({ key: "a:1", home: "England", away: "Czech Republic" }, base),                          // iddaa'da yok
    Object.assign({ key: "b:1", home: "France", away: "Belgium", iddaa: 1.38, ev: -0.161 }, base),        // avantajsız
    Object.assign({ key: "c:1", home: "Spain", away: "Italy", iddaa: 1.70, ev: 0.012 }, base),            // eşiğin altında
    Object.assign({ key: "d:1", home: "Portugal", away: "Wales", iddaa: 1.80, ev: 0.098 }, base),         // fırsat
  ];
  const t = boot(s, "#firsat");
  const card = t.$$(".card").find((x) => x.querySelector("h2") && x.querySelector("h2").textContent === "Düşen oranlar (Pinnacle)");
  assert.match(card.textContent, /Portugal – Wales/);
  assert.match(card.textContent, /iddaa 1,80 \+%9,8/);
  assert.doesNotMatch(card.textContent, /England|France|Spain/);
  assert.match(card.textContent, /3 hareket gizlendi/);
  s.radar.moves = s.radar.moves.slice(0, 2);
  const t2 = boot(s, "#firsat");
  const card2 = t2.$$(".card").find((x) => x.querySelector("h2") && x.querySelector("h2").textContent === "Düşen oranlar (Pinnacle)");
  assert.match(card2.textContent, /Şu an avantajlı düşen oran yok/);
});

test("ROI analizi grafikleri ve düşüş", async () => {
  const t = boot(clone(baseState), "#gecmis");
  assert.match(t.text(), /ROI analizi/);
  assert.match(t.text(), /MAÇ SAYISINA GÖRE/);
  assert.match(t.text(), /En büyük düşüş/);
  assert.ok(t.$$(".bars .fill").length > 0);
});

test("radar sıklığı ayarı ve kredi tahmini", async () => {
  const s = clone(baseState);
  s.settings.estimatedCredits = 780;
  const t = boot(s, "#ayarlar");
  assert.match(t.text(), /Ücretsiz planı aşar/);
  t.$("#sRadar").value = "2";
  t.button("Ayarları kaydet").click();
  await t.tick();
  assert.equal(t.calls.at(-1).payload.settings.radarScans, 2);
});

test("günde birden fazla kupon gösterilir", async () => {
  const s = clone(baseState);
  const ids = s.coupons.slice(0, 3).map((c) => c.id);
  s.coupons.slice(0, 3).forEach((c) => Object.assign(c, { result: null, played: true }));
  s.todayRun = { day: s.today, decision: "kupon", reason: "", summary: "özet", couponId: ids[0], couponIds: ids };
  const t = boot(s);
  assert.match(t.text(), /Günün kuponu 1\/3/);
  assert.match(t.text(), /Günün kuponu 3\/3/);
  assert.equal(t.text().match(/Açık kupon · #/g), null); // günün kuponları ayrıca "açık" diye tekrar edilmez
});

test("pazarlar, KG ve günlük kupon ayarları kaydedilir; kredi uyarısı", async () => {
  const s = clone(baseState);
  s.credits = { remaining: 25, at: s.now };
  const t = boot(s, "#ayarlar");
  assert.match(t.text(), /Kalan API kredisi: 25/);
  t.$("#sTotals").checked = true;
  t.$("#sKg").value = "8";
  t.$("#f_maxCouponsPerDay").value = "2";
  t.$("#f_maxDailyExposure").value = "12";
  t.button("Ayarları kaydet").click();
  await t.tick();
  const st = t.calls.at(-1).payload.settings;
  assert.equal(st.totals, true);
  assert.equal(st.kgEvents, 8);
  assert.equal(st.maxCouponsPerDay, 2);
  assert.ok(Math.abs(st.maxDailyExposure - 0.12) < 1e-12);
  t.w.show("bugun");
  assert.match(t.text(), /API kredisi az: 25/);
});

test("KG ve Alt/Üst etiketleri", async () => {
  const s = clone(baseState);
  const c = s.coupons.find((x) => x.id === s.todayRun.couponId);
  c.legs[0].market = "KG"; c.legs[0].outcome = "VAR";
  const t = boot(s);
  assert.match(t.text(), /KG Var/);
});

test("oyun planı: hangi kupona kasanın yüzde kaçı, tutarsa ne döner", async () => {
  const s = clone(baseState);
  const c = unplayed(s);
  c.stakeNow = 25000; // güncel kasadan hesaplanan tutar, sabahki öneriden önce gelir
  const auto = s.coupons.find((x) => x.id !== c.id);
  Object.assign(auto, { played: true, autoPlayed: true, stake: 15000, result: null, totalOdds: 2.26 });
  const lost = s.coupons.find((x) => x.id !== c.id && x.id !== auto.id);
  Object.assign(lost, { played: false, stake: 0, result: null, lastCheck: null });
  lost.legs.forEach((l) => (l.kickoff = new Date(Date.parse(s.now) + 7200000).toISOString()));
  lost.lastCheck = checkFor(lost, minus(s.now, 5), false);
  s.todayRun.couponIds = [c.id, auto.id, lost.id];
  s.stats.balance = 985000; s.stats.openStake = 15000; // kasa 10.000 TL
  const t = boot(s);
  const plan = t.$(".plan").textContent;
  assert.match(plan, /BUGÜNÜN OYUN PLANI · ANA PARA 10\.000,00 TL/);
  assert.match(plan, /250,00 TL%2,5 ana paratutarsa 695,00 TL/);
  assert.match(plan, /oynandı \(otomatik\)/);
  assert.match(plan, /150,00 TL%1,5 ana paratutarsa 339,00 TL/);
  assert.match(plan, /oynama/);
  assert.match(plan, /Toplam bahis400,00 TL \(%4,0\)/);
  assert.match(plan, /Kasada 9\.850,00 TL \+ oyunda 150,00 TL/);
  assert.match(plan, /Otomatik takip açık/);
  assert.match(t.text(), /Oynandı \(otomatik\)/);
});

test("güncel kontrol tutarı planda kullanılır; elle takipte açıklama değişir", async () => {
  const s = clone(baseState);
  const c = unplayed(s);
  c.stakeNow = 25000;
  c.lastCheck = checkFor(c, minus(s.now, 5), true);
  s.settings.autoTrack = false;
  const t = boot(s);
  const plan = t.$(".plan").textContent;
  assert.match(plan, /oran 2,90oyna · güncel/);
  assert.match(plan, /180,00 TL/);
  assert.match(plan, /Elle takip/);
});

test("otomatik oynanan kupon geri alınabilir", async () => {
  const s = clone(baseState);
  const c = s.coupons.find((x) => x.id === s.todayRun.couponId);
  Object.assign(c, { played: true, autoPlayed: true, result: null });
  const t = boot(s);
  t.button("Oynamadım, geri al").click();
  await t.tick();
  assert.deepEqual(t.calls.at(-1), { action: "unplay", payload: { coupon: c.id } });
  // sonuçlanmış kupon geri alınamaz
  Object.assign(c, { result: "kaybetti" });
  const t2 = boot(s);
  assert.ok(!t2.$$("button").some((b) => b.textContent.trim() === "Oynamadım, geri al"));
});

test("kasa takibi ve kredi planı ayarları; daraltma uyarısı", async () => {
  const s = clone(baseState);
  s.settings.oddsApiKey = "k";
  s.creditPlan = {
    leagues: ["soccer_turkey_super_league"], totals: false, kgEvents: 0, radarScans: 1, daysLeft: 20,
    budget: 9.2, cost: 9, remaining: 199, quota: 500, coupons: 3, narrowed: true,
    notes: ["Karşılıklı Gol maç sayısı 0'e indirildi", "2,5 Alt/Üst bugünlük kapatıldı"],
  };
  const t = boot(s);
  assert.match(t.text(), /Kredi planı bugün kapsamı daralttı/);
  assert.match(t.text(), /2,5 Alt\/Üst bugünlük kapatıldı/);
  t.w.show("ayarlar");
  assert.match(t.text(), /Kalan kredi199 \/ ayda 500/);
  assert.match(t.text(), /Günlük kuponen fazla 3/);
  assert.match(t.text(), /Yenilenmeye20 gün/);
  assert.match(t.text(), /Bugün taranan liglerTürkiye Süper Lig/);
  assert.match(t.text(), /kapalı · kapalı · günde 1/);
  t.$("#sAuto").checked = false;
  t.$("#sCreditAuto").checked = false;
  t.$("#sResetDay").value = "15";
  t.button("Ayarları kaydet").click();
  await t.tick();
  const st = t.calls.at(-1).payload.settings;
  assert.equal(st.autoTrack, false);
  assert.equal(st.creditAuto, false);
  assert.equal(st.creditResetDay, 15);
});

test("kanıt koruması: geçmişte ölçüm, devredeyse uyarı, ayardan kapatılır", async () => {
  const s = clone(baseState);
  s.edge = {
    "*": { n: 120, predicted: 0.07, realized: 0.01, ratio: 0.3, factor: 0.6 },
    MS: { n: 90, predicted: 0.07, realized: 0.02, ratio: 0.35, factor: 0.7 },
    KG: { n: 30, predicted: 0.08, realized: -0.01, ratio: 0.2, factor: 0.4 },
  };
  const t = boot(s);
  assert.ok(t.$$(".banner").some((x) => /Kanıt koruması devrede\. Son 120 seçimde/.test(x.textContent)));
  t.w.show("gecmis");
  assert.match(t.text(), /Avantaj: öngörülen → kapanışta\+%7,0 → \+%1,0/);
  assert.match(t.text(), /Karşılıklı Gol30 maç · ×0,40/);
  assert.match(t.text(), /Devrede: avantajlar ×0,60/);
  t.w.show("ayarlar");
  assert.equal(t.$("#sEdge").checked, true);
  t.$("#sEdge").checked = false;
  t.button("Ayarları kaydet").click();
  await t.tick();
  assert.equal(t.calls.at(-1).payload.settings.edgeGuard, false);
  // sağlam ölçüm: uyarı yok
  const s2 = clone(baseState);
  s2.edge = { "*": { n: 60, predicted: 0.06, realized: 0.05, ratio: 0.9, factor: 1 } };
  const t2 = boot(s2);
  assert.ok(!t2.$$(".banner").some((x) => /Kanıt koruması devrede/.test(x.textContent)));
  t2.w.show("gecmis");
  assert.match(t2.text(), /Devrede değil/);
});

test("Çifte Şans etiketi", async () => {
  const s = clone(baseState);
  const c = s.coupons.find((x) => x.id === s.todayRun.couponId);
  c.legs[0].market = "CS"; c.legs[0].outcome = "X2";
  const t = boot(s);
  assert.match(t.text(), /ÇŞ X-2/);
});

test("basketbol: kupon ve düşen oran etiketi, ayrı lig kartı, kaydedilir", async () => {
  const s = clone(baseState);
  const c = s.coupons.find((x) => x.id === s.todayRun.couponId);
  c.legs[0].market = "BS"; c.legs[0].outcome = "2";
  s.radar = s.radar || { values: [], moves: [] };
  s.radar.moves = [{ key: "e:1", home: "Fenerbahçe Beko", away: "Real Madrid", kickoff: s.now, outcome: "1", market: "BS", from: 0.5, to: 0.56, since: s.now, iddaa: 1.95, ev: 0.092 }];
  const t = boot(s);
  assert.match(t.text(), /Basket MS 2/);
  t.w.show("firsat");
  assert.match(t.text(), /Basket MS 1 Pinnacle/);
  t.w.show("ayarlar");
  const card = t.$("#basketCard");
  assert.match(card.textContent, /EuroLeague \(basketbol\)/);
  assert.match(card.textContent, /NBA \(basketbol\)/);
  const ligler = t.$$(".card").find((x) => x.querySelector("h2") && x.querySelector("h2").textContent === "Ligler");
  assert.doesNotMatch(ligler.textContent, /basketbol/);
  const nba = card.querySelector('input[value="basketball_nba"]');
  nba.checked = !nba.checked;
  const wanted = nba.checked;
  t.button("Ayarları kaydet").click();
  await t.tick();
  const st = t.calls.at(-1).payload.settings;
  assert.equal(st.leagues.includes("basketball_nba"), wanted);
});

test("birden fazla API anahtarı: alanlar, kayıt ve anahtar başına kredi", async () => {
  const s = clone(baseState);
  s.settings.oddsApiKey = "anahtar-bir-1111\nanahtar-iki-2222";
  s.credits = { remaining: 780, at: s.now, keys: [
    { label: "…1111", remaining: 480, at: s.now }, { label: "…2222", remaining: 300, at: s.now }, { label: "…3333", remaining: null, at: null }] };
  const t = boot(s, "#ayarlar");
  assert.equal(t.$("#sKey0").value, "anahtar-bir-1111");
  assert.equal(t.$("#sKey1").value, "anahtar-iki-2222");
  assert.equal(t.$$(".apikey").length, 10);
  assert.match(t.text(), /Kalan API kredisi: 780/);
  assert.match(t.text(), /…1111: 480 · …2222: 300 · …3333: henüz kullanılmadı/);
  t.$("#sKey2").value = " anahtar-uc-3333 ";
  t.$("#sKey3").value = "anahtar-bir-1111"; // tekrar: bir kez kaydedilir
  t.button("Ayarları kaydet").click();
  await t.tick();
  assert.equal(t.calls.at(-1).payload.settings.oddsApiKey, "anahtar-bir-1111\nanahtar-iki-2222\nanahtar-uc-3333");
  t.button("Göster").click();
  assert.ok(t.$$(".apikey").every((x) => x.type === "text"));
});

test("kredi bol: ek ligler ayrı satırda, genişletme ayarı kaydedilir", async () => {
  const s = clone(baseState);
  s.settings.oddsApiKey = "k";
  s.settings.creditExpand = true;
  s.creditPlan = {
    leagues: ["soccer_epl", "soccer_efl_champ", "soccer_greece_super_league"], expanded: ["soccer_efl_champ", "soccer_greece_super_league"],
    totals: true, kgEvents: 12, radarScans: 4, daysLeft: 31, budget: 78.7, cost: 57, remaining: 2455, quota: 2500, coupons: 5,
    narrowed: false, notes: ["Kredi bol: 2 ek lig tarandı"],
  };
  const t = boot(s);
  assert.doesNotMatch(t.$$(".banner").map((b) => b.textContent).join(" "), /daralttı/);
  t.w.show("ayarlar");
  assert.match(t.text(), /Bugün taranan liglerİngiltere Premier Lig(?!,)/);
  assert.match(t.text(), /Kredi bol, ek liglerİngiltere Championship, Yunanistan Süper Lig/);
  assert.match(t.text(), /Kredi bol: 2 ek lig tarandı/);
  assert.equal(t.$("#sCreditExpand").checked, true);
  t.$("#sCreditExpand").checked = false;
  t.button("Ayarları kaydet").click();
  await t.tick();
  assert.equal(t.calls.at(-1).payload.settings.creditExpand, false);
});

test("bugünkü maçlar bilinmiyorsa açıklama ve ücretsiz sorgu düğmesi", async () => {
  const s = clone(baseState);
  s.settings.oddsApiKey = "k";
  s.creditPlan = {
    leagues: ["soccer_epl"], expanded: [], activeKnown: false, totals: true, kgEvents: 12, radarScans: 4, daysLeft: 31,
    budget: 78.7, cost: 73, remaining: 2455, quota: 2500, coupons: 5, narrowed: false, notes: [],
  };
  const t = boot(s, "#ayarlar");
  assert.match(t.text(), /henüz sorulmadı/);
  t.button("Bugünün maçlarını sor (ücretsiz)").click();
  await t.tick();
  assert.equal(t.calls.at(-1).action, "probe");
  s.creditPlan.activeKnown = true;
  const t2 = boot(s, "#ayarlar");
  const card = t2.$$(".card").find((x) => x.querySelector("h2") && x.querySelector("h2").textContent === "Kredi planı");
  assert.doesNotMatch(card.textContent, /henüz sorulmadı/);
  assert.ok(!t2.$$("button").some((b) => b.textContent.includes("Bugünün maçlarını sor")));
});

test("kadro saati taraması ve gece sessizliği ayarları kaydedilir", async () => {
  const s = clone(baseState);
  s.settings.lineupScans = true;
  s.settings.quietNights = true;
  const t = boot(s, "#ayarlar");
  assert.equal(t.$("#sLineup").checked, true);
  assert.equal(t.$("#sQuiet").checked, true);
  assert.match(t.text(), /Kadro saati taraması/);
  t.$("#sLineup").checked = false;
  t.$("#sQuiet").checked = false;
  t.button("Ayarları kaydet").click();
  await t.tick();
  const st = t.calls.at(-1).payload.settings;
  assert.equal(st.lineupScans, false);
  assert.equal(st.quietNights, false);
  s.creditPlan = { leagues: ["soccer_epl"], expanded: [], activeKnown: true, totals: true, kgEvents: 0, radarScans: 2, lineupScans: true,
    daysLeft: 20, budget: 50, cost: 20, remaining: 1000, quota: 3500, coupons: 5, narrowed: false, notes: [] };
  const t2 = boot(s, "#ayarlar");
  assert.match(t2.text(), /günde 2 \+ kadro saati/);
});

test("pas kartında en yakın seçim ve tutarı ayrı satırda", async () => {
  const s = clone(baseState);
  s.todayRun = { day: s.today, decision: "pas", reason: "69 seçim karşılaştırıldı. Bugün pas.", couponId: null,
    summary: "Bülten 650 maç · eşleşen 30\nEn yakın seçim: 03.10 20:00 Ev – Dep · MS 1 @ 1,95 (adil 2,00, −%2,5)"
      + "\nÖnerilen tutar: 0 TL — avantaj yok, oynanmaz (yine de 100 TL oynanırsa beklenen kayıp ≈ 2,50 TL). Oran 2,06 ya da üstüne çıkarsa önerilen 30,00 TL (kasa payı %0,7)." };
  const t = boot(s);
  const near = t.$$("p").find((p) => p.textContent.startsWith("En yakın seçim:"));
  assert.ok(near, "en yakın seçim satırı yok");
  assert.match(near.textContent, /Ev – Dep · MS 1 @ 1,95 \(adil 2,00, −%2,5\)/);
  assert.ok(near.querySelector("b"));
  assert.ok(t.$$("p.muted").some((p) => p.textContent === "Bülten 650 maç · eşleşen 30"));
  const stake = t.$(".near-stake");
  assert.ok(stake, "tutar satırı yok");
  assert.equal(stake.querySelector("b").textContent, "Önerilen tutar:");
  assert.match(stake.textContent, /0 TL — avantaj yok, oynanmaz .* Oran 2,06 ya da üstüne çıkarsa önerilen 30,00 TL/);
  // Zirve Oran'daki en yakın seçim de karşılaştırılır
  const zn = { id: "1:1:2", kickoff: new Date(Date.parse(s.now) + 7200000).toISOString(), ev: -0.071,
    text: "Hırvatistan – İngiltere · MS 2 @ 1,74 (adil 1,87, −%7,1)", stake: "0 TL — avantaj yok, oynanmaz. Oran 1,93 ya da üstüne çıkarsa önerilen 40,00 TL (kasa payı %0,9)." };
  s.settings.zirve = true;
  s.zirve = { at: s.now, play: 0, rows: [], nearest: zn };
  // normal oranlardaki daha yakın (−%2,5): Zirve ikinci satırda
  const t2 = boot(s);
  assert.match(t2.text(), /En yakın seçim: 03.10 20:00 Ev – Dep/);
  assert.match(t2.$(".zirve-pass").textContent, /Zirve Oran'da en yakın: Hırvatistan – İngiltere · MS 2 @ 1,74 \(adil 1,87, −%7,1\) · Önerilen tutar: 0 TL/);
  // Zirve daha yakınsa öne geçer, normal oranlardaki ikinci sıraya iner
  s.todayRun.summary = s.todayRun.summary.replace("MS 1 @ 1,95 (adil 2,00, −%2,5)", "MS 1 @ 1,99 (adil 2,25, −%11,7)");
  const t3 = boot(s);
  const firstNear = t3.$$("p").find((p) => /^En yakın seçim/.test(p.textContent));
  assert.match(firstNear.textContent, /^En yakın seçim \(Zirve Oran\): Hırvatistan – İngiltere · MS 2 @ 1,74/);
  assert.match(t3.text(), /Normal oranlarda en yakın: 03.10 20:00 Ev – Dep · MS 1 @ 1,99 \(adil 2,25, −%11,7\)/);
  assert.equal(t3.$$(".near-stake").length, 1); // kalın tutar satırı Zirve'nin; normal olanın tutarı sönük
  assert.match(t3.$(".near-stake").textContent, /Oran 1,93 ya da üstüne çıkarsa önerilen 40,00 TL/);
  // başlamış Zirve maçı ya da kapalı ayar: karşılaştırılmaz
  s.zirve.nearest.kickoff = new Date(Date.parse(s.now) - 60000).toISOString();
  assert.equal(boot(s).$(".zirve-pass"), null);
  s.zirve.nearest.kickoff = zn.kickoff;
  s.settings.zirve = false;
  assert.equal(boot(s).$(".zirve-pass"), null);
  assert.ok(Math.abs(t3.w.nearEv("x (adil 2,25, −%11,7)") + 0.117) < 1e-9);
  assert.ok(Math.abs(t3.w.nearEv("x (adil 2,25, +%3,0)") - 0.03) < 1e-9);
  assert.equal(t3.w.nearEv("bozuk"), null);
});

test("promosyon kontrolü: adil oranlar kendiliğinden listelenir, karar oran yazılınca görünür", async () => {
  const s = clone(baseState);
  s.radar = s.radar || { values: [], moves: [] };
  s.radar.fairsAt = s.now;
  const soon = new Date(Date.parse(s.now) + 7200000).toISOString(), past = new Date(Date.parse(s.now) - 600000).toISOString();
  s.radar.fairs = [
    { ref: "b8", sref: "s8", sport: "lig", code: "1", home: "Başlamış", away: "Maç", kickoff: past, league: "Lig", sel: [{ m: "MS", o: "1", label: "MS 1", p: 0.5, i: 1.9 }] },
    { ref: "b9", sref: "s9", sport: "lig", code: "123", home: "Fenerbahçe", away: "Galatasaray", kickoff: soon, league: "Süper Lig",
      sel: [{ m: "MS", o: "1", label: "MS 1", p: 0.5, i: 1.9 }, { m: "MS", o: "2", label: "MS 2", p: 0.25, i: 3.6 }] },
    { ref: "b7", sref: "s7", sport: "lig", code: "7", home: "Arsenal", away: "Chelsea", kickoff: soon, league: "Premier Lig", sel: [{ m: "MS", o: "X", label: "MS X", p: 0.27, i: 3.3 }] },
  ];
  Object.assign(s.settings, { minLegEv: 0.03, kellyMultiplier: 0.25, maxStakeFraction: 0.03, edgeGuard: true });
  s.edge = { "*": { n: 0, factor: 1 } };
  s.stats.balance = 500000;
  const t = boot(s, "#firsat");
  // aramadan: yaklaşan maçlar (başlamış maç yok)
  const list = t.$("#promoList").textContent;
  assert.match(list, /Fenerbahçe – Galatasaray/);
  assert.match(list, /Arsenal – Chelsea/);
  assert.doesNotMatch(list, /Başlamış/);
  assert.match(list, /MS 1 adil 2,00 · iddaa 1,90/);
  // süzme
  const q = t.$("#promoQ");
  q.value = "GALATA";
  q.dispatchEvent(new t.w.Event("input"));
  assert.doesNotMatch(t.$("#promoList").textContent, /Arsenal/);
  t.$$(".promo-sel")[0].click();
  assert.match(t.$("#sheet").textContent, /iddaa'nın normal oranı 1,90/);
  assert.ok(!t.$$("#sheet button").some((b) => b.textContent === "Kontrol et")); // düğmesiz
  const o = t.$("#promoOdds");
  o.value = "2,02";
  o.dispatchEvent(new t.w.Event("input"));
  assert.match(t.$("#promoRes").textContent, /Oynama/);
  assert.equal(t.$("#promoAmt"), null);
  o.value = "2,40";
  o.dispatchEvent(new t.w.Event("input"));
  const res = t.$("#promoRes").textContent;
  assert.match(res, /avantaj \+%20,0/); // Java Promo.evaluate ile aynı: +%20, 150 TL
  assert.match(res, /Oyna: önerilen tutar 150,00 TL \(kasanın %3,0'ü\)/);
  assert.equal(t.$("#promoAmt").value, "150");
  assert.equal(t.calls.length, 0); // değerlendirme için köprü çağrısı yok
  t.button("Bu tutarla oynadım").click();
  await t.tick();
  assert.deepEqual(t.calls.at(-1), { action: "promoPlay", payload: { ref: "b9", m: "MS", o: "1", odds: "2,40", amount: "150" } });
  // kanıt koruması küçültüyorsa avantaj da küçülür
  s.edge = { "*": { n: 40, factor: 0.5 } };
  const t2 = boot(s, "#firsat");
  t2.$$(".promo-sel")[0].click();
  t2.$("#promoOdds").value = "2,40";
  t2.$("#promoOdds").dispatchEvent(new t2.w.Event("input"));
  assert.doesNotMatch(t2.$("#promoRes").textContent, /\+%20,0/);
});

test("yenilenmiş plandan kalan kupon açık kupon sayılmaz", async () => {
  const s = clone(baseState);
  const old = s.coupons.find((x) => x.id !== s.todayRun.couponId);
  Object.assign(old, { played: false, stake: 0, result: null, superseded: true });
  old.legs.forEach((l) => (l.kickoff = new Date(Date.parse(s.now) + 7200000).toISOString()));
  const t = boot(s);
  assert.equal(t.$$(".card").filter((x) => /Açık kupon · #/.test(x.textContent)).length, 0);
  t.w.show("gecmis");
  assert.ok(t.$$(".item").some((x) => x.textContent.includes("#" + old.id) && x.textContent.includes("Yenilendi")));
});

test("tahmin isabeti: ölçüler, anlamlılık ve kalibrasyon", async () => {
  const s = clone(baseState);
  s.accuracy = {
    n: 640, pending: 41, diff: 0.012, diffSe: 0.004,
    model: { brier: 0.581, logLoss: 0.972 }, first: { brier: 0.586, logLoss: 0.981 }, book: { brier: 0.589, logLoss: 0.984 },
    bins: [{ from: 0.2, to: 0.3, predicted: 0.254, actual: 0.262, n: 512 }, { from: 0.9, to: 1, predicted: 0.91, actual: 1, n: 3 }],
  };
  const t = boot(s);
  t.w.show("gecmis");
  const card = t.$$(".card").find((x) => /Tahmin isabeti/.test(x.textContent)).textContent;
  assert.match(card, /Değerlendirilen maç640 · bekleyen 41/);
  assert.match(card, /Uygulama \(maç öncesi son\)log kayıp 0,972 · Brier 0,581/);
  assert.match(card, /anlamlı ölçüde isabetli \(fark 0,012 ± 0,004\)/);
  assert.match(card, /%20–30tahmin %25,4 → gerçekleşen %26,2 \(512\)/);
  assert.doesNotMatch(card, /%90–100/); // az örnekli dilim gösterilmez
  s.accuracy = { n: 60, pending: 5, diff: -0.01, diffSe: 0.02, model: { brier: 0.6, logLoss: 1 }, first: { brier: 0.6, logLoss: 1 }, book: { brier: 0.6, logLoss: 1 }, bins: [] };
  const t2 = boot(s);
  t2.w.show("gecmis");
  assert.ok(t2.$$(".card").some((x) => /en az 100 maç gerekir/.test(x.textContent)));
  s.accuracy = { n: 0, pending: 12 };
  const t3 = boot(s);
  t3.w.show("gecmis");
  assert.ok(t3.$$(".card").some((x) => /Henüz sonuçlanmış maç yok \(12 maç bekliyor\)/.test(x.textContent)));
});

test("uzun işlemde ilerleme metni görünür, bitince temizlenir", async () => {
  const t = boot(clone(baseState), "#ayarlar");
  t.w.act("check");
  assert.match(t.$("#busy").className, /show/);
  t.w.onProgress("Pinnacle oranları 3/11: UEFA Nations League");
  assert.equal(t.$("#progress").textContent, "Pinnacle oranları 3/11: UEFA Nations League");
  await t.tick();
  assert.doesNotMatch(t.$("#busy").className, /show/);
  assert.equal(t.$("#progress").textContent, "");
});

test("uzun sonuç balonu kaplamaz, ayrıntı kutuda; dokununca kapanır", async () => {
  const s = clone(baseState);
  const t = boot(s, "#ayarlar");
  const long = "iddaa bülteni (Nesine): 639 maç\n" + "MTID 805: 6 maç, seçenek [10, 12]\n".repeat(40);
  t.w.MockAndroid.act = (action, payload, id) => setTimeout(() => t.w.onActResult(id, { ok: true, message: action === "check" ? long : "tamam" }), 0);
  t.w.checkSources();
  await t.tick();
  await t.tick();
  const toast = t.$("#toast");
  assert.equal(toast.textContent, "iddaa bülteni (Nesine): 639 maç (ayrıntı ekranda)");
  assert.equal(t.$("#checkOut").textContent, long);
  // metin olarak paylaşılabilir (ekran görüntüsü gerekmez)
  const calls = [];
  t.w.MockAndroid.act = (action, payload, id) => { calls.push({ action, payload: JSON.parse(payload) }); setTimeout(() => t.w.onActResult(id, { ok: true, message: "" }), 0); };
  t.button("Çıktıyı paylaş").click();
  await t.tick();
  assert.deepEqual(calls.at(-1), { action: "shareText", payload: { text: long } });
  assert.equal(t.$("#checkOut").textContent, long); // yeniden çizimde çıktı kaybolmaz
  toast.click();
  assert.equal(toast.className, "");
  // başlıklı çıktı: ilk anlamlı satır
  assert.equal(t.w.shortMessage("ÖZET\n  Nesine: 639 maç · Pinnacle: 35 maç\n" + "x\n".repeat(200)),
    "Nesine: 639 maç · Pinnacle: 35 maç (ayrıntı ekranda)");
});

test("Zirve Oran: kendiliğinden listelenir, adil oranı geçen oynanır, ana sayfada uyarı", async () => {
  const s = clone(baseState);
  s.settings.zirve = true;
  s.stats.balance = 500000;
  const soon = new Date(Date.parse(s.now) + 7200000).toISOString(), later = new Date(Date.parse(s.now) + 90000000).toISOString();
  const past = new Date(Date.parse(s.now) - 600000).toISOString();
  s.zirve = { at: s.now, fairsAt: s.now, events: 3, offers: 5, matched: 1, evaluated: 2, play: 1, rows: [
    { id: "1:1:1", event: "1", home: "Belçika", away: "Türkiye", kickoff: soon, league: "UEFA Uluslar Ligi", name: "MS 1", label: "MS 1", m: "MS", o: "1",
      val: 1.37, tval: 1.4, ref: "b1", code: "123", p: 0.75, i: 1.37, ev: 0.05, status: "oyna", stake: 15000, fraction: 0.03 },
    { id: "1:2:1", event: "1", home: "Belçika", away: "Türkiye", kickoff: soon, name: "KG Var", label: "KG Var", m: "KG", o: "VAR",
      val: 1.8, tval: 1.9, ref: "b1", p: 0.5, i: 1.8, ev: -0.05, status: "oynama" },
    { id: "2:1:1", event: "2", home: "Hırvatistan", away: "İngiltere", kickoff: later, name: "MS 1", val: 3.48, tval: 3.65, status: "mac",
      why: "ligi 07:50'de tarandı; Pinnacle'da bu maç yok ya da adlar eşleşmedi" },
    { id: "2:2:1", event: "2", home: "Hırvatistan", away: "İngiltere", kickoff: later, name: "MS X", val: 3.43, tval: 3.6, status: "mac" },
    { id: "3:1:1", event: "3", home: "Başlamış", away: "Maç", kickoff: past, name: "MS 1", val: 2, tval: 2.1, p: 0.6, ev: 0.26, status: "oyna" },
  ] };
  const t = boot(s, "#firsat");
  const card = t.$("#zirveCard").textContent;
  assert.match(card, /3 maçta 5 artırılmış oran · adil oranı geçen 1/);
  assert.match(card, /Belçika – Türkiye/);
  assert.match(card, /MS 1 1,40 1,37 · adil 1,33 · tutma %75\+%5,0 Oyna/);
  assert.match(card, /KG Var 1,90 1,80 · adil 2,00 · tutma %50−%5,0 değer yok/);
  assert.match(card, /Hırvatistan – İngiltere/);
  // son taramada olmayan maç: tek açıklama, seçimlerde tekrar yok
  assert.equal(t.$$(".zirve-why").length, 1);
  assert.match(t.$(".zirve-why").textContent, /Adil oran yok: ligi 07:50'de tarandı; Pinnacle'da bu maç yok ya da adlar eşleşmedi\./);
  assert.match(card, /MS X 3,60 3,43/);
  assert.equal((card.match(/adil oran yok/g) || []).length, 0);
  assert.doesNotMatch(card, /Başlamış/);
  t.$(".zirve-play").click();
  const sheet = t.$("#sheet").textContent;
  assert.match(sheet, /Zirve 1,40 \(normal 1,37\) · adil 1,33 · avantaj \+%5,0/);
  assert.match(sheet, /önerilen tutar 150,00 TL \(kasanın %3,0'ü\)/);
  assert.equal(t.$("#zirveOdds").value, "1,4");
  assert.equal(t.$("#zirveAmt").value, "150");
  t.button("Bu tutarla oynadım").click();
  await t.tick();
  assert.deepEqual(t.calls.at(-1), { action: "promoPlay", payload: { ref: "b1", m: "MS", o: "1", odds: "1,4", amount: "150" } });
  t.button("Yeniden oku").click();
  await t.tick();
  assert.equal(t.calls.at(-1).action, "zirveRefresh");
  // ana sayfada uyarı, Fırsatlar'a götürür
  const h = boot(s, "");
  assert.match(h.$("#zirveHome").textContent, /1 Zirve Oran seçimi adil oranı geçiyor/);
  h.button("Fırsatlar'da gör").click();
  assert.ok(h.$("#zirveCard"));
  // değerli yokken en yakın Zirve seçimi ve tutarı kartın üstünde
  const nz = clone(s);
  nz.zirve.play = 0;
  nz.zirve.rows = nz.zirve.rows.filter((r) => r.status !== "oyna");
  nz.zirve.nearest = { id: "1:2:1", kickoff: soon, ev: -0.05, text: "Belçika – Türkiye · KG Var @ 1,90 (adil 2,00, −%5,0)",
    stake: "0 TL — avantaj yok, oynanmaz (yine de 100 TL oynanırsa beklenen kayıp ≈ 5,00 TL). Oran 2,06 ya da üstüne çıkarsa (Zirve oranı yükselirse) önerilen 30,00 TL (kasa payı %0,7)." };
  const nt = boot(nz, "#firsat");
  assert.match(nt.$(".zirve-near").textContent, /En yakın Zirve seçimi: Belçika – Türkiye · KG Var @ 1,90 \(adil 2,00, −%5,0\)Önerilen tutar: 0 TL — avantaj yok/);
  nz.zirve.nearest.kickoff = past; // başlamış maç gösterilmez
  assert.equal(boot(nz, "#firsat").$(".zirve-near"), null);
  // okuma sürerken: adım yazılır, düğme kapalı, ekran kendiliğinden yenilenir
  const b = clone(s);
  b.zirve.busy = "Zirve maçlarının ligi taranıyor (UEFA Nations League)…";
  const tb = boot(b, "#firsat");
  assert.match(tb.$(".zirve-busy").textContent, /ligi taranıyor \(UEFA Nations League\)… Bitince liste kendiliğinden yenilenir/);
  assert.equal(tb.button("Yeniden oku").disabled, true);
  assert.match(tb.$(".zirve-fairs").textContent, /Adil oranlar: son tam tarama/);
  let reads = 0;
  const done = clone(s);
  tb.w.MockAndroid.state = () => { reads++; return JSON.stringify(done); };
  await new Promise((r) => setTimeout(r, 3100));
  assert.ok(reads >= 1);
  assert.equal(tb.$(".zirve-busy"), null);
  // hiç okunmadıysa elle okuma düğmesi; kapalıysa açıklama; demoda yok
  const n = clone(s);
  n.zirve = null;
  assert.match(boot(n, "#firsat").$("#zirveCard").textContent, /Henüz okunmadı/);
  n.settings.zirve = false;
  assert.match(boot(n, "#firsat").$("#zirveCard").textContent, /Kapalı/);
  const d = clone(s);
  d.demo = true;
  assert.equal(boot(d, "#firsat").$("#zirveCard"), null);
  // ayar kaydedilir
  const a = boot(s, "#ayarlar");
  assert.equal(a.$("#sZirve").checked, true);
  a.$("#sZirve").checked = false;
  a.button("Ayarları kaydet").click();
  await a.tick();
  assert.equal(a.calls.at(-1).payload.settings.zirve, false);
  assert.deepEqual(t.errors, []);
});

test("sanal takip: en yakın seçimlerin sanal sonucu Geçmiş'te", async () => {
  const s = clone(baseState);
  const ko = new Date(Date.parse(s.now) - 86400000).toISOString(), soon = new Date(Date.parse(s.now) + 7200000).toISOString();
  s.virtual = { n: 2, won: 1, lost: 1, voids: 0, open: 1, pl: -6000, staked: 20000, expectedPl: -1650, hitRate: 0.5, expectedHitRate: 0.59, roi: -0.3,
    normal: { n: 1, won: 1, lost: 0, open: 1, pl: 4000 }, zirve: { n: 1, won: 0, lost: 1, open: 0, pl: -10000 },
    recent: [
      { kind: "normal", home: "İtalya", away: "Türkiye", kickoff: soon, label: "MS 1", odds: 1.31, result: null },
      { kind: "zirve", home: "Hırvatistan", away: "İngiltere", kickoff: ko, label: "MS 2", odds: 1.74, result: "kaybetti", score: "1-1", pl: -10000 },
      { kind: "normal", home: "Macaristan", away: "Gürcistan", kickoff: ko, label: "MS 1", odds: 1.4, result: "kazandi", score: "2-0", pl: 4000 },
    ] };
  const t = boot(s, "#gecmis");
  const card = t.$("#virtualCard").textContent;
  assert.match(card, /Sonuçlanan2 \(tutan 1, yatan 1\) · bekleyen 1/);
  assert.match(card, /Tutma oranı%50,0 \(adil olasılığa göre beklenen %59,0\)/);
  assert.match(card, /Sanal kâr\/zarar-60,00 TL \(2 × 100 TL, ROI −%30,0\)/);
  assert.match(card, /Beklenen kâr\/zarar-16,50 TL/);
  assert.match(card, /Normal oranlarda1 bahis · tutan 1 · 40,00 TL/);
  assert.match(card, /Zirve Oran'da1 bahis · tutan 0 · -100,00 TL/);
  assert.equal(t.$$(".virtual-row").length, 3);
  assert.match(t.$$(".virtual-row")[0].textContent, /İtalya – Türkiye MS 1 1,31Bekliyor/);
  assert.match(t.$$(".virtual-row")[1].textContent, /Zirve Oran1-1Hırvatistan – İngiltere MS 2 1,74Yattı -100,00 TL/);
  assert.match(card, /50\+ bahisten sonra/);
  // kayıt yokken açıklama; demoda kart yok
  const e = clone(s);
  e.virtual = { n: 0, won: 0, lost: 0, voids: 0, open: 0, pl: 0, normal: {}, zirve: {}, recent: [] };
  assert.match(boot(e, "#gecmis").$("#virtualCard").textContent, /Henüz kayıt yok/);
  const d = clone(s);
  d.demo = true;
  assert.equal(boot(d, "#gecmis").$("#virtualCard"), null);
  assert.deepEqual(t.errors, []);
});

test("kampanya hesaplayıcı: bedava bahis, kayıp iadesi, kazanç artışı, erken ödeme", async () => {
  const s = clone(baseState);
  const soon = new Date(Date.parse(s.now) + 7200000).toISOString(), past = new Date(Date.parse(s.now) - 600000).toISOString();
  s.radar = s.radar || { values: [], moves: [] };
  s.radar.fairsAt = s.now;
  s.radar.fairs = [
    { ref: "b1", sref: "s1", sport: "soccer_uefa_nations_league", code: "3172999", home: "Kazakistan", away: "Moldova", kickoff: soon, league: "UEFA Nations League", at: s.now,
      sel: [{ m: "MS", o: "1", label: "MS 1", p: 0.5, i: 1.8 }, { m: "MS", o: "X", label: "MS X", p: 0.2, i: 3.0 }, { m: "MS", o: "2", label: "MS 2", p: 0.3, i: 3.2 },
        { m: "AU25", o: "UST", label: "2,5 Üst", p: 0.45, i: 1.8 }, { m: "CS", o: "1X", label: "ÇŞ 1-X", p: 0.7, i: 1.2 }] },
    { ref: "b2", sref: "s2", sport: "x", code: "1", home: "Başlamış", away: "Maç", kickoff: past, league: "L", sel: [{ m: "MS", o: "1", label: "MS 1", p: 0.9, i: 9 }] },
  ];
  s.settings.minLegProb = 0; // önce olasılık şartı olmadan; aşağıda %40 ile
  s.radar.fairs[0].sel.push({ m: "AU25", o: "ALT", label: "2,5 Alt", p: 0.55, i: 2.6, mbs: 3 }); // MBS 3: tek oynanamaz
  const t = boot(s, "#firsat");
  const res = () => t.$("#campRes").textContent;
  // bedava bahis 100 TL: en değerli MS 2 (0,30 x 2,20 = 0,66) -> 66 TL; ÇŞ 1-X en düşük oranın (1,50) altında
  assert.match(res(), /Bu bedava bahsin gerçek değeri ≈ 66,00 TL \(%66,0\)/);
  assert.equal(t.$$(".camp-row").length, 4);
  assert.doesNotMatch(res(), /Başlamış|ÇŞ 1-X/);
  assert.match(t.$$(".camp-row")[0].textContent, /Kazakistan – Moldova MS 2 3,20 adil 3,33.*66,00 TL/);
  t.$("#campReturn").checked = true;
  t.$("#campReturn").dispatchEvent(new t.w.Event("change"));
  assert.match(res(), /≈ 96,00 TL/); // tutar da ödenirse 0,30 x 3,20
  t.$("#campReturn").checked = false;
  t.$("#campReturn").dispatchEvent(new t.w.Event("change"));
  // tutar etiketi: oynanacak tutar ayrı, sağdaki rakam değer
  assert.match(t.$(".camp-stake").textContent, /Oynanacak tutar: 100,00 TL bedava bahis \(kendi paran değil\)\. Sağdaki rakam oynanacak tutar değil/);
  assert.match(t.$$(".camp-row")[0].textContent, /tutma %30.*değer 66,00 TL/);
  // MBS 3 olan seçim tek maçlık kampanyada listelenmez; kombineye izin verilirse görünür
  assert.doesNotMatch(res(), /2,5 Alt/);
  t.$("#campCombos").checked = true;
  t.$("#campCombos").dispatchEvent(new t.w.Event("change"));
  assert.match(res(), /2,5 Alt MBS 3/);
  t.$("#campCombos").checked = false;
  t.$("#campCombos").dispatchEvent(new t.w.Event("change"));
  // en az tutma olasılığı %40: MS 2 (%30) ve MS X (%20) çıkar; en değerlisi MS 1 (0,50 x 0,80 = 40 TL)
  const prob = t.$("#campProb");
  prob.value = "40";
  prob.dispatchEvent(new t.w.Event("input"));
  assert.match(res(), /≈ 40,00 TL/);
  assert.doesNotMatch(res(), /MS 2|MS X/);
  // en sık tutan sıralaması
  prob.value = "0";
  prob.dispatchEvent(new t.w.Event("input"));
  const sort = t.$("#campSort");
  sort.value = "prob";
  sort.dispatchEvent(new t.w.Event("change"));
  assert.match(t.$$(".camp-row")[0].textContent, /MS 1/); // %50, değeri en iyinin yarısından fazla
  assert.match(res(), /≈ 66,00 TL/); // özet yine en değerli seçimin değeri
  sort.value = "value";
  sort.dispatchEvent(new t.w.Event("change"));
  // kayıp iadesi: 100 TL, %50 nakit iade: MS 2 -> 100(0,96 − 1) + 0,70 x 50 = +31 TL
  const type = t.$("#campType");
  type.value = "cashback";
  type.dispatchEvent(new t.w.Event("change"));
  const pctIn = t.$("#campPct");
  pctIn.value = "50";
  pctIn.dispatchEvent(new t.w.Event("input"));
  assert.match(res(), /Oyna: en iyi seçimde beklenen kâr 31,00 TL \(100,00 TL'lik bahiste, \+%31,0\)/);
  const cap = t.$("#campCap");
  cap.value = "10"; // iade en fazla 10 TL: 100(0,96 − 1) + 0,70 x 10 = +3 TL
  cap.dispatchEvent(new t.w.Event("input"));
  assert.match(res(), /beklenen kâr 3,00 TL/);
  pctIn.value = "0";
  pctIn.dispatchEvent(new t.w.Event("input"));
  assert.match(res(), /Oynama: bu şartla en iyi seçimde bile beklenen sonuç -4,00 TL \(−%4,0\)/);
  // kazanç artışı %10: MS 2 3,20 -> 1 + 2,2 x 1,1 = 3,42; 0,30 x 3,42 − 1 = +%2,6
  type.value = "boost";
  type.dispatchEvent(new t.w.Event("change"));
  assert.match(t.$$(".camp-row")[0].textContent, /MS 2 3,42 3,20 adil 3,33 · tutma %30\+%2,6 \(2,60 TL\)/);
  assert.match(res(), /Oyna: en iyi seçimde beklenen kâr 2,60 TL/);
  // erken ödeme: yalnızca MS 1 / MS 2 ve kazanma olasılığına model katkısı
  type.value = "early";
  type.dispatchEvent(new t.w.Event("change"));
  assert.ok(t.$$(".camp-row").length === 2);
  assert.match(res(), /MS 1 1,80 adil 2,00 · tutma %50 · \+\d+,\d puan/);
  // gol modeli: uyum ve erken ödeme olasılığı sıralaması
  const g = t.w.goalOutcomes(1.6, 1.1), l = t.w.fitGoals(g[0], g[1], g[2]);
  assert.ok(Math.abs(l[0] - 1.6) < 0.03 && Math.abs(l[1] - 1.1) < 0.03, String(l));
  const win = t.w.leadWin(1.6, 1.1, 99), two = t.w.leadWin(1.6, 1.1, 2), one = t.w.leadWin(1.6, 1.1, 1);
  assert.ok(Math.abs(win - g[0]) < 0.01, win + " " + g[0]); // dakika adımlı model Poisson kazanma olasılığına yakın
  assert.ok(one > two && two > win, [one, two, win].join(" "));
  assert.equal(t.w.freebetRate(0.3, 3.2, false).toFixed(2), "0.66");
  assert.equal(t.w.boostOdds(2.2, 0.1).toFixed(2), "2.32");
  // adil oran yokken açıklama; demoda kart yok
  const e = clone(s);
  e.radar.fairs = [];
  assert.match(boot(e, "#firsat").$("#campRes").textContent, /Uygun seçim yok/);
  const d = clone(s);
  d.demo = true;
  assert.equal(boot(d, "#firsat").$("#campCard"), null);
  assert.deepEqual(t.errors, []);
});

test("tutma olasılığı şartı, seyrek rozeti ve ek basketbol ligleri", async () => {
  const s = clone(baseState);
  s.settings.minLegProb = 0.4;
  s.basketLeagues = [["basketball_wnba", "WNBA (basketbol)"], ["basketball_nbl", "NBL (basketbol)"]];
  const a = boot(s, "#ayarlar");
  assert.match(a.$("#basketCard").textContent, /WNBA \(basketbol\)NBL \(basketbol\)/);
  assert.match(a.$(".basket-extra").textContent, /ek ligler de seçilebilir \(2\).*İspanya, İtalya, Almanya/);
  assert.equal(a.$("#f_minLegProb").value, "40");
  a.$$(".lg").find((x) => x.value === "basketball_wnba").checked = true;
  a.$("#f_minLegProb").value = "50";
  a.button("Ayarları kaydet").click();
  await a.tick();
  const st = a.calls.at(-1).payload.settings;
  assert.ok(st.leagues.indexOf("basketball_wnba") >= 0);
  assert.equal(st.minLegProb, 0.5);
  // değerli ama seyrek tutan seçim rozetle gösterilir
  const soon = new Date(Date.parse(s.now) + 7200000).toISOString();
  s.radar = { values: [{ bookRef: "b1", code: "1", home: "Ev", away: "Dep", kickoff: soon, league: "Lig", market: "MS", outcome: "2", odds: 3.4, fair: 0.32, ev: 0.088, mbs: 1, inRange: true, lowProb: true }],
    moves: [], fairsAt: s.now, fairs: [{ ref: "b1", sref: "s1", sport: "lig", code: "1", home: "Ev", away: "Dep", kickoff: soon, league: "Lig",
      sel: [{ m: "MS", o: "2", label: "MS 2", p: 0.3, i: 3.0, mbs: 1 }] }] };
  const t = boot(s, "#firsat");
  assert.match(t.text(), /tutma %32, seyrek/);
  // kampanya oranı avantajlı ama seyrek: oynama
  t.$$(".promo-sel")[0].click();
  const o = t.$("#promoOdds");
  o.value = "3,60";
  o.dispatchEvent(new t.w.Event("input"));
  assert.match(t.$("#promoRes").textContent, /Oynama: avantajlı ama tutma olasılığı düşük \(%30,0; ayar en az %40,0\)/);
  assert.equal(t.$("#promoAmt"), null);
  assert.deepEqual(t.errors, []);
});

test("basketbol handikap: etiket ve ayar", async () => {
  const s = clone(baseState);
  s.settings.basketHandicap = true;
  const t = boot(s, "#ayarlar");
  assert.equal(t.w.label("BH@-4.5", "1"), "Basket H. Ev −4,5");
  assert.equal(t.w.label("BH@-4.5", "2"), "Basket H. Dep +4,5");
  assert.equal(t.w.label("BH@3.5", "1"), "Basket H. Ev +3,5");
  assert.equal(t.$("#sHandicap").checked, true);
  assert.match(t.$("#basketCard").textContent, /Handikap \(Basket H\. Ev −4,5 \/ Dep \+4,5\).*Basketbol taramasına \+1 kredi/);
  t.$("#sHandicap").checked = false;
  t.button("Ayarları kaydet").click();
  await t.tick();
  assert.equal(t.calls.at(-1).payload.settings.basketHandicap, false);
  assert.deepEqual(t.errors, []);
});

let failed = 0;
for (const [name, fn] of tests) {
  try {
    await fn();
    console.log("✓", name);
  } catch (e) {
    failed++;
    console.log("✗", name, "\n ", e.message);
  }
}
console.log(failed ? `${failed} test başarısız` : `${tests.length} arayüz testi geçti`);
process.exit(failed ? 1 : 0);
