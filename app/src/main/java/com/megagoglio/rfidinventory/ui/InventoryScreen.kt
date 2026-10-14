package com.megagoglio.rfidinventory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megagoglio.rfidinventory.reader.ReaderState
import com.megagoglio.rfidinventory.reader.TagAggregate
import com.uk.tsl.rfid.asciiprotocol.device.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    viewModel: InventoryViewModel,
    onSelectReader: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenEpcFilter: () -> Unit,
    onLogout: () -> Unit,
) {
    val readerState by viewModel.readerState.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val activeEpcFilter by viewModel.activeEpcFilter.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val pending by viewModel.pendingSessions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val inventory by viewModel.inventory.collectAsStateWithLifecycle()
    val sector by viewModel.sector.collectAsStateWithLifecycle()
    val sectors by viewModel.sectors.collectAsStateWithLifecycle()
    val sectorsLoading by viewModel.sectorsLoading.collectAsStateWithLifecycle()
    val sectorsFromCache by viewModel.sectorsFromCache.collectAsStateWithLifecycle()
    val unsentHere by viewModel.unsentForCurrentSector.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val tidMode by viewModel.tidMode.collectAsStateWithLifecycle()
    val readingTidFor by viewModel.readingTidFor.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var pickingSector by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.consumeMessage()
        }
    }

    // Reconfigura o leitor sempre que a conexão é (re)estabelecida — os parâmetros de
    // inventário não sobrevivem a uma desconexão.
    LaunchedEffect(readerState.isConnected) {
        if (readerState.isConnected) viewModel.onReaderConnected()
    }

    // Toda abertura do seletor busca a lista completa no servidor; o cache só
    // aparece se a busca falhar.
    LaunchedEffect(pickingSector) {
        if (pickingSector) viewModel.refreshSectors()
    }

    if (pickingSector) {
        SectorPickerSheet(
            sectors = sectors,
            loading = sectorsLoading,
            fromCache = sectorsFromCache,
            selectedId = sector?.id,
            onSelect = { chosen ->
                viewModel.selectSector(chosen)
                pickingSector = false
            },
            onDismiss = { pickingSector = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Inventário RFID") },
                actions = {
                    if (pending > 0) {
                        PendingBadge(pending)
                    }
                    IconButton(onClick = onOpenQueue) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Fila de envio")
                    }
                    IconButton(onClick = onOpenEpcFilter) {
                        Icon(
                            Icons.Default.FilterAlt,
                            contentDescription = "Filtro de EPC",
                            // Tingido quando há filtro ativo — o operador nunca deve
                            // descobrir "na marra" que só uma parte das tags aparece.
                            tint = if (activeEpcFilter != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                    }
                    IconButton(onClick = onSelectReader) {
                        Icon(Icons.Default.Bluetooth, contentDescription = "Selecionar leitor")
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sair")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ConnectionBar(readerState, onDisconnect)

            ContextBar(
                inventoryCode = inventory?.code,
                sectorName = sector?.name,
                onPickSector = { pickingSector = true },
            )

            SessionSummary(
                uniqueTags = tags.size,
                totalReads = tags.sumOf { it.readCount },
            )

            if (tags.isEmpty()) {
                EmptyState(readerState)
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        horizontal = 12.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(tags, key = { it.epc }) { tag ->
                        TagRow(
                            tag = tag,
                            isReadingTid = readingTidFor == tag.epc,
                            // Leitura de TID é um comando ao leitor: exige conexão, uma
                            // leitura por vez e o leitor livre — durante a varredura
                            // contínua o comando não teria vez.
                            canReadTid = readerState.isConnected && !isScanning &&
                                readingTidFor == null,
                            onReadTid = { viewModel.readTid(tag.epc) },
                        )
                    }
                }
            }

            ActionBar(
                isConnected = readerState.isConnected,
                isScanning = isScanning,
                hasTags = tags.isNotEmpty(),
                hasSector = sector != null,
                // Concluir o setor com coleta ainda na fila fecharia o setor no servidor
                // antes de a leitura chegar — a conciliação rodaria sem ela.
                canCompleteSector = sector != null && unsentHere == 0 && !busy,
                tidMode = tidMode,
                onToggleScan = viewModel::toggleScan,
                onClear = viewModel::clearSession,
                onFinish = viewModel::finishSession,
                onCompleteSector = viewModel::completeSector,
                onTidModeChange = viewModel::setTidMode,
            )
        }
    }
}

/**
 * Contexto da coleta: em qual inventário e setor as leituras vão entrar.
 *
 * Fica sempre visível porque é o dado que o operador mais erra — varrer um
 * corredor inteiro com o setor errado selecionado gera divergência falsa em dois
 * setores de uma vez.
 */
@Composable
private fun ContextBar(
    inventoryCode: String?,
    sectorName: String?,
    onPickSector: () -> Unit,
) {
    val missingSector = sectorName == null
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (missingSector) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = sectorName ?: "Nenhum setor selecionado",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = inventoryCode?.let { "Inventário $it" }
                        ?: "Nenhum inventário aberto no sistema",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = onPickSector) {
                Text(if (missingSector) "Escolher setor" else "Trocar")
            }
        }
    }
}

