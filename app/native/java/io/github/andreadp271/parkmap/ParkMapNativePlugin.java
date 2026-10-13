package io.github.andreadp271.parkmap;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Ponte tra la pagina e il codice nativo. Dalla pagina:
 *   Capacitor.nativePromise('ParkMapNative', 'setCar', { car: '<json>' | null })
 *   Capacitor.nativePromise('ParkMapNative', 'takePendingAction') -> { action: 'saveCar' | 'showCar' | 'nearby' | null }
 *   Capacitor.nativePromise('ParkMapNative', 'takePendingCar')    -> { car: '<json>' | null, ended: '[{car, end, discard?}]' | null }
 *       (auto salvata scollegandosi dal Bluetooth, soste chiuse ricollegandosi)
 *   Capacitor.nativePromise('ParkMapNative', 'bluetoothStatus')   -> { supported, device, permissions }
 *   Capacitor.nativePromise('ParkMapNative', 'listBluetoothDevices') -> { devices: [{ address, name, car }] }
 *   Capacitor.nativePromise('ParkMapNative', 'setBluetoothCar', { address, name } | { address: null })
 *   Capacitor.nativePromise('ParkMapNative', 'requestAccess', { which: 'bluetooth' | 'location' | 'backgroundLocation' }) -> { granted }
 *   Capacitor.nativePromise('ParkMapNative', 'share', { title, text, url })
 *   Capacitor.nativePromise('ParkMapNative', 'updateInfo')        -> { canInstall, fdroid }
 *   Capacitor.nativePromise('ParkMapNative', 'checkUpdate')       -> { version, apkUrl, notes, page, publishedAt }
 *   Capacitor.nativePromise('ParkMapNative', 'startUpdate', { url, version }) -> { started } | { needsPermission }
 *   Capacitor.nativePromise('ParkMapNative', 'updateStatus')      -> { phase, progress, error, detail }
 *   Capacitor.nativePromise('ParkMapNative', 'openAppSettings')
 */
@CapacitorPlugin(
    name = "ParkMapNative",
    permissions = {
        @Permission(alias = "bluetooth", strings = { Manifest.permission.BLUETOOTH_CONNECT }),
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION }),
        @Permission(alias = "backgroundLocation", strings = { Manifest.permission.ACCESS_BACKGROUND_LOCATION })
    }
)
public class ParkMapNativePlugin extends Plugin {

    @PluginMethod
    public void setCar(PluginCall call) {
        ParkMapState.setCarJson(getContext(), call.getString("car"));
        call.resolve();
    }

    @PluginMethod
    public void takePendingAction(PluginCall call) {
        JSObject result = new JSObject();
        result.put("action", ParkMapState.takePendingAction(getContext()));
        call.resolve(result);
    }

    @PluginMethod
    public void takePendingCar(PluginCall call) {
        JSObject result = new JSObject();
        result.put("car", ParkMapState.takePendingCar(getContext()));
        result.put("ended", ParkMapState.takeEndedParkings(getContext()));
        call.resolve(result);
    }

    // ─── Salvataggio automatico via Bluetooth ───

    /** Il permesso esiste su questa versione di Android? (Bluetooth: 12+, background: 10+) */
    private static boolean applies(String which) {
        if ("bluetooth".equals(which)) return Build.VERSION.SDK_INT >= 31;
        if ("backgroundLocation".equals(which)) return Build.VERSION.SDK_INT >= 29;
        return true;
    }

    private boolean granted(String which) {
        if (!applies(which)) {
            // Prima di Android 10 la posizione in background segue quella normale
            return !"backgroundLocation".equals(which) || granted("location");
        }
        String perm = "bluetooth".equals(which) ? Manifest.permission.BLUETOOTH_CONNECT
            : "location".equals(which) ? Manifest.permission.ACCESS_FINE_LOCATION
            : Manifest.permission.ACCESS_BACKGROUND_LOCATION;
        return ContextCompat.checkSelfPermission(getContext(), perm) == PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return bm != null ? bm.getAdapter() : null;
    }

