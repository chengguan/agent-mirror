package dev.chengguan.mirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.chengguan.mirror.ChatMessage
import dev.chengguan.mirror.MirrorUiState

@Composable
fun ChatScreen(
    state: MirrorUiState,
    onUnlock: () -> Unit,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onApplyLink: (String) -> Unit,
    onUnpair: () -> Unit,
    onLock: () -> Unit,
) {
    if (state.locked) {
        LockScreen(state = state, onUnlock = onUnlock)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Mirror", style = MaterialTheme.typography.headlineMedium)
                Text(
                    state.pairing?.let { "Session ${it.sessionId.take(8)}… · ${it.host}:${it.port}" }
                        ?: "Not paired",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onLock) { Text("Lock") }
        }

        if (state.pairing == null) {
            PairPane(status = state.status, onApplyLink = onApplyLink)
        } else {
            ThreadPane(
                modifier = Modifier.weight(1f),
                messages = state.messages,
                draft = state.draft,
                sending = state.sending,
                status = state.status,
                onDraft = onDraft,
                onSend = onSend,
                onUnpair = onUnpair,
            )
        }
    }
}

@Composable
private fun LockScreen(state: MirrorUiState, onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Mirror", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Unlock to open the pairing secret and this Grok thread. Same Wi-Fi only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
        Button(onClick = onUnlock, enabled = !state.authenticating) {
            Text(if (state.authenticating) "Waiting…" else "Unlock")
        }
        state.status?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun PairPane(status: String?, onApplyLink: (String) -> Unit) {
    var draft = remember { androidx.compose.runtime.mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Scan the QR with the system Camera (opens grok-mirror://), or paste the pairing URL from the Mac companion.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft.value,
            onValueChange = { if (it.length <= 2_000) draft.value = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Pairing URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onApplyLink(draft.value) }),
        )
        Button(
            onClick = { onApplyLink(draft.value) },
            enabled = draft.value.isNotBlank(),
        ) { Text("Pair") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun ThreadPane(
    modifier: Modifier,
    messages: List<ChatMessage>,
    draft: String,
    sending: Boolean,
    status: String?,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onUnpair: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "Waiting for this session’s messages. Keep the Mac companion running.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(messages, key = { "${it.role}:${it.text.hashCode()}" }) { message ->
                MessageBubble(message)
            }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message") },
            minLines = 1,
            maxLines = 6,
            enabled = !sending,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, enabled = !sending && draft.isNotBlank()) {
                Text(if (sending) "Sending…" else "Send")
            }
            OutlinedButton(onClick = onUnpair, enabled = !sending) { Text("Unpair") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val mine = message.role == "user"
    val align = if (mine) Alignment.CenterEnd else Alignment.CenterStart
    val color = if (mine) UserBubble else AssistantBubble
    val textColor = if (mine) Color.White else Ink
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = align) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = color,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (mine) "You" else "Grok",
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                )
                Text(message.text, color = textColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