@Composable
private fun ConnectionBar(state: ReaderState, onDisconnect: () -> Unit) {
    val (color, label) = when (state.connectionState) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary to "Conectado"
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary to "Conectando…"
        ConnectionState.INTERRUPTED, ConnectionState.LOST ->
            MaterialTheme.colorScheme.error to "Conexão perdida"
        else -> MaterialTheme.colorScheme.outline to "Desconectado"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.readerName ?: "Nenhum leitor selecionado",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = state.reason?.takeIf { it.isNotBlank() } ?: label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isConnected) {
                OutlinedButton(onClick = onDisconnect) { Text("Desconectar") }
            }
        }
    }
}

@Composable
private fun SessionSummary(uniqueTags: Int, totalReads: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Metric("Tags únicas", uniqueTags.toString())
        Metric("Leituras", totalReads.toString())
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TagRow(
    tag: TagAggregate,
    isReadingTid: Boolean,
    canReadTid: Boolean,
    onReadTid: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = tag.epc,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                // O TID só aparece quando foi realmente lido. Antes disso o rótulo diz
                // que falta ler, em vez de sugerir um dado que o app não tem.
                Text(
                    text = tag.tid?.let { "TID $it" } ?: "TID não lido",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (tag.tid != null) FontFamily.Monospace else FontFamily.Default,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isReadingTid) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
            } else if (tag.tid == null) {
                IconButton(onClick = onReadTid, enabled = canReadTid) {
                    Icon(Icons.Default.Fingerprint, contentDescription = "Ler TID")
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${tag.readCount}×",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${tag.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.EmptyState(state: ReaderState) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (state.isConnected) {
                "Puxe o gatilho do leitor para inventariar."
            } else {
                "Conecte um leitor para começar."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionBar(
    isConnected: Boolean,
    isScanning: Boolean,
    hasTags: Boolean,
    hasSector: Boolean,
    canCompleteSector: Boolean,
    tidMode: Boolean,
    onToggleScan: () -> Unit,
    onClear: () -> Unit,
    onFinish: () -> Unit,
    onCompleteSector: () -> Unit,
    onTidModeChange: (Boolean) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.padding(16.dp)) {
            // Modo de auditoria de clonagem: custa uma leitura de banco por tag, então
            // fica desligado por padrão e avisa do custo quando ligado.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Inventário com TID",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = if (tidMode) {
                            "Lendo o TID de cada tag — leitura mais lenta."
                        } else {
                            "Só EPC. Use o botão de digital para ler o TID de uma tag."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = tidMode, onCheckedChange = onTidModeChange)
            }
            Spacer(Modifier.size(8.dp))
            Button(
                onClick = onToggleScan,
                enabled = isConnected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isScanning) "Parar leitura" else "Ler (ou use o gatilho)")
            }
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onClear,
                    enabled = hasTags,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Limpar")
                }
                Button(
                    // Sem setor não há onde gravar: o servidor amarra a leitura à visita
                    // de setor, e é isso que dá sentido à conciliação.
                    onClick = onFinish,
                    enabled = hasTags && hasSector,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Finalizar leitura")
                }
            }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(
                onClick = onCompleteSector,
                enabled = canCompleteSector,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Concluir setor")
            }
        }
    }
}

@Composable
private fun PendingBadge(count: Int) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CloudUpload,
            contentDescription = "Sessões aguardando envio",
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.size(4.dp))
        Text(count.toString(), style = MaterialTheme.typography.labelLarge)
    }
}
