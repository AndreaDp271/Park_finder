package io.github.andreadp271.parkmap;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.text.DateFormat;
import java.util.Date;

/**
 * Salvataggio automatico dell'auto: quando il telefono si scollega dal
 * Bluetooth dell'auto scelta nelle Impostazioni, legge la posizione e la
 * salva come "La mia auto", con una notifica.
 *
 * ACTION_ACL_DISCONNECTED è tra le trasmissioni di sistema che arrivano anche
 * ai receiver dichiarati nel manifest con l'app chiusa. Per leggere la
 * posizione con l'app in background serve il permesso "Consenti sempre".
 */
public class BluetoothCarReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "parkmap_car";
    private static final int NOTIFICATION_ID = 7301;
    private static final long FIX_TIMEOUT_MS = 25000;
    // Una posizione già nota va bene se recente e precisa (es. navigatore acceso fino all'arrivo)
    private static final long RECENT_FIX_MS = 60000;
    private static final float GOOD_ACCURACY_M = 40;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) return;
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        String target = ParkMapState.getBluetoothAddress(context);
        if (device == null || target == null || !target.equalsIgnoreCase(device.getAddress())) return;

        final Context ctx = context.getApplicationContext();
        final String deviceName = ParkMapState.getBluetoothName(ctx);
        final PendingResult pending = goAsync();
        locate(ctx, loc -> {
            try {
                if (loc != null) {
                    ParkMapState.saveCarFromBluetooth(ctx, loc, deviceName);
                    notify(ctx, ctx.getString(R.string.parkmap_bt_saved_title),
                        ctx.getString(R.string.parkmap_bt_saved_text, deviceName != null ? deviceName : "Bluetooth",
                            DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date())),
                        ParkMapState.ACTION_SHOW_CAR);
                } else {
                    notify(ctx, ctx.getString(R.string.parkmap_bt_failed_title),
                        ctx.getString(R.string.parkmap_bt_failed_text), ParkMapState.ACTION_SAVE_CAR);
                }
            } finally {
                pending.finish();
            }
        });
    }

    interface Callback { void done(Location loc); }

    private static Location lastKnown(LocationManager lm) {
        Location best = null;
        for (String provider : new String[] { LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER }) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        return best;
    }

    private static boolean isGood(Location l) {
        return l != null && System.currentTimeMillis() - l.getTime() < RECENT_FIX_MS
            && l.hasAccuracy() && l.getAccuracy() <= GOOD_ACCURACY_M;
    }

    @SuppressWarnings("deprecation")
    static void locate(Context ctx, Callback cb) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { cb.done(null); return; }
        final Location known = lastKnown(lm);
        if (isGood(known)) { cb.done(known); return; }

        String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER
            : (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null);
        // Senza un nuovo fix si usa l'ultima posizione nota, se è di pochi minuti fa
        final Location fallback = known != null && System.currentTimeMillis() - known.getTime() < 10 * 60000 ? known : null;
        if (provider == null) { cb.done(fallback); return; }

        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] finished = { false };
        final LocationListener[] listenerRef = { null };
        final Runnable timeout = () -> {
            if (finished[0]) return;
            finished[0] = true;
            if (listenerRef[0] != null) lm.removeUpdates(listenerRef[0]);
            cb.done(fallback);
        };
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, ctx.getMainExecutor(), loc -> {
                    if (finished[0]) return;
                    finished[0] = true;
                    handler.removeCallbacks(timeout);
                    cb.done(loc != null ? loc : fallback);
                });
            } else {
                LocationListener listener = new LocationListener() {
                    @Override public void onLocationChanged(Location loc) {
                        if (finished[0]) return;
                        finished[0] = true;
                        handler.removeCallbacks(timeout);
                        lm.removeUpdates(this);
                        cb.done(loc);
                    }
                    @Override public void onStatusChanged(String p, int s, Bundle b) {}
                    @Override public void onProviderEnabled(String p) {}
                    @Override public void onProviderDisabled(String p) {}
                };
                listenerRef[0] = listener;
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            }
            handler.postDelayed(timeout, FIX_TIMEOUT_MS);
        } catch (SecurityException e) {
            // Permesso di posizione (in background) non concesso
            finished[0] = true;
            cb.done(null);
        }
    }

    private static void notify(Context ctx, String title, String text, String action) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                ctx.getString(R.string.parkmap_bt_channel), NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(ctx, MainActivity.class)
            .putExtra(ParkMapState.EXTRA_ACTION, action)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 3, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new android.app.Notification.Builder(ctx, CHANNEL_ID)
            : new android.app.Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_stat_parkmap)
            .setColor(0xFF2563EB)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true);
        try {
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) {
            // Notifiche non consentite: l'auto è comunque salvata
        }
    }
}
