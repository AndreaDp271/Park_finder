package io.github.andreadp271.parkmap.car;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Parcheggi vicini per Android Auto: stessa fonte e stessi filtri dell'app
 * (Postpass, database OSM mondiale), interrogata direttamente dal telefono.
 */
final class ParkingRepository {
    static final class Parking {
        final String name;
        final String summary;
        final double lat, lon;
        float distance;

        Parking(String name, String summary, double lat, double lon) {
            this.name = name;
            this.summary = summary;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final String POSTPASS_URL = "https://postpass.geofabrik.de/api/interpreter";
    private static final double RADIUS_DEG = 0.008; // ~900 m in latitudine

    private ParkingRepository() {}

    static boolean hasLocationPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Ultima posizione nota (GPS, rete o fused): in auto è sempre recente. */
    @SuppressWarnings("MissingPermission")
    static Location lastLocation(Context ctx) {
        if (!hasLocationPermission(ctx)) return null;
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        for (String provider : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) {}
        }
        return best;
    }

    static List<Parking> nearby(double lat, double lon, int limit) throws Exception {
        String sql = String.format(Locale.US,
            "SELECT osm_type, osm_id, tags, geom FROM postpass_pointpolygon WHERE ("
                + "tags->>'amenity' = 'parking' OR "
                + "(tags->>'amenity' = 'parking_space' AND tags->>'parking_space' <> 'normal')"
                + ") AND geom && ST_MakeEnvelope(%f,%f,%f,%f,4326)",
            lon - RADIUS_DEG * 1.4, lat - RADIUS_DEG, lon + RADIUS_DEG * 1.4, lat + RADIUS_DEG);

        HttpURLConnection conn = (HttpURLConnection) new URL(POSTPASS_URL).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "ParkMap-AndroidAuto (+https://github.com/AndreaDp271/Park_finder)");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(("data=" + URLEncoder.encode(sql, "UTF-8")).getBytes(StandardCharsets.UTF_8));
        }
        if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
        StringBuilder sb = new StringBuilder();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
        }

        JSONArray features = new JSONObject(sb.toString()).optJSONArray("features");
        List<Parking> result = new ArrayList<>();
        if (features == null) return result;
        float[] dist = new float[1];
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.getJSONObject(i);
            JSONObject props = f.optJSONObject("properties");
            JSONObject geom = f.optJSONObject("geometry");
            if (props == null || geom == null) continue;
            JSONObject tags = props.optJSONObject("tags");
            if (tags == null) tags = new JSONObject();
            if (isRestricted(tags)) continue; // in auto servono i parcheggi utilizzabili da tutti
            double[] c = center(geom);
            if (c == null) continue;
            Parking p = new Parking(title(tags), summary(tags), c[0], c[1]);
            Location.distanceBetween(lat, lon, p.lat, p.lon, dist);
            p.distance = dist[0];
            result.add(p);
        }
        Collections.sort(result, (a, b) -> Float.compare(a.distance, b.distance));
        return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
    }

    private static boolean isRestricted(JSONObject tags) {
        String access = tags.optString("access", "");
        return access.equals("private") || access.equals("customers") || access.equals("destination")
            || access.equals("permit") || access.equals("no") || tags.optString("vehicle", "").equals("permit");
    }

    private static String title(JSONObject tags) {
        String name = tags.optString("name", tags.optString("ref", ""));
        if (!name.isEmpty()) return name;
        switch (tags.optString("parking_space", "")) {
            case "disabled": return "Stallo disabili";
            case "charging": return "Ricarica EV";
            case "loading": case "delivery": return "Carico/scarico";
            default: break;
        }
        switch (tags.optString("parking", "")) {
            case "underground": return "Parcheggio sotterraneo";
            case "multi-storey": return "Parcheggio multipiano";
            case "street_side": case "lane": return "Sosta su strada";
            default: return "Area di sosta";
        }
    }

    private static String summary(JSONObject tags) {
        List<String> parts = new ArrayList<>();
        String fee = tags.optString("fee", "");
        if (tags.has("fee:conditional")) parts.add("A pagamento in certi orari");
        else if (fee.equals("no")) parts.add("Gratuito");
        else if (fee.equals("yes")) parts.add("A pagamento");
        String cond = tags.optString("parking:condition", "").toLowerCase(Locale.ROOT);
        String maxstay = tags.optString("maxstay", "").toLowerCase(Locale.ROOT);
        if (cond.contains("disc") || (!maxstay.isEmpty() && !maxstay.equals("no") && !maxstay.equals("unlimited")) || tags.has("maxstay:conditional")) {
            parts.add("Disco orario");
        }
        String capacity = tags.optString("capacity", "");
        if (!capacity.isEmpty()) parts.add(capacity + " posti");
        return parts.isEmpty() ? "Costo non indicato" : String.join(" · ", parts);
    }

    /** Punto da mostrare: il nodo, oppure il centro dell'anello esterno del poligono. */
    private static double[] center(JSONObject geom) throws Exception {
        String type = geom.optString("type");
        JSONArray coords = geom.optJSONArray("coordinates");
        if (coords == null) return null;
        if (type.equals("Point")) return new double[] { coords.getDouble(1), coords.getDouble(0) };
        JSONArray ring;
        if (type.equals("Polygon")) ring = coords.getJSONArray(0);
        else if (type.equals("MultiPolygon")) ring = coords.getJSONArray(0).getJSONArray(0);
        else return null;
        double sLat = 0, sLon = 0;
        int n = ring.length();
        for (int i = 0; i < n; i++) {
            JSONArray pt = ring.getJSONArray(i);
            sLon += pt.getDouble(0);
            sLat += pt.getDouble(1);
        }
        return n == 0 ? null : new double[] { sLat / n, sLon / n };
    }

    static String formatDistance(float meters) {
        if (meters < 1000) return Math.round(meters / 10f) * 10 + " m";
        return String.format(Locale.ITALY, "%.1f km", meters / 1000f);
    }
}
