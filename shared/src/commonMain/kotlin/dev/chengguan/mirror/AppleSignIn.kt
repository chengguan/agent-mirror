package dev.chengguan.mirror

/**
 * iOS installs Sign in with Apple. The identity token is sent to the companion
 * and never logged. Android leaves [host] null.
 */
fun interface AppleSignInHost {
    fun signIn(onResult: (token: String?, error: String?) -> Unit)
}

object AppleSignIn {
    var host: AppleSignInHost? = null
}
