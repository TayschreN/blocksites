package com.bloqueador.sites

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bloqueador.sites.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_VPN   = 100
        private const val REQ_ADMIN = 101
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComp: ComponentName

    // Sessão desbloqueada por PIN (reseta ao fechar o app)
    private var unlocked = false

    // ─────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dpm       = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComp = ComponentName(this, DeviceAdminReceiver::class.java)

        setupClickListeners()
        showPinDialog()   // exige PIN logo de início
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    // ─────────────────────────────────────────────────────────────────
    // Configuração de listeners
    // ─────────────────────────────────────────────────────────────────

    private fun setupClickListeners() {

        binding.btnToggleVpn.setOnClickListener {
            requireUnlock { toggleVpn() }
        }

        binding.btnAddSite.setOnClickListener {
            requireUnlock { showAddSiteDialog() }
        }

        binding.btnRequestAdmin.setOnClickListener {
            activateDeviceAdmin()
        }

        binding.btnChangePin.setOnClickListener {
            requireUnlock { showChangePinDialog() }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // PIN
    // ─────────────────────────────────────────────────────────────────

    private fun showPinDialog(onSuccess: (() -> Unit)? = null) {
        val input = buildPinInput("Digite o PIN")

        AlertDialog.Builder(this)
            .setTitle("🔐 Acesso Restrito")
            .setMessage("Insira o PIN para acessar o painel de controle.")
            .setView(wrapWithPadding(input))
            .setCancelable(false)
            .setPositiveButton("Entrar") { _, _ ->
                if (PinManager.verify(this, input.text.toString())) {
                    unlocked = true
                    refreshUI()
                    if (PinManager.isDefaultPin(this)) {
                        toast("⚠️ Troque o PIN padrão (1234) imediatamente!")
                    }
                    onSuccess?.invoke()
                } else {
                    unlocked = false
                    refreshUI()
                    toast("PIN incorreto")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showChangePinDialog() {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        val current = buildPinInput("PIN atual")
        val newPin  = buildPinInput("Novo PIN (4–8 dígitos)")
        val confirm = buildPinInput("Confirmar novo PIN")

        ll.addView(current)
        ll.addView(newPin)
        ll.addView(confirm)

        AlertDialog.Builder(this)
            .setTitle("🔑 Alterar PIN")
            .setView(ll)
            .setPositiveButton("Salvar") { _, _ ->
                when {
                    !PinManager.verify(this, current.text.toString()) ->
                        toast("PIN atual incorreto")
                    newPin.text.toString() != confirm.text.toString() ->
                        toast("Os PINs não conferem")
                    !PinManager.setPin(this, newPin.text.toString()) ->
                        toast("PIN inválido — use 4 a 8 dígitos")
                    else ->
                        toast("✅ PIN alterado com sucesso!")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun requireUnlock(action: () -> Unit) {
        if (unlocked) action()
        else showPinDialog { action() }
    }

    // ─────────────────────────────────────────────────────────────────
    // VPN
    // ─────────────────────────────────────────────────────────────────

    private fun toggleVpn() {
        if (isVpnRunning()) {
            stopVpn()
        } else {
            val prepare = VpnService.prepare(this)
            if (prepare != null) {
                // Android pede permissão ao usuário
                startActivityForResult(prepare, REQ_VPN)
            } else {
                startVpnService()
            }
        }
    }

    private fun startVpnService() {
        startForegroundService(
            Intent(this, BlockerVpnService::class.java).apply {
                action = BlockerVpnService.ACTION_START
            }
        )
        refreshUI()
    }

    private fun stopVpn() {
        startService(
            Intent(this, BlockerVpnService::class.java).apply {
                action = BlockerVpnService.ACTION_STOP
            }
        )
        refreshUI()
    }

    private fun isVpnRunning(): Boolean =
        getSharedPreferences(BlockerVpnService.PREFS_STATE, MODE_PRIVATE)
            .getBoolean(BlockerVpnService.KEY_RUNNING, false)

    // ─────────────────────────────────────────────────────────────────
    // Admin de dispositivo
    // ─────────────────────────────────────────────────────────────────

    private fun activateDeviceAdmin() {
        if (dpm.isAdminActive(adminComp)) {
            toast("🔒 Proteção já está ativa")
            return
        }
        startActivityForResult(
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Ative para impedir que o Bloqueador seja desinstalado sem autorização."
                )
            },
            REQ_ADMIN
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN   -> if (resultCode == Activity.RESULT_OK) startVpnService()
            REQ_ADMIN -> refreshUI()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Gerenciamento de sites
    // ─────────────────────────────────────────────────────────────────

    private fun showAddSiteDialog() {
        val input = EditText(this).apply {
            hint        = "ex: instagram.com ou tiktok.com"
            inputType   = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            maxLines    = 1
            setSingleLine()
        }

        AlertDialog.Builder(this)
            .setTitle("➕ Adicionar site bloqueado")
            .setView(wrapWithPadding(input))
            .setPositiveButton("Bloquear") { _, _ ->
                val result = BlockedSitesManager.addSite(this, input.text.toString())
                if (result != null) {
                    toast("✅ Bloqueado: $result")
                    refreshUI()
                } else {
                    toast("❌ Domínio inválido — ex: instagram.com")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmRemoveSite(domain: String) {
        AlertDialog.Builder(this)
            .setTitle("Remover bloqueio")
            .setMessage("Desbloquear \"$domain\"?")
            .setPositiveButton("Remover") { _, _ ->
                BlockedSitesManager.removeSite(this, domain)
                toast("$domain desbloqueado")
                refreshUI()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────
    // Atualização da UI
    // ─────────────────────────────────────────────────────────────────

    private fun refreshUI() {
        val vpnOn    = isVpnRunning()
        val adminOn  = dpm.isAdminActive(adminComp)
        val sites    = BlockedSitesManager.getBlockedSites(this).sorted()

        // Status VPN
        binding.tvVpnStatus.apply {
            text      = if (vpnOn) "🟢 Bloqueio ATIVO" else "🔴 Bloqueio INATIVO"
            setTextColor(if (vpnOn) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
        }

        // Status admin
        binding.tvAdminStatus.apply {
            text      = if (adminOn) "🔒 Proteção anti-desinstalação ATIVA"
                        else "⚠️ Sem proteção — app pode ser desinstalado"
            setTextColor(if (adminOn) Color.parseColor("#1565C0") else Color.parseColor("#F57F17"))
        }

        // Botão VPN
        binding.btnToggleVpn.text = if (vpnOn) "⏹ Desativar Bloqueio" else "▶️ Ativar Bloqueio"

        // Botão admin
        binding.btnRequestAdmin.isEnabled = !adminOn
        binding.btnRequestAdmin.text = if (adminOn) "✅ Proteção já ativa" else "🔒 Ativar Proteção Admin"

        // Botões que exigem unlock
        binding.btnAddSite.isEnabled   = unlocked
        binding.btnChangePin.isEnabled = unlocked

        // Contador
        binding.tvSiteCount.text = "${sites.size} site(s) bloqueado(s)"

        // Lista de sites
        renderSiteList(sites)
    }

    private fun renderSiteList(sites: List<String>) {
        val container = binding.siteListContainer
        container.removeAllViews()

        if (sites.isEmpty()) {
            val empty = TextView(this).apply {
                text      = "Nenhum site bloqueado ainda.\nToque em \"Adicionar Site\" para começar."
                textSize  = 13f
                setTextColor(Color.parseColor("#9E9E9E"))
                gravity   = Gravity.CENTER
                setPadding(16, 24, 16, 24)
            }
            container.addView(empty)
            return
        }

        sites.forEach { domain ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity     = Gravity.CENTER_VERTICAL
                setPadding(12, 12, 12, 12)
            }

            val tv = TextView(this).apply {
                text      = domain
                textSize  = 14f
                setTextColor(Color.parseColor("#212121"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btn = Button(this).apply {
                text      = "✕"
                textSize  = 12f
                setPadding(8, 0, 8, 0)
                setTextColor(Color.parseColor("#C62828"))
                background = null
                isEnabled  = unlocked
                setOnClickListener { confirmRemoveSite(domain) }
            }

            row.addView(tv)
            row.addView(btn)

            // Divisor
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(12, 0, 12, 0) }
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            }

            container.addView(row)
            container.addView(divider)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers de UI
    // ─────────────────────────────────────────────────────────────────

    private fun buildPinInput(hint: String): EditText = EditText(this).apply {
        this.hint      = hint
        inputType      = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        maxLines       = 1
        setSingleLine()
    }

    private fun wrapWithPadding(view: View): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(view)
        }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
