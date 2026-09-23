package com.megagoglio.rfidinventory

import android.app.Application
import com.megagoglio.rfidinventory.data.auth.AuthRepository
import com.megagoglio.rfidinventory.data.auth.TokenStore
import com.megagoglio.rfidinventory.data.inventory.InventoryContextRepository
import com.megagoglio.rfidinventory.data.local.AppDatabase
import com.megagoglio.rfidinventory.data.local.EpcFilterRepository
import com.megagoglio.rfidinventory.data.remote.ApiClient
import com.megagoglio.rfidinventory.data.sync.SyncRepository
import com.megagoglio.rfidinventory.reader.InventoryController
import com.megagoglio.rfidinventory.reader.ReaderConnectionManager
import com.uk.tsl.rfid.asciiprotocol.AsciiCommander
import com.uk.tsl.rfid.asciiprotocol.device.ReaderManager
import com.uk.tsl.rfid.asciiprotocol.responders.LoggerResponder

class RfidApp : Application() {

    lateinit var connection: ReaderConnectionManager
        private set

    lateinit var inventory: InventoryController
        private set

    lateinit var syncRepository: SyncRepository
        private set

    lateinit var auth: AuthRepository
        private set

    lateinit var inventoryContext: InventoryContextRepository
        private set

    lateinit var epcFilters: EpcFilterRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // O AsciiCommander e um singleton compartilhado por todo o app.
        AsciiCommander.createSharedInstance(applicationContext)
        val commander = AsciiCommander.sharedInstance()

        commander.clearResponders()

        // O LoggerResponder tem de ser o PRIMEIRO da cadeia: ele apenas ecoa cada linha
        // recebida do leitor para o Logcat e repassa adiante. Registrado depois de outro
        // responder, as linhas consumidas por aquele nunca apareceriam no log — e esse log
        // e a principal ferramenta de diagnostico ao trabalhar com o leitor.
        commander.addResponder(LoggerResponder())

        // Habilita os comandos sincronos (*.synchronousCommand()).
        commander.addSynchronousResponder()

        // Suporte a BLE fica desligado (padrao): os leitores Serie 1000/2000 usam
        // Bluetooth Classic, que tem throughput melhor e dispensa a dependencia Blessed.
        ReaderManager.create(applicationContext)

        connection = ReaderConnectionManager()
        inventory = InventoryController(commander)

        val db = AppDatabase.get(applicationContext)
        val dao = db.sessionDao()

        val tokens = TokenStore(applicationContext)

        // A referência é preenchida logo abaixo: o ApiClient precisa avisar o
        // AuthRepository quando o refresh é recusado, e o AuthRepository precisa da
        // AuthApi que o ApiClient cria. Um lateinit resolve o ciclo sem framework de DI.
        lateinit var authRepository: AuthRepository
        val apiClient = ApiClient(
            tokens = tokens,
            onSessionExpired = { authRepository.onSessionEnded() },
        )
        authRepository = AuthRepository(apiClient.authApi, tokens)
        auth = authRepository

        inventoryContext = InventoryContextRepository(applicationContext, apiClient.inventoryApi, dao)
        syncRepository = SyncRepository(applicationContext, dao, apiClient.inventoryApi)
        epcFilters = EpcFilterRepository(db.epcFilterDao())
    }
}
