package be.mygod.vpnhotspot

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * VhPersist v2 (RAM-kill UX, фон-тред).
 *
 * Задача юзера: «плашка wlan2 = факт ядра; при RAM-kill она должна рисоваться ВКЛ
 * если раздача идёт; никаких блокировок UI, никаких лагов».
 *
 * Идеи v2 (fix, взамен v1, которая висела в UI-thread):
 *  - Все вызовы `su -c ip rule` — СТРОГО на фоне (singleExecutor + bounded cache 3 с).
 *  - UI потоки никогда не ждут ядра:得到的 читают только cached-значение.
 *  - Кэш-инвалидируется через 3 с и обновляется на фоне по требованию.
 *
 * Root-скрипт (fastwatch) держит правило `17500 iif wlan2 lookup tun0`
 * при ВКЛ и снимает при ВЫКЛ (управляется из чата юзера или плашки приложения).
 */
object VhPersist {
    private const val PREFS = "vh_state"
    private const val KEY_UI_CACHE = "ui_cache_vpn"
    private const val MONITOR_FILE = "/data/adb/wlan2_mode"

    /** Фон-исполнитель: допускает одновременный один запрос, не блокирует UI. */
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vh-persist-root").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    /** Счётчик запущенных запросов (bounded — больше 1 в очереди не держим). */
    private val inFlight = AtomicInteger(0)

    /** Последний кэш-снимок fact'а ядра (используется UI-thread без блокировки). */
    private data class Snap(val iface: String, val enabled: Boolean?, val ts: Long)
    private val snap = AtomicReference<Snap?>(null)

    /** TTL кэша: 3 с (быстро обновляем, но не каждый рендер). */
    private const val TTL_MS = 3000

    /** UI может прочитать мгновенно: Boolean? = факт ядра или null (неизвестно). */
    fun ifaceEnabledCached(iface: String): Boolean? {
        val s = snap.get()
        val now = System.currentTimeMillis()
        return when {
            s != null && s.iface == iface && now - s.ts < TTL_MS -> s.enabled
            else -> null
        }
    }

    /**
     * Фоновое обновление кэша (не блокирует UI). Каждый вызов отбивается по
     * TTL-времени и по AtomicInteger (не плодим потоков-сирот).
     */
    fun refreshAsync(iface: String, onUpdate: ((Boolean?) -> Unit)? = null) {
        if (inFlight.get() > 0) return   // уже работаем — пропустим, не копим очередь
        exec.execute {
            try {
                inFlight.incrementAndGet()
                val res = ifaceEnabled(iface)
                snap.set(Snap(iface, res, System.currentTimeMillis()))
                onUpdate?.invoke(res)
            } catch (e: Throwable) {
                snap.set(Snap(iface, null, System.currentTimeMillis()))
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    /**
     * Синхронное чтение ядра. Вызывать ТОЛЬКО на фоне!
     * (один-единственный допустимый поток — `exec`, перекрыт гвардией inFlight)
     */
    private fun ifaceEnabled(iface: String): Boolean? = try {
        val pat = "^17500:.*iif $iface.*lookup tun0"
        val p = ProcessBuilder("su", "-c", "ip rule show | grep -q '$pat' && echo 1 || echo 0")
            .redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).readLine()?.trim()
        p.waitFor()
        out?.let { it == "1" }
    } catch (e: Throwable) { null }

    /** Режим (vpn|novpn|auto) — только кэш-файл, мгновенно, без exec. */
    fun kernelMode(): String? = try {
        val f = File(MONITOR_FILE)
        if (f.canRead()) f.readText().trim().takeIf { it.isNotEmpty() } else null
    } catch (e: Throwable) { null }

    private val prefs: SharedPreferences by lazy {
        App.app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun cacheUiState(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UI_CACHE, enabled).apply()
    }

    fun cachedUiState(): Boolean = prefs.getBoolean(KEY_UI_CACHE, false)

    /**
     * App.onCreate: хотел был синхронизироваться с ядром — v1 делала это на
     * main-thread (это и был лаг). v2: фоновый одиночный запрос, результат
     * просто в кэш; UI при первом рендерисе возьмёт его из ifaceEnabledCached.
     */
    fun warmUpOnLaunch(iface: String) {
        refreshAsync(iface) { res ->
            res?.let { cacheUiState(it) }
        }
    }
}
