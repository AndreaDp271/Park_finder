package io.github.andreadp271.parkmap;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // I plugin locali vanno registrati prima di super.onCreate
        registerPlugin(ParkMapNativePlugin.class);
        super.onCreate(savedInstanceState);
        ParkMapState.storePendingAction(this, getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        ParkMapState.storePendingAction(this, intent);
    }
}
