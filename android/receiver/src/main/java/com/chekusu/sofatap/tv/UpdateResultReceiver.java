package com.chekusu.sofatap.tv;

import android.content.*;

/** Explicit non-exported target of the installer-owned PendingIntent. */
public final class UpdateResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { ReceiverUpdates.get(context).installerResult(intent); }
}
