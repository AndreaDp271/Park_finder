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

# Codice nativo di ParkMap (app/native): MainActivity con il plugin locale,
# widget "La mia auto" e Android Auto. Sovrascrive la MainActivity generata.
cp -r ../native/java/. app/src/main/java/
cp -r ../native/res/. app/src/main/res/
python3 - "$MANIFEST" <<'PY'
import sys
path = sys.argv[1]
s = open(path, encoding='utf-8').read()
block = '''
        <!-- Widget "La mia auto" -->
        <receiver
            android:name=".ParkMapWidget"
            android:exported="false"
            android:label="@string/parkmap_widget_label">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/parkmap_widget_info" />
        </receiver>

        <!-- Android Auto: app della categoria parcheggi -->
        <service
            android:name=".car.ParkMapCarAppService"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.car.app.CarAppService" />
                <category android:name="androidx.car.app.category.PARKING" />
            </intent-filter>
        </service>
        <meta-data
            android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc" />
        <meta-data
            android:name="androidx.car.app.minCarApiLevel"
            android:value="1" />
'''
assert s.count('</application>') == 1
s = s.replace('</application>', block + '    </application>')
open(path, 'w', encoding='utf-8').write(s)
PY
grep -q "ParkMapCarAppService" "$MANIFEST" || { echo "manifest: Android Auto non inserito" >&2; exit 1; }
sed -i 's#implementation project(.:capacitor-android.)#&\n    implementation "androidx.car.app:app:1.7.0"#' app/build.gradle
grep -q "androidx.car.app:app" app/build.gradle || { echo "dipendenza Android Auto non inserita" >&2; exit 1; }

# Versione: cresce a ogni build, così Android accetta l'aggiornamento sopra la precedente
sed -i -E "s/versionCode [0-9]+/versionCode ${BUILD}/; s/versionName \"[^\"]*\"/versionName \"1.0.${BUILD}\"/" app/build.gradle
grep -q "versionCode ${BUILD}" app/build.gradle || { echo "versionCode non aggiornato" >&2; exit 1; }

# Icone e schermata di avvio, tutte generate da icons/icon.svg
# (icons/render-icons.js): legacy quadrata e tonda, adattiva (sfondo +
# primo piano nella zona sicura) e monocromatica per le icone a tema di Android 13+
command -v convert >/dev/null || { sudo apt-get update -q && sudo apt-get install -y -q imagemagick; }
ICONS=../../icons
RES=app/src/main/res
BRAND="#2563EB"
for pair in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  d=${pair%%:*}; px=${pair##*:}
  fg=$((px * 108 / 48))
  mkdir -p "$RES/mipmap-$d"
  convert "$ICONS/android-legacy.png" -resize "${px}x${px}" "$RES/mipmap-$d/ic_launcher.png"
  convert "$ICONS/android-round.png" -resize "${px}x${px}" "$RES/mipmap-$d/ic_launcher_round.png"
  convert "$ICONS/android-foreground.png" -resize "${fg}x${fg}" "$RES/mipmap-$d/ic_launcher_foreground.png"
  convert "$ICONS/android-background.png" -resize "${fg}x${fg}" "$RES/mipmap-$d/ic_launcher_background.png"
  convert "$ICONS/android-monochrome.png" -resize "${fg}x${fg}" "$RES/mipmap-$d/ic_launcher_monochrome.png"
done
# Icona piccola delle notifiche (promemoria sosta): sagoma bianca su trasparente
for pair in mdpi:24 hdpi:36 xhdpi:48 xxhdpi:72 xxxhdpi:96; do
  d=${pair%%:*}; px=${pair##*:}; inner=$((px * 84 / 100))
  mkdir -p "$RES/drawable-$d"
  convert "$ICONS/android-monochrome.png" -trim -resize "${inner}x${inner}" -background none -gravity center \
    -extent "${px}x${px}" "$RES/drawable-$d/ic_stat_parkmap.png"
done
for xml in "$RES/mipmap-anydpi-v26/ic_launcher.xml" "$RES/mipmap-anydpi-v26/ic_launcher_round.xml"; do
  cat > "$xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>
</adaptive-icon>
XML
done

# Schermata di avvio: logo bianco su blu. Su Android 12+ il sistema mostra
# prima l'icona su windowSplashScreenBackground: stesso colore, nessuno stacco.
for f in $(find "$RES" -name 'splash.png'); do
  size=$(identify -format '%wx%h' "$f")
  w=${size%x*}; h=${size#*x}; m=$(( (w < h ? w : h) * 6 / 10 ))
  convert -size "$size" "xc:$BRAND" \( "$ICONS/splash-logo.png" -resize "${m}x${m}" \) -gravity center -composite "$f"
done
sed -i "s|<item name=\"android:background\">@drawable/splash</item>|&\n        <item name=\"windowSplashScreenBackground\">$BRAND</item>\n        <item name=\"windowSplashScreenAnimatedIcon\">@mipmap/ic_launcher_foreground</item>|" "$RES/values/styles.xml"
grep -q windowSplashScreenBackground "$RES/values/styles.xml" || { echo "tema di avvio non aggiornato" >&2; exit 1; }
echo "progetto Android personalizzato (build ${BUILD})"
