# Inventário RFID — leitor TSL → API REST

App Android que conecta a leitores **TSL Série 1000/2000** (1128, 1153, 1166, 2128) por
Bluetooth Classic, faz inventário contínuo pelo gatilho físico, deduplica os EPCs em tela e
envia cada sessão de leitura para a API REST do sistema — com fila offline.

Construído sobre o SDK Android oficial da TSL (`Rfid.AsciiProtocol 4.0.1`), copiado de
`../SDK Android RFID`.

## Pré-requisitos

1. **Android Studio** — https://developer.android.com/studio
2. No primeiro início, o Studio instala o **Android SDK** e cria o `local.properties`
   apontando para ele. Se preferir configurar na mão:
   ```properties
   # local.properties (não versionar)
   sdk.dir=C\:\\Users\\gabriel.hochscheidt\\AppData\\Local\\Android\\Sdk
   ```
3. **Android SDK Platform 36** e **Build-Tools 35** — o Gradle baixa sozinho na primeira
   compilação, se o SDK Manager já tiver aceitado as licenças.
4. Um **JDK 21** (ver abaixo — o do Android Studio não serve).

### O JDK importa: use 17, 21 ou 23 — não o JBR do Android Studio

O Gradle 8.13 (wrapper deste projeto) roda em Java 8 a 23. O JBR que acompanha as
versões atuais do Android Studio é o **Java 25**, e com ele o build falha logo na leitura
do script:

```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_'
Unsupported class file major version 69
```

Instale um JDK 21 e aponte `JAVA_HOME` para ele:

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.1-hotspot"
.\gradlew assembleDebug
```

No Android Studio, o equivalente é **Settings > Build, Execution, Deployment > Build Tools
> Gradle > Gradle JDK**, escolhendo o JDK 21 (ou baixando um por ali).

> Subir para o Gradle 9.1 faria o JBR 25 funcionar, mas obrigaria a subir Kotlin e KSP
> junto — migração à parte, que não vale misturar com mudança de funcionalidade.

## Configuração da API

O app é **cliente do inventário** do sistema de gestão (`../rfid-asset`). A URL base vem de
`local.properties` no debug — cada máquina/coletor aponta para o seu backend sem editar o
build:

```properties
# local.properties (não versionar)
api.base.url=http://192.168.0.42:3000/
```

Sem essa chave, o debug usa `http://10.0.2.2:3000/` (o host visto de dentro do emulador).
A porta é a **3000** (API NestJS) — a 8080 é o nginx do frontend Angular. Em release a URL
fica fixa em `app/build.gradle` (`https://api.megagoglio.com/`).

O backend precisa estar rodando com o schema atualizado:

```bash
cd ../rfid-asset
docker compose exec -T postgres psql -U rfid -d rfid_assets \
  < database/migrations/2026-08-24_inventory_reads_collector.sql
```

### O contrato

`data/remote/InventoryApi.kt`. Os nomes de campo são os do backend porque o
`ValidationPipe` global roda com `forbidNonWhitelisted`: **um campo a mais no corpo devolve
400.**

| Uso | Rota |
|---|---|
| inventário aberto | `GET api/v1/inventory/current` |
| setores (cacheados no Room) | `GET api/v1/sectors?skip=0&take=200` |
| abrir/retomar visita | `POST api/v1/inventory/{id}/sectors` |
| enviar leituras | `POST api/v1/inventory/{id}/reads` |
| concluir setor | `PATCH api/v1/inventory/{id}/sectors/{visitId}/complete` |

`clientBatchId` (o id da coleta, gerado no app) é a **chave de idempotência**: o servidor
tem um índice único parcial `(client_batch_id, epc)`, então reenviar o mesmo lote é no-op.
Mudar esse id a cada tentativa traria de volta o inventário duplicado.

### Autenticação

Login de operador (`POST api/v1/auth/login`), com access token de 15 min e refresh de 7
dias guardados em `EncryptedSharedPreferences`. O `Authenticator` do OkHttp renova em 401.

O refresh do backend é **rotacionado** — cada renovação revoga o token usado. Por isso a
renovação é serializada num lock em `ApiClient.kt`: dois refreshes em paralelo (SyncWorker
e UI tomando 401 ao mesmo tempo) derrubariam a sessão inteira.

O perfil `operator` precisa de `sectors:read`, senão o seletor de setor volta 403 — o seed
do backend já concede, e a migração acima corrige bancos já criados.

## Como o app funciona

```
Leitor TSL ──BT Classic──► AsciiCommander ──► InventoryController ──Flow<TagRead>──►
    InventoryViewModel (dedup por EPC) ──► UI Compose
                │ "Finalizar leitura" (exige inventário + setor)
                ▼
        Room (coletas PENDENTE) ──► SyncWorker (WorkManager)
                                          │
                                          ├─► POST /inventory/{id}/sectors  (resolve a visita)
                                          └─► POST /inventory/{id}/reads    (idempotente)
```

A coleta **sempre** é gravada no Room antes de subir para a API. Enviar direto do callback
do leitor perderia a leitura quando a rede caísse — e inventário em galpão acontece
justamente onde não há sinal.

### O fluxo do operador

