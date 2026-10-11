package io.github.andreadp271.parkmap.car;

import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarLocation;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Metadata;
import androidx.car.app.model.Place;
import androidx.car.app.model.PlaceListMapTemplate;
import androidx.car.app.model.PlaceMarker;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import io.github.andreadp271.parkmap.ParkMapState;

/**
 * Schermata principale in auto: mappa (disegnata dall'auto) con i parcheggi
 * più vicini, la propria auto se salvata e il pulsante "Ho parcheggiato qui".
 * Toccando un parcheggio si avvia la navigazione con il navigatore dell'auto.
 */
public final class NearbyParkingScreen extends Screen {
    private static final int MAX_ITEMS = 6; // limite dei modelli a elenco di Android Auto

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private List<ParkingRepository.Parking> parkings = new ArrayList<>();
    private boolean loading = true;
    private String message = null;

    public NearbyParkingScreen(@NonNull CarContext carContext) {
        super(carContext);
        load();
    }

    private void load() {
        loading = true;
        message = null;
        executor.execute(() -> {
            List<ParkingRepository.Parking> found = new ArrayList<>();
            String msg = null;
            Location loc = ParkingRepository.lastLocation(getCarContext());
            if (!ParkingRepository.hasLocationPermission(getCarContext())) {
                msg = "Apri ParkMap sul telefono e consenti l'accesso alla posizione";
            } else if (loc == null) {
                msg = "Posizione non ancora disponibile: riprova tra poco";
            } else {
                try {
                    found = ParkingRepository.nearby(loc.getLatitude(), loc.getLongitude(), MAX_ITEMS);
                    if (found.isEmpty()) msg = "Nessun parcheggio pubblico nei dintorni";
                } catch (Exception e) {
                    msg = "Dati non disponibili: controlla la connessione del telefono";
                }
            }
            final List<ParkingRepository.Parking> result = found;
            final String finalMsg = msg;
            main.post(() -> {
                parkings = result;
                message = finalMsg;
                loading = false;
                invalidate();
            });
        });
    }

    private void navigateTo(double lat, double lon) {
        try {
            getCarContext().startCarApp(new Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:0,0?q=" + lat + "," + lon)));
        } catch (Exception e) {
            CarToast.makeText(getCarContext(), "Nessun navigatore disponibile", CarToast.LENGTH_LONG).show();
        }
    }

    private void parkHere() {
        Location loc = ParkingRepository.lastLocation(getCarContext());
        if (loc == null) {
            CarToast.makeText(getCarContext(), "Posizione non disponibile", CarToast.LENGTH_LONG).show();
            return;
        }
        ParkMapState.saveCarHere(getCarContext(), loc.getLatitude(), loc.getLongitude());
        CarToast.makeText(getCarContext(), "Posizione dell'auto salvata", CarToast.LENGTH_SHORT).show();
        invalidate();
    }

    private static Place place(double lat, double lon, CarColor color) {
        PlaceMarker.Builder marker = new PlaceMarker.Builder();
        if (color != null) marker.setColor(color);
        return new Place.Builder(CarLocation.create(lat, lon)).setMarker(marker.build()).build();
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        PlaceListMapTemplate.Builder builder = new PlaceListMapTemplate.Builder()
            .setTitle("Parcheggi vicini")
            .setHeaderAction(Action.APP_ICON)
            .setCurrentLocationEnabled(ParkingRepository.hasLocationPermission(getCarContext()))
            .setActionStrip(new ActionStrip.Builder()
                .addAction(new Action.Builder().setTitle("Ho parcheggiato qui").setOnClickListener(this::parkHere).build())
                .addAction(new Action.Builder().setTitle("Aggiorna").setOnClickListener(() -> { load(); invalidate(); }).build())
                .build());

        if (loading) return builder.setLoading(true).build();

        ItemList.Builder items = new ItemList.Builder();
        int count = 0;

        JSONObject car = ParkMapState.getCar(getCarContext());
        if (car != null) {
            double lat = car.optDouble("lat"), lon = car.optDouble("lon");
            String when = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(car.optLong("ts", System.currentTimeMillis())));
            long expiry = ParkMapState.getExpiry(car);
            String text = "Parcheggiata alle " + when + (expiry > 0
                ? " · sosta fino alle " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(expiry)) : "");
            items.addItem(new Row.Builder()
                .setTitle("La mia auto")
                .addText(text)
                .setMetadata(new Metadata.Builder().setPlace(place(lat, lon, CarColor.GREEN)).build())
                .setOnClickListener(() -> navigateTo(lat, lon))
                .build());
            count++;
        }

        for (ParkingRepository.Parking p : parkings) {
            if (count >= MAX_ITEMS) break;
            items.addItem(new Row.Builder()
                .setTitle(p.name)
                .addText(ParkingRepository.formatDistance(p.distance) + " · " + p.summary)
                .setMetadata(new Metadata.Builder().setPlace(place(p.lat, p.lon, CarColor.BLUE)).build())
                .setOnClickListener(() -> navigateTo(p.lat, p.lon))
                .build());
            count++;
        }

        if (count == 0) items.setNoItemsMessage(message != null ? message : "Nessun parcheggio nei dintorni");
        return builder.setItemList(items.build()).build();
    }
}
