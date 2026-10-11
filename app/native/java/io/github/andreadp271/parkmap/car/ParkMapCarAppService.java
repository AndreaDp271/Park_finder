package io.github.andreadp271.parkmap.car;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

/** Punto di ingresso di Android Auto (categoria "parcheggio"). */
public final class ParkMapCarAppService extends CarAppService {

    @NonNull
    @Override
    public HostValidator createHostValidator() {
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        }
        // Solo gli host ufficiali (Android Auto, Android Automotive) firmati da Google
        return new HostValidator.Builder(getApplicationContext())
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build();
    }

    @NonNull
    @Override
    public Session onCreateSession() {
        return new Session() {
            @NonNull
            @Override
            public Screen onCreateScreen(@NonNull Intent intent) {
                return new NearbyParkingScreen(getCarContext());
            }
        };
    }
}
