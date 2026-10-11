// Service worker di ParkMap (solo sito web): rende l'app utilizzabile offline.
// - Pagina: prima la rete (così gli aggiornamenti arrivano subito), copia
//   salvata se si è offline.
// - Librerie (unpkg, font): versioni fisse, prima la copia salvata.
// - Tessere di mappa: prima la copia salvata, al massimo TILE_LIMIT tessere.
// - Dati dei parcheggi precalcolati: prima la rete, copia salvata offline.
// Le API (Postpass, Overpass, Nominatim, GitHub, OSM) passano sempre dalla rete.
const VERSION = 'v1';
const SHELL_CACHE = `parkmap-shell-${VERSION}`;
const LIB_CACHE = 'parkmap-libs-v1';
const TILE_CACHE = 'parkmap-tiles-v1';
const DATA_CACHE = 'parkmap-data-v1';
const TILE_LIMIT = 1500;

const SHELL = ['./', './index.html', './manifest.json', './icon-192.png', './icon-512.png', './icons/icon.svg'];
const LIBS = [
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css',
  'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js',
  'https://unpkg.com/lucide@0.460.0/dist/umd/lucide.min.js'
];
const TILE_HOSTS = /(^|\.)tile\.openstreetmap\.(org|fr)$|^server\.arcgisonline\.com$/;
const LIB_HOSTS = /^(unpkg\.com|fonts\.googleapis\.com|fonts\.gstatic\.com)$/;

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const shell = await caches.open(SHELL_CACHE);
    await shell.addAll(SHELL);
    const libs = await caches.open(LIB_CACHE);
    await Promise.all(LIBS.map(url => libs.add(new Request(url, { mode: 'cors' })).catch(() => {})));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keep = [SHELL_CACHE, LIB_CACHE, TILE_CACHE, DATA_CACHE];
    for (const key of await caches.keys()) if (!keep.includes(key)) await caches.delete(key);
    await self.clients.claim();
  })());
});

async function networkFirst(request, cacheName, timeoutMs) {
  const cache = await caches.open(cacheName);
  try {
    const response = await Promise.race([
      fetch(request),
      new Promise((_, reject) => setTimeout(() => reject(new Error('timeout')), timeoutMs))
    ]);
    if (response.ok) cache.put(request, response.clone());
    return response;
  } catch (err) {
    const cached = await cache.match(request, { ignoreSearch: request.mode === 'navigate' });
    if (cached) return cached;
    if (request.mode === 'navigate') {
      const shell = await caches.open(SHELL_CACHE);
      const page = await shell.match('./index.html');
      if (page) return page;
    }
    throw err;
  }
}

let tilePuts = 0;
async function trimTiles() {
  const cache = await caches.open(TILE_CACHE);
  const keys = await cache.keys();
  for (let i = 0; i < keys.length - TILE_LIMIT; i++) await cache.delete(keys[i]); // le più vecchie per prime
}

async function cacheFirst(request, cacheName, isTile) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);
  if (cached) return cached;
  const response = await fetch(request);
  if (response.ok || response.type === 'opaque') {
    cache.put(request, response.clone());
    if (isTile && ++tilePuts % 100 === 0) trimTiles();
  }
  return response;
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);

  if (request.mode === 'navigate' || (url.origin === location.origin && /\/(index\.html)?$/.test(url.pathname))) {
    event.respondWith(networkFirst(request, SHELL_CACHE, 4000));
  } else if (url.origin === location.origin && url.pathname.includes('/data/')) {
    event.respondWith(networkFirst(request, DATA_CACHE, 6000));
  } else if (url.origin === location.origin) {
    event.respondWith(networkFirst(request, SHELL_CACHE, 4000));
  } else if (TILE_HOSTS.test(url.hostname)) {
    event.respondWith(cacheFirst(request, TILE_CACHE, true));
  } else if (LIB_HOSTS.test(url.hostname)) {
    event.respondWith(cacheFirst(request, LIB_CACHE, false));
  }
  // Tutto il resto (API) va direttamente in rete
});
