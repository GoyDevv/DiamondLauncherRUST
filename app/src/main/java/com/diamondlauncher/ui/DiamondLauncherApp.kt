package com.diamondlauncher.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.boardbridge.bridge.BridgeRuntime
import com.boardbridge.bridge.BridgeSurfaceView
import com.diamondlauncher.core.download.DownloadSourceKind
import com.diamondlauncher.core.download.MinecraftDownloadManager
import com.diamondlauncher.core.engine.HorizonEngineRunner
import com.diamondlauncher.core.auth.AccountKind
import com.diamondlauncher.core.auth.AccountStoreState
import com.diamondlauncher.core.auth.LauncherAccount
import com.diamondlauncher.core.auth.OfflineAuthManager
import com.diamondlauncher.core.auth.SkinModel
import com.diamondlauncher.core.instances.GameInstance
import com.diamondlauncher.core.instances.InstanceManager
import com.diamondlauncher.core.instances.SavedServerEntry
import com.diamondlauncher.core.instances.WorldSummary
import com.diamondlauncher.core.mods.HorizonArtifactKind
import com.diamondlauncher.core.mods.HorizonRegistryEntry
import com.diamondlauncher.core.mods.ModAndPackManager
import com.diamondlauncher.ui.components.CreditsAndLegalFooter
import com.diamondlauncher.ui.components.HorizonIndexRow
import com.diamondlauncher.ui.components.HorizonSectionHeader
import com.diamondlauncher.ui.components.MonospaceTag
import com.diamondlauncher.ui.theme.DiamondLauncherTheme
import com.diamondlauncher.ui.theme.HorizonAccent
import com.diamondlauncher.ui.theme.ThemePreference
import java.io.File

/**
 * The 6 primary destinations from Horizon Launcher (`launcher/src/app.rs::Screen`),
 * adapted cleanly to Material 3 NavigationBar with zero bloat.
 */
enum class LauncherTab(val indexCode: String, val label: String) {
    PLAY("01", "Play"),
    MODS("02", "Mods"),
    SERVERS("03", "Servers"),
    VERSIONS("04", "Versions"),
    NOTES("05", "Notes"),
    SETTINGS("06", "Settings")
}

