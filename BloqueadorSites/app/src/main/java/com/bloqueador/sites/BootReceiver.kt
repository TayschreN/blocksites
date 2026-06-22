package com.bloqueador.sites

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reinicia automaticamente o serviço VPN após o dispositivo ser ligado,
 * caso ele estivesse ativo antes do desligamento.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        val wasRunning = context
            .getSharedPreferences(BlockerVpnService.PREFS_STATE, Context.MODE_PRIVATE)
            .getBoolean(BlockerVpnService.KEY_RUNNING, false)

        if (wasRunning) {
            val vpnIntent = Intent(context, BlockerVpnService::class.java).apply {
                action = BlockerVpnService.ACTION_START
            }
            context.startForegroundService(vpnIntent)
        }
    }
}
