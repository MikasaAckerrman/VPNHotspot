package com.armorberserk.rotip

import android.app.Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RotipApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // libsu: редирект stderr, чтобы ошибки cmd/svc не терялись.
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        val builder = Shell.Builder.create()
            .setFlags(Shell.FLAG_REDIRECT_STDERR)
            .setTimeout(15L)
        Shell.setDefaultBuilder(builder)
        // ФАКТ (ревью 05.11): Shell.isAppGrantedRoot() до инициализации root-shell
        // возвращает null → UI показывал «root ПОТЕРЯН» даже при выданном руте.
        //Поэтому: одна фоновая инициализация root-shell при старте приложения
        // — это и есть момент запроса su у ReSukiSU (если ещё не выдан).
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = Shell.getShell().isRoot
                android.util.Log.i(TAG, "root shell init result: $ok")
            } catch (t: Throwable) {
                android.util.Log.i(TAG, "root shell init failed: ${t.message}")
            }
        }
    }

    companion object {
        private const val TAG = "RotIP"
        @Volatile lateinit var instance: RotipApp
            private set
    }
}
