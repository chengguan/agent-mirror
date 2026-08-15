package dev.chengguan.mirror

/** Copy conversation text. Platforms use the system clipboard — no logging. */
expect fun copyToClipboard(text: String)
