// Genera tutte le icone PNG di ParkMap a partire dallo stesso disegno.
// Uso (serve Chrome/Chromium):  node icons/render-icons.js [percorso-chrome]
// Richiede puppeteer-core (npm i puppeteer-core) nella cartella da cui si lancia.
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');

const OUT = __dirname;
const ROOT = path.join(__dirname, '..');
const CHROME = process.argv[2] || 'C:/Program Files/Google/Chrome/Application/chrome.exe';

const GRADIENT = `<linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
  <stop offset="0" stop-color="#3b82f6"/><stop offset="1" stop-color="#1d4ed8"/></linearGradient>`;
// "P" centrata in (cx, cy), alta `h` unità (tratto incluso nel calcolo della scala)
const P = (cx, cy, h, color = '#fff') => {
  const s = h / 10;
  return `<path d="M9 17V7h4a3 3 0 0 1 0 6H9" transform="translate(${cx} ${cy}) scale(${s}) translate(-12.5 -12)"
    fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>`;
};
const svg = (vb, body, defs = GRADIENT) =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${vb} ${vb}"><defs>${defs}</defs>${body}</svg>`;

const roundedIcon = svg(24, `<rect width="24" height="24" rx="5.5" fill="url(#g)"/>${P(12, 12, 13.2)}`);
const fullBleed = (pHeight) => svg(24, `<rect width="24" height="24" fill="url(#g)"/>${P(12, 12, pHeight)}`);
const roundIcon = svg(24, `<circle cx="12" cy="12" r="12" fill="url(#g)"/>${P(12, 12, 11.5)}`);
// Icona adattiva Android: tela 108dp, zona sempre visibile = cerchio di 66dp al centro
const adaptiveForeground = svg(108, P(54, 54, 44));
const adaptiveBackground = svg(108, `<rect width="108" height="108" fill="url(#g)"/>`);

const targets = [
  // Web / PWA
  { file: path.join(ROOT, 'icon-192.png'), svg: roundedIcon, size: 192 },
  { file: path.join(ROOT, 'icon-512.png'), svg: roundedIcon, size: 512 },
  { file: path.join(OUT, 'maskable-512.png'), svg: fullBleed(11), size: 512 },
  { file: path.join(OUT, 'apple-touch-icon.png'), svg: fullBleed(12.5), size: 180 },
  // Android (ridimensionate per densità da app/customize-android.sh)
  { file: path.join(OUT, 'android-legacy.png'), svg: roundedIcon, size: 512 },
  { file: path.join(OUT, 'android-round.png'), svg: roundIcon, size: 512 },
  { file: path.join(OUT, 'android-foreground.png'), svg: adaptiveForeground, size: 432 },
  { file: path.join(OUT, 'android-background.png'), svg: adaptiveBackground, size: 432 },
  { file: path.join(OUT, 'android-monochrome.png'), svg: adaptiveForeground, size: 432 },
];

// Logo della schermata di avvio: "P" e scritta bianche, da comporre su sfondo blu
const splashHtml = `<!doctype html><html><head>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@800&display=block" rel="stylesheet">
<style>html,body{margin:0;background:transparent}
.w{width:720px;height:720px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:36px}
.t{font:800 88px Inter,sans-serif;color:#fff;letter-spacing:-1px}</style></head>
<body><div class="w">${svg(24, P(12, 12, 20)).replace('<svg ', '<svg width="300" height="300" ')}<div class="t">ParkMap</div></div></body></html>`;

(async () => {
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new' });
  const page = await browser.newPage();
  for (const t of targets) {
    await page.setViewport({ width: t.size, height: t.size });
    await page.setContent(`<html><body style="margin:0;background:transparent">${t.svg.replace('<svg ', `<svg width="${t.size}" height="${t.size}" `)}</body></html>`);
    await page.screenshot({ path: t.file, omitBackground: true, clip: { x: 0, y: 0, width: t.size, height: t.size } });
    console.log('ok', path.relative(ROOT, t.file));
  }
  await page.setViewport({ width: 720, height: 720 });
  await page.setContent(splashHtml, { waitUntil: 'networkidle0' });
  await page.evaluate(() => document.fonts.ready);
  await page.screenshot({ path: path.join(OUT, 'splash-logo.png'), omitBackground: true });
  console.log('ok icons/splash-logo.png');
  await browser.close();
})();
