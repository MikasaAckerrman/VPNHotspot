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
 * Ядро RotIP v0.3: бесконечная ротация внешнего IP через root.
 *
 * v0.3 изменения (ревью 05.11 23:35, юзер: «ротация долго идёт, IP не показывает»):
 *  - добавлен progress-каллбек: UI видит этапы (определяю IP / airplane ON / OFF / жду сеть),
 *    «busy» перестал быть загадочной 60-сек паузой без текста;
 *  - waitForNetwork timeout 26 c → 14 c, авто-повтор 4 → 2 (SAME_IP повторяется редко);
 *  - fetchIp:ellular-first c perceived timeout 9 c → 6 c; любой fail → default-route fallback.
 */
object Engine {

    const val TAG_LOG = "RotIP"

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

    /** Прогресс-каллбек (вызывается на IO-потоке rotation; UI сам переключает на Main). */
    @Volatile var onProgress: ((String) -> Unit)? = null
    private fun progress(msg: String) { try { onProgress?.invoke(msg) } catch (_: Throwable) {} }

    /** История в памяти + JSONL на диске. */
    val history: java.util.concurrent.CopyOnWriteArrayList<Result> = java.util.concurrent.CopyOnWriteArrayList()
    val busy = AtomicBoolean(false)
    private val mutex = Mutex()

    @Volatile var cooldownMs: Long = 90_000L
    @Volatile var lastRotateAt: Long = 0L

    // root-cache (non-blocking для UI)
    @Volatile private var rootStateCache: Boolean? = null
    fun hasRoot(): Boolean = rootStateCache == true
    fun rootUnknown(): Boolean = rootStateCache == null
    fun setRootState(v: Boolean?) { rootStateCache = v }

    /** Синхронная проверка root; ТОЛЬКО на Dispatchers.IO. */
    fun checkRootBlocking(): Boolean = try {
        val ok = Shell.getShell().isRoot
        rootStateCache = ok
        android.util.Log.i(TAG_LOG, "root shell init result: $ok")
        ok
    } catch (t: Throwable) {
        rootStateCache = false
        android.util.Log.i(TAG_LOG, "root shell init failed: ${t.message}")
        false
    }

    suspend fun rotate(context: Context, allowed: List<Mode> = listOf(Mode.AIRPLANE, Mode.DATA)): Result {
        if (busy.getAndSet(true)) {
            return Result(false, null, null, null, 0, false, "busy")
        }
        try {
            mutex.withLock {
                val t0 = System.currentTimeMillis()
                if (rootUnknown()) { progress("проверяю root..."); checkRootBlocking() }
                if (!hasRoot()) {
                    progress("root отсутствует")
                    return Result(false, null, null, null, 0, false, "root_lost")
                }

                progress("определяю текущий IP...")
                val ipBefore = fetchIp(context)
                progress("текущий IP: ${ipBefore ?: "??"}")

                var mode_used: Mode? = null
                var lastError: String? = null
                for (mode in allowed) {
                    progress("применяю ${mode.name.lowercase()}...")
                    val ok = execPrimitive(mode)
                    if (!ok) { lastError = mode.name.lowercase() + "_failed"; continue }
                    mode_used = mode
                    progress("жду сеть (после ${mode.name.lowercase()})...")
                    if (!waitForNetwork(context, timeoutMs = 14_000L)) {
                        lastError = "no_network_after_" + mode.name.lowercase()
                        continue
                    }
                    progress("определяю новый IP...")
                    val ipAfter = fetchIp(context)
                    val r = Result(
                        ok = ipAfter != null && ipBefore != null && ipBefore != ipAfter,
                        modeUsed = mode_used,
                        ipBefore = ipBefore,
                        ipAfter = ipAfter,
                        elapsedMs = System.currentTimeMillis() - t0,
                        sameIp = ipAfter == ipBefore,
                        error = lastError
                    )
                    if (r.ipAfter != null) {
                        lastRotateAt = System.currentTimeMillis()
                        history.add(0, r)
                        if (history.size > 100) { while (history.size > 100) history.removeAt(history.size - 1) }
                        appendHistoryFile(context, r)
                        progress(if (r.sameIp) "SAME_IP: ${r.ipAfter}" else "готово: ${r.ipAfter}")
                        return r
                    }
                }
                progress("ротация не удалась: $lastError")
                return Result(false, mode_used, ipBefore, null,
                    System.currentTimeMillis() - t0, sameIp = false, error = lastError ?: "no_mode_succeeded")
            }
        } finally {
            busy.set(false)
        }
    }

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
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.requestMethod = "GET"
                conn.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
            } catch (_: Throwable) {
                null
            } finally {
                try { conn.disconnect() } catch (_: Throwable) { }
            }
        }

        var result: String? = null

        if (cellular != null) {
            try {
                val conn = cellular.openConnection(URL(url)) as HttpURLConnection
                result = withTimeoutOrNull(7000L) { fetchHttp(conn) }
            } catch (_: Throwable) { }
        }

        if (result == null) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                result = withTimeoutOrNull(7000L) { fetchHttp(conn) }
            } catch (_: Throwable) { null }
        }
        result
    }

    private suspend fun waitForNetwork(context: Context, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ip = withTimeoutOrNull(3000L) { fetchIp(context) }
            if (ip != null) return true
            kotlinx.coroutines.delay(1200L)
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
            if (hasRoot()) {
                try {
                    Shell.cmd("mkdir -p /data/adb/rotip && cp ${historyFile(context).canonicalPath} /data/adb/rotip/history.jsonl").exec()
                } catch (_: Throwable) { }
            }
        } catch (_: Throwable) { }
    }

    /** Инициализация состояния из persist-файла (root-зеркало приоритет).
     *  При root=false НЕ дергает Shell (возвращает быстро). */
    fun initState(context: Context) {
        if (rootUnknown()) { checkRootBlocking() }
        if (!hasRoot()) return
        try {
            val r = Shell.cmd("tail -n 1 /data/adb/rotip/history.jsonl 2>/dev/null").exec()
            val line = r.out.lastOrNull { it.isNotBlank() }
            val ts = Regex("\"ts\":(\\d+)").find(line ?: "")?.groupValues?.get(1)?.toLongOrNull()
            if (ts != null) { lastRotateAt = ts; return }
        } catch (_: Throwable) { }
        try {
            val f = historyFile(context)
            if (f.exists()) {
                val line = f.readLines().lastOrNull { it.isNotBlank() }
                Regex("\"ts\":(\\d+)").find(line ?: "")?.groupValues?.get(1)?.toLongOrNull()?.let { lastRotateAt = it }
            }
        } catch (_: Throwable) { }
    }
}
