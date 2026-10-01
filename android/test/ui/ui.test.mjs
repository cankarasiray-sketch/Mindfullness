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
  t.$("#sKey").value = "  abc123 ";
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
    budget: 9.2, cost: 9, remaining: 199, narrowed: true,
    notes: ["Karşılıklı Gol maç sayısı 0'e indirildi", "2,5 Alt/Üst bugünlük kapatıldı"],
  };
  const t = boot(s);
  assert.match(t.text(), /Kredi planı bugün kapsamı daralttı/);
  assert.match(t.text(), /2,5 Alt\/Üst bugünlük kapatıldı/);
  t.w.show("ayarlar");
  assert.match(t.text(), /Kalan kredi199/);
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
