package com.megagoglio.rfidinventory.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.megagoglio.rfidinventory.data.local.SessionEntity
import com.megagoglio.rfidinventory.data.local.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fila de envio.
 *
 * Existe para o operador nunca ficar no escuro sobre o destino da coleta: o que
 * já subiu, o que espera rede e — principalmente — o que ficou travado porque o
 * inventário foi encerrado antes de a coleta chegar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    sessions: List<SessionEntity>,
    blockedCount: Int,
    onRetryBlocked: () -> Unit,
    onSyncNow: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fila de envio") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (blockedCount > 0) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "$blockedCount coleta(s) travada(s)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = "O inventário já estava encerrado quando esta leitura chegou. " +
                                "Peça ao gestor para reabrir o inventário no painel web e toque " +
                                "em Tentar de novo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.size(8.dp))
                        OutlinedButton(onClick = onRetryBlocked) { Text("Tentar de novo") }
                    }
                }
            }

            if (sessions.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Nenhuma coleta gravada ainda.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(sessions, key = { it.id }) { session -> SessionRow(session) }
                }
            }

            Surface(tonalElevation = 3.dp) {
                OutlinedButton(
                    onClick = onSyncNow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text("Sincronizar agora")
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionEntity) {
    val (label, color) = when (session.status) {
        SessionStatus.ENVIADA -> "Enviada" to MaterialTheme.colorScheme.primary
        SessionStatus.PENDENTE -> "Aguardando rede" to MaterialTheme.colorScheme.tertiary
        SessionStatus.BLOQUEADA -> "Travada — inventário encerrado" to MaterialTheme.colorScheme.error
        SessionStatus.ERRO -> "Rejeitada" to MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = session.sectorName.ifBlank { "Setor não informado" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${session.inventoryCode} · ${formatMoment(session.finishedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
            }
            session.lastError?.takeIf { session.status != SessionStatus.ENVIADA }?.let { error ->
                Spacer(Modifier.size(6.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val momentFormat = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

private fun formatMoment(epochMillis: Long): String =
    synchronized(momentFormat) { momentFormat.format(Date(epochMillis)) }
