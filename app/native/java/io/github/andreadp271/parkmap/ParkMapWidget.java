package io.github.andreadp271.parkmap;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import org.json.JSONObject;

/**
 * Widget "La mia auto": senza auto salvata propone "Ho parcheggiato qui";
 * con l'auto salvata mostra da quando è parcheggiata e il conto alla rovescia
 * della sosta. Tocco sul widget o sul pulsante = apre l'app sull'auto.
 */
public class ParkMapWidget extends AppWidgetProvider {
    public static final String ACTION_REFRESH = "io.github.andreadp271.parkmap.WIDGET_REFRESH";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        RemoteViews views = buildViews(ctx);
        for (int id : ids) mgr.updateAppWidget(id, views);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (ACTION_REFRESH.equals(intent.getAction())) updateAll(ctx);
    }

    public static void updateAll(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, ParkMapWidget.class));
        if (ids == null || ids.length == 0) return;
        RemoteViews views = buildViews(ctx);
        for (int id : ids) mgr.updateAppWidget(id, views);
    }

    private static PendingIntent openApp(Context ctx, String action, int requestCode) {
        Intent intent = new Intent(ctx, MainActivity.class)
            .putExtra(ParkMapState.EXTRA_ACTION, action)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String clock(Context ctx, long ts) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ts));
    }

    private static boolean isToday(long ts) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        b.setTimeInMillis(ts);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    static RemoteViews buildViews(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.parkmap_widget);
        JSONObject car = ParkMapState.getCar(ctx);
        long now = System.currentTimeMillis();

        if (car == null) {
            v.setTextViewText(R.id.widget_title, ctx.getString(R.string.parkmap_widget_no_car));
            v.setTextViewText(R.id.widget_subtitle, ctx.getString(R.string.parkmap_widget_no_car_hint));
            v.setViewVisibility(R.id.widget_timer, View.GONE);
            v.setTextViewText(R.id.widget_button, ctx.getString(R.string.parkmap_widget_save));
            v.setOnClickPendingIntent(R.id.widget_button, openApp(ctx, ParkMapState.ACTION_SAVE_CAR, 1));
            v.setOnClickPendingIntent(R.id.widget_root, openApp(ctx, ParkMapState.ACTION_SAVE_CAR, 1));
            return v;
        }

        long ts = car.optLong("ts", now);
        String since = isToday(ts) ? clock(ctx, ts) : DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(ts)) + " " + clock(ctx, ts);
        v.setTextViewText(R.id.widget_title, ctx.getString(R.string.parkmap_widget_parked));
        v.setTextViewText(R.id.widget_button, ctx.getString(R.string.parkmap_widget_open));
        v.setOnClickPendingIntent(R.id.widget_button, openApp(ctx, ParkMapState.ACTION_SHOW_CAR, 2));
        v.setOnClickPendingIntent(R.id.widget_root, openApp(ctx, ParkMapState.ACTION_SHOW_CAR, 2));

        long expiry = ParkMapState.getExpiry(car);
        if (expiry > now) {
            v.setTextViewText(R.id.widget_subtitle, ctx.getString(R.string.parkmap_widget_expires, clock(ctx, expiry)));
            // Conto alla rovescia gestito dal sistema: nessun aggiornamento ogni secondo
            v.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() + (expiry - now), null, true);
            v.setChronometerCountDown(R.id.widget_timer, true);
            v.setViewVisibility(R.id.widget_timer, View.VISIBLE);
        } else if (expiry > 0) {
            v.setTextViewText(R.id.widget_subtitle, ctx.getString(R.string.parkmap_widget_expired, clock(ctx, expiry)));
            v.setViewVisibility(R.id.widget_timer, View.GONE);
        } else {
            v.setTextViewText(R.id.widget_subtitle, ctx.getString(R.string.parkmap_widget_since, since));
            v.setViewVisibility(R.id.widget_timer, View.GONE);
        }
        return v;
    }
}
