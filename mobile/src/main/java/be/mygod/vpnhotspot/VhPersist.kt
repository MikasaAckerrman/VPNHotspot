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
 * VhPersist v4 (RAM-kill UX; юзер-тап = авторитет).
 *
 * Роли:
 *  - fastwatch/dog root-стек — единственный администратор ядра (rule `17500 wlan2→tun0`);
 *  - intent-файл /data/adb/.vh_intent + /data/adb/wlan2_mode — единственный КАНТ между
 *    приложением и fastwatch (пишем su; читает fastwatch оба мира);
 *  - юзер-тап (userTap) — авторитет на 5 с: kstate-снимок мгновенно, intent фон;
 *    ядро-факт (refreshAsync) молчит всё окно и не может откатить тап;
 *  - RAM-kill: managed.isEmpty() → UI рисует kernel-fact из snap (не фиктивный ВЫКЛ).
 *
 * Никаких exec на main-thread; никаких потоков-утечек (bounded single executor).
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

    /** v-vh4: авторитет юзер-тапа (epoch ms последнего тапа; окно 5 с). */
    private val userLatch = AtomicLong(0)
    private const val LATCH_MS = 5000L

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
     *
     * v-vh4: ПЕРИОД LATCH — сразу после тапа юзера ядро-факт НЕ читается вообще:
     * ядро сходится к тапу за ~2 с (fastwatch), и ядро-факт не должен перезаписать
     * юзер-решение в течение этого окна (корейский глюк v-vh3: тап «откатывался»).
     */
    fun refreshAsync(iface: String, onUpdate: ((Boolean?) -> Unit)? = null) {
        if (isLatched() || inFlight.get() > 0) return   // latch > очередь > читаем ядро
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

    fun cacheUiState(enabled: Boolean, syncIntent: Boolean = true) {
        prefs.edit().putBoolean(KEY_UI_CACHE, enabled).apply()
        // v-vh5: intent-файл — authority юзера/чата. Пишем его ТОЛЬКО по явному
        // решению юзера (userTap) и по чат-командам. Kernel-fact (RAM-kill reload)
        // обновляет только отображение, НЕ intent (иначе «VPN сам включается»
        // — warmUp перезаписывал intent-файл фактом ядра, resurrect-loop 09:22).
        if (syncIntent) updateKernelIntent(enabled)
    }

    /**
     * v-vh4: ЮЗЕР-ТАП — единственный авторитет на 5-секундное окно.
     * Реализует: (1) мгновенный UI-снимок (snap) без чтения ядра;
     * (2) intent-файл .vh_intent + wlan2_mode (fastwatch обязан сходиться ≤2 с);
     * (3) latch — ни refreshAsync, ни中超 kernel-fact не могут перезаписать тап.
     */
    fun userTap(iface: String, enabled: Boolean) {
        userLatch.set(System.currentTimeMillis())
        snap.set(Snap(iface, enabled, System.currentTimeMillis()))
        cacheUiState(enabled, syncIntent = true)
    }

    fun isLatched(): Boolean =
        System.currentTimeMillis() - userLatch.get() < LATCH_MS

    /**
     * v-vh3: прямая запись разрыва источника — приложение пишет intent-файл от себя.
     * Фон тред, timeout 2с; быстрый (обычная запись файла не блокирует).
     */
    fun updateKernelIntent(enabled: Boolean) {
        exec.execute {
            try {
                val mode = if (enabled) "vpn" else "novpn"
                val pb = ProcessBuilder("su", "-c",
                    "echo $mode > /data/adb/.vh_intent && echo $mode > /data/adb/wlan2_mode")
                    .redirectErrorStream(true).start()
                pb.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Throwable) { }
        }
    }

    /**
     * App.onCreate: хотел был синхронизироваться с ядром — v1 делала это на
     * main-thread (это и был лаг). v2: фоновый одиночный запрос, результат
     * просто в кэш; UI при первом рендерисе возьмёт его из ifaceEnabledCached.
     */
    fun warmUpOnLaunch(iface: String) {
        refreshAsync(iface) { res ->
            // v-vh5: kernel-fact → ТОЛЬКО отображение/snapshot/prefs.
            // syncIntent=false — нем ожsterь resurrect-loop (intent писать не надо:
            // intent-файл — авторитет юзера, ядро сам по нему сходится).
            res?.let { cacheUiState(it, syncIntent = false) }
        }
    }
}
