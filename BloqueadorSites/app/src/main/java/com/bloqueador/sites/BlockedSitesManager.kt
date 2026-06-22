package com.bloqueador.sites

import android.content.Context
import android.content.SharedPreferences

/**
 * Gerencia a lista persistente de domínios bloqueados (SharedPreferences).
 */
object BlockedSitesManager {

    private const val PREFS = "blocked_sites"
    private const val KEY_SITES = "sites"

    fun getBlockedSites(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_SITES, emptySet()) ?: emptySet()
    }

    /**
     * Adiciona um site à lista. Aceita URLs completas ou domínios.
     * Retorna o domínio limpo ou null se inválido.
     */
    fun addSite(context: Context, input: String): String? {
        val domain = cleanDomain(input) ?: return null
        val current = getBlockedSites(context).toMutableSet()
        current.add(domain)
        prefs(context).edit().putStringSet(KEY_SITES, current).apply()
        return domain
    }

    fun removeSite(context: Context, domain: String) {
        val current = getBlockedSites(context).toMutableSet()
        current.remove(domain)
        prefs(context).edit().putStringSet(KEY_SITES, current).apply()
    }

    fun clearAll(context: Context) {
        prefs(context).edit().remove(KEY_SITES).apply()
    }

    /**
     * Verifica se o domínio (ou um de seus pais) está na blocklist.
     */
    fun isBlocked(domain: String, blocked: Set<String>): Boolean {
        val lower = domain.lowercase().trim()
        return blocked.any { b ->
            val bl = b.lowercase().trim()
            lower == bl || lower.endsWith(".$bl")
        }
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private fun cleanDomain(input: String): String? {
        var d = input.trim().lowercase()
        d = d.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        d = d.substringBefore("/").substringBefore("?").substringBefore("#")
        if (d.isEmpty() || !d.contains('.') || d.length > 253) return null
        // Valida caracteres permitidos em domínio
        if (!d.matches(Regex("^[a-z0-9.\\-]+$"))) return null
        return d
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
