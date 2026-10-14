package com.megagoglio.rfidinventory.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.megagoglio.rfidinventory.data.local.SectorEntity
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Seletor de setor.
 *
 * Ao abrir, a tela pede a lista completa ao servidor ([loading]); enquanto isso
 * (e sem rede) mostra o cache local do Room. [fromCache] indica que a última busca
 * falhou e o que aparece pode estar desatualizado — trocar de setor no meio de um
 * corredor sem sinal é o caso normal, não a exceção.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectorPickerSheet(
    sectors: List<SectorEntity>,
    loading: Boolean,
    fromCache: Boolean,
    selectedId: String?,
    onSelect: (SectorEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    // Ordenação no Kotlin, não no SQL: o ORDER BY do SQLite é binário (maiúsculas
    // antes de minúsculas, acentuadas no fim), e os setores do ERP vêm em caixa alta.
    val sorted = remember(sectors) {
        val collator = Collator.getInstance(Locale("pt", "BR")).apply { strength = Collator.PRIMARY }
        sectors.sortedWith { a, b -> collator.compare(a.name, b.name) }
    }

    val filtered = remember(sorted, query) {
        val term = query.trim().foldForSearch()
        if (term.isEmpty()) sorted
        else sorted.filter {
            it.name.foldForSearch().contains(term) ||
                it.acronym?.foldForSearch()?.contains(term) == true
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                text = "Setor a inventariar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.size(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Buscar setor") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.size(4.dp))
            }

            if (fromCache && !loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "Sem conexão com o servidor — lista salva no aparelho.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (sectors.isEmpty()) {
                Text(
                    text = if (loading) "Carregando setores…"
                    else "Nenhum setor no aparelho ainda. Conecte-se à rede e reabra " +
                        "esta lista para baixá-la.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return@Column
            }

            Text(
                text = if (filtered.size == sorted.size) "${sorted.size} setores"
                else "${filtered.size} de ${sorted.size} setores",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            if (filtered.isEmpty()) {
                Text(
                    text = "Nenhum setor encontrado para \"${query.trim()}\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(filtered, key = { it.id }) { sector ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(sector) }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(sector.name, style = MaterialTheme.typography.bodyLarge)
                            sector.acronym?.takeIf { it.isNotBlank() }?.let { acronym ->
                                Text(
                                    text = acronym,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (sector.id == selectedId) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Setor atual",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Minúsculas e sem acentos: "manutenção" encontra "MANUTENCAO" e vice-versa. */
private fun String.foldForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
