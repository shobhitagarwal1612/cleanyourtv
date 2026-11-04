package com.gathin.chillradio;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public final class RadioService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if ("stop".equals(intent.getStringExtra("cmd"))) {
            RadioReceiver.stopPlayer();
            stopSelf();
            return START_NOT_STICKY;
        }
        RadioReceiver.handle(getApplicationContext(), intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        RadioReceiver.stopPlayer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

