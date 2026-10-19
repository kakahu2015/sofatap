package com.chekusu.hosttap;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Single-screen HostTap phone controller. */
public final class MainActivity extends Activity {
    private HostTapRemoteView remotePage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff0a0d11);
        getWindow().setNavigationBarColor(0xff0a0d11);
        getWindow().getDecorView().setSystemUiVisibility(0);
        remotePage = new HostTapRemoteView(this, new HostTapPreferences(this));
        setContentView(remotePage);
        handlePairingIntent(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        if (remotePage != null) remotePage.onVisible();
    }

    @Override protected void onPause() {
        if (remotePage != null) remotePage.onHidden();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePairingIntent(intent);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != HostTapRemoteView.REQUEST_QR_SCAN || resultCode != RESULT_OK || data == null) return;
        String link = data.getStringExtra(QrScannerActivity.EXTRA_PAIRING_LINK);
        if (link != null && remotePage != null) remotePage.acceptScannedPairingLink(link);
    }

    private void handlePairingIntent(Intent intent) {
        if (intent == null || intent.getData() == null || remotePage == null) return;
        Uri data = intent.getData();
        if ("sofatap".equalsIgnoreCase(data.getScheme()) && "pair".equals(data.getHost()))
            remotePage.setPairingLink(data.toString());
    }
}
