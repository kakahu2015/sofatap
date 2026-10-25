package com.chekusu.sofatap.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts only the foreground listener; it never interrupts the TV with an Activity. */
public final class StartupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && new ReceiverStartup(context).shouldStartFor(intent.getAction())) {
            ReceiverStartup.start(context);
        }
    }
}
