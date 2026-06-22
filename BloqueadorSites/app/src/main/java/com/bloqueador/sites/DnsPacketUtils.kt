package com.bloqueador.sites

/**
 * Utilitários para parsing e construção de pacotes DNS sobre IP/UDP.
 *
 * Estrutura de um pacote IP/UDP/DNS:
 *  [IP Header: 20 bytes] [UDP Header: 8 bytes] [DNS Payload: variável]
 */
object DnsPacketUtils {

    private const val UDP_HEADER_SIZE = 8
    private const val DNS_HEADER_SIZE = 12
    private const val PROTOCOL_UDP = 17
    private const val PORT_DNS = 53

    // ─────────────────────────────────────────────
    // Verificação
    // ─────────────────────────────────────────────

    /**
     * Retorna true se o pacote IP raw é uma query DNS (UDP porta 53, QR=0).
     */
    fun isDnsQuery(packet: ByteArray, length: Int): Boolean {
        if (length < 28 + DNS_HEADER_SIZE) return false

        // Versão IPv4
        val version = (packet[0].toInt() and 0xFF) shr 4
        if (version != 4) return false

        // Protocolo UDP
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTOCOL_UDP) return false

        val ihl = ipHeaderLen(packet)

        // Porta destino = 53
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or
                (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != PORT_DNS) return false

        // Bit QR = 0 → query
        val dnsStart = ihl + UDP_HEADER_SIZE
        if (dnsStart + 4 > length) return false
        val qr = (packet[dnsStart + 2].toInt() and 0xFF) shr 7
        return qr == 0
    }

    // ─────────────────────────────────────────────
    // Extração de domínio
    // ─────────────────────────────────────────────

    /**
     * Extrai o nome de domínio da seção de perguntas do DNS.
     */
    fun extractDomain(packet: ByteArray, length: Int): String? {
        return try {
            val dnsStart = ipHeaderLen(packet) + UDP_HEADER_SIZE
            // Pula o cabeçalho DNS (12 bytes) e vai para a seção de perguntas
            var pos = dnsStart + DNS_HEADER_SIZE
            val sb = StringBuilder()

            while (pos < length) {
                val labelLen = packet[pos].toInt() and 0xFF
                pos++
                if (labelLen == 0) break
                // Ponteiro de compressão — ignora por simplicidade
                if (labelLen and 0xC0 == 0xC0) {
                    pos++; break
                }
                if (sb.isNotEmpty()) sb.append('.')
                repeat(labelLen) {
                    if (pos < length) sb.append((packet[pos++].toInt() and 0xFF).toChar())
                }
            }
            sb.toString().lowercase().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────────
    // Construção de respostas
    // ─────────────────────────────────────────────

    /**
     * Constrói uma resposta NXDOMAIN para a query original.
     * Troca src/dst IP e porta, seta flags NXDOMAIN.
     */
    fun buildNxdomainResponse(packet: ByteArray, length: Int): ByteArray {
        val ihl = ipHeaderLen(packet)
        val dnsStart = ihl + UDP_HEADER_SIZE

        val response = packet.copyOf(length)

        // Troca IP origem ↔ destino (bytes 12–15 e 16–19)
        for (i in 0..3) {
            val tmp = response[12 + i]
            response[12 + i] = response[16 + i]
            response[16 + i] = tmp
        }

        // Troca porta UDP origem ↔ destino (bytes ihl e ihl+2)
        for (i in 0..1) {
            val tmp = response[ihl + i]
            response[ihl + i] = response[ihl + 2 + i]
            response[ihl + 2 + i] = tmp
        }

        // Flags DNS: QR=1 (resposta), RA=1, RCODE=3 (NXDOMAIN)
        response[dnsStart + 2] = 0x81.toByte() // QR=1, RD=1
        response[dnsStart + 3] = 0x83.toByte() // RA=1, RCODE=3

        // Zera contadores de respostas (answers, authority, additional)
        for (i in 6..11) response[dnsStart + i] = 0

        // Zera checksum UDP (opcional em IPv4)
        response[ihl + 6] = 0
        response[ihl + 7] = 0

        // Recalcula checksum IP
        recalcIpChecksum(response, ihl)

        return response
    }

    /**
     * Extrai apenas o payload DNS de um pacote IP/UDP/DNS.
     */
    fun extractDnsPayload(packet: ByteArray, length: Int): ByteArray {
        val dnsStart = ipHeaderLen(packet) + UDP_HEADER_SIZE
        return packet.copyOfRange(dnsStart, length)
    }

    /**
     * Lê a porta UDP de origem do pacote de query.
     */
    fun getSourcePort(packet: ByteArray): Int {
        val ihl = ipHeaderLen(packet)
        return ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
    }

    /**
     * Lê o IP de origem do pacote (4 bytes).
     */
    fun getSourceIp(packet: ByteArray): ByteArray = packet.copyOfRange(12, 16)

    /**
     * Lê o IP de destino do pacote (4 bytes).
     */
    fun getDestIp(packet: ByteArray): ByteArray = packet.copyOfRange(16, 20)

    /**
     * Monta um pacote IP/UDP completo com o payload DNS fornecido.
     *
     * @param dnsPayload  resposta DNS crua
     * @param srcIp       IP de origem (4 bytes) — nosso DNS virtual
     * @param dstIp       IP de destino (4 bytes) — dispositivo do usuário
     * @param srcPort     porta de origem (53)
     * @param dstPort     porta destino (porta efêmera da query original)
     */
    fun buildDnsResponsePacket(
        dnsPayload: ByteArray,
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int
    ): ByteArray {
        val udpLen = UDP_HEADER_SIZE + dnsPayload.size
        val totalLen = 20 + udpLen
        val pkt = ByteArray(totalLen)

        // ── Cabeçalho IP ──────────────────────────
        pkt[0] = 0x45.toByte()                     // Versão=4, IHL=5
        pkt[1] = 0                                  // DSCP/ECN
        pkt[2] = (totalLen shr 8).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        pkt[4] = 0; pkt[5] = 1                     // ID
        pkt[6] = 0x40.toByte()                     // Don't Fragment
        pkt[7] = 0
        pkt[8] = 64                                 // TTL
        pkt[9] = PROTOCOL_UDP.toByte()
        // Checksum calculado abaixo
        srcIp.copyInto(pkt, 12)
        dstIp.copyInto(pkt, 16)

        // ── Cabeçalho UDP ─────────────────────────
        pkt[20] = (srcPort shr 8).toByte()
        pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = (dstPort shr 8).toByte()
        pkt[23] = (dstPort and 0xFF).toByte()
        pkt[24] = (udpLen shr 8).toByte()
        pkt[25] = (udpLen and 0xFF).toByte()
        pkt[26] = 0; pkt[27] = 0                   // Checksum UDP zerado

        // ── Payload DNS ───────────────────────────
        dnsPayload.copyInto(pkt, 28)

        // ── Checksum IP ───────────────────────────
        recalcIpChecksum(pkt, 20)

        return pkt
    }

    // ─────────────────────────────────────────────
    // Helpers privados
    // ─────────────────────────────────────────────

    private fun ipHeaderLen(packet: ByteArray): Int = (packet[0].toInt() and 0x0F) * 4

    private fun recalcIpChecksum(packet: ByteArray, ihl: Int) {
        // Zera campo de checksum antes de calcular
        packet[10] = 0; packet[11] = 0
        var sum = 0
        var i = 0
        while (i < ihl - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val checksum = sum.inv() and 0xFFFF
        packet[10] = (checksum shr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }
}