1. Login com o usuário do sistema (a leitura fica atribuída à pessoa, não ao aparelho).
2. O app carrega o inventário aberto e a lista de setores, e **guarda os setores no Room**.
3. O operador escolhe o setor — do cache, sem depender de rede.
4. Lê pelo gatilho; ao finalizar, a coleta entra na fila com `inventoryId` + `sectorId`.
5. "Concluir setor" só habilita quando não há coleta daquele setor na fila — fechar o setor
   antes disso faria a conciliação rodar sem a leitura.

A coleta guarda **`sectorId`, não o id da visita**: a visita é resolvida no envio, chamando
`selectSector` (idempotente no servidor). É isso que deixa o operador trabalhar offline.

### Arquivos que concentram a lógica

| Arquivo | O que faz |
|---|---|
| `RfidApp.kt` | Cria `AsciiCommander` e `ReaderManager` uma única vez. A ordem dos responders importa. |
| `reader/ReaderConnectionManager.kt` | Conexão, reconexão e ciclo de vida do leitor. |
| `reader/InventoryController.kt` | Configura o leitor e transforma as leituras em `Flow<TagRead>`. |
| `data/remote/ApiClient.kt` | Bearer + renovação serializada do token em 401. |
| `data/inventory/InventoryContextRepository.kt` | Inventário aberto, setor atual e cache de setores. |
| `data/sync/SyncRepository.kt` | Fila de coletas e envio, com classificação de erro por status. |
| `ui/InventoryViewModel.kt` | Deduplicação por EPC e contagem de leituras. |

### Os quatro estados de uma coleta

| Estado | Quando | O que fazer |
|---|---|---|
| `PENDENTE` | gravada, aguardando rede | nada — o worker envia sozinho |
| `ENVIADA` | aceita pelo servidor | — |
| `BLOQUEADA` | HTTP 409: o inventário foi encerrado antes de a coleta chegar | gestor reabre (`PATCH /inventory/{id}/reopen`) e o operador toca em "Tentar de novo" na fila |
| `ERRO` | 4xx definitivo (payload inválido, setor removido) | precisa de intervenção |

`BLOQUEADA` existe para **não descartar leitura boa que chegou tarde**. Um 401 nunca cai em
`ERRO`: se o refresh expirou de madrugada, a coleta do dia seguiria pendente, não perdida.

### Dois detalhes do SDK que não são óbvios

1. **São necessários dois `InventoryCommand` distintos** — um emite configuração e dispara
   scans, o outro é registrado como responder e apenas escuta. Usar o mesmo objeto para as
   duas coisas não funciona.

2. **`setCaptureNonLibraryResponses(true)` é obrigatório no responder.** As leituras
   disparadas pelo gatilho físico não são resposta a nenhum comando que o app enviou; sem
   essa flag elas não chegam ao app. Se o gatilho "não funcionar", verifique isso primeiro.

## Verificação

Antes de testar o app, rode o sample da TSL para validar o hardware — isso separa
"problema no meu código" de "problema no leitor":

```powershell
cd "..\SDK Android RFID"
.\gradlew ":Sample Code:Inventory:installDebug"
```

Parear o leitor nas configurações de Bluetooth do Android antes de abrir qualquer app.

Depois, no app deste projeto:

1. **Conexão** — conecta e reconecta ao voltar do background; ao minimizar, o leitor fica
   livre (confirme abrindo o sample Inventory em paralelo e conectando nele).
2. **Gatilho** — leituras aparecem ao puxar o gatilho físico.
3. **Dedup** — ler as mesmas 5 tags por 30 s: 5 linhas na lista, contador `×N` subindo.
4. **Login** — entrar com um usuário de perfil `operator`; matar o app e reabrir sem pedir
   senha de novo.
5. **Renovação de token** — subir o backend com `JWT_ACCESS_TTL=30s`, ler durante alguns
   minutos e finalizar: a coleta sobe sem o app pedir login (o `Authenticator` renovou).
6. **Offline** — modo avião → escolher setor pelo cache → ler → finalizar → coleta fica
   PENDENTE (ícone de nuvem na barra superior) → desligar modo avião → envio automático,
   e as leituras aparecem no painel da web **no setor certo**.
7. **Idempotência** — matar o app durante o POST para forçar o reenvio da mesma coleta;
   a contagem no painel não pode dobrar.
8. **Coleta tardia** — concluir o setor pela web e então subir uma coleta offline daquele
   setor: a visita reabre sozinha e as leituras entram.
9. **Inventário encerrado** — finalizar o inventário pela web com uma coleta ainda
   pendente: ela vira `BLOQUEADA` (não `ERRO`). Reabrir com `PATCH /inventory/{id}/reopen`
   e tocar em "Tentar de novo" na fila → sobe.
10. **Permissões** — instalação limpa em Android 12+: o fluxo de `BLUETOOTH_CONNECT` /
    `BLUETOOTH_SCAN` aparece e negar mostra explicação em vez de derrubar o app.
11. **Volume** — 200+ tags em campo; a UI não deve engasgar.

O `LoggerResponder` ecoa no Logcat cada linha trocada com o leitor — é a principal
ferramenta de diagnóstico quando algo não funciona.

## Fora do escopo desta v1

Já mapeados no SDK, prontos para quando forem necessários:

- **Escrita/comissionamento de tags** — `Sample Code/ReadWrite/ReadWriteModel.java`
- **Busca de item específico por RSSI** — `Sample Code/TagFinder/`
- **Código de barras** — `BarcodeCommand` + `IBarcodeReceivedDelegate`
- **Restringir leitores autorizados** — `Sample Code/LicenceKeyUserApp/`
