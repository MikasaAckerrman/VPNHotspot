package com.armorberserk.rotip

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ядро RotIP: бесконечная ротация внешнего IP через root.
 *
 * Примитивы (только то, что реально есть на vivo SM8650 / Android 15):
 *  - DATA:      su -c "svc data disable|enable"   (пересоздание PDU-сессии)
 *  - AIRPLANE:  su -c "cmd connectivity airplane-mode enable|disable"
 *  - RIL:       НЕ ИСПОЛЬЗУЕМ — RIL-демон на vivo = qcrilNrd,
 *               рестарт непредсказуем и НЕ нужен (DATA/AIRPLANE хватает).
 *  - WARP:      Ф4 (не в MVP).
 *
 * Эскалация: DATA ->财经大学 AIRPLANE. Если после двух шагов IP тот же —
 * фиксируем SAME_IP и возвращаем результат вызывающему (UI/услуга решает).
 *
 * Сеть: детект внешнего IP делаем ПЕРЕ-СЕТЬЮ (TRANSPORT_CELLULAR) без VPN,
 * если эта сеть существует. Иначе — дефолт-роут. VPN может игнорировать
 * network.bindSocket, если у него kill-switch/per-app — помечено как
 * эксп.: уточняем в Ф0.2 тестах.
 */
object Engine {

    enum class Mode { DATA, AIRPLANE }

    data class Result(
        val ok: Boolean,          // ротация прошла и IP изменился
        val modeUsed: Mode?,
        val ipBefore: String?,
        val ipAfter: String?,
        val elapsedMs: Long,
        val sameIp: Boolean,      // IP не изменился
        val error: String? = null
    )

    /** last-R-rotation history — в памяти для UI + JSONL на диск. */
    val history: MutableList<Result> = java.util.concurrent.CopyOnWriteArrayList()
    val busy = AtomicBoolean(false)
    private val mutex = Mutex()

    /** Порядок эскалации по факту теста F0.2 (05.11): на этой прошивке DATA-toggle НЕ меняет
     *  операторный IP (CGNAT ref-ref у vodens subscriber), AIRPLANE-تحديث модема меняет стабильно. */
    val ROTATION_ORDER: List<Mode> = listOf(Mode.AIRPLANE, Mode.DATA)

    /** Гварды (изменяются из UI/сервиса). */
    @Volatile var cooldownMs: Long = 90_000L
    @Volatile var lastRotateAt: Long = 0L

    /** Проверить, что приложение имеет живой root (su). */
    fun hasRoot(): Boolean = try {
        Shell.isAppGrantedRoot() == true
    } catch (_: Throwable) {
        false
    }

    suspend fun rotate(context: Context, allowed: List<Mode> = ROTATION_ORDER): Result {
        // Reentrant guard: не пускать параллельные ротации (требование Р_results-корректности).
        if (busy.getAndSet(true)) {
            return Result(false, null, null, null, 0, false, "busy")
        }
        try {
            mutex.withLock {
                val t0 = System.currentTimeMillis()
                if (!hasRoot()) {
                    return Result(false, null, null, null, 0, false, "root_lost")
                }
                val ipBefore = fetchIp(context)
                var mode_used: Mode? = null
                var lastError: String? = null
                for (mode in allowed) {
                    val ok = execPrimitive(mode)
                    if (!ok) { lastError = mode.name.lowercase() + "_failed"; continue }
                    mode_used = mode
                    // Ждём поднятия данных (PDU ~10-15 c, airplane ~15-20 c).
                    if (!waitForNetwork(context, timeoutMs = 26_000L)) {
                        lastError = "no_network_after_" + mode.name.lowercase()
                        continue
                    }
                    val ipAfter = fetchIp(context)
                    val r = Result(
                        ok = ipAfter != null && ipBefore != null && ipBefore != ipAfter,
                        modeUsed = mode_used,
                        ipBefore = ipBefore,
                        ipAfter = ipAfter,
                        elapsedMs = System.currentTimeMillis() - t0,
                        sameIp = ipAfter == ipBefore || (ipBefore == null && ipAfter == null),
                        error = lastError
                    )
                    if (r.ipAfter != null) {
                        lastRotateAt = System.currentTimeMillis()
                        history.add(0, r)
                        if (history.size > 200) { while (history.size > 100) history.removeAt(history.size - 1) }
                        appendHistoryFile(context, r)
                        return r
                    }
                }
                return Result(false, mode_used, ipBefore, null,
                    System.currentTimeMillis() - t0, sameIp = false, error = lastError ?: "no_mode_succeeded")
            }
        } finally {
            busy.set(false)
        }
    }

