package com.bloqueador.sites

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Receptor de administrador de dispositivo.
 *
 * Enquanto este app estiver registrado como administrador,
 * o Android BLOQUEIA a desinstalação pela Play Store ou pela tela
 * de "Apps instalados" nas Configurações.
 *
 * Para desinstalar, o usuário precisa primeiro ir em:
 *   Configurações → Segurança → Administradores de dispositivo
 *   → Desativar "Bloqueador"
 *
 * O método onDisableRequested() é chamado nessa tela e pode exibir
 * uma mensagem de aviso antes da confirmação.
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(
            context,
            "🔒 Proteção ativada — desinstalação bloqueada",
            Toast.LENGTH_LONG
        ).show()
    }

    /** Exibe aviso quando o usuário tenta remover o status de admin. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "⚠️ Ao desativar, qualquer pessoa poderá desinstalar o Bloqueador. Tem certeza?"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(
            context,
            "⚠️ Proteção removida — app pode ser desinstalado",
            Toast.LENGTH_LONG
        ).show()
    }
}
