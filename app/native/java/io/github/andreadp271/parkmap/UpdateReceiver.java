package io.github.andreadp271.parkmap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Esito dell'installazione dell'aggiornamento: se serve la conferma dell'utente apre la schermata di sistema. */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                ParkMapUpdater.finish(null, null);
                break;
            default: {
                String raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (raw == null) raw = "";
                String code = raw.contains("DOWNGRADE") ? "downgrade"
                    : raw.contains("INSUFFICIENT_STORAGE") ? "storage"
                    : (raw.contains("SIGNATURE") || raw.contains("UPDATE_INCOMPATIBLE")) ? "incompatible"
                    : "failed";
                ParkMapUpdater.finish(code, raw.isEmpty() ? null : raw);
            }
        }
    }
}