    /** Оставшийся cooldown (мс) до следующей ротации планировщика. */
    fun cooldownRemaining(): Long {
        val passed = System.currentTimeMillis() - lastRotateAt
        return if (passed >= cooldownMs) 0L else cooldownMs - passed
    }

    // ---- Примитивы через libsu ----

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun execPrimitive(mode: Mode): Boolean {
        return try {
            when (mode) {
                Mode.DATA -> {
                    val disable = Shell.cmd("svc data disable").exec().isSuccess
                    sleep(4000L)
                    val enable = Shell.cmd("svc data enable").exec().isSuccess
                    disable && enable
                }
                Mode.AIRPLANE -> {
                    val on = Shell.cmd("cmd connectivity airplane-mode enable").exec().isSuccess
                    sleep(6000L)
                    val off = Shell.cmd("cmd connectivity airplane-mode disable").exec().isSuccess
                    on && off
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    // ---- Сеть ----

    /** Внешний IP: сначала через TRANSPORT_CELLULAR (обход VPN), иначе дефолт-роут. */
    suspend fun fetchIp(context: Context): String? = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cellular: Network? = try {
            cm.allNetworks.firstOrNull { n ->
                val c = cm.getNetworkCapabilities(n)
                c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Throwable) { null }
        val url = "https://api.ipify.org"

        suspend fun fetchHttp(conn: HttpURLConnection): String? {
            return try {
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
            } catch (_: Throwable) {
                null
            } finally {
                try { conn.disconnect() } catch (_: Throwable) { }
            }
        }

        var result: String? = null

        // Предпочтительно: ИЗ CELLULAR-сети (равноценно через network, VPN-ipv4 может маскировать).
        if (cellular != null) {
            try {
                val conn = cellular.openConnection(URL(url)) as HttpURLConnection
                result = withTimeoutOrNull(9000L) { fetchHttp(conn) }
            } catch (_: Throwable) {
                // падение в fallback
            }
        }

        // Fallback: дефолт-роут (в VPN-режиме этот IP может быть не мобильным).
        if (result == null) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                result = withTimeoutOrNull(9000L) { fetchHttp(conn) }
            } catch (_: Throwable) {
                null
            }
        }
        result
    }

    private suspend fun waitForNetwork(context: Context, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ip = withTimeoutOrNull(9000L) { fetchIp(context) }
            if (ip != null) return true
            kotlinx.coroutines.delay(1500L)
        }
        return false
    }

    // ---- История (JSONL) ----

    private fun historyFile(context: Context): File =
        File(context.filesDir, "rotip_history.jsonl")

    private fun appendHistoryFile(context: Context, r: Result) {
        try {
            val json = buildString {
                append("{\"ts\":"); append(System.currentTimeMillis())
                append(",\"ok\":"); append(r.ok)
                append(",\"mode\":\""); append(r.modeUsed?.name ?: "none")
                append("\",\"before\":"); append(r.ipBefore?.let { "\"$it\"" } ?: "null")
                append(",\"after\":"); append(r.ipAfter?.let { "\"$it\"" } ?: "null")
                append(",\"ms\":"); append(r.elapsedMs)
                append(",\"same\":"); append(r.sameIp)
                append(",\"err\":\""); append(r.error ?: "")
                append("\"}")
            }
            historyFile(context).appendText(json + "\n")
        } catch (_: Throwable) { }
    }
}
