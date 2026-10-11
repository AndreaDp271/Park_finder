package io.github.andreadp271.parkmap;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Ponte tra la pagina e il codice nativo. Dalla pagina:
 *   Capacitor.nativePromise('ParkMapNative', 'setCar', { car: '<json>' | null })
 *   Capacitor.nativePromise('ParkMapNative', 'getCar')            -> { car: '<json>' | null }
 *   Capacitor.nativePromise('ParkMapNative', 'takePendingAction') -> { action: 'saveCar' | 'showCar' | null }
 */
@CapacitorPlugin(name = "ParkMapNative")
public class ParkMapNativePlugin extends Plugin {

    @PluginMethod
    public void setCar(PluginCall call) {
        ParkMapState.setCarJson(getContext(), call.getString("car"));
        call.resolve();
    }

    @PluginMethod
    public void getCar(PluginCall call) {
        JSObject result = new JSObject();
        result.put("car", ParkMapState.getCarJson(getContext()));
        call.resolve(result);
    }

    @PluginMethod
    public void takePendingAction(PluginCall call) {
        JSObject result = new JSObject();
        result.put("action", ParkMapState.takePendingAction(getContext()));
        call.resolve(result);
    }
}
