package dev.chengguan.mirror

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.chengguan.mirror.security.platformDeviceAuthenticator
import dev.chengguan.mirror.security.platformProtectedFiles
import dev.chengguan.mirror.security.platformSecureStore
import dev.chengguan.mirror.ui.ChatScreen
import dev.chengguan.mirror.ui.MirrorTheme

@Composable
fun MirrorApp(incomingLink: String? = null) {
    val viewModel = remember {
        MirrorViewModel(
            store = platformSecureStore(),
            authenticator = platformDeviceAuthenticator(),
            files = platformProtectedFiles(),
            incomingLink = incomingLink,
        )
    }
    val state by viewModel.state.collectAsState()

    DisposableEffect(viewModel) {
        MirrorSession.onBackground = { viewModel.lock() }
        MirrorSession.onLink = { link -> viewModel.applyLink(link) }
        onDispose {
            viewModel.lock()
            if (MirrorSession.onBackground != null) {
                MirrorSession.onBackground = null
            }
            if (MirrorSession.onLink != null) {
                MirrorSession.onLink = null
            }
        }
    }

    MirrorTheme {
        ChatScreen(
            state = state,
            onUnlock = viewModel::unlock,
            onDraft = viewModel::onDraft,
            onSend = viewModel::send,
            onApplyLink = viewModel::applyLink,
            onSelectSession = viewModel::selectSession,
            onStartRename = viewModel::startRename,
            onCancelRename = viewModel::cancelRename,
            onRename = viewModel::renameSession,
            onRequestUnpair = viewModel::requestUnpair,
            onConfirmUnpair = viewModel::confirmUnpair,
            onCancelUnpair = viewModel::cancelUnpair,
            onLock = viewModel::lock,
            onToggleStatus = viewModel::toggleStatusExpanded,
            onToggleBackgroundRefresh = viewModel::toggleBackgroundRefresh,
        )
    }
}