    @PluginMethod
    public void bluetoothStatus(PluginCall call) {
        JSObject result = new JSObject();
        result.put("supported", adapter() != null);
        String address = ParkMapState.getBluetoothAddress(getContext());
        if (address != null) {
            JSObject device = new JSObject();
            device.put("address", address);
            device.put("name", ParkMapState.getBluetoothName(getContext()));
            result.put("device", device);
        } else {
            result.put("device", null);
        }
        JSObject perms = new JSObject();
        perms.put("bluetooth", granted("bluetooth"));
        perms.put("location", granted("location"));
        perms.put("backgroundLocation", granted("backgroundLocation"));
        result.put("permissions", perms);
        call.resolve(result);
    }

    @PluginMethod
    public void listBluetoothDevices(PluginCall call) {
        BluetoothAdapter ba = adapter();
        if (ba == null) { call.reject("Bluetooth non disponibile"); return; }
        if (!granted("bluetooth")) { call.reject("Permesso Bluetooth negato"); return; }
        JSArray list = new JSArray();
        try {
            for (BluetoothDevice d : ba.getBondedDevices()) {
                JSObject o = new JSObject();
                o.put("address", d.getAddress());
                o.put("name", d.getName() != null ? d.getName() : d.getAddress());
                BluetoothClass bc = d.getBluetoothClass();
                int cls = bc != null ? bc.getDeviceClass() : 0;
                o.put("car", cls == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || cls == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE);
                list.put(o);
            }
        } catch (SecurityException e) {
            call.reject("Permesso Bluetooth negato");
            return;
        }
        JSObject result = new JSObject();
        result.put("devices", list);
        call.resolve(result);
    }

    @PluginMethod
    public void setBluetoothCar(PluginCall call) {
        ParkMapState.setBluetoothCar(getContext(), call.getString("address"), call.getString("name"));
        call.resolve();
    }

    @PluginMethod
    public void requestAccess(PluginCall call) {
        String which = call.getString("which", "location");
        if (granted(which) || !applies(which)) {
            JSObject result = new JSObject();
            result.put("granted", granted(which));
            call.resolve(result);
            return;
        }
        requestPermissionForAlias(which, call, "accessCallback");
    }

    @PermissionCallback
    private void accessCallback(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", granted(call.getString("which", "location")));
        call.resolve(result);
    }

    /** Menu di condivisione di Android (la WebView non ha navigator.share). */
    @PluginMethod
    public void share(PluginCall call) {
        String text = call.getString("text", "");
        String url = call.getString("url");
        String body = url == null ? text : (text.isEmpty() ? url : text + "\n" + url);
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body);
        if (call.getString("title") != null) send.putExtra(Intent.EXTRA_SUBJECT, call.getString("title"));
        Intent chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(chooser);
        call.resolve();
    }

    // ─── Aggiornamenti da GitHub (vedi ParkMapUpdater) ───

    @PluginMethod
    public void updateInfo(PluginCall call) {
        JSObject result = new JSObject();
        result.put("canInstall", ParkMapUpdater.canInstall(getContext()));
        result.put("fdroid", ParkMapUpdater.fromFDroid(getContext()));
        call.resolve(result);
    }

    @PluginMethod
    public void checkUpdate(final PluginCall call) {
        new Thread(() -> {
            try {
                call.resolve(JSObject.fromJSONObject(ParkMapUpdater.latest()));
            } catch (Exception e) {
                call.reject(e.getMessage() != null ? e.getMessage() : "network");
            }
        }, "parkmap-update-check").start();
    }

    @PluginMethod
    public void startUpdate(PluginCall call) {
        Context ctx = getContext();
        JSObject result = new JSObject();
        if (!ParkMapUpdater.canInstall(ctx)) {
            // Android chiede di consentire una volta l'installazione di app da ParkMap
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            result.put("needsPermission", true);
            call.resolve(result);
            return;
        }
        ParkMapUpdater.start(ctx.getApplicationContext(), call.getString("url"), call.getString("version"));
        result.put("started", true);
        call.resolve(result);
    }

    @PluginMethod
    public void updateStatus(PluginCall call) {
        JSObject result = new JSObject();
        result.put("phase", ParkMapUpdater.phase);
        result.put("progress", (double) ParkMapUpdater.progress);
        result.put("error", ParkMapUpdater.error);
        result.put("detail", ParkMapUpdater.detail);
        call.resolve(result);
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", getContext().getPackageName(), null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
    }
}
