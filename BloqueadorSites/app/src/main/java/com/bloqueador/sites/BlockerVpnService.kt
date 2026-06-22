package com.bloqueador.sites

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
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

class BlockerVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.bloqueador.sites.START"
        const val ACTION_STOP  = "com.bloqueador.sites.STOP"

        private const val TAG        = "BlockerVPN"
        private const val NOTIF_ID   = 1001
        private const val CHANNEL_ID = "bloqueador_vpn"

        private const val VPN_ADDR   = "10.0.0.2"
        private const val VPN_DNS    = "10.0.0.1"
        private const val REAL_DNS   = "8.8.8.8"
        private const val DNS_PORT   = 53

        const val PREFS_STATE = "vpn_state"
        const val KEY_RUNNING = "running"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private var readerThread: Thread? = null
    private lateinit var dnsPool: ExecutorService

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> { stopVpn(); START_NOT_STICKY }
            else        -> { startVpn(); START_STICKY    }
        }
    }

    override fun onRevoke() {
        stopVpn()
    }

    override fun onDestroy() {
        running = false
        readerThread?.interrupt()
        super.onDestroy()
    }

    private fun startVpn() {
        if (running) return

        createNotificationChannel()

        // Android 14+ exige o tipo explicitamente
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        try {
            vpnInterface = Builder()
                .setSession("BloqueadorSites")
                .addAddress(VPN_ADDR, 32)
                .addDnsServer(VPN_DNS)
                .addRoute(VPN_DNS, 32)
                .setMtu(1500)
                .establish()
                ?: throw IllegalStateException("Não foi possível criar interface VPN")

            dnsPool = Executors.newCachedThreadPool()
            running = true
            saveRunningState(true)
            Log.i(TAG, "VPN iniciada")

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
    }

    private fun packetLoop() {
        val fd  = vpnInterface?.fileDescriptor ?: return
        val inp = FileInputStream(fd)
        val out = FileOutputStream(fd)
        val buf = ByteArray(32767)

        while (running) {
            try {
                val len = inp.read(buf)
                if (len <= 0) { Thread.sleep(5); continue }
                if (!DnsPacketUtils.isDnsQuery(buf, len)) continue

                val domain  = DnsPacketUtils.extractDomain(buf, len)
                val blocked = BlockedSitesManager.getBlockedSites(this)
                val pkt     = buf.copyOf(len)

                if (domain != null && BlockedSitesManager.isBlocked(domain, blocked)) {
                    val resp = DnsPacketUtils.buildNxdomainResponse(pkt, len)
                    synchronized(out) { out.write(resp) }
                } else {
                    dnsPool.submit { forwardDns(pkt, len, out) }
                }

            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                Log.e(TAG, "Erro no loop", e)
            }
        }
    }

    private fun forwardDns(packet: ByteArray, length: Int, out: FileOutputStream) {
        try {
            val origSrcIp   = DnsPacketUtils.getSourceIp(packet)
            val origDstIp   = DnsPacketUtils.getDestIp(packet)
            val origSrcPort = DnsPacketUtils.getSourcePort(packet)
            val dnsPayload  = DnsPacketUtils.extractDnsPayload(packet, length)

            val socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 2000

            val realDnsAddr = InetAddress.getByName(REAL_DNS)
            socket.send(DatagramPacket(dnsPayload, dnsPayload.size, realDnsAddr, DNS_PORT))

            val respBuf = ByteArray(4096)
            val respPkt = DatagramPacket(respBuf, respBuf.size)
            socket.receive(respPkt)
            socket.close()

            val fullResp = DnsPacketUtils.buildDnsResponsePacket(
                dnsPayload = respBuf.copyOf(respPkt.length),
                srcIp      = origDstIp,
                dstIp      = origSrcIp,
                srcPort    = DNS_PORT,
                dstPort    = origSrcPort
            )

            synchronized(out) { out.write(fullResp) }

        } catch (e: Exception) {
            Log.w(TAG, "DNS forward falhou: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Bloqueador de Sites", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Mantém o bloqueio ativo em segundo plano" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ Bloqueador Ativo")
            .setContentText("Filtrando sites indesejados")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(intent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun saveRunningState(running: Boolean) {
        getSharedPreferences(PREFS_STATE, MODE_PRIVATE)
            .edit().putBoolean(KEY_RUNNING, running).apply()
    }
}
