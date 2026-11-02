package com.armorberserk.rotip

import android.app.Application
import com.topjohnwu.superuser.Shell

class RotipApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // libsu: редирект stderr в CombinedOutput, чтобы ошибки cmd/svc не терялись.
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        val builder = Shell.Builder.create()
            .setFlags(Shell.FLAG_REDIRECT_STDERR)
            .setTimeout(15L)
        Shell.setDefaultBuilder(builder)
    }

    companion object {
        @Volatile lateinit var instance: RotipApp
            private set
    }

    init { instance = this }
}
