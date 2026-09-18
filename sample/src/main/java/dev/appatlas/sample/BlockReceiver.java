package dev.appatlas.sample;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/** Wedges the main thread for longer than the broadcast ANR timeout. */
public class BlockReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SystemClock.sleep(40_000L);
    }
}
