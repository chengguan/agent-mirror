package dev.chengguan.mirror

/**
 * Process-local hooks so Android/iOS hosts can lock the thread when the app
 * backgrounds and forward a grok-mirror:// deep link. No secrets live here.
 */
object MirrorSession {
    var onBackground: (() -> Unit)? = null
    var onLink: ((String) -> Unit)? = null
}
