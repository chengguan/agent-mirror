package dev.chengguan.mirror

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dev.chengguan.mirror.security.AndroidSecurityHost

actual fun copyToClipboard(text: String) {
    val clipped = text.take(16_384)
    if (clipped.isEmpty()) return
    val context = AndroidSecurityHost.requireContext() ?: return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Mirror", clipped))
}
