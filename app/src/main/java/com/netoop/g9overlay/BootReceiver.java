package com.netoop.g9overlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Inicia o overlay no boot, se a opção "Iniciar com o sistema" estiver ligada. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        AppLog.init(context);
        Prefs p = new Prefs(context);
        if (!p.autostart()) return;
        AppLog.log("boot", "BOOT_COMPLETED: iniciando overlay");
        try {
            context.startForegroundService(new Intent(context, OverlayService.class));
        } catch (Exception e) {
            AppLog.log("boot", "falha ao iniciar: " + e);
        }
    }
}
