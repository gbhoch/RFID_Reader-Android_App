package com.megagoglio.rfidinventory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megagoglio.rfidinventory.data.local.EpcFilterEntity
import com.megagoglio.rfidinventory.reader.TagAggregate

/**
 * Tela "EPC Filter": configura start/length/valor de um trecho do EPC e
 * liga/desliga a aplicação desse filtro no leitor.
 *
 * Não abre um segundo canal de leitura — "escanear" e "selecionar de leitura
 * anterior" reaproveitam o mesmo `isScanning`/`onToggleScan`/tags já usados pela
 * tela de inventário (ver [EpcFilterViewModel]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpcFilterScreen(
    viewModel: EpcFilterViewModel,
    recentTags: List<TagAggregate>,
    isScanning: Boolean,
    onToggleScan: () -> Unit,
    onBack: () -> Unit,
) {
    val readerState by viewModel.readerState.collectAsStateWithLifecycle()
    val activeFilter by viewModel.activeFilter.collectAsStateWithLifecycle()
    val savedFilters by viewModel.savedFilters.collectAsStateWithLifecycle()
    val baseEpc by viewModel.baseEpc.collectAsStateWithLifecycle()
    val start by viewModel.start.collectAsStateWithLifecycle()
    val length by viewModel.length.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var namingFilter by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it.text)
            viewModel.consumeMessage()
        }
    }

    if (namingFilter) {
        SaveFilterDialog(
            onConfirm = { name ->
                viewModel.saveCurrentAs(name)
                namingFilter = false
            },
            onDismiss = { namingFilter = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Filtro de EPC") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BaseEpcSection(
                baseEpc = baseEpc,
                start = start,
                length = length,
                isScanning = isScanning,
                readerConnected = readerState.isConnected,
                recentTags = recentTags,
                onBaseEpcChange = viewModel::setBaseEpc,
                onToggleScan = onToggleScan,
                onPickEpc = viewModel::pickEpc,
            )

            RangeSteppers(
                start = start,
                length = length,
                epcLength = baseEpc.length,
                onIncStart = viewModel::incStart,
                onDecStart = viewModel::decStart,
                onIncLength = viewModel::incLength,
                onDecLength = viewModel::decLength,
            )

            EnableFilterRow(
                enabled = activeFilter != null,
                onToggle = { checked ->
                    if (checked) viewModel.enableFilter() else viewModel.disableFilter()
                },
            )

            OutlinedButton(
                onClick = { namingFilter = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Salvar filtro")
            }

            SavedFiltersSection(
                filters = savedFilters,
                onApply = viewModel::applySaved,
                onEdit = viewModel::editSaved,
                onDelete = viewModel::deleteSaved,
            )
        }
    }
}

@Composable
private fun BaseEpcSection(
    baseEpc: String,
    start: Int,
    length: Int,
    isScanning: Boolean,
    readerConnected: Boolean,
    recentTags: List<TagAggregate>,
    onBaseEpcChange: (String) -> Unit,
    onToggleScan: () -> Unit,
    onPickEpc: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = baseEpc,
            onValueChange = onBaseEpcChange,
            label = { Text("EPC base") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )

        if (baseEpc.isNotEmpty()) {
            val highlight = MaterialTheme.colorScheme.primaryContainer
            Text(
                text = highlightedEpc(baseEpc, start, length, highlight),
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            )
        }

        Button(
            onClick = onToggleScan,
            enabled = readerConnected,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isScanning) "Parar leitura" else "Escanear tag")
        }

        if (recentTags.isNotEmpty()) {
            Text(
                text = "Leituras recentes — toque para usar como base",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recentTags.take(8).forEach { tag ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickEpc(tag.epc) },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = tag.epc,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun highlightedEpc(epc: String, start: Int, length: Int, highlight: Color) = buildAnnotatedString {
    val from = start.coerceIn(0, epc.length)
    val to = (start + length).coerceIn(from, epc.length)
    append(epc.substring(0, from))
    withStyle(SpanStyle(background = highlight)) {
        append(epc.substring(from, to))
    }
    append(epc.substring(to))
}

@Composable
private fun RangeSteppers(
    start: Int,
    length: Int,
    epcLength: Int,
    onIncStart: () -> Unit,
    onDecStart: () -> Unit,
    onIncLength: () -> Unit,
    onDecLength: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Stepper(label = "Início", value = start, onInc = onIncStart, onDec = onDecStart)
        Stepper(label = "Comprimento", value = length, onInc = onIncLength, onDec = onDecLength)
        if (epcLength > 0) {
            Text(
                text = "EPC base tem $epcLength caracteres",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onInc: () -> Unit, onDec: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, modifier = Modifier.weight(1f))
        IconButton(onClick = onDec) { Text("«", style = MaterialTheme.typography.titleLarge) }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        IconButton(onClick = onInc) { Text("»", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun EnableFilterRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Habilitar filtro", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (enabled) "Só tags que casarem serão reportadas" else "Todas as tags são reportadas",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun SavedFiltersSection(
    filters: List<EpcFilterEntity>,
    onApply: (EpcFilterEntity) -> Unit,
    onEdit: (EpcFilterEntity) -> Unit,
    onDelete: (EpcFilterEntity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Filtros salvos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (filters.isEmpty()) {
            Text(
                text = "Nenhum filtro salvo ainda.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            filters.forEach { filter ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(filter.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            text = "início ${filter.startChar}, comprimento ${filter.lengthChar}, valor ${filter.valueHex}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onApply(filter) }) { Text("Aplicar") }
                            TextButton(onClick = { onEdit(filter) }) { Text("Editar") }
                            TextButton(onClick = { onDelete(filter) }) { Text("Excluir") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveFilterDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Salvar filtro") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome (ex.: Setor TI)") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

