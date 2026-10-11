#!/usr/bin/env bash
# Adatta il progetto generato da "npx cap add android": permessi di
# posizione, numero di versione e icone ParkMap al posto di quelle di Capacitor.
# Uso: customize-android.sh <numero_build>
set -euo pipefail
cd "$(dirname "$0")/android"
BUILD="${1:-1}"

MANIFEST=app/src/main/AndroidManifest.xml
# Posizione per il pallino GPS (la WebView di Capacitor chiede il permesso
# all'utente quando la pagina usa navigator.geolocation)
sed -i 's#<uses-permission android:name="android.permission.INTERNET" />#&\n    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />\n    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />\n    <uses-feature android:name="android.hardware.location.gps" android:required="false" />#' "$MANIFEST"
grep -q ACCESS_FINE_LOCATION "$MANIFEST" || { echo "permessi non inseriti nel manifest" >&2; exit 1; }

# Versione: cresce a ogni build, così Android accetta l'aggiornamento sopra la precedente
sed -i -E "s/versionCode [0-9]+/versionCode ${BUILD}/; s/versionName \"[^\"]*\"/versionName \"1.0.${BUILD}\"/" app/build.gradle
grep -q "versionCode ${BUILD}" app/build.gradle || { echo "versionCode non aggiornato" >&2; exit 1; }

# Icone: legacy (quadrata e tonda) e adattiva (primo piano con margine di
# sicurezza su sfondo bianco, che Android ritaglia nella forma del launcher)
command -v convert >/dev/null || { sudo apt-get update -q && sudo apt-get install -y -q imagemagick; }
SRC=../../icon-512.png
RES=app/src/main/res
for pair in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  d=${pair%%:*}; px=${pair##*:}
  fg=$((px * 108 / 48)); inner=$((fg * 62 / 100))
  mkdir -p "$RES/mipmap-$d"
  convert "$SRC" -resize "${px}x${px}" "$RES/mipmap-$d/ic_launcher.png"
  convert "$SRC" -resize "${px}x${px}" \
    \( -size "${px}x${px}" xc:black -fill white -draw "circle $((px/2)),$((px/2)) $((px/2)),0" \) \
    -alpha off -compose CopyOpacity -composite "$RES/mipmap-$d/ic_launcher_round.png"
  convert -size "${fg}x${fg}" xc:none \( "$SRC" -resize "${inner}x${inner}" \) -gravity center -composite \
    "$RES/mipmap-$d/ic_launcher_foreground.png"
done
if [ -f "$RES/values/ic_launcher_background.xml" ]; then
  sed -i -E 's#<color name="ic_launcher_background">[^<]*</color>#<color name="ic_launcher_background">\#FFFFFF</color>#' "$RES/values/ic_launcher_background.xml"
fi

# Schermata di avvio: icona al centro su sfondo bianco
for f in $(find "$RES" -name 'splash.png'); do
  size=$(identify -format '%wx%h' "$f")
  w=${size%x*}; h=${size#*x}; m=$(( (w < h ? w : h) / 4 ))
  convert -size "$size" xc:white \( "$SRC" -resize "${m}x${m}" \) -gravity center -composite "$f"
done
echo "progetto Android personalizzato (build ${BUILD})"
