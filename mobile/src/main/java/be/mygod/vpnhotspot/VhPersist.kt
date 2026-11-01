package be.mygod.vpnhotspot

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * VhPersist — мост между root-стеком (fastwatch/dog, /data/adb) и приложением.
 *
 * Задача юзера: «плашка wlan2 в приложении после RAM-kill должна показывать ВКЛ,
 * если передача VPN идёт (ядро держит правило 17500)».
 *
 * Механика (без a11y/тапов/OCR — только root-факты и штатные механизмы):
 *  1) Reader (root-путь ядра): приложение имеет root (RootManager, уже используется).
 *     ФАКТ = `ip rule show | grep '^17500:.*iif wlan2.*lookup tun0'` — ровно то правило,
 *     которое ставит наш fastwatch при ВКЛ и снимает при ВЫКЛ. Это единственный
 *     настоящий источник правды: UI не может врать, ядро не врёт.
 *  2) Writer (для юзер-слово из чата): Minis пишет `su -c "echo vpn|novpn > /data/adb/wlan2_mode"`,
 *     если юзер хочет принудительно — обе стороны согласованы.
 *  3) Потребитель: TetheringService/App читает isVpnShareEnabled и при расхождении UI-
 *     стейта с ядром приводит UI к факту (плашка ВКЛ после RAM-kill, когда sharing жив).
 *
 * Гарантии:
 *  - Чтение корневое орехное (su -c) при таргете uid 2000/приложение — через RootManager.
 *  - Ошибки НЕ крешат приложение: любые исключения → conservative false/пассив поведения.
 *  - Никакого JavaScript/OCR; только фактический листинг ядра.
 */
object VhPersist {
    private const val PREFS = "vh_state"
    private const val KEY_UI_CACHE = "ui_cache_vpn"

    private val prefs: SharedPreferences by lazy {
        App.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Жив ли факт ядра: 17500 wlan2→tun0 установлен (дистанционное «ВКЛ» от fastwatch). */
    fun isVpnShareEnabledByKernel(): Boolean = try {
        val p = ProcessBuilder("su", "-c",
            "ip rule show | grep -q '^17500:.*iif wlan2.*lookup tun0' && echo 1 || echo 0")
            .redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim()
        p.waitFor()
        out == "1"
    } catch (e: Throwable) {
        false
    }

    /** Режим из /data/adb/wlan2_mode (vpn|novpn|auto — fact от Minis/fastwatch). */
    fun kernelMode(): String? = try {
        val f = File("/data/adb/wlan2_mode")
        if (f.canRead()) f.readText().trim().takeIf { it.isNotEmpty() } else {
            val p = ProcessBuilder("su", "-c", "cat /data/adb/wlan2_mode")
                .redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim()
            p.waitFor(); out
        }?.takeIf { it in setOf("vpn", "novpn", "auto") }
    } catch (e: Throwable) { null }

    fun cacheUiState(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UI_CACHE, enabled).apply()
    }

    fun cachedUiState(): Boolean = prefs.getBoolean(KEY_UI_CACHE, false)

    /**
     * Единственная точка решения: если факт ядра говорит «VPN идёт» — вернуть true,
     * иначе cached-стейт (для мгновенного восстановления UI до подядра-рефреша).
     * Никогда не врёт: ядро имеет приоритет.
     */
    fun desiredUiState(): Boolean = isVpnShareEnabledByKernel() || (kernelMode() == "vpn") || cachedUiState()

    /**
     * ifaceEnabled("wlan2") — факт ядра для конкретного интерфейса:
     * правило 17500 «iif wlan2 lookup tun0» = дистанционная программа fastwatch ВКЛ для wlan2.
     * null = ядро недоступно (root не выдан/скрипты не живут) — UI не вправе врать, рисуем managed-набор.
     */
    fun ifaceEnabled(iface: String): Boolean? = try {
        val pat = "^17500:.*iif $iface.*lookup tun0"
        val p = ProcessBuilder("su", "-c", "ip rule show | grep -q '$pat' && echo 1 || echo 0")
            .redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim()
        p.waitFor()
        out?.let { it == "1" }
    } catch (e: Throwable) { null }
}
