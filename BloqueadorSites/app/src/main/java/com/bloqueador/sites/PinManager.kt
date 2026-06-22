package com.bloqueador.sites

import android.content.Context
import android.content.SharedPreferences

/**
 * Gerencia o PIN de acesso ao painel de controle.
 * Padrão: 1234. Troque imediatamente após instalar.
 */
object PinManager {

    private const val PREFS = "pin_store"
    private const val KEY_PIN = "pin"
    private const val DEFAULT_PIN = "1234"

    fun getPin(context: Context): String =
        prefs(context).getString(KEY_PIN, DEFAULT_PIN) ?: DEFAULT_PIN

    fun verify(context: Context, input: String): Boolean =
        input == getPin(context)

    /**
     * Salva novo PIN. Retorna false se o formato for inválido (4–8 dígitos).
     */
    fun setPin(context: Context, newPin: String): Boolean {
        if (newPin.length < 4 || newPin.length > 8) return false
        if (!newPin.all { it.isDigit() }) return false
        prefs(context).edit().putString(KEY_PIN, newPin).apply()
        return true
    }

    /** True se o PIN ainda é o padrão de fábrica. */
    fun isDefaultPin(context: Context) = getPin(context) == DEFAULT_PIN

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
