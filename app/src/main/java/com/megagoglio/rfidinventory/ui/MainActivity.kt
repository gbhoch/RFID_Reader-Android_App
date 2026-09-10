package com.megagoglio.rfidinventory.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.megagoglio.rfidinventory.RfidApp
import com.uk.tsl.rfid.DeviceListActivity

class MainActivity : ComponentActivity() {

    private val viewModel: InventoryViewModel by viewModels()

    private val loginViewModel: LoginViewModel by viewModels()

    private val app: RfidApp get() = application as RfidApp

    /**
     * Permissões de Bluetooth por nível de API. Até a 30 elas são concedidas na instalação;
     * da 31 em diante BLUETOOTH_CONNECT e BLUETOOTH_SCAN precisam ser pedidas em runtime.
     * Ver checkForBluetoothPermission() em InventoryActivity.java (SDK da TSL).
     */
    private val bluetoothPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            app.connection.onBluetoothPermissionsGranted()
        } else {
            showPermissionDeniedDialog()
        }
    }

    private val selectReaderLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        app.connection.isSelectingReader = false

        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val extras = result.data?.extras ?: return@registerForActivityResult

        val index = extras.getInt(DeviceListActivity.EXTRA_DEVICE_INDEX, -1)
        val action = extras.getInt(DeviceListActivity.EXTRA_DEVICE_ACTION, -1)

        when (action) {
            DeviceListActivity.DEVICE_DISCONNECT -> app.connection.disconnect()
            else -> if (index >= 0) app.connection.useReaderAt(index)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app.connection.registerObservers()
        app.inventory.attach()

        setContent {
            val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            ) {
                val loggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
                var showingQueue by rememberSaveable { mutableStateOf(false) }

                // Ao autenticar, busca o inventário aberto e a lista de setores. É aqui
                // que o cache offline é preenchido — o operador entra no galpão com o
                // contexto já no aparelho.
                LaunchedEffect(loggedIn) {
                    if (loggedIn) viewModel.refreshContext(announce = true)
                    else showingQueue = false
                }

                when {
                    !loggedIn -> LoginScreen(viewModel = loginViewModel)

                    showingQueue -> {
                        // O botão voltar do sistema tem de devolver ao inventário. Sem
                        // isto ele fecha a Activity inteira, e o operador cai fora do app
                        // só por conferir a fila.
                        BackHandler { showingQueue = false }
                        QueueScreen(
                            sessions = viewModel.recentSessions.collectAsStateWithLifecycle().value,
                            blockedCount = viewModel.blockedSessions.collectAsStateWithLifecycle().value,
                            onRetryBlocked = viewModel::retryBlocked,
                            onSyncNow = viewModel::syncNow,
                            onBack = { showingQueue = false },
                        )
                    }

                    else -> InventoryScreen(
                        viewModel = viewModel,
                        onSelectReader = ::openReaderList,
                        onDisconnect = { app.connection.disconnect() },
                        onOpenQueue = { showingQueue = true },
                        onLogout = viewModel::logout,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val allowed = hasBluetoothPermissions()
        app.connection.onResume(canUseBluetooth = allowed)
        if (!allowed) requestBluetoothPermissions()
        // O gestor pode ter aberto/fechado inventário ou mexido nos setores enquanto o
        // app estava em segundo plano.
        viewModel.refreshContext()
    }

    override fun onPause() {
        super.onPause()
        app.connection.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        app.inventory.detach()
        app.connection.unregisterObservers()
    }

    // ------------------------------------------------------------- seleção

    private fun openReaderList() {
        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }
        // Sinaliza antes de abrir a tela: sem isso o onPause resultante desconectaria o
        // leitor que o usuário está prestes a escolher.
        app.connection.isSelectingReader = true

        val intent = Intent(this, DeviceListActivity::class.java)
        app.connection.currentReaderIndex().takeIf { it >= 0 }?.let { index ->
            intent.putExtra(DeviceListActivity.EXTRA_DEVICE_INDEX, index)
        }
        selectReaderLauncher.launch(intent)
    }

    // ---------------------------------------------------------- permissões

    private fun hasBluetoothPermissions(): Boolean = bluetoothPermissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBluetoothPermissions() {
        val needsRationale = bluetoothPermissions.any { shouldShowRequestPermissionRationale(it) }
        if (needsRationale) {
            AlertDialog.Builder(this)
                .setTitle("Permitir Bluetooth?")
                .setMessage("O app precisa de Bluetooth para conectar ao leitor RFID.")
                .setPositiveButton("Permitir") { _, _ ->
                    permissionLauncher.launch(bluetoothPermissions)
                }
                .setNegativeButton("Agora não", null)
                .show()
        } else {
            permissionLauncher.launch(bluetoothPermissions)
        }
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Bluetooth negado")
            .setMessage(
                "Sem a permissão de Bluetooth não é possível conectar ao leitor. " +
                    "Você pode conceder a permissão nas configurações do sistema."
            )
            .setPositiveButton("Entendi", null)
            .show()
    }
}
