#!/usr/bin/env bash
# Prepara app/www: la stessa pagina del sito, con Leaflet e Lucide inclusi
# nell'APK invece che scaricati da unpkg a ogni avvio (l'app si apre subito e
# non dipende da un CDN esterno per partire).
# Uso: prepare-www.sh [numero_build]  (diventa la versione 1.0.<numero_build>)
set -euo pipefail
cd "$(dirname "$0")"
BUILD="${1:-0}"

LEAFLET=1.9.4
LUCIDE=0.460.0

rm -rf www
mkdir -p www/vendor/leaflet/images www/icons
cp ../index.html ../manifest.json ../icon-192.png ../icon-512.png www/
cp ../icons/icon.svg ../icons/apple-touch-icon.png ../icons/maskable-512.png www/icons/

# Versione mostrata nelle Impostazioni e confrontata con le Releases di GitHub
sed -i "s/const APP_VERSION = null;/const APP_VERSION = '1.0.${BUILD}';/" www/index.html
grep -q "const APP_VERSION = '1.0.${BUILD}';" www/index.html || { echo "APP_VERSION non trovata in index.html" >&2; exit 1; }

curl -sSfL -o www/vendor/leaflet/leaflet.css "https://unpkg.com/leaflet@${LEAFLET}/dist/leaflet.css"
curl -sSfL -o www/vendor/leaflet/leaflet.js  "https://unpkg.com/leaflet@${LEAFLET}/dist/leaflet.js"
for img in layers.png layers-2x.png marker-icon.png marker-icon-2x.png marker-shadow.png; do
  curl -sSfL -o "www/vendor/leaflet/images/${img}" "https://unpkg.com/leaflet@${LEAFLET}/dist/images/${img}"
done
curl -sSfL -o www/vendor/lucide.min.js "https://unpkg.com/lucide@${LUCIDE}/dist/umd/lucide.min.js"

# Gli attributi integrity restano validi: sono gli stessi file, alla stessa versione
sed -i \
  -e "s#https://unpkg.com/leaflet@${LEAFLET}/dist/leaflet.css#vendor/leaflet/leaflet.css#" \
  -e "s#https://unpkg.com/leaflet@${LEAFLET}/dist/leaflet.js#vendor/leaflet/leaflet.js#" \
  -e "s#https://unpkg.com/lucide@${LUCIDE}/dist/umd/lucide.min.js#vendor/lucide.min.js#" \
  www/index.html

# Se una versione nell'HTML cambia senza aggiornare questo script, meglio
# fermarsi che produrre un'app che carica le librerie da internet
if grep -q "unpkg.com/\(leaflet\|lucide\)@" www/index.html; then
  echo "index.html usa versioni di Leaflet/Lucide diverse da quelle in prepare-www.sh" >&2
  exit 1
fi
echo "www pronta"
