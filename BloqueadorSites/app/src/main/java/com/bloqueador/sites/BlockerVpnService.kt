package com.bloqueador.sites

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serviço VPN local que intercepta consultas DNS.
 *
 * Funcionamento:
 *  1. Cria uma interface VPN virtual com DNS = 10.0.0.1 (nosso servidor).
 *  2. Roteia APENAS o tráfego para 10.0.0.1/32 pela VPN
 *     (todo o resto vai pela interface de rede normal).
 *  3. Lê pacotes IP/UDP/DNS da interface VPN.
 *  4. Domínio bloqueado → resposta NXDOMAIN.
 *  5. Domínio permitido → encaminha para 8.8.8.8 via socket protegido
 *     (bypass da VPN) e devolve a resposta real.
 */
class BlockerVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.bloqueador.sites.START"
        const val ACTION_STOP  = "com.bloqueador.sites.STOP"

        private const val TAG            = "BlockerVPN"
        private const val NOTIF_ID       = 1001
        private const val CHANNEL_ID     = "bloqueador_vpn"

        // Endereços virtuais dentro da VPN
        private const val VPN_ADDR       = "10.0.0.2"   // endereço da interface VPN
        private const val VPN_DNS        = "10.0.0.1"   // servidor DNS virtual (nós mesmos)
        private const val REAL_DNS       = "8.8.8.8"    // DNS real para encaminhar queries
        private const val DNS_PORT       = 53

        const val PREFS_STATE = "vpn_state"
        const val KEY_RUNNING = "running"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var readerThread: Thread? = null
    private lateinit var dnsPool: ExecutorService

    // ─────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopVpn(); START_NOT_STICKY }
            else        -> { startVpn(); START_STICKY    }
        }
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revogada pelo sistema")
        stopVpn()
    }

    override fun onDestroy() {
        running = false
        readerThread?.interrupt()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────
    // Inicialização
    // ─────────────────────────────────────────────────────────────────

    private fun startVpn() {
        if (running) return

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        try {
            vpnInterface = Builder()
                .setSession("BloqueadorSites")
                .addAddress(VPN_ADDR, 32)
                .addDnsServer(VPN_DNS)
                // Roteia SOMENTE tráfego para o DNS virtual pela VPN
                .addRoute(VPN_DNS, 32)
                .setMtu(1500)
                .establish()
                ?: throw IllegalStateException("Não foi possível criar interface VPN")

            dnsPool = Executors.newCachedThreadPool()
            running = true

            saveRunningState(true)
            Log.i(TAG, "VPN iniciada — DNS virtual em $VPN_DNS")

            readerThread = Thread(::packetLoop, "VPN-Reader").also { it.start() }

        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar VPN", e)
            stopSelf()
        }
    }

    private fun stopVpn() {
        running = false
        if (::dnsPool.isInitialized) dnsPool.shutdownNow()
        readerThread?.interrupt()
        vpnInterface?.close()
        vpnInterface = null
        saveRunningState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN encerrada")
    }

    // ─────────────────────────────────────────────────────────────────
    // Loop de leitura de pacotes
    // ─────────────────────────────────────────────────────────────────

    private fun packetLoop() {
        val fd   = vpnInterface?.fileDescriptor ?: return
        val inp  = FileInputStream(fd)
        val out  = FileOutputStream(fd)
        val buf  = ByteArray(32767)

        while (running) {
            try {
                val len = inp.read(buf)
                if (len <= 0) { Thread.sleep(5); continue }

                if (!DnsPacketUtils.isDnsQuery(buf, len)) continue

                val domain = DnsPacketUtils.extractDomain(buf, len)
                val blocked = BlockedSitesManager.getBlockedSites(this)

                // Cópia imutável do pacote para o thread pool
                val pkt = buf.copyOf(len)

                if (domain != null && BlockedSitesManager.isBlocked(domain, blocked)) {
                    Log.d(TAG, "BLOQUEADO: $domain")
                    val resp = DnsPacketUtils.buildNxdomainResponse(pkt, len)
                    synchronized(out) { out.write(resp) }
                } else {
                    Log.d(TAG, "PERMITIDO: $domain")
                    dnsPool.submit { forwardDns(pkt, len, out) }
                }

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                Log.e(TAG, "Erro no loop de pacotes", e)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Encaminhamento DNS para servidor real
    // ─────────────────────────────────────────────────────────────────

    private fun forwardDns(packet: ByteArray, length: Int, out: FileOutputStream) {
        try {
            // IPs e porta originais do pacote de query
            val origSrcIp   = DnsPacketUtils.getSourceIp(packet)   // cliente → resposta vai para cá
            val origDstIp   = DnsPacketUtils.getDestIp(packet)     // nosso DNS virtual → será src da resposta
            val origSrcPort = DnsPacketUtils.getSourcePort(packet)  // porta efêmera do cliente

            val dnsPayload = DnsPacketUtils.extractDnsPayload(packet, length)

            // Socket protegido — bypassa a VPN para alcançar a internet
            val socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 2000

            val realDnsAddr = InetAddress.getByName(REAL_DNS)
            socket.send(DatagramPacket(dnsPayload, dnsPayload.size, realDnsAddr, DNS_PORT))

            val respBuf = ByteArray(4096)
            val respPkt = DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPkt)
            socket.close()

            val dnsResp = respBuf.copyOf(respPkt.length)

            // Monta IP+UDP+DNS com src=origDstIp (DNS virtual) e dst=origSrcIp (cliente)
            val fullResp = DnsPacketUtils.buildDnsResponsePacket(
                dnsPayload  = dnsResp,
                srcIp       = origDstIp,
                dstIp       = origSrcIp,
                srcPort     = DNS_PORT,
                dstPort     = origSrcPort
            )

            synchronized(out) { out.write(fullResp) }

        } catch (e: Exception) {
            // Timeout ou erro de rede — a app vai tentar novamente por conta própria
            Log.w(TAG, "DNS forward falhou: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Notificação persistente
    // ─────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Bloqueador de Sites",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Mantém o bloqueio de sites ativo em segundo plano" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Bloqueador Ativo")
            .setContentText("Filtrando sites indesejados")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ─────────────────────────────────────────────────────────────────
    // Estado persistido
    // ─────────────────────────────────────────────────────────────────

    private fun saveRunningState(running: Boolean) {
        getSharedPreferences(PREFS_STATE, MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, running).apply()
    }
}
