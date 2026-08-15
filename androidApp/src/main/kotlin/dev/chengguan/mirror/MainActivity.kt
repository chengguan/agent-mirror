package dev.chengguan.mirror

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import dev.chengguan.mirror.security.AndroidSecurityHost
import java.lang.ref.WeakReference

class MainActivity : FragmentActivity() {
    private val qrScanHost = AndroidQrScanHost(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OWASP M6: keep the thread off screenshots and the recents thumbnail.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        AndroidSecurityHost.context = WeakReference(applicationContext)
        AndroidSecurityHost.activity = WeakReference(this)
        QrScanner.host = qrScanHost
        enableEdgeToEdge()
        setContent { MirrorApp(incomingLink = intent?.data?.toString()) }
    }

    override fun onDestroy() {
        if (QrScanner.host === qrScanHost) QrScanner.host = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.toString()?.let { link ->
            MirrorSession.onLink?.invoke(link)
        }
    }

    override fun onResume() {
        super.onResume()
        AndroidSecurityHost.activity = WeakReference(this)
    }

    override fun onPause() {
        MirrorSession.onBackground?.invoke()
        if (AndroidSecurityHost.activity?.get() === this) {
            AndroidSecurityHost.activity = null
        }
        super.onPause()
    }
}
