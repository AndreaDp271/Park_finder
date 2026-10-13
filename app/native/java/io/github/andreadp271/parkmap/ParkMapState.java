package io.github.andreadp271.parkmap;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.NotificationManager;
import android.location.Location;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stato condiviso tra la pagina web (tramite ParkMapNativePlugin), il widget
 * e il salvataggio automatico via Bluetooth: la posizione dell'auto, l'azione
 * chiesta da widget/scorciatoie e l'auto scelta per il Bluetooth.
 * L'auto è un JSON {lat, lon, ts, expiry} come in localStorage ("parkCar_v1").
 */
public final class ParkMapState {
    public static final String EXTRA_ACTION = "parkmap_action";
    public static final String ACTION_SAVE_CAR = "saveCar";
    public static final String ACTION_SHOW_CAR = "showCar";

    private static final String PREFS = "parkmap";
    private static final String KEY_CAR = "car";
    private static final String KEY_PENDING = "pending_action";
    // Auto salvata dal lato nativo (Bluetooth) che la pagina non ha ancora letto
    private static final String KEY_PENDING_CAR = "pending_car";
    // Soste chiuse ricollegandosi al Bluetooth, che la pagina non ha ancora letto
    private static final String KEY_ENDED = "ended_parkings";
    // Riconnessione subito dopo il salvataggio: era un falso distacco (es. motore riacceso)
    private static final long FALSE_ALARM_MS = 3 * 60 * 1000;
    // Promemoria della sosta programmati dalla pagina (LocalNotifications, vedi index.html)
    private static final int[] REMINDER_IDS = { 7101, 7102 };
    private static final String KEY_BT_ADDRESS = "bt_address";
    private static final String KEY_BT_NAME = "bt_name";

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

    /** Auto salvata alla disconnessione dal Bluetooth: la pagina la adotta con takePendingCar. */
    public static void saveCarFromBluetooth(Context ctx, Location loc, String deviceName) {
        try {
            JSONObject car = new JSONObject();
            car.put("lat", Math.round(loc.getLatitude() * 1e6) / 1e6);
            car.put("lon", Math.round(loc.getLongitude() * 1e6) / 1e6);
            car.put("ts", System.currentTimeMillis());
            car.put("expiry", JSONObject.NULL);
            car.put("source", "bluetooth");
            if (loc.hasAccuracy()) car.put("acc", Math.round(loc.getAccuracy()));
            if (deviceName != null) car.put("device", deviceName);
            String json = car.toString();
            prefs(ctx).edit().putString(KEY_PENDING_CAR, json).apply();
            setCarJson(ctx, json);
        } catch (Exception ignored) {
        }
    }

    /**
     * Ricollegato al Bluetooth dell'auto: la sosta è finita. L'auto va tolta
     * (widget compreso) e i promemoria annullati anche se l'app è chiusa; la
     * pagina la sposta nello storico con takeEndedParkings.
     * @return la durata della sosta in ms, oppure -1 se non c'era un'auto
     */
    public static long endParkingFromBluetooth(Context ctx) {
        JSONObject car = getCar(ctx);
        if (car == null) return -1;
        long now = System.currentTimeMillis();
        long ts = car.optLong("ts", now);
        try {
            JSONObject item = new JSONObject();
            item.put("car", car);
            item.put("end", now);
            if ("bluetooth".equals(car.optString("source")) && now - ts < FALSE_ALARM_MS) item.put("discard", true);
            JSONArray list = new JSONArray(prefs(ctx).getString(KEY_ENDED, "[]"));
            list.put(item);
            prefs(ctx).edit().putString(KEY_ENDED, list.toString()).remove(KEY_PENDING_CAR).apply();
        } catch (Exception ignored) {
        }
        cancelReminders(ctx);
        setCarJson(ctx, null);
        return now - ts;
    }

    public static String takeEndedParkings(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String json = p.getString(KEY_ENDED, null);
        if (json != null) p.edit().remove(KEY_ENDED).apply();
        return json;
    }

    /** Annulla gli allarmi dei promemoria come fa il plugin LocalNotifications (stesso intent e request code). */
    private static void cancelReminders(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        int flags = PendingIntent.FLAG_NO_CREATE | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        for (int id : REMINDER_IDS) {
            Intent intent = new Intent().setClassName(ctx, "com.capacitorjs.plugins.localnotifications.TimedNotificationPublisher");
            PendingIntent pi = PendingIntent.getBroadcast(ctx, id, intent, flags);
            if (pi != null && am != null) {
                am.cancel(pi);
                pi.cancel();
            }
            if (nm != null) nm.cancel(id);
        }
    }

    public static String takePendingCar(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String json = p.getString(KEY_PENDING_CAR, null);
        if (json != null) p.edit().remove(KEY_PENDING_CAR).apply();
        return json;
    }

    public static String getBluetoothAddress(Context ctx) {
        return prefs(ctx).getString(KEY_BT_ADDRESS, null);
    }

    public static String getBluetoothName(Context ctx) {
        return prefs(ctx).getString(KEY_BT_NAME, null);
    }

    public static void setBluetoothCar(Context ctx, String address, String name) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (address == null) ed.remove(KEY_BT_ADDRESS).remove(KEY_BT_NAME);
        else ed.putString(KEY_BT_ADDRESS, address).putString(KEY_BT_NAME, name);
        ed.apply();
    }

    public static long getExpiry(JSONObject car) {
        if (car == null || car.isNull("expiry")) return 0;
        return car.optLong("expiry", 0);
    }

    /** Widget e scorciatoie aprono l'app con un'azione: la pagina la legge con takePendingAction. */
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
