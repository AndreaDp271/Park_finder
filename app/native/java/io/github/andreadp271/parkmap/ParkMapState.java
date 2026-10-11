package io.github.andreadp271.parkmap;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * Stato condiviso tra la pagina web (tramite ParkMapNativePlugin), il widget
 * e Android Auto: la posizione dell'auto e l'azione chiesta dal widget.
 * L'auto è un JSON {lat, lon, ts, expiry} come in localStorage ("parkCar_v1").
 */
public final class ParkMapState {
    public static final String EXTRA_ACTION = "parkmap_action";
    public static final String ACTION_SAVE_CAR = "saveCar";
    public static final String ACTION_SHOW_CAR = "showCar";

    private static final String PREFS = "parkmap";
    private static final String KEY_CAR = "car";
    private static final String KEY_PENDING = "pending_action";

    private ParkMapState() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static JSONObject getCar(Context ctx) {
        String raw = prefs(ctx).getString(KEY_CAR, null);
        if (raw == null) return null;
        try {
            JSONObject car = new JSONObject(raw);
            return car.has("lat") && car.has("lon") ? car : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String getCarJson(Context ctx) {
        return prefs(ctx).getString(KEY_CAR, null);
    }

    /** Salva (o cancella, con null) l'auto e aggiorna widget e promemoria di aggiornamento. */
    public static void setCarJson(Context ctx, String json) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (json == null || json.equals("null")) ed.remove(KEY_CAR); else ed.putString(KEY_CAR, json);
        ed.apply();
        ParkMapWidget.updateAll(ctx);
        scheduleWidgetRefreshAtExpiry(ctx);
    }

    public static void saveCarHere(Context ctx, double lat, double lon) {
        try {
            JSONObject car = new JSONObject();
            car.put("lat", lat);
            car.put("lon", lon);
            car.put("ts", System.currentTimeMillis());
            car.put("expiry", JSONObject.NULL);
            car.put("source", "auto");
            setCarJson(ctx, car.toString());
        } catch (Exception ignored) {}
    }

    public static long getExpiry(JSONObject car) {
        if (car == null || car.isNull("expiry")) return 0;
        return car.optLong("expiry", 0);
    }

    /** Il widget apre l'app con un'azione: la pagina la legge con takePendingAction. */
    public static void storePendingAction(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra(EXTRA_ACTION);
        if (action != null) {
            prefs(ctx).edit().putString(KEY_PENDING, action).apply();
            intent.removeExtra(EXTRA_ACTION);
        }
    }

    public static String takePendingAction(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String action = p.getString(KEY_PENDING, null);
        if (action != null) p.edit().remove(KEY_PENDING).apply();
        return action;
    }

    /** Alla scadenza della sosta il widget passa da "scade alle" a "scaduta". */
    private static void scheduleWidgetRefreshAtExpiry(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(ctx, ParkMapWidget.class).setAction(ParkMapWidget.ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 7201, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        long expiry = getExpiry(getCar(ctx));
        if (expiry > System.currentTimeMillis()) {
            // Non serve la precisione al secondo: niente permesso di allarme esatto
            am.setAndAllowWhileIdle(AlarmManager.RTC, expiry + 1000, pi);
        }
    }
}
