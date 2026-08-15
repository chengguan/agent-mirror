package dev.chengguan.mirror

import platform.UIKit.UIPasteboard

actual fun copyToClipboard(text: String) {
    val clipped = text.take(16_384)
    if (clipped.isEmpty()) return
    UIPasteboard.generalPasteboard.string = clipped
}
