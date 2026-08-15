package dev.chengguan.mirror

import android.app.Application
import dev.chengguan.mirror.security.AndroidSecurityHost
import java.lang.ref.WeakReference

class MirrorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidSecurityHost.context = WeakReference(applicationContext)
    }
}