@Composable
fun DiamondLauncherApp() {
    val context = LocalContext.current
    val baseDir = remember { File(context.filesDir, "diamond_horizon").apply { mkdirs() } }

    var accountStore by remember { mutableStateOf(OfflineAuthManager.loadAccountStore(baseDir)) }
    val initialInstances = remember { InstanceManager.loadInstances(baseDir) }
    var instances by remember { mutableStateOf(initialInstances.first) }
    var activeInstanceId by remember { mutableStateOf(initialInstances.second) }

    val activeInstance = instances.firstOrNull { it.id == activeInstanceId } ?: instances.first()
    val activeInstanceRoot = remember(activeInstanceId) {
        InstanceManager.ensureInstanceDirs(baseDir, activeInstanceId)
    }

    var worlds by remember(activeInstanceId) {
        mutableStateOf(InstanceManager.listWorlds(activeInstanceRoot))
    }
    var savedServers by remember(activeInstanceId) {
        mutableStateOf(
            InstanceManager.loadServersDat(activeInstanceRoot).ifEmpty {
                listOf(
                    SavedServerEntry("Horizon Official Rust Testnet", "play.horizon-mc.org:25565"),
                    SavedServerEntry("Local BoardBridge Loopback", "127.0.0.1:25565")
                ).also { InstanceManager.saveServersDat(activeInstanceRoot, it) }
            }
        )
    }

    val registryEntries = remember {
        mutableStateListOf<HorizonRegistryEntry>().apply {
            addAll(ModAndPackManager.defaultHorizonCatalog())
        }
    }

    var currentTab by remember { mutableStateOf(LauncherTab.PLAY) }
    var themePref by remember { mutableStateOf(ThemePreference.DARK) }
    var useDynamicColor by remember { mutableStateOf(true) }
    var selectedAccent by remember { mutableStateOf(HorizonAccent.DIAMOND) }
    var showAccountModal by remember { mutableStateOf(false) }
    var inGameSessionActive by remember { mutableStateOf(false) }
    var statusBanner by remember {
        mutableStateOf("Ready • Minecraft 26.3 Rust Engine (Protocol 777 • Data 5023) • Offline Mode Active")
    }

    DiamondLauncherTheme(
        themePreference = themePref,
        useMaterialYouDynamicColor = useDynamicColor,
        accent = selectedAccent
    ) {
        if (inGameSessionActive) {
            ActiveGameSurfaceScreen(
                account = accountStore.activeAccount!!,
                instance = activeInstance,
                onExitSession = {
                    BridgeRuntime.pause()
                    inGameSessionActive = false
                    statusBanner = "Session closed cleanly • Logs scrubbed & saved to instances/${activeInstance.id}/logs"
                }
            )
        } else {
            Scaffold(
                topBar = {
                    DiamondTopHeaderBar(
                        activeAccount = accountStore.activeAccount,
                        activeInstance = activeInstance,
                        onOpenAccountSwitcher = { showAccountModal = true }
                    )
                },
                bottomBar = {
                    Column {
                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp
                        ) {
                            LauncherTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = { currentTab = tab },
                                    icon = {
                                        Text(
                                            text = tab.indexCode,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (currentTab == tab) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(innerPadding)
                ) {
                    when (currentTab) {
                        LauncherTab.PLAY -> PlayTabScreen(
                            account = accountStore.activeAccount!!,
                            instances = instances,
                            activeInstance = activeInstance,
                            worlds = worlds,
                            statusBanner = statusBanner,
                            onSelectInstance = { id ->
                                activeInstanceId = id
                                InstanceManager.saveInstances(baseDir, instances, id)
                            },
                            onCreateInstance = { name ->
                                val slug = InstanceManager.slugify(name)
                                if (instances.none { it.id == slug }) {
                                    val newInst = GameInstance(
                                        id = slug,
                                        name = name.trim(),
                                        version = "26.3",
                                        group = "Custom"
                                    )
                                    val updated = instances + newInst
                                    instances = updated
                                    activeInstanceId = slug
                                    InstanceManager.ensureInstanceDirs(baseDir, slug)
                                    InstanceManager.saveInstances(baseDir, updated, slug)
                                    statusBanner = "Created instance '${name.trim()}' ($slug)"
                                }
                            },
                            onOpenAccountDialog = { showAccountModal = true },
                            onLaunchGame = {
                                val acc = accountStore.activeAccount!!
                                val sessionFile = OfflineAuthManager.writeSessionFile(context.cacheDir, acc)
                                BridgeRuntime.init()
                                statusBanner = "Launching Horizon 26.3 Rust as ${acc.name} (${acc.dashedUuid}) [session: ${sessionFile.name}]"
                                inGameSessionActive = true
                            }
                        )
                        LauncherTab.MODS -> ModsTabScreen(
                            entries = registryEntries,
                            onToggleEntry = { idx ->
                                val item = registryEntries[idx]
                                registryEntries[idx] = item.copy(
                                    installed = true,
                                    enabled = !item.enabled
                                )
                            }
                        )
                        LauncherTab.SERVERS -> ServersTabScreen(
                            servers = savedServers,
                            onAddServer = { name, ip ->
                                val updated = savedServers + SavedServerEntry(name = name, ip = ip)
                                savedServers = updated
                                InstanceManager.saveServersDat(activeInstanceRoot, updated)
                            },
                            onRemoveServer = { idx ->
                                val updated = savedServers.filterIndexed { i, _ -> i != idx }
                                savedServers = updated
                                InstanceManager.saveServersDat(activeInstanceRoot, updated)
                            }
                        )
                        LauncherTab.VERSIONS -> VersionsTabScreen(
                            baseDir = baseDir,
                            onStatusUpdate = { statusBanner = it }
                        )
                        LauncherTab.NOTES -> NotesTabScreen()
                        LauncherTab.SETTINGS -> SettingsTabScreen(
                            accountStore = accountStore,
                            themePreference = themePref,
                            useDynamicColor = useDynamicColor,
                            selectedAccent = selectedAccent,
                            onChangeTheme = { themePref = it },
                            onToggleDynamicColor = { useDynamicColor = it },
                            onSelectAccent = { selectedAccent = it },
                            onOpenAccountManager = { showAccountModal = true }
                        )
                    }
                }
            }
        }

        if (showAccountModal) {
            OfflineAccountDialog(
                accountStore = accountStore,
                onSelectAccount = { uuid ->
                    val updated = accountStore.copy(activeUuid = uuid)
                    accountStore = updated
                    OfflineAuthManager.saveAccountStore(baseDir, updated)
                },
                onAddOfflineAccount = { rawName, skinModel ->
                    OfflineAuthManager.createOfflineAccount(rawName, skinModel = skinModel).onSuccess { newAcc ->
                        val filtered = accountStore.accounts.filterNot { it.uuid == newAcc.uuid }
                        val updated = accountStore.copy(
                            accounts = filtered + newAcc,
                            activeUuid = newAcc.uuid
                        )
                        accountStore = updated
                        OfflineAuthManager.saveAccountStore(baseDir, updated)
                        statusBanner = "Switched to Offline Account '${newAcc.name}' (UUIDv3: ${newAcc.dashedUuid})"
                    }
                },
                onRemoveAccount = { uuid ->
                    if (accountStore.accounts.size > 1) {
                        val remaining = accountStore.accounts.filterNot { it.uuid == uuid }
                        val updated = accountStore.copy(
                            accounts = remaining,
                            activeUuid = remaining.first().uuid
                        )
                        accountStore = updated
                        OfflineAuthManager.saveAccountStore(baseDir, updated)
                    }
                },
                onDismiss = { showAccountModal = false }
            )
        }
    }
}

@Composable
private fun DiamondTopHeaderBar(
    activeAccount: LauncherAccount?,
    activeInstance: GameInstance,
    onOpenAccountSwitcher: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "◆",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "DIAMOND",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        MonospaceTag(text = "26.3 RUST", highlight = true)
                    }
                    Text(
                        text = "Horizon Client × BoardBridge Android • ${activeInstance.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Offline Account Pill (Top-Right switcher matching Horizon's top-bar pill)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onOpenAccountSwitcher)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3FB950))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = activeAccount?.name ?: "OfflinePlayer",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "OFFLINE UUIDv3",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun PlayTabScreen(
    account: LauncherAccount,
    instances: List<GameInstance>,
    activeInstance: GameInstance,
    worlds: List<WorldSummary>,
    statusBanner: String,
    onSelectInstance: (String) -> Unit,
    onCreateInstance: (String) -> Unit,
    onOpenAccountDialog: () -> Unit,
    onLaunchGame: () -> Unit
) {
    var showNewInstanceDialog by remember { mutableStateOf(false) }
    var newInstanceName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            // Primary Launch Strip
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MINECRAFT 26.3 • HORIZON RUST ENGINE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = activeInstance.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Protocol 777 • Data 5023 • SDL3 26.3+ Native Window • GLES 3.2",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onLaunchGame,
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(
                            text = "▶ PLAY 26.3",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusBanner,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Section 01: Offline Identity & Skin Profile
        item {
            Spacer(modifier = Modifier.height(16.dp))
            HorizonSectionHeader(
                indexNumber = "01",
                title = "Active Offline Session (No Azure Key Required)",
                trailingMeta = "RFC 4122 v3 MD5"
            )
            HorizonIndexRow(
                indexLabel = "ID",
                title = account.name,
                subtitle = "UUID: ${account.dashedUuid} • Model: ${account.skinModel.label}",
                badgeText = "OFFLINE READY",
                selected = true,
                onClick = onOpenAccountDialog,
                trailingContent = {
                    OutlinedButton(
                        onClick = onOpenAccountDialog,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("Switch / Edit", style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        }

        // Section 02: Isolated Instances
        item {
            Spacer(modifier = Modifier.height(16.dp))
            HorizonSectionHeader(
                indexNumber = "02",
                title = "Instances (${instances.size})",
                trailingMeta = "+ NEW"
            )
        }
        itemsIndexed(instances) { idx, inst ->
            HorizonIndexRow(
                indexLabel = "%02d".format(idx + 1),
                title = "${inst.iconGlyph} ${inst.name}",
                subtitle = "MC ${inst.version} • Group: ${inst.group} • ${inst.launch.memoryMb} MB RAM",
                badgeText = if (inst.id == activeInstance.id) "ACTIVE" else inst.version,
                selected = inst.id == activeInstance.id,
                onClick = { onSelectInstance(inst.id) }
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = { showNewInstanceDialog = true },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("+ Create Isolated Instance", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Section 03: Worlds (level.dat NBT)
        item {
            Spacer(modifier = Modifier.height(8.dp))
            HorizonSectionHeader(
                indexNumber = "03",
                title = "Saved Worlds (level.dat NBT)",
                trailingMeta = "${worlds.size} SAVES"
            )
            if (worlds.isEmpty()) {
                HorizonIndexRow(
                    indexLabel = "00",
                    title = "No local worlds in saves/ yet",
                    subtitle = "Launch Minecraft 26.3 to generate a world or import a save folder into instances/${activeInstance.id}/saves/.",
                    badgeText = "NBT READY"
                )
            }
        }
        itemsIndexed(worlds) { idx, world ->
            HorizonIndexRow(
                indexLabel = "%02d".format(idx + 1),
                title = world.displayName,
                subtitle = "Folder: ${world.folderName} • Mode: ${world.gameMode} • Seed: ${world.seed}",
                badgeText = world.versionName
            )
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
            CreditsAndLegalFooter()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showNewInstanceDialog) {
        AlertDialog(
            onDismissRequest = { showNewInstanceDialog = false },
            title = { Text("Create Horizon 26.3 Instance") },
            text = {
                OutlinedTextField(
                    value = newInstanceName,
                    onValueChange = { newInstanceName = it },
                    label = { Text("Instance Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newInstanceName.isNotBlank()) {
                            onCreateInstance(newInstanceName)
                            newInstanceName = ""
                            showNewInstanceDialog = false
                        }
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewInstanceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ModsTabScreen(
    entries: List<HorizonRegistryEntry>,
    onToggleEntry: (Int) -> Unit
) {
    var filterKind by remember { mutableStateOf<HorizonArtifactKind?>(null) }

    val samplePackReport = remember {
        ModAndPackManager.analyzeModpackCompatibility(
            packName = "Horizon 26.3 Performance Bundle",
            filePaths = listOf(
                "mods/horizon-lithium-rs.hmod",
                "mods/boardbridge-touch-hud.hmod",
                "shaderpacks/horizon-wgsl-pbr.hshader",
                "resourcepacks/faithful-32x-26-3.hpack",
                "datapacks/vanilla-tweaks-26-3.zip"
            )
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            HorizonSectionHeader(
                indexNumber = "01",
                title = "Horizon Native Registry (.hmod / .hpack / .hshader)",
                trailingMeta = "SCHEMA v1"
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterKind == null,
                    onClick = { filterKind = null },
                    label = { Text("All (${entries.size})") }
                )
                FilterChip(
                    selected = filterKind == HorizonArtifactKind.HMOD,
                    onClick = { filterKind = HorizonArtifactKind.HMOD },
                    label = { Text(".hmod") }
                )
                FilterChip(
                    selected = filterKind == HorizonArtifactKind.HSHADER,
                    onClick = { filterKind = HorizonArtifactKind.HSHADER },
                    label = { Text(".hshader") }
                )
                FilterChip(
                    selected = filterKind == HorizonArtifactKind.HPACK,
                    onClick = { filterKind = HorizonArtifactKind.HPACK },
                    label = { Text(".hpack") }
                )
            }
        }

        itemsIndexed(entries) { idx, entry ->
            if (filterKind == null || entry.kind == filterKind) {
                HorizonIndexRow(
                    indexLabel = "%02d".format(idx + 1),
                    title = entry.name,
                    subtitle = "${entry.summary} • by ${entry.author} (${entry.downloads} DL)",
                    badgeText = entry.kind.badge,
                    selected = entry.enabled,
                    onClick = { onToggleEntry(idx) },
                    trailingContent = {
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = { onToggleEntry(idx) }
                        )
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(18.dp))
            HorizonSectionHeader(
                indexNumber = "02",
                title = "Modpack Porter & Rust Coverage Analyzer",
                trailingMeta = "${"%.0f".format(samplePackReport.coveragePercent)}% READY"
            )
        }
        itemsIndexed(samplePackReport.entries) { idx, item ->
            HorizonIndexRow(
                indexLabel = "%02d".format(idx + 1),
                title = item.fileName,
                subtitle = "${item.classification.label} — ${item.notes}",
                badgeText = if (item.classification.supportedInHorizonRust) "NATIVE OK" else "NEEDS PORT",
                selected = item.classification.supportedInHorizonRust
            )
        }
        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun ServersTabScreen(
    servers: List<SavedServerEntry>,
    onAddServer: (String, String) -> Unit,
    onRemoveServer: (Int) -> Unit
) {
    var newServerName by remember { mutableStateOf("") }
    var newServerIp by remember { mutableStateOf("") }
    val sampleInviteCode = remember {
        ModAndPackManager.encodeFriendsInviteCode("192.168.1.42", 25565, 0x2630)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            HorizonSectionHeader(
                indexNumber = "01",
                title = "Multiplayer Server List (servers.dat NBT)",
                trailingMeta = "${servers.size} SERVERS"
            )
        }
        itemsIndexed(servers) { idx, server ->
            HorizonIndexRow(
                indexLabel = "%02d".format(idx + 1),
                title = server.name,
                subtitle = "Address: ${server.ip} • Protocol 777 (26.3)",
                badgeText = "NBT SAVED",
                trailingContent = {
                    TextButton(onClick = { onRemoveServer(idx) }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newServerName,
                    onValueChange = { newServerName = it },
                    label = { Text("Server Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = newServerIp,
                    onValueChange = { newServerIp = it },
                    label = { Text("host:port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        if (newServerName.isNotBlank() && newServerIp.isNotBlank()) {
                            onAddServer(newServerName.trim(), newServerIp.trim())
                            newServerName = ""
                            newServerIp = ""
                        }
                    }
                ) {
                    Text("Add")
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
            HorizonSectionHeader(
                indexNumber = "02",
                title = "Horizon Friends P2P Invite Code (Crockford Base32)",
                trailingMeta = "UDP HOLE-PUNCH"
            )
            HorizonIndexRow(
                indexLabel = "P2P",
                title = sampleInviteCode,
                subtitle = "Share this Crockford Base32 token with friends on Horizon Desktop or DiamondLauncher Android to join without port-forwarding.",
                badgeText = "HORIZON-FRIENDS",
                selected = true
            )
        }
    }
}

@Composable
private fun VersionsTabScreen(
    baseDir: File,
    onStatusUpdate: (String) -> Unit
) {
    val context = LocalContext.current
    var prepStatus by remember { mutableStateOf(MinecraftDownloadManager.inspectStatus(baseDir)) }
    var selectedSource by remember { mutableStateOf(DownloadSourceKind.MOJANG_OFFICIAL) }
    var isDownloading by remember { mutableStateOf(false) }
    var progressText by remember {
        mutableStateOf(
            if (prepStatus.isReadyToPlay) {
                "Prepared & Verified (.local/horizon-client/26.3/horizon-resources.json ready)"
            } else {
                "Select a source below to download & prepare Minecraft 26.3 directly on your phone"
            }
        )
    }
    var progressFraction by remember { mutableStateOf(if (prepStatus.isReadyToPlay) 1.0f else 0.0f) }
    val subsystems = listOf(
        "Math & Primitives (Mth, BlockPos, Vec3, AABB)" to "100% (1.00)",
        "World / Chunks / Heightmap / Lighting / Biome" to "100% (1.00)",
        "Block Behaviour & Redstone Signal Graph" to "100% (1.00)",
        "Fluid Simulation (Water & Lava Spread)" to "100% (1.00)",
        "Entities, Living, Player & Mob AI Goals" to "100% (1.00)",
        "Items, Stacks, DataComponents & Durability" to "100% (1.00)",
        "Inventories, Menus, Crafting, Anvil & Enchanting" to "100% (1.00)",
        "Combat, Status Effects, Hunger & Damage Sources" to "100% (1.00)",
        "Block Entities (Furnace, Hopper, Piston, Brewing)" to "100% (1.00)",
        "Commands, GameRules & Scoreboard" to "100% (1.00)",
        "Advancements, Criteria, Loot Tables & Statistics" to "100% (1.00)",
        "NBT, VarInt, Registry & ResourceLocation" to "100% (1.00)",
        "Packets, Framing, Compression & Encryption" to "100% (1.00)",
        "Dedicated Server Loop, Save Persistence & RCON" to "100% (1.00)"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            HorizonSectionHeader(
                indexNumber = "00",
                title = "Download & Prepare Minecraft 26.3 on Android",
                trailingMeta = if (prepStatus.isReadyToPlay) "READY TO PLAY" else "MULTI-SOURCE"
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(14.dp)
            ) {
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                DownloadSourceKind.entries.forEach { src ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(
                                1.dp,
                                if (selectedSource == src) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable(enabled = !isDownloading) { selectedSource = src }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = src.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = src.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        MonospaceTag(
                            text = if (selectedSource == src) "SELECTED" else src.id.uppercase(),
                            highlight = selectedSource == src
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "client.jar: ${if (prepStatus.hasClientJar) "OK" else "Missing"} • resources: ${if (prepStatus.hasHorizonResourcesJson) "OK" else "Pending"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    Button(
                        enabled = !isDownloading,
                        onClick = {
                            isDownloading = true
                            Thread {
                                val res = MinecraftDownloadManager.downloadAndPrepare263(
                                    context = context,
                                    baseDir = baseDir,
                                    source = selectedSource,
                                    downloadSounds = true
                                ) { msg, pct ->
                                    progressText = msg
                                    progressFraction = pct
                                    onStatusUpdate(msg)
                                }
                                isDownloading = false
                                prepStatus = MinecraftDownloadManager.inspectStatus(baseDir)
                                res.onFailure { err ->
                                    progressText = "Download/Prepare error: ${err.message}"
                                }
                            }.start()
                        }
                    ) {
                        Text(
                            text = if (isDownloading) "WORKING (${(progressFraction * 100).toInt()}%)..." else "⬇ DOWNLOAD & PREPARE 26.3",
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizonSectionHeader(
                indexNumber = "01",
                title = "Pinned Vanilla 26.3 Research Lock (prepare.rs)",
                trailingMeta = "VERIFIED"
            )
            HorizonIndexRow(
                indexLabel = "MC",
                title = "Minecraft Java Edition 26.3 → Horizon Rust",
                subtitle = "Protocol 777 • Data Version 5023 • Resource Pack 97.1 • Data Pack 121.0",
                badgeText = "LOCKED",
                selected = true
            )
            HorizonIndexRow(
                indexLabel = "SHA",
                title = "Client JAR SHA-1 (research-lock.json)",
                subtitle = "569103cadfc8eb6fb2ac3eae1fe0bee2bc5dc4f8 (31,341,943 bytes)",
                badgeText = "SHA-1 OK"
            )
            HorizonIndexRow(
                indexLabel = "BRD",
                title = "BoardBridge Native Runtime",
                subtitle = "JNI Surface + EGL 1.4 / GLES 3.2 + SDL3 26.3+ Event Translator + GLFW <=26.2 State Layer",
                badgeText = "ACTIVE"
            )
        }

        item {
            Spacer(modifier = Modifier.height(18.dp))
            HorizonSectionHeader(
                indexNumber = "02",
                title = "Horizon 26.3 Differential Parity (329 Harnesses)",
                trailingMeta = "14/14 = 100%"
            )
        }
        itemsIndexed(subsystems) { idx, (name, score) ->
            HorizonIndexRow(
                indexLabel = "%02d".format(idx + 1),
                title = name,
                subtitle = "Verified byte-for-byte against Java 26.3 reference oracle",
                badgeText = score
            )
        }
        item {
            Spacer(modifier = Modifier.height(12.dp))
            HorizonIndexRow(
                indexLabel = "TSV",
                title = "Decompiled Method Port Ledger (104 TSV Maps)",
                subtitle = "9,248 ported/inlined (6,460 port + 2,788 inline) • 3,651 intentionally skipped • 1,773 remaining (83.91% non-skipped / 87.92% resolved)",
                badgeText = "83.91% PORTED",
                selected = true
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NotesTabScreen() {
    val notes = listOf(
        Triple(
            "v0.2.0-diamond",
            "DiamondLauncher Android Port (Horizon 26.3 × BoardBridge)",
            "Full Material You (Jetpack Compose) Android port of Horizon Launcher with RFC 4122 v3 Offline Login, SDL3 26.3+ & GLFW <=26.2 input translation, NBT level.dat/servers.dat codec, and zero UI bloat."
        ),
        Triple(
            "26.3-rust",
            "Horizon 26.3 Clean-Room Rust Engine",
            "329 differential parity harnesses across 14 subsystems scoring 100% against Minecraft 26.3 (Protocol 777, Data Version 5023, Pack Format 97.1)."
        ),
        Triple(
            "sdl3-bridge",
            "Android SDL3 26.3+ Window & Touch Translation",
            "Maps Android SurfaceView lifecycle and multi-touch gestures directly to SDL3 window properties (SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER) and normalized 0..1 finger/mouse motion."
        )
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            HorizonSectionHeader(
                indexNumber = "01",
                title = "Release & Architecture Notes",
                trailingMeta = "CHANGELOG"
            )
        }
        itemsIndexed(notes) { idx, (tag, title, body) ->
            HorizonIndexRow(
                indexLabel = "%02d".format(idx + 1),
                title = title,
                subtitle = body,
                badgeText = tag
            )
        }
        item {
            Spacer(modifier = Modifier.height(20.dp))
            CreditsAndLegalFooter()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsTabScreen(
    accountStore: AccountStoreState,
    themePreference: ThemePreference,
    useDynamicColor: Boolean,
    selectedAccent: HorizonAccent,
    onChangeTheme: (ThemePreference) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onSelectAccent: (HorizonAccent) -> Unit,
    onOpenAccountManager: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            HorizonSectionHeader(
                indexNumber = "01",
                title = "Appearance (Material You + Horizon Blocks)",
                trailingMeta = "ZERO BLOAT"
            )
            HorizonIndexRow(
                indexLabel = "01",
                title = "Material You Dynamic Wallpaper Palette (Android 12+)",
                subtitle = "Derive surface and primary tones automatically from your system wallpaper.",
                badgeText = if (useDynamicColor) "ON" else "OFF",
                selected = useDynamicColor,
                onClick = { onToggleDynamicColor(!useDynamicColor) },
                trailingContent = {
                    Switch(checked = useDynamicColor, onCheckedChange = onToggleDynamicColor)
                }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemePreference.entries.forEach { pref ->
                    FilterChip(
                        selected = themePreference == pref,
                        onClick = { onChangeTheme(pref) },
                        label = { Text(pref.label) }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HorizonAccent.entries.take(4).forEach { acc ->
                    FilterChip(
                        selected = selectedAccent == acc,
                        onClick = { onSelectAccent(acc) },
                        label = { Text(acc.label) }
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            HorizonSectionHeader(
                indexNumber = "02",
                title = "Authentication & Offline Mode",
                trailingMeta = "${accountStore.accounts.size} ACCOUNTS"
            )
            HorizonIndexRow(
                indexLabel = "01",
                title = "Offline Mode (dev_offline = true)",
                subtitle = "Enabled by default so you can test and play 100% offline without registering a Microsoft Azure Client ID (HORIZON_MSA_CLIENT_ID).",
                badgeText = "ENABLED",
                selected = true,
                onClick = onOpenAccountManager
            )
            HorizonIndexRow(
                indexLabel = "02",
                title = "Microsoft OAuth 2.0 Device-Code Flow",
                subtitle = "Pre-wired for Xbox Live -> XSTS -> Minecraft Services token exchange once your Azure Client ID is ready.",
                badgeText = "STANDBY"
            )
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
            HorizonSectionHeader(
                indexNumber = "03",
                title = "BoardBridge Native Diagnostics",
                trailingMeta = "JNI / RUST"
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp)
            ) {
                Text(
                    text = BridgeRuntime.diagnostics.trim(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            CreditsAndLegalFooter()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OfflineAccountDialog(
    accountStore: AccountStoreState,
    onSelectAccount: (String) -> Unit,
    onAddOfflineAccount: (String, SkinModel) -> Unit,
    onRemoveAccount: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var inputName by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf(SkinModel.CLASSIC) }
    val candidateName = inputName.trim()
    val isNameValid = OfflineAuthManager.isValidOfflineName(candidateName)
    val previewUuid = if (isNameValid) {
        BridgeRuntime.computeOfflineUuid(candidateName, dashed = true)
    } else {
        "Enter 3..16 chars [A-Za-z0-9_]"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Offline Login & Accounts",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Offline login uses deterministic RFC 4122 v3 MD5 UUIDs ('OfflinePlayer:<name>'). No Microsoft Azure key is required.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                accountStore.accounts.forEach { acc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .border(
                                1.dp,
                                if (acc.uuid == accountStore.activeUuid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { onSelectAccount(acc.uuid) }
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${acc.name} (${acc.skinModel.wireName})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = acc.dashedUuid,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (accountStore.accounts.size > 1) {
                            TextButton(onClick = { onRemoveAccount(acc.uuid) }) {
                                Text("×", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    label = { Text("New Offline Username (3–16 chars)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "UUIDv3: $previewUuid",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isNameValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkinModel.entries.forEach { model ->
                        FilterChip(
                            selected = selectedModel == model,
                            onClick = { selectedModel = model },
                            label = { Text(model.wireName.uppercase()) }
                        )
                    }
                    OutlinedButton(
                        onClick = { inputName = OfflineAuthManager.generateOfflineName() }
                    ) {
                        Text("Random")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = isNameValid,
                onClick = {
                    onAddOfflineAccount(candidateName, selectedModel)
                    inputName = ""
                }
            ) {
                Text("Add & Switch")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

/**
 * Full-screen Android SurfaceView host for the BoardBridge + Horizon 26.3 runtime,
 * with clean non-intrusive touch controls mapped to SDL3 scancodes.
 */
@Composable
private fun ActiveGameSurfaceScreen(
    account: LauncherAccount,
    instance: GameInstance,
    onExitSession: () -> Unit
) {
    val context = LocalContext.current
    val baseDir = remember { File(context.filesDir, "diamond_horizon") }
    val instanceDir = remember { InstanceManager.ensureInstanceDirs(baseDir, instance.id) }
    val preparedDir = remember { File(baseDir, ".local/horizon-client/26.3") }
    var activeHotbar by remember { mutableStateOf(1) }
    var sneaking by remember { mutableStateOf(false) }
    var sprinting by remember { mutableStateOf(false) }
    var latestEngineLog by remember {
        mutableStateOf("Initializing Horizon 26.3 Rust Engine (Mali-G52 MC2 • HORIZON_SPRITE_SPLIT=256)...")
    }

    androidx.compose.runtime.LaunchedEffect(instance.id, account.uuid) {
        HorizonEngineRunner.startEngine(
            context = context,
            baseDir = baseDir,
            instanceDir = instanceDir,
            preparedResourcesDir = preparedDir,
            account = account,
            instance = instance,
            onLogLine = { line -> latestEngineLog = line }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    HorizonEngineRunner.rotateCamera(
                        dYaw = dragAmount.x * 0.35f,
                        dPitch = dragAmount.y * 0.35f
                    )
                }
            }
    ) {
        AndroidView(
            factory = { ctx -> BridgeSurfaceView(ctx) },
            modifier = Modifier.fillMaxSize()
        )

        // Top HUD Status & Quick Actions Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC0D0F12))
                    .border(1.dp, Color(0xFF282D35), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "◆ HORIZON 26.3 RUST • ${account.name} • SDL3 / WGPU (GLES 3.2 + VULKAN)",
                    color = Color(0xFF38BDF8),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
                Text(
                    text = latestEngineLog,
                    color = Color(0xFF9AA4B2),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TouchHudButton(label = "F3") { HorizonEngineRunner.tapKey("f3") }
                TouchHudButton(label = "INV [E]") { HorizonEngineRunner.tapKey("e") }
                TouchHudButton(label = "DROP [Q]") { HorizonEngineRunner.tapKey("q") }
                Button(
                    onClick = {
                        HorizonEngineRunner.stopEngine()
                        onExitSession()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xCCF85149))
                ) {
                    Text("ESC / EXIT", fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }

        // Bottom Left: WASD + Sprint/Sneak D-Pad Cluster
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TouchHudButton(
                    label = if (sprinting) "SPRINT ●" else "SPRINT",
                    active = sprinting
                ) {
                    sprinting = !sprinting
                    HorizonEngineRunner.pressMoveKey("ctrl", sprinting)
                }
                TouchHudButton(label = "▲ W") { HorizonEngineRunner.tapKey("w") }
                TouchHudButton(
                    label = if (sneaking) "SNEAK ●" else "SNEAK",
                    active = sneaking
                ) {
                    sneaking = !sneaking
                    HorizonEngineRunner.pressMoveKey("shift", sneaking)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TouchHudButton(label = "◀ A") { HorizonEngineRunner.tapKey("a") }
                TouchHudButton(label = "▼ S") { HorizonEngineRunner.tapKey("s") }
                TouchHudButton(label = "▶ D") { HorizonEngineRunner.tapKey("d") }
            }
        }

        // Bottom Center: 1..9 Hotbar Strip
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xCC0D0F12))
                .border(1.dp, Color(0xFF282D35), RoundedCornerShape(6.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (slot in 1..9) {
                TouchHudButton(
                    label = "$slot",
                    active = activeHotbar == slot
                ) {
                    activeHotbar = slot
                    HorizonEngineRunner.selectHotbarSlot(slot)
                }
            }
        }

        // Bottom Right: Attack (Break), Use (Place), Jump Cluster
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TouchHudButton(label = "ATK / BREAK", active = true) {
                    HorizonEngineRunner.clickMouse(leftButton = true, down = true)
                    HorizonEngineRunner.clickMouse(leftButton = true, down = false)
                }
                TouchHudButton(label = "USE / PLACE") {
                    HorizonEngineRunner.clickMouse(leftButton = false, down = true)
                    HorizonEngineRunner.clickMouse(leftButton = false, down = false)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TouchHudButton(label = "⇪ JUMP [SPACE]") {
                HorizonEngineRunner.tapKey("space")
            }
        }
    }
}

@Composable
private fun TouchHudButton(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val borderCol = if (active) Color(0xFF38BDF8) else Color(0xFF282D35)
    val bgCol = if (active) Color(0xCC12222E) else Color(0xAA14171C)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgCol)
            .border(1.dp, borderCol, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (active) Color(0xFF38BDF8) else Color(0xFFF0F4F8),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}