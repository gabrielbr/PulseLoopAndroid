package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.i18n.tr

import com.pulseloop.ui.components.switchRow

import com.pulseloop.ui.theme.dither
import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.coach.config.CoachProviderMode
import com.pulseloop.coach.config.CoachProviderSettingsStore
import com.pulseloop.coach.config.GeminiModel
import com.pulseloop.coach.config.LocalStructuredOutput
import com.pulseloop.coach.local.LocalCapabilityProbe
import com.pulseloop.coach.local.LocalEndpoint
import com.pulseloop.coach.config.MiniMaxModel
import com.pulseloop.coach.config.OpenRouterModel
import com.pulseloop.data.DemoDataSeeder
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.health.HealthConnectAvailability
import com.pulseloop.health.HealthConnectPermissions
import com.pulseloop.health.HealthConnectPrefs
import com.pulseloop.health.HealthConnectExportWorker
import com.pulseloop.health.HealthConnectPermissionReconcile
import com.pulseloop.health.HealthConnectPrefsStore
import com.pulseloop.health.HealthConnectRemovalWorker
import com.pulseloop.health.HealthConnectWatermarks
import com.pulseloop.health.HealthConnectSdk
import com.pulseloop.notifications.CoachNotifications
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.ring.RingConnectionState
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.WearableCapability
import com.pulseloop.service.GlucoseUnit
import com.pulseloop.service.MetricZone
import com.pulseloop.service.RingSyncCoordinator
import com.pulseloop.service.RingSyncWorker
import com.pulseloop.service.VitalColorToken
import com.pulseloop.service.VitalSample
import com.pulseloop.service.ZoneSeverity
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.QuietHoursPrefs
import com.pulseloop.settings.UnitSystem
import com.pulseloop.settings.ZenModeListener
import com.pulseloop.ui.components.DeviceHeroStatus
import com.pulseloop.ui.components.ZoneLineChart
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.wearables.WearableModel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Ported from the Views/Settings Swift screens (iOS #49 rehaul): the focused detail screens the new
 * Settings hub pushes. Each screen hosts the card content that previously lived inline on the
 * flat SettingsScreen, relocated 1:1 (internals unchanged except where the iOS diff changes them).
 */

// MARK: - Shared scaffold

/** Pushed-screen chrome shared by every settings detail screen: back arrow + title + scroll column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubScreen(
    title: String,
    onBack: () -> Unit,
    bottomOverlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = PulseColors.background,
        // The NavHost route wrapper (PulseLoopApp's paddedComposable) has ALREADY applied the
        // outer Scaffold's system-bar padding to this whole subtree. Consuming the insets a
        // second time here — once via contentWindowInsets, once via TopAppBar's own default —
        // is what produced the tall dead band above every settings title.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back"))
                    }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PulseColors.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                // imePadding: the app is edge-to-edge, so without it the keyboard covers fields.
                Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
            bottomOverlay?.invoke(this)
        }
    }
}

// MARK: - AI Coach

/**
 * AI Coach detail screen: master toggle, provider/model/key configuration, tool toggles, and
 * the coach memory list — the old inline "AI Coach" + "Coach Memory" cards. iOS #49 adds the
 * enable-coach prompt: first enabling the coach offers to turn on daily check-ins (the OS
 * notification permission is requested only on "Enable").
 */
@Composable
fun CoachSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val providerStore = remember { CoachProviderSettingsStore(context) }
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(keyStore.apiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(keyStore.model) }
    var coachEnabled by remember { mutableStateOf(keyStore.coachEnabled) }
    var webSearch by remember { mutableStateOf(keyStore.webSearchEnabled) }
    var writeTools by remember { mutableStateOf(keyStore.writeToolsEnabled) }
    var liveMeasurements by remember { mutableStateOf(keyStore.liveMeasurementsEnabled) }
    var environmentContext by remember { mutableStateOf(keyStore.enableEnvironmentContext) }
    var showMemory by remember { mutableStateOf(false) }
    var memoryToDelete by remember { mutableStateOf<String?>(null) }

    // Location & weather (iOS #65d) — opt-in, requests ACCESS_COARSE_LOCATION only when
    // the user turns it on, mirroring CoachEnvironmentContextService.requestPermissionIfNeeded().
    val environmentPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Granted or not, the toggle stays on — WeatherContextService degrades to city-only
        // (or nothing) without a location fix rather than needing a second opt-out step.
    }
    fun setEnvironmentContext(enabled: Boolean) {
        environmentContext = enabled
        keyStore.enableEnvironmentContext = enabled
        if (enabled) environmentPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    // "Enable Coach Check-Ins?" prompt state (iOS #49 CoachSettingsSection).
    var askEnableCheckIns by remember { mutableStateOf(false) }
    var checkInPermissionDenied by remember { mutableStateOf(false) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        keyStore.notificationsEnabled = granted
        checkInPermissionDenied = !granted
        if (granted) CoachNotifications.schedule(context)
    }
    fun enableCheckIns() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            keyStore.notificationsEnabled = true
            checkInPermissionDenied = false
            CoachNotifications.schedule(context)
        }
    }

    // Coach memories — loaded on composition via LaunchedEffect
    var memories by remember { mutableStateOf(emptyList<com.pulseloop.data.entity.CoachMemoryEntity>()) }
    LaunchedEffect(coachEnabled) {
        if (coachEnabled) {
            memories = db.coachMemoryDao().allRanked()
        }
    }

    // The curated OpenAI picks live in one place, [OpenAIModel]; the retired gpt-4o / gpt-4o-mini
    // / o4-mini are gone from it (issue #77), and a stored one reads as the default.
    val models = com.pulseloop.coach.config.OpenAIModel.entries.map { it.slug to it.blurb }

    SettingsSubScreen(title = tr("AI Coach"), onBack = onBack) {
        // AI Coach section — ported from CoachSettingsSection.swift
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("AI Coach"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                // Readiness differs by provider: the local mode is gated by the server address,
                // not a key, so the old key-only check told a correctly-configured user their
                // "API key" was missing.
                val isLocal = providerStore.providerMode == CoachProviderMode.LOCAL_OPENAI_COMPAT
                val ready = if (isLocal) providerStore.hasLocalBaseUrl else keyStore.apiKey.isNotBlank()
                val activeModel = if (isLocal) providerStore.localModel.ifBlank { "local server" }
                    else selectedModel
                Text(
                    if (coachEnabled && ready) trf("Active — {0}", activeModel)
                    else if (coachEnabled && isLocal) tr("Server address needed")
                    else if (coachEnabled) tr("API key needed")
                    else tr("Disabled"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Master toggle
                Row(Modifier.fillMaxWidth()
                    .switchRow(checked = coachEnabled, onCheckedChange = { enabled ->
                        coachEnabled = enabled; keyStore.coachEnabled = enabled
                        if (!enabled) {
                            CoachNotifications.cancel(context)
                        } else if (keyStore.notificationsEnabled) {
                            CoachNotifications.schedule(context)
                        } else {
                            // First time on: ask before enabling check-ins (OS permission only
                            // on "Enable") — iOS #49.
                            askEnableCheckIns = true
                        }
                    }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Enable AI Coach"), Modifier.weight(1f))
                    Switch(checked = coachEnabled, onCheckedChange = null)
                }

                if (checkInPermissionDenied) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("Notifications are off for PulseLoop. Turn them on in Android Settings to get check-ins."),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.danger,
                    )
                }

                if (coachEnabled) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    // Provider picker — ported from CoachSettingsSection.swift (iOS #9/#22).
                    // Key/model/provider changes apply from the next message (the orchestrator
                    // resolves a fresh client per turn).
                    var providerMode by remember { mutableStateOf(providerStore.providerMode) }
                    var geminiKey by remember { mutableStateOf(providerStore.geminiApiKey) }
                    var geminiKeyVisible by remember { mutableStateOf(false) }
                    var orKey by remember { mutableStateOf(providerStore.openRouterApiKey) }
                    var orKeyVisible by remember { mutableStateOf(false) }
                    var minimaxKey by remember { mutableStateOf(providerStore.minimaxApiKey) }
                    var minimaxKeyVisible by remember { mutableStateOf(false) }
                    var geminiModel by remember { mutableStateOf(providerStore.geminiModel) }
                    var orModel by remember { mutableStateOf(providerStore.openRouterModel) }
                    var minimaxModel by remember { mutableStateOf(providerStore.minimaxModel) }
                    var orPrivacy by remember { mutableStateOf(providerStore.orPrivacyRouting) }
                    var orSort by remember { mutableStateOf(providerStore.orProviderSort) }
                    var reasoningEffort by remember { mutableStateOf(providerStore.reasoningEffort) }
                    // Bumped on every key save/remove so the model picker refetches the provider's
                    // list with the new key; the key fields' own state changes per keystroke.
                    var keySaves by remember { mutableIntStateOf(0) }
                    // The provider's live model list (CoachModelCatalog), for the Reasoning picker.
                    var liveListing by remember { mutableStateOf<com.pulseloop.coach.config.CoachModelCatalog.Result.Success?>(null) }
                    var imageInput by remember { mutableStateOf(providerStore.imageInputEnabled) }
                    // Local / self-hosted (docs/local-llm-coach.md).
                    var localBaseUrl by remember { mutableStateOf(providerStore.localBaseUrl) }
                    var localKey by remember { mutableStateOf(providerStore.localApiKey) }
                    var localKeyVisible by remember { mutableStateOf(false) }
                    var localModel by remember { mutableStateOf(providerStore.localModel) }
                    var localToolCalling by remember { mutableStateOf(providerStore.localToolCalling) }
                    var localStructured by remember { mutableStateOf(providerStore.localStructuredOutput) }
                    var localMaxTokens by remember { mutableStateOf(providerStore.localMaxTokens.takeIf { it > 0 }?.toString() ?: "") }
                    var localTimeout by remember { mutableStateOf(providerStore.localTimeoutSeconds.toString()) }
                    // Discovered via GET /v1/models; advisory, the typed slug always wins.
                    var localDiscovered by remember { mutableStateOf<List<String>>(emptyList()) }
                    var localProbeBusy by remember { mutableStateOf(false) }
                    var localProbeResult by remember { mutableStateOf<String?>(null) }
                    var localProbeNotes by remember { mutableStateOf<List<String>>(emptyList()) }
                    var localProbeOk by remember { mutableStateOf(false) }

                    val providerOptions = listOf(
                        CoachProviderMode.USER_OPENAI_KEY to "OpenAI",
                        CoachProviderMode.USER_GEMINI_KEY to "Google Gemini",
                        CoachProviderMode.USER_OPENROUTER_KEY to "OpenRouter (100+ models)",
                        CoachProviderMode.USER_MINIMAX_KEY to "MiniMax",
                        CoachProviderMode.LOCAL_OPENAI_COMPAT to "Local / self-hosted (no key needed)",
                    )
                    val providerLabel = providerOptions.firstOrNull { it.first == providerMode }?.second ?: tr("OpenAI")

                    Text(tr("Provider"), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    var providerExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { providerExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(providerLabel, Modifier.weight(1f))
                            Icon(Icons.Filled.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = providerExpanded, onDismissRequest = { providerExpanded = false }) {
                            providerOptions.forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { providerMode = mode; providerStore.providerMode = mode; providerExpanded = false },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Reusable API-key field with visibility toggle + save/remove, matching the
                    // original OpenAI key field (iOS CoachSettingsSection keyField).
                    @Composable
                    fun KeyField(
                        label: String, value: String, visible: Boolean, saved: Boolean,
                        onValue: (String) -> Unit, onVisibility: () -> Unit, onSave: () -> Unit, onRemove: () -> Unit,
                    ) {
                        // Saving an API key gave no feedback (issue #22). Flash a brief "Saved ✓"
                        // confirmation after a save from either the button or the keyboard's Done.
                        var justSaved by remember { mutableStateOf(false) }
                        LaunchedEffect(justSaved) {
                            if (justSaved) { kotlinx.coroutines.delay(2000); justSaved = false }
                        }
                        val save = { onSave(); justSaved = true }
                        OutlinedTextField(
                            value = value,
                            onValueChange = onValue,
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None
                                else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            trailingIcon = {
                                IconButton(onClick = onVisibility) {
                                    Icon(
                                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (visible) tr("Hide key") else tr("Show key")
                                    )
                                }
                            },
                            keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) save() }),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = save, enabled = value.isNotBlank(), modifier = Modifier.weight(1f)) {
                                Text(if (saved) tr("Update Key") else tr("Save Key"))
                            }
                            if (saved) {
                                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text(tr("Remove")) }
                            }
                        }
                        if (justSaved) {
                            Text(
                                tr("Saved ✓"),
                                style = MaterialTheme.typography.bodySmall,
                                color = PulseColors.success,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    @Composable
                    fun ModelDropdown(label: String, selected: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(selected, Modifier.weight(1f))
                                Icon(Icons.Filled.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                options.forEach { (slug, blurb) ->
                                    DropdownMenuItem(
                                        text = { Column { Text(slug); if (blurb.isNotEmpty()) Text(blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                                        onClick = { onPick(slug); expanded = false },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    when (providerMode) {
                        CoachProviderMode.USER_GEMINI_KEY -> {
                            CoachModelPicker(
                                provider = providerMode, providerName = tr("Gemini"), selected = geminiModel,
                                presets = GeminiModel.entries.map { it.slug to it.blurb },
                                savedKey = keySaves.let { providerStore.geminiApiKey },
                                onPick = { geminiModel = it; providerStore.geminiModel = it },
                                onListing = { liveListing = it },
                            )
                            KeyField(
                                label = tr("Gemini API Key"), value = geminiKey, visible = geminiKeyVisible,
                                saved = providerStore.hasGeminiKey,
                                onValue = { geminiKey = it }, onVisibility = { geminiKeyVisible = !geminiKeyVisible },
                                onSave = { providerStore.geminiApiKey = geminiKey; keySaves++ },
                                onRemove = { geminiKey = ""; providerStore.geminiApiKey = ""; keySaves++ },
                            )
                        }
                        CoachProviderMode.USER_OPENROUTER_KEY -> {
                            CoachModelPicker(
                                provider = providerMode, providerName = tr("OpenRouter"), selected = orModel,
                                presets = OpenRouterModel.entries.map { it.slug to it.blurb },
                                savedKey = keySaves.let { providerStore.openRouterApiKey },
                                onPick = { orModel = it; providerStore.openRouterModel = it },
                                onListing = { liveListing = it },
                            )
                            // The OpenRouter catalog is huge and shifts often — any current
                            // vendor/model slug from openrouter.ai/models works here.
                            OutlinedTextField(
                                value = orModel,
                                onValueChange = { orModel = it; providerStore.openRouterModel = it },
                                label = { Text(tr("Custom model slug (vendor/model)")) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            KeyField(
                                label = tr("OpenRouter API Key"), value = orKey, visible = orKeyVisible,
                                saved = providerStore.hasOpenRouterKey,
                                onValue = { orKey = it }, onVisibility = { orKeyVisible = !orKeyVisible },
                                onSave = { providerStore.openRouterApiKey = orKey; keySaves++ },
                                onRemove = { orKey = ""; providerStore.openRouterApiKey = ""; keySaves++ },
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth()
                                .switchRow(checked = orPrivacy, onCheckedChange = { orPrivacy = it; providerStore.orPrivacyRouting = it }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(tr("Privacy routing"))
                                    Text(tr("Only providers that don't retain data"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = orPrivacy, onCheckedChange = null)
                            }
                            ModelDropdown(
                                tr("Route by"), orSort ?: "auto",
                                listOf("auto" to "Balanced (default)", "price" to "Cheapest first", "throughput" to "Fastest first", "latency" to "Lowest latency first"),
                            ) { providerStore.orProviderSort = it.takeIf { s -> s != "auto" }; orSort = providerStore.orProviderSort }
                        }
                        CoachProviderMode.USER_MINIMAX_KEY -> {
                            CoachModelPicker(
                                provider = providerMode, providerName = tr("MiniMax"), selected = minimaxModel,
                                presets = MiniMaxModel.entries.map { it.slug to it.blurb },
                                savedKey = keySaves.let { providerStore.minimaxApiKey },
                                onPick = { minimaxModel = it; providerStore.minimaxModel = it },
                                onListing = { liveListing = it },
                            )
                            KeyField(
                                label = tr("MiniMax API Key"), value = minimaxKey, visible = minimaxKeyVisible,
                                saved = providerStore.hasMinimaxKey,
                                onValue = { minimaxKey = it }, onVisibility = { minimaxKeyVisible = !minimaxKeyVisible },
                                onSave = { providerStore.minimaxApiKey = minimaxKey; keySaves++ },
                                onRemove = { minimaxKey = ""; providerStore.minimaxApiKey = ""; keySaves++ },
                            )
                        }
                        CoachProviderMode.LOCAL_OPENAI_COMPAT -> {
                            // Any OpenAI-Chat-Completions-compatible server the user runs: Ollama,
                            // llama.cpp, vLLM, SGLang, LM Studio. No key required — see
                            // docs/local-llm-coach.md. The base URL is what gates readiness.
                            val urlProblem = LocalEndpoint.validate(localBaseUrl)
                                .takeIf { localBaseUrl.isNotBlank() }
                            OutlinedTextField(
                                value = localBaseUrl,
                                onValueChange = {
                                    localBaseUrl = it; providerStore.localBaseUrl = it
                                    localProbeResult = null; localProbeOk = false
                                    localProbeNotes = emptyList(); localDiscovered = emptyList()
                                },
                                label = { Text(tr("Server address")) },
                                placeholder = { Text("http://192.168.1.50:11434") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                isError = urlProblem != null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            )
                            Text(
                                urlProblem?.let { LocalEndpoint.message(it) }
                                    ?: tr("Default ports — Ollama 11434 · LM Studio 1234 · llama.cpp 8080 · vLLM 8000 · SGLang 30000. " +
                                       "The /v1 path is added for you."),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (urlProblem != null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )

                            Spacer(Modifier.height(8.dp))
                            // One press does the whole setup: identifies the engine, lists models,
                            // and probes whether the server actually accepts `tools` and
                            // `response_format` — which depend on launch flags that no metadata
                            // endpoint exposes. The detected values are applied straight to the
                            // controls below, which stay editable.
                            Button(
                                onClick = {
                                    localProbeBusy = true; localProbeResult = null; localProbeNotes = emptyList()
                                    scope.launch {
                                        try {
                                            val report = LocalCapabilityProbe.run(
                                                baseUrl = localBaseUrl,
                                                apiKey = localKey,
                                                currentModel = localModel,
                                            )
                                            localDiscovered = report.models
                                            if (report.suggestedModel.isNotBlank()) {
                                                localModel = report.suggestedModel
                                                providerStore.localModel = localModel
                                            }
                                            // Only a probe that reached a verdict may overwrite
                                            // these. Detect is also how you refresh the model
                                            // list, so it gets pressed on a working setup — and
                                            // the safe defaults behind `suggested*` (tools ON,
                                            // structured OFF) would then undo a deliberate choice
                                            // rather than leave it alone. The notes say what was
                                            // skipped and why.
                                            if (report.toolCallingConclusive) {
                                                localToolCalling = report.suggestedToolCalling
                                                providerStore.localToolCalling = localToolCalling
                                            }
                                            if (report.structuredOutputConclusive) {
                                                localStructured = report.suggestedStructuredOutput
                                                providerStore.localStructuredOutput = localStructured
                                            }
                                            // Derived from the server's context window, minus a
                                            // reserve for the prompt — never a straight copy, or
                                            // `prompt + max_tokens` would exceed the context and
                                            // the server would reject the request. 0 means the
                                            // server reported no context window at all (a thin
                                            // proxy, an engine we can't identify, Ollama when
                                            // `/api/show` doesn't parse) — "not detected", which
                                            // is no reason to clear a value the user typed.
                                            if (report.suggestedMaxTokens > 0) {
                                                localMaxTokens = report.suggestedMaxTokens.toString()
                                                providerStore.localMaxTokens = report.suggestedMaxTokens
                                            }
                                            localProbeOk = true
                                            localProbeResult = report.summary
                                            localProbeNotes = report.notes
                                        } catch (e: LocalCapabilityProbe.Unreachable) {
                                            localProbeOk = false; localProbeResult = e.reason
                                        } catch (e: Exception) {
                                            localProbeOk = false
                                            localProbeResult = e.message ?: tr("Couldn't reach the server.")
                                        }
                                        localProbeBusy = false
                                    }
                                },
                                enabled = localBaseUrl.isNotBlank() && urlProblem == null && !localProbeBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (localProbeBusy) tr("Detecting…") else tr("Detect server & configure")) }
                            if (localProbeBusy) {
                                Text(
                                    tr("Sending three tiny test messages. This can take a minute if the model " +
                                    "still has to load."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            localProbeResult?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (localProbeOk) PulseColors.success else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            // Only non-empty when a probe was inconclusive or refused — the
                            // difference between "off because your server said no" and "off
                            // because we couldn't tell" is the whole point of showing it.
                            localProbeNotes.forEach { note ->
                                Text(
                                    "• $note",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            if (localDiscovered.isNotEmpty()) {
                                ModelDropdown(
                                    tr("Model"), localModel.ifBlank { tr("Choose a model") },
                                    localDiscovered.map { it to "" },
                                ) { localModel = it; providerStore.localModel = it }
                            }
                            // Always typeable: a router in front of the server can serve names
                            // /v1/models doesn't list, and llama.cpp ignores the field entirely.
                            OutlinedTextField(
                                value = localModel,
                                onValueChange = { localModel = it; providerStore.localModel = it },
                                label = { Text(tr("Model name")) },
                                placeholder = { Text("qwen3:8b") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))

                            KeyField(
                                label = tr("API key (optional)"), value = localKey, visible = localKeyVisible,
                                saved = providerStore.hasLocalKey,
                                onValue = { localKey = it }, onVisibility = { localKeyVisible = !localKeyVisible },
                                onSave = { providerStore.localApiKey = localKey },
                                onRemove = { localKey = ""; providerStore.localApiKey = "" },
                            )
                            Text(
                                tr("Only needed if you started the server with --api-key. Leave blank otherwise."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth()
                                .switchRow(checked = localToolCalling, onCheckedChange = {
                                    localToolCalling = it; providerStore.localToolCalling = it
                                }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(tr("Tool calling"))
                                    Text(
                                        tr("Lets the coach read your data. Detect sets this for you; turn it off " +
                                        "if your server rejects tools (vLLM needs --enable-auto-tool-choice)."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = localToolCalling, onCheckedChange = null)
                            }

                            Spacer(Modifier.height(8.dp))
                            ModelDropdown(
                                tr("Response format"), localStructured.label,
                                LocalStructuredOutput.entries.map { it.label to it.blurb },
                            ) { picked ->
                                val mode = LocalStructuredOutput.entries.first { it.label == picked }
                                localStructured = mode; providerStore.localStructuredOutput = mode
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = localMaxTokens,
                                    onValueChange = {
                                        localMaxTokens = it.filter { c -> c.isDigit() }
                                        providerStore.localMaxTokens = localMaxTokens.toIntOrNull() ?: 0
                                    },
                                    label = { Text(tr("Max tokens")) },
                                    placeholder = { Text("auto") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                )
                                OutlinedTextField(
                                    value = localTimeout,
                                    onValueChange = {
                                        localTimeout = it.filter { c -> c.isDigit() }
                                        localTimeout.toIntOrNull()?.let { v -> providerStore.localTimeoutSeconds = v }
                                    },
                                    label = { Text(tr("Timeout (s)")) },
                                    modifier = Modifier
                                        .weight(1f)
                                        // The store clamps to 10..1800 on write, and a blank field
                                        // skips the write entirely, so the text can show a value
                                        // that was never stored (type "5", store holds 10) or an
                                        // empty box over a live setting. Reconciling per keystroke
                                        // would fight the user — "1" would jump to "10" — so read
                                        // the stored value back when the field loses focus.
                                        .onFocusChanged { focus ->
                                            if (!focus.isFocused)
                                                localTimeout = providerStore.localTimeoutSeconds.toString()
                                        },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                )
                            }
                            Text(
                                tr("Leave max tokens blank to let the server decide. Raise the timeout for a large " +
                                "model on CPU — a slow reply is not retried."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        else -> {
                            // OpenAI (and legacy modes): the model picker + key field.
                            CoachModelPicker(
                                provider = CoachProviderMode.USER_OPENAI_KEY, providerName = tr("OpenAI"), selected = selectedModel,
                                presets = models,
                                savedKey = keySaves.let { keyStore.apiKey },
                                onPick = { selectedModel = it; keyStore.model = it },
                                onListing = { liveListing = it },
                            )
                            KeyField(
                                label = tr("OpenAI API Key"), value = apiKey, visible = apiKeyVisible,
                                saved = keyStore.apiKey.isNotBlank(),
                                onValue = { apiKey = it }, onVisibility = { apiKeyVisible = !apiKeyVisible },
                                onSave = { keyStore.apiKey = apiKey; keySaves++ },
                                onRemove = { apiKey = ""; keyStore.apiKey = ""; keySaves++ },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("Stored securely in Android Keystore. Never leaves your device except to call the model. Provider changes apply from your next message."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Reasoning effort / Gemini thinking level (iOS #140). The levels differ per model
                    // (GPT-6 Sol rejects None, DeepSeek has no Medium), so the picker lists only what
                    // the selected model accepts, and shows "default" for a stored level it doesn't —
                    // matching CoachClientResolver.resolvedReasoningEffort, which won't send it.
                    val effortProvider = if (providerMode in setOf(
                            CoachProviderMode.USER_GEMINI_KEY, CoachProviderMode.USER_OPENROUTER_KEY,
                            CoachProviderMode.USER_MINIMAX_KEY, CoachProviderMode.LOCAL_OPENAI_COMPAT,
                        )) providerMode else CoachProviderMode.USER_OPENAI_KEY
                    val effortModel = when (effortProvider) {
                        CoachProviderMode.USER_GEMINI_KEY -> geminiModel
                        CoachProviderMode.USER_OPENROUTER_KEY -> orModel
                        CoachProviderMode.USER_OPENAI_KEY -> selectedModel
                        else -> ""
                    }
                    val liveEntry = liveListing?.find(effortModel)
                    val effortOptions = com.pulseloop.coach.config.ReasoningEfforts.options(effortProvider, effortModel, liveEntry)
                    if (effortOptions.isNotEmpty()) {
                        val shownEffort = com.pulseloop.coach.config.ReasoningEfforts.resolve(effortProvider, effortModel, reasoningEffort, liveEntry)
                        Spacer(Modifier.height(8.dp))
                        ModelDropdown(
                            tr("Reasoning effort"),
                            com.pulseloop.coach.config.ReasoningEffort.fromRaw(shownEffort)?.label ?: tr("Default"),
                            listOf("default" to "Model decides (default)") + effortOptions.map { it.rawValue to it.label },
                        ) { providerStore.reasoningEffort = it.takeIf { s -> s != "default" }; reasoningEffort = providerStore.reasoningEffort }
                    }

                    // Image attachments (iOS #31): gates the attach button in the coach chat.
                    Row(Modifier.fillMaxWidth()
                        .switchRow(checked = imageInput, onCheckedChange = { imageInput = it; providerStore.imageInputEnabled = it }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Image attachments"))
                            Text(tr("Attach photos to coach messages"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = imageInput, onCheckedChange = null)
                    }

                    HorizontalDivider()

                    // Tool toggles. Neither MiniMax's compat endpoint nor any self-hosted engine
                    // has a hosted web search, so hide the toggle for both (each client drops the
                    // tool anyway — this is the UI half).
                    if (providerMode != CoachProviderMode.USER_MINIMAX_KEY &&
                        providerMode != CoachProviderMode.LOCAL_OPENAI_COMPAT) {
                        Row(Modifier.fillMaxWidth()
                            .switchRow(checked = webSearch, onCheckedChange = {
                                webSearch = it; keyStore.webSearchEnabled = it
                            }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("Web Search"))
                                Text(tr("Uses additional tokens"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = webSearch, onCheckedChange = null)
                        }
                    }
                    Row(Modifier.fillMaxWidth()
                        .switchRow(checked = writeTools, onCheckedChange = {
                            writeTools = it; keyStore.writeToolsEnabled = it
                        }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Write Actions"))
                            Text(tr("Set goals, log, edit data"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = writeTools, onCheckedChange = null)
                    }
                    Row(Modifier.fillMaxWidth()
                        .switchRow(checked = liveMeasurements, onCheckedChange = {
                            liveMeasurements = it; keyStore.liveMeasurementsEnabled = it
                        }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Live Measurements"))
                            Text(tr("Trigger real-time ring readings"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = liveMeasurements, onCheckedChange = null)
                    }
                    Row(Modifier.fillMaxWidth()
                        .switchRow(checked = environmentContext, onCheckedChange = { setEnvironmentContext(it) }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Use location & weather"))
                            Text(tr("Grounds suggestions in your city's current weather"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = environmentContext, onCheckedChange = null)
                    }
                }
            }
        }

        // Coach Memory — ported from CoachSettingsSection memoryRow
        if (memories.isNotEmpty() && coachEnabled) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Coach Memory"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showMemory = !showMemory }) {
                            Text(if (showMemory) tr("Hide") else trf("Show ({0})", memories.size))
                        }
                    }
                    if (showMemory) {
                        Spacer(Modifier.height(8.dp))
                        memories.forEach { memory ->
                            Card(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(memory.key, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(memory.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                    }
                                    IconButton(onClick = { memoryToDelete = memory.key }) {
                                        Icon(Icons.Filled.Delete, trf("Delete {0}", memory.key), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    memoryToDelete?.let { key ->
        AlertDialog(
            onDismissRequest = { memoryToDelete = null },
            title = { Text(tr("Delete memory?")) },
            text = { Text(trf("The coach will forget \"{0}\". This can't be undone.", key)) },
            confirmButton = {
                TextButton(onClick = {
                    memoryToDelete = null
                    scope.launch { db.coachMemoryDao().deleteByKey(key) }
                }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { memoryToDelete = null }) { Text(tr("Cancel")) }
            },
        )
    }

    if (askEnableCheckIns) {
        AlertDialog(
            onDismissRequest = { askEnableCheckIns = false },
            title = { Text(tr("Enable Coach Check-Ins?")) },
            text = { Text(tr("Get a daily check-in from your coach. You can change this anytime in Settings → Notifications.")) },
            confirmButton = {
                TextButton(onClick = { askEnableCheckIns = false; enableCheckIns() }) { Text(tr("Enable")) }
            },
            dismissButton = {
                TextButton(onClick = { askEnableCheckIns = false }) { Text(tr("Not now")) }
            },
        )
    }
}

// MARK: - Coach Check-Ins

/**
 * Coach Check-Ins detail screen (iOS NotificationsSettingsView): the old inline "Notifications"
 * card, retitled. The controls depend on the AI Coach being enabled, so when it's off they are
 * shown disabled with a hint. Enabling requests the notification permission (Android 13+).
 */
@Composable
fun CheckInsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val scope = rememberCoroutineScope()

    val coachEnabled = keyStore.coachEnabled
    var notificationEnabled by remember { mutableStateOf(keyStore.notificationsEnabled) }
    var notifPermissionDenied by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationEnabled = granted
        keyStore.notificationsEnabled = granted
        notifPermissionDenied = !granted
        if (granted) CoachNotifications.schedule(context)
    }

    fun setNotifications(enabled: Boolean) {
        if (!enabled) {
            notificationEnabled = false
            keyStore.notificationsEnabled = false
            CoachNotifications.cancel(context)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationEnabled = true
            keyStore.notificationsEnabled = true
            notifPermissionDenied = false
            CoachNotifications.schedule(context)
        }
    }

    // Ring battery alerts (iOS #61a) — independent of the coach toggle, its own POST_NOTIFICATIONS
    // gate. Default ON; delivery no-ops until the OS permission is granted.
    var batteryAlertsEnabled by remember { mutableStateOf(keyStore.batteryAlertsEnabled) }
    var batteryPermDenied by remember { mutableStateOf(false) }

    val batteryPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        batteryAlertsEnabled = granted
        keyStore.batteryAlertsEnabled = granted
        batteryPermDenied = !granted
    }

    fun setBatteryAlerts(enabled: Boolean) {
        if (!enabled) {
            batteryAlertsEnabled = false
            keyStore.batteryAlertsEnabled = false
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            batteryPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            batteryAlertsEnabled = true
            keyStore.batteryAlertsEnabled = true
            batteryPermDenied = false
        }
    }

    SettingsSubScreen(title = tr("Notifications"), onBack = onBack) {
        // Guidance and battery alerts work without the coach, so they lead; the coach's own
        // check-ins come last and explain themselves when the coach is off.
        DailyGuidanceSettingsCard()

        // Ring battery alerts — independent of the coach toggle (iOS #61a puts this in its own
        // NotificationsSettingsView section): a local notification at <20% / <10%, once per day.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Ring battery alerts"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("Get notified when your ring drops below 20% and 10%, so tracking doesn't stop mid-day."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()
                    .switchRow(checked = batteryAlertsEnabled, onCheckedChange = { setBatteryAlerts(it) }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Low Battery Notifications"), Modifier.weight(1f))
                    Switch(checked = batteryAlertsEnabled, onCheckedChange = null)
                }
                if (batteryPermDenied) {
                    Text(
                        tr("Notifications are disabled for PulseLoop in Android Settings."),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.danger,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Daily check-ins"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!coachEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("Enable the AI Coach to change these — daily check-ins are written by the coach from your recent trends."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth()
                    .switchRow(checked = notificationEnabled, enabled = coachEnabled, onCheckedChange = { setNotifications(it) }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Daily Check-in Notifications"), Modifier.weight(1f))
                    Switch(checked = notificationEnabled, enabled = coachEnabled, onCheckedChange = null)
                }

                if (notifPermissionDenied) {
                    Text(
                        tr("Notifications are disabled for PulseLoop in Android Settings."),
                        style = MaterialTheme.typography.bodySmall,
                        color = PulseColors.danger,
                    )
                }

                if (notificationEnabled) {
                    Spacer(Modifier.height(8.dp))
                    // Morning time picker
                    var morningHour by remember { mutableStateOf(keyStore.morningHour) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Morning"), Modifier.weight(1f))
                        var amExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(enabled = coachEnabled, onClick = { amExpanded = true }) {
                                Text(String.format("%02d:00", morningHour))
                            }
                            DropdownMenu(expanded = amExpanded, onDismissRequest = { amExpanded = false }) {
                                (0..<24).forEach { h ->
                                    DropdownMenuItem(
                                        text = { Text(String.format("%02d:00", h)) },
                                        onClick = { morningHour = h; keyStore.morningHour = h; amExpanded = false },
                                    )
                                }
                            }
                        }
                    }

                    // Evening time picker
                    var eveningHour by remember { mutableStateOf(keyStore.eveningHour) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("Evening"), Modifier.weight(1f))
                        var pmExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(enabled = coachEnabled, onClick = { pmExpanded = true }) {
                                Text(String.format("%02d:00", eveningHour))
                            }
                            DropdownMenu(expanded = pmExpanded, onDismissRequest = { pmExpanded = false }) {
                                (0..<24).forEach { h ->
                                    DropdownMenuItem(
                                        text = { Text(String.format("%02d:00", h)) },
                                        onClick = { eveningHour = h; keyStore.eveningHour = h; pmExpanded = false },
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = coachEnabled,
                        onClick = {
                            scope.launch {
                                CoachNotifications.showNow(
                                    context,
                                    tr("PulseLoop Coach"),
                                    tr("This is a test check-in notification."),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("Send Test Check-in Now"))
                    }
                }
            }
        }

    }
}

/**
 * Daily guidance switches (docs/health-app-feature-research.md). They work without the AI coach:
 * every message is built on the phone from ring data.
 */
@Composable
private fun DailyGuidanceSettingsCard() {
    val context = LocalContext.current
    val prefs = remember { com.pulseloop.notifications.GuidancePrefs(context) }
    var vitals by remember { mutableStateOf(prefs.vitalsAlerts) }
    var weekly by remember { mutableStateOf(prefs.weeklyReport) }
    var bedtime by remember { mutableStateOf(prefs.bedtimeReminder) }
    var meal by remember { mutableStateOf(prefs.mealReminder) }
    var battery by remember { mutableStateOf(prefs.bedtimeBattery) }
    var inactive by remember { mutableStateOf(prefs.inactivityAlerts) }
    var progress by remember { mutableStateOf(prefs.activityProgress) }
    var medsOn by remember { mutableStateOf(prefs.medReminders) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Daily guidance"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                tr("Built on your phone from your ring's data. No AI key needed."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GuidanceSwitch(
                tr("Unusual signals alert"),
                tr("When two or more of last night's signals are outside your usual range."),
                vitals,
            ) { vitals = it; prefs.vitalsAlerts = it }
            GuidanceSwitch(
                tr("Weekly report"),
                tr("Sunday evening: your week compared with the last one, and one thing to focus on."),
                weekly,
            ) { weekly = it; prefs.weeklyReport = it }
            GuidanceSwitch(
                tr("Wind-down reminder"),
                tr("A nudge about 30 minutes before your suggested bedtime."),
                bedtime,
            ) { bedtime = it; prefs.bedtimeReminder = it }
            GuidanceSwitch(
                tr("Meal reminder"),
                tr("Around lunch and dinner, only when nothing is logged yet. Needs nutrition tracking on."),
                meal,
            ) { meal = it; prefs.mealReminder = it }
            GuidanceSwitch(
                tr("Charge before bed"),
                tr("When the ring is at 30% or less a couple of hours before your usual bedtime."),
                battery,
            ) { battery = it; prefs.bedtimeBattery = it }
            GuidanceSwitch(
                tr("Move reminder"),
                tr("Under 100 steps in an hour, 9:00 to 20:00, at most every two hours. Off in Rest Mode."),
                inactive,
            ) { inactive = it; prefs.inactivityAlerts = it }
            GuidanceSwitch(
                tr("Step progress"),
                tr("Early evening, when you're under 70% of your step goal. Off in Rest Mode."),
                progress,
            ) { progress = it; prefs.activityProgress = it }
            GuidanceSwitch(
                tr("Medication reminders"),
                tr("Once per dose, at its time, for what you add with + Log → Medication or supplement."),
                medsOn,
            ) { medsOn = it; prefs.medReminders = it }
        }
    }
}

@Composable
private fun GuidanceSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.switchRow(checked = checked, onCheckedChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

// MARK: - User Profile

/**
 * User Profile detail screen: measurement units (folded in from the old standalone "Units"
 * card — iOS keeps units inside the profile editor) plus the shared ProfileEditor and save.
 * BP/glucose calibration moved to the capability-gated Calibration screen (iOS #49).
 */
@Composable
fun ProfileSettingsScreen(coordinator: RingSyncCoordinator?, onBack: () -> Unit, onOpenPostpartum: () -> Unit = {}) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    var profileDraft by remember { mutableStateOf<com.pulseloop.ui.onboarding.ProfileDraft?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    var profileReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(profileReload) {
        val p = db.userProfileDao().get()
        profileDraft = com.pulseloop.ui.onboarding.ProfileDraft.from(
            p, existingUnits = keyStore.resolvedUnitSystem,
        )
    }

    SettingsSubScreen(title = tr("User Profile"), onBack = onBack) {
        Text(
            tr("Your age, sex, height and weight let the ring compute accurate calories (and, on " +
                "56ff/Jring rings, blood sugar). Blood pressure is measured directly by the sensor."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A smart scale's weight and body composition, read from Health Connect.
        HealthConnectBodyCard(onUpdated = { profileReload++ })
        // Postpartum / breastfeeding: only ever switched on here, by the user.
        PostpartumProfileCard(onOpen = onOpenPostpartum)

        profileDraft?.let { draft ->
            // Shared editor (iOS #48) — same component as the onboarding Profile step, with
            // the units picker shown (the old Units card folded in). Unit changes persist to
            // the app-wide preference immediately, like the old card did.
            com.pulseloop.ui.onboarding.ProfileEditor(
                draft = draft,
                onDraftChange = { updated ->
                    if (updated.units != draft.units) {
                        keyStore.unitSystem = updated.units.name
                        // Widgets show unit-converted distance/temperature — refresh them.
                        com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                    }
                    profileDraft = updated
                    savedMsg = null
                },
                showUnits = true,
            )
            if (keyStore.unitSystem != null) {
                TextButton(onClick = {
                    keyStore.unitSystem = null
                    profileDraft = draft.copy(units = keyStore.resolvedUnitSystem)
                    com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                }) {
                    Text(tr("Reset units to auto-detect"), style = MaterialTheme.typography.labelSmall)
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        val current = profileDraft ?: return@launch
                        val existing = db.userProfileDao().get()
                        val entity = current.applyTo(existing ?: com.pulseloop.data.entity.UserProfileEntity())
                        db.userProfileDao().upsert(entity)
                        // Keep pushing the stored BP calibration alongside the profile (the
                        // calibration values themselves are edited on the Calibration screen).
                        coordinator?.applyUserSettings(entity, keyStore.bpAdjustSystolic, keyStore.bpAdjustDiastolic)
                        // Profile (age/sex) shifts widget reference zones — republish.
                        com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                        savedMsg = if (coordinator?.isConnected == true)
                            tr("Saved & sent to ring ✓") else tr("Saved — will sync on next connect")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Save profile")) }
            savedMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// MARK: - Physiology

/**
 * Physiology detail screen (iOS #35 PhysiologySettingsView): optional inputs that tune the vitals
 * reference ranges (VitalsThresholdEngine). Everything is optional — the engine uses sensible
 * defaults when unset. Persisted to [ApiKeyStore] alongside units/calibration (Android keeps these
 * app-side prefs off the Room UserProfile). Saving republishes the widget snapshot so its zones
 * re-interpret without a new measurement (the values feed [com.pulseloop.service.UserPhysiologyProfile]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhysiologySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val imperial = keyStore.resolvedUnitSystem == UnitSystem.IMPERIAL

    // Initialised straight from the (synchronous) prefs: loading them in a LaunchedEffect showed
    // the defaults for a frame and then flipped every control to the stored value.
    var athleteMode by remember { mutableStateOf(keyStore.athleteMode) }
    var altitudeText by remember {
        mutableStateOf(
            keyStore.altitudeMeters?.let { m ->
                // Stored canonical metres; show in the display unit (iOS enters ft, stores m).
                (if (imperial) (m / 0.3048).roundToInt() else m.roundToInt()).toString()
            } ?: "",
        )
    }
    var betaBlockers by remember { mutableStateOf(keyStore.usesBetaBlockers) }
    var lungCondition by remember { mutableStateOf(keyStore.hasKnownLungCondition) }
    var glucoseUnit by remember { mutableStateOf(keyStore.preferredGlucoseUnit) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    SettingsSubScreen(title = tr("Physiology"), onBack = onBack) {
        Text(
            tr("Optional refinements to how your vitals are interpreted. Leave anything unset and " +
                "PulseLoop uses sensible defaults."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PhysiologyCard(
            title = tr("Fitness"),
            footer = tr("Treats a low resting heart rate as a sign of fitness rather than a concern, and relaxes the low-HR threshold."),
        ) {
            Row(Modifier.switchRow(checked = athleteMode, onCheckedChange = { athleteMode = it; savedMsg = null }), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Athlete mode"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = athleteMode, onCheckedChange = null)
            }
        }

        PhysiologyCard(
            title = tr("Environment"),
            footer = tr("Above ~2000 m, normal blood-oxygen readings run lower. We use this to avoid false low-oxygen warnings."),
        ) {
            OutlinedTextField(
                value = altitudeText,
                onValueChange = { altitudeText = it.filter(Char::isDigit).take(5); savedMsg = null },
                label = { Text(if (imperial) tr("Typical altitude (ft)") else tr("Typical altitude (m)")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        PhysiologyCard(
            title = tr("Health context"),
            footer = tr("Optional. Both can change what's expected for your heart rate or oxygen, so we adjust labels instead of alarming."),
        ) {
            TriStateRow(tr("Beta-blockers"), betaBlockers) { betaBlockers = it; savedMsg = null }
            Spacer(Modifier.height(12.dp))
            TriStateRow(tr("Known lung condition"), lungCondition) { lungCondition = it; savedMsg = null }
        }

        PhysiologyCard(title = tr("Units"), footer = null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Glucose unit"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    GlucoseUnit.entries.forEachIndexed { i, u ->
                        SegmentedButton(
                            selected = glucoseUnit == u,
                            onClick = { glucoseUnit = u; savedMsg = null },
                            shape = SegmentedButtonDefaults.itemShape(i, GlucoseUnit.entries.size),
                        ) { Text(u.label) }
                    }
                }
            }
        }

        Button(
            onClick = {
                keyStore.athleteMode = athleteMode
                keyStore.usesBetaBlockers = betaBlockers
                keyStore.hasKnownLungCondition = lungCondition
                keyStore.preferredGlucoseUnit = glucoseUnit
                val entered = altitudeText.trim().toDoubleOrNull()
                keyStore.altitudeMeters =
                    if (entered != null && entered > 0) (if (imperial) entered * 0.3048 else entered) else null
                // Physiology shifts the vitals reference zones the widgets render — republish.
                com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                savedMsg = tr("Saved ✓")
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(tr("Save")) }
        savedMsg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A titled card with optional explanatory footer, matching iOS `SettingsGroup`. */
@Composable
private fun PhysiologyCard(title: String, footer: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
            if (footer != null) {
                Spacer(Modifier.height(8.dp))
                Text(footer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Not set / No / Yes segmented control over a nullable Boolean, mirroring iOS `TriState`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriStateRow(title: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    val options = listOf<Pair<String, Boolean?>>("Not set" to null, "No" to false, "Yes" to true)
    // Stacked rather than side by side: three translated segments ("Não definido" …) left the
    // weighted title a few dp and broke it a character per line.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (label, v) ->
                SegmentedButton(
                    selected = value == v,
                    onClick = { onChange(v) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    modifier = Modifier.weight(1f),
                ) { Text(tr(label), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// MARK: - Calibration

/**
 * Calibration detail screen (iOS CalibrationSettingsView): blood-pressure cuff calibration and
 * the app-side blood-sugar display offset, relocated from the old inline Profile card. The hub
 * only shows this for devices declaring BLOOD_PRESSURE / BLOOD_SUGAR (jring — Colmi rings
 * support neither).
 */
@Composable
fun CalibrationSettingsScreen(coordinator: RingSyncCoordinator?, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    // Initialised from the prefs directly, so the fields don't flash empty / "No calibration set".
    var bpSys by remember { mutableStateOf(keyStore.bpAdjustSystolic.takeIf { it > 0 }?.toString() ?: "") }
    var bpDia by remember { mutableStateOf(keyStore.bpAdjustDiastolic.takeIf { it > 0 }?.toString() ?: "") }
    var glucoseRef by remember { mutableStateOf(keyStore.glucoseRefMgdl.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "") }
    var glucoseOffset by remember { mutableStateOf(keyStore.glucoseOffsetMgdl) }
    var glucoseMsg by remember { mutableStateOf<String?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    SettingsSubScreen(title = tr("Calibration"), onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Blood pressure calibration"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    tr("Optional. Enter a recent cuff reading so the ring offsets its values to match."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = bpSys, onValueChange = { bpSys = it.filter(Char::isDigit).take(3) },
                        label = { Text(tr("Systolic")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = bpDia, onValueChange = { bpDia = it.filter(Char::isDigit).take(3) },
                        label = { Text(tr("Diastolic")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val sysI = bpSys.toIntOrNull() ?: 0
                            val diaI = bpDia.toIntOrNull() ?: 0
                            keyStore.bpAdjustSystolic = sysI
                            keyStore.bpAdjustDiastolic = diaI
                            coordinator?.applyUserSettings(db.userProfileDao().get(), sysI, diaI)
                            savedMsg = if (coordinator?.isConnected == true)
                                tr("Saved & sent to ring ✓") else tr("Saved — will sync on next connect")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("Save calibration")) }
                savedMsg?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Blood sugar calibration"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    tr("Optional. The ring estimates glucose from your profile, not a real sensor. " +
                        "Enter a recent lab/meter reading (mg/dL) to offset the displayed value to match."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = glucoseRef, onValueChange = { glucoseRef = it.filter(Char::isDigit).take(3) },
                        label = { Text(tr("Reading (mg/dL)")) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.weight(1.4f),
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                val ref = glucoseRef.toDoubleOrNull()
                                if (ref == null || ref <= 0) {
                                    glucoseMsg = tr("Enter a valid mg/dL reading")
                                    return@launch
                                }
                                val latestRaw = db.measurementDao().latest(MeasurementKind.BLOOD_SUGAR.name)
                                if (latestRaw == null) {
                                    glucoseMsg = tr("Take a blood sugar measurement first, then calibrate")
                                    return@launch
                                }
                                // Stored readings are raw (offset is applied only at display),
                                // so latestRaw is the ring's uncalibrated value.
                                keyStore.glucoseOffsetMgdl = ref - latestRaw
                                keyStore.glucoseRefMgdl = ref
                                glucoseOffset = keyStore.glucoseOffsetMgdl
                                glucoseMsg = tr("Calibrated ✓ (offset %+.0f mg/dL)").format(glucoseOffset)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("Calibrate")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (glucoseOffset != 0.0) tr("Current offset: %+.0f mg/dL").format(glucoseOffset) else tr("No calibration set"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (glucoseOffset != 0.0) {
                        TextButton(onClick = {
                            keyStore.glucoseOffsetMgdl = 0.0
                            keyStore.glucoseRefMgdl = 0.0
                            glucoseOffset = 0.0
                            glucoseRef = ""
                            glucoseMsg = tr("Calibration cleared")
                        }, modifier = Modifier.widthIn(max = 120.dp)) { Text(tr("Reset"), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                glucoseMsg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

// MARK: - Goals

/** Goals detail screen: the old inline "Goals" card (shared GoalEditor + save). */
@Composable
fun GoalsSettingsScreen(coordinator: RingSyncCoordinator?, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    val goalUnits = keyStore.resolvedUnitSystem
    var goalDraft by remember { mutableStateOf<com.pulseloop.ui.onboarding.GoalDraft?>(null) }
    var goalSavedMsg by remember { mutableStateOf<String?>(null) }
    var goalReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(goalReload) {
        goalDraft = com.pulseloop.ui.onboarding.GoalDraft.from(db.userGoalDao().get(), goalUnits)
    }

    SettingsSubScreen(title = tr("Goals"), onBack = onBack) {
        Text(
            tr("Daily and weekly targets behind the activity rings."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CoachManagedCard(com.pulseloop.service.GoalSync.GOALS_FIELDS) { goalReload++ }

        goalDraft?.let { draft ->
            com.pulseloop.ui.onboarding.GoalEditor(
                draft = draft,
                onDraftChange = { goalDraft = it; goalSavedMsg = null },
                units = goalUnits,
                includeWeeklyWorkouts = true,
            )
            Button(
                onClick = {
                    scope.launch {
                        val existing = db.userGoalDao().get()
                        val saved = draft.applyTo(
                            existing ?: com.pulseloop.data.entity.UserGoalEntity(),
                            goalUnits,
                            includeWeeklyWorkouts = true,
                        )
                        db.userGoalDao().upsert(saved)
                        com.pulseloop.service.GoalSync.afterManualSave(context, saved, coordinator)
                        goalSavedMsg = if (coordinator?.isConnected == true)
                            tr("Saved & sent to ring ✓") else tr("Saved")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Save goals")) }
            goalSavedMsg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// MARK: - Measurement Frequency

/**
 * Measurement Frequency detail screen: the old inline "Measurement" card (iOS #19 content).
 * The hub only shows the row for rings declaring MEASUREMENT_INTERVAL (Colmi).
 */
@Composable
fun MeasurementSettingsScreen(coordinator: RingSyncCoordinator?, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    // Optional-wrapped: null = still loading, empty = no ring. Reading null as "no ring" flashed
    // "Pair a ring to configure…" before the toggles appeared.
    val deviceLoad = remember(db) { db.deviceDao().currentFlow().map { java.util.Optional.ofNullable(it) } }
        .collectAsState(initial = null).value
    val device: com.pulseloop.data.entity.DeviceEntity? = deviceLoad?.orElse(null)
    val capabilities = device?.capabilities.orEmpty()
    val minimumInterval = if (device?.deviceType == RingDeviceType.YCBT) 30 else 5
    val intervalSteps = ((60 - minimumInterval) / 5 - 1).coerceAtLeast(0)
    // Only offered where the ring's protocol actually carries a separate blood-oxygen interval —
    // elsewhere the slider moved, the value saved, and "Saved & sent to ring ✓" appeared over a
    // setting nothing on the wire could express (issue #66).
    val supportsSpo2Interval = device?.deviceType?.supportsSeparateSpo2Interval == true
    // The SpO₂ slider runs five minutes lower than the HR one (its floor is "follow heart rate"),
    // so it needs its own step count: reusing the HR one put ticks 5.45 min apart, none of which
    // the 5-minute snap could land on.
    val spo2IntervalSteps = ((60 - (minimumInterval - 5)) / 5 - 1).coerceAtLeast(0)
    val supportsStressSetting = WearableCapability.STRESS in capabilities &&
        device?.deviceType != RingDeviceType.YCBT

    var cfgHrEnabled by remember { mutableStateOf(true) }
    var cfgHrInterval by remember { mutableStateOf(5) }
    var cfgSpo2 by remember { mutableStateOf(true) }
    /** 0 = follow the heart-rate interval, which is what it always did (issue #66). */
    var cfgSpo2Interval by remember { mutableStateOf(0) }
    var cfgStress by remember { mutableStateOf(true) }
    var cfgHrv by remember { mutableStateOf(true) }
    var cfgTemp by remember { mutableStateOf(true) }
    var cfgLoaded by remember { mutableStateOf(false) }
    var cfgSavedMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(device?.id, device?.capabilitiesRaw) {
        val id = device?.id ?: return@LaunchedEffect
        cfgLoaded = false
        val config = db.deviceMeasurementConfigDao().byDevice(id)
        cfgHrEnabled = config?.hrEnabled ?: true
        cfgHrInterval = (config?.hrIntervalMinutes ?: minimumInterval).coerceIn(minimumInterval, 60)
        cfgSpo2 = (config?.spo2Enabled ?: true) && WearableCapability.SPO2 in capabilities
        cfgSpo2Interval = (config?.spo2IntervalMinutes ?: 0).let {
            if (it <= 0) 0 else it.coerceIn(minimumInterval, 60)
        }
        cfgStress = (config?.stressEnabled ?: true) && supportsStressSetting
        cfgHrv = (config?.hrvEnabled ?: true) && WearableCapability.HRV in capabilities
        cfgTemp = (config?.temperatureEnabled ?: true) && WearableCapability.TEMPERATURE in capabilities
        cfgLoaded = true
    }

    @Composable
    fun VitalToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth()
            .switchRow(checked = checked, enabled = cfgLoaded, onCheckedChange = { onChange(it); cfgSavedMsg = null }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = checked, enabled = cfgLoaded, onCheckedChange = null)
        }
    }

    SettingsSubScreen(title = tr("Measurement Frequency"), onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Measurement"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("How often the ring measures in the background. Shorter heart-rate intervals give finer history at the cost of battery."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (deviceLoad == null) {
                    // Loading: the header above is enough until the ring row arrives.
                } else if (device == null) {
                    Text(
                        tr("Pair a ring to configure its all-day measurements."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    VitalToggle(tr("All-day heart rate"), cfgHrEnabled) { cfgHrEnabled = it }
                    if (cfgHrEnabled) {
                        Text(
                            trf("Every {0} min", cfgHrInterval),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            enabled = cfgLoaded,
                            value = cfgHrInterval.toFloat(),
                            onValueChange = { cfgHrInterval = ((it / 5).toInt() * 5).coerceIn(minimumInterval, 60); cfgSavedMsg = null },
                            valueRange = minimumInterval.toFloat()..60f,
                            steps = intervalSteps,
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    if (WearableCapability.SPO2 in capabilities) {
                        VitalToggle(tr("Blood oxygen (SpO₂)"), cfgSpo2) { cfgSpo2 = it }
                        // The monitor command takes the same shape for every vital
                        // (`01 <key> {enable, interval}`), so blood oxygen always had an interval —
                        // it just wasn't surfaced and silently followed heart rate. On a ring
                        // running both monitors hourly that is most of the battery (issue #66).
                        if (cfgSpo2 && supportsSpo2Interval) {
                            Text(
                                if (cfgSpo2Interval <= 0) tr("Same as heart rate") else trf("Every {0} min", cfgSpo2Interval),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                enabled = cfgLoaded,
                                // The bottom of the track is "follow heart rate" rather than a
                                // shorter interval than the ring will honour.
                                value = (if (cfgSpo2Interval <= 0) minimumInterval - 5 else cfgSpo2Interval).toFloat(),
                                onValueChange = {
                                    val stepped = (it / 5).toInt() * 5
                                    cfgSpo2Interval = if (stepped < minimumInterval) 0 else stepped.coerceAtMost(60)
                                    cfgSavedMsg = null
                                },
                                valueRange = (minimumInterval - 5).toFloat()..60f,
                                steps = spo2IntervalSteps,
                            )
                        }
                    }
                    if (supportsStressSetting) {
                        VitalToggle(tr("Stress"), cfgStress) { cfgStress = it }
                    }
                    if (WearableCapability.HRV in capabilities) {
                        VitalToggle("HRV", cfgHrv) { cfgHrv = it }
                    }
                    if (WearableCapability.TEMPERATURE in capabilities) {
                        VitalToggle(tr("Temperature"), cfgTemp) { cfgTemp = it }
                    }

                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = cfgLoaded,
                        onClick = {
                            scope.launch {
                                db.deviceMeasurementConfigDao().upsert(
                                    com.pulseloop.data.entity.DeviceMeasurementConfigEntity(
                                        deviceId = device.id,
                                        hrIntervalMinutes = cfgHrInterval.coerceIn(minimumInterval, 60),
                                        hrEnabled = cfgHrEnabled,
                                        spo2Enabled = cfgSpo2 && WearableCapability.SPO2 in capabilities,
                                        spo2IntervalMinutes = if (!supportsSpo2Interval || cfgSpo2Interval <= 0) 0
                                            else cfgSpo2Interval.coerceIn(minimumInterval, 60),
                                        stressEnabled = cfgStress && supportsStressSetting,
                                        hrvEnabled = cfgHrv && WearableCapability.HRV in capabilities,
                                        temperatureEnabled = cfgTemp && WearableCapability.TEMPERATURE in capabilities,
                                        updatedAt = System.currentTimeMillis(),
                                    )
                                )
                                coordinator?.applyMeasurementSettings()
                                cfgSavedMsg = if (coordinator?.isConnected == true)
                                    tr("Saved & sent to ring ✓") else tr("Saved — will sync on next connect")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("Save measurement settings")) }
                    cfgSavedMsg?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// MARK: - Wearable

/**
 * The opt-in quiet-hours gate (issue #79): sleep the ring opens outside the window is ignored on
 * import. The rationale lives on [QuietHoursPrefs]; this is just the controls — a switch and the
 * two window ends, on the Wearable screen because it governs what a sync is allowed to store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietHoursCard() {
    val context = LocalContext.current
    val prefs = remember { QuietHoursPrefs(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var gateByZenMode by remember {
        mutableStateOf(prefs.gateByZenMode && ZenModeListener.isAccessGranted(context))
    }
    var start by remember { mutableStateOf(prefs.startMinutes) }
    var end by remember { mutableStateOf(prefs.endMinutes) }
    // "start" | "end" while a picker is open.
    var picking by remember { mutableStateOf<String?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ZenModeListener.isAccessGranted(context)
                if (prefs.gateByZenMode && !granted) {
                    prefs.gateByZenMode = false
                    ZenModeListener.setEnabled(context, false)
                }
                gateByZenMode = prefs.gateByZenMode && granted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth()
                .switchRow(checked = enabled, onCheckedChange = { enabled = it; prefs.enabled = it }),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Sleep quiet hours"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = null)
            }
            Text(
                tr("When on, sleep the ring records outside the window is trimmed off on import — a " +
                    "still wrist reads as sleep, so an evening on the sofa can log a phantom session " +
                    "and run the night high. Set the start to when you actually go to bed. Naps " +
                    "outside the window are skipped too. Already-imported nights are never touched, " +
                    "and what the gate skips while it is narrower stays skipped."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (enabled) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth()
                    .switchRow(checked = gateByZenMode, onCheckedChange = { desired ->
                            if (desired) {
                                ZenModeListener.setEnabled(context, true)
                                if (ZenModeListener.isAccessGranted(context)) {
                                    gateByZenMode = true
                                    prefs.gateByZenMode = true
                                } else {
                                    showPermissionDialog = true
                                }
                            } else {
                                gateByZenMode = false
                                prefs.gateByZenMode = false
                                ZenModeListener.setEnabled(context, false)
                            }
                        }),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            tr("Track Bedtime / DND mode"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            tr("Automatically align quiet hours with Android's Bedtime or Do Not Disturb mode."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = gateByZenMode, onCheckedChange = null)
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    if (gateByZenMode) tr("Fallback schedule (used when no Bedtime/DND mode was active):")
                    else tr("Quiet hours schedule:"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.weight(1f)) {
                        Text(trf("From {0}", formatMinutesOfDay(start)))
                    }
                    OutlinedButton(onClick = { picking = "end" }, modifier = Modifier.weight(1f)) {
                        Text(trf("Until {0}", formatMinutesOfDay(end)))
                    }
                }
            }
        }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(tr("Permission required")) },
            text = {
                Text(
                    tr("To log when Bedtime or Do Not Disturb turns on and off while PulseLoop is " +
                        "in the background, Android requires Notification access. PulseLoop only " +
                        "listens for Mode changes and does not read your notifications.\n\n" +
                        "If the switch is greyed out, open PulseLoop's App info, tap ⋮ → Allow restricted settings, then come back here."),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    try {
                        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(
                                    android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                    ZenModeListener.component(context).flattenToString(),
                                )
                        } else {
                            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        }
                        context.startActivity(intent)
                        prefs.gateByZenMode = true
                    } catch (_: Exception) {}
                }) { Text(tr("Open Settings")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    if (!prefs.gateByZenMode) ZenModeListener.setEnabled(context, false)
                }) { Text(tr("Cancel")) }
            },
        )
    }

    picking?.let { which ->
        val initial = if (which == "start") start else end
        val pickerState = rememberTimePickerState(
            initialHour = initial / 60,
            initialMinute = initial % 60,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (which == "start") tr("Quiet from") else tr("Quiet until")) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = pickerState.hour * 60 + pickerState.minute
                    if (which == "start") { start = minutes; prefs.startMinutes = minutes }
                    else { end = minutes; prefs.endMinutes = minutes }
                    picking = null
                }) { Text(tr("Done")) }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text(tr("Cancel")) }
            },
        )
    }
}

private fun formatMinutesOfDay(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)

/**
 * Wearable detail screen (iOS WearableSettingsView) — the hero card opens this. Connection
 * state, exact model name, firmware, sync/find/disconnect actions, plus the old inline "Ring"
 * card's forget + factory-reset management.
 */
@Composable
fun WearableSettingsScreen(
    bleClient: RingBLEClient?,
    coordinator: RingSyncCoordinator?,
    onAddRing: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    val bleState = bleClient?.state?.collectAsState()?.value ?: RingBLEClient.BLEState()
    // Observe the device row reactively so the connection status reflects live BLE state
    // changes (connect / disconnect / reconnect) in real time.
    // Optional-wrapped: null = still loading, empty = no ring. Reading null as "no ring" flashed
    // "No ring paired" + "Add a ring" before the stored ring's details.
    val deviceLoad by remember(db) { db.deviceDao().currentFlow().map { java.util.Optional.ofNullable(it) } }
        .collectAsState(initial = null)
    val deviceLoaded = deviceLoad != null
    val device: com.pulseloop.data.entity.DeviceEntity? = deviceLoad?.orElse(null)
    val isConnected = bleState.connectionState == RingConnectionState.CONNECTED
    val supportsFactoryReset = bleState.activeCapabilities
        .contains(com.pulseloop.ring.WearableCapability.FACTORY_RESET)
    val supportsFindDevice = bleState.activeCapabilities
        .contains(com.pulseloop.ring.WearableCapability.FIND_DEVICE)
    var showFactoryReset by remember { mutableStateOf(false) }
    var showForgetRing by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetStatus by remember { mutableStateOf("") }

    // Exact model preferred; falls back to the family / advertised-name heuristic.
    val wearableDisplayName = WearableModel.model(bleState.activeWearableModelID)?.displayName
        ?: WearableModel.model(device?.wearableModelID)?.displayName
        ?: com.pulseloop.ring.ringModelLabel(device?.advertisedName ?: device?.name, device?.deviceType)

    val battery = bleState.batteryPercent ?: device?.batteryPercent
    val lastSyncedLabel = (coordinator?.lastSyncAt ?: device?.lastSyncAt)
        ?.let { DeviceHeroStatus.relativeShort(it, System.currentTimeMillis()) }
        ?: tr("Not yet")

    SettingsSubScreen(title = tr("Wearable"), onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Bluetooth, null, Modifier.size(18.dp),
                        tint = if (isConnected) PulseColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isConnected) trf("Connected — {0} · {1}%", wearableDisplayName, battery ?: 0)
                        else if (!deviceLoaded) "" // keeps the line's height while loading
                        else device?.let { trf("Last seen: {0}", wearableDisplayName) } ?: tr("No ring paired"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // Firmware version — show just the trailing version (e.g. "V138"), matching
                // the official app, which displays only the last segment of "003A002AV138".
                if (device?.firmwareVersion != null) {
                    val rawFw = device!!.firmwareVersion!!
                    val fwDisplay = if (rawFw.contains("V")) "V" + rawFw.substringAfterLast("V") else rawFw
                    Text(trf("Firmware: {0}", fwDisplay), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                } else if (device != null) {
                    Text(tr("Firmware: reading… (connect ring to read)"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
                if (device != null) {
                    Text(trf("Last synced: {0}", lastSyncedLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }

                if (deviceLoaded && device == null) {
                    // No ring yet — route into the redesigned pairing screen (iOS #48).
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAddRing, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Add a ring"))
                    }
                }

                if (isConnected) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { coordinator?.syncNow() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Sync, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Sync now"))
                    }
                    if (supportsFindDevice) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { coordinator?.findRing() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.NotificationsActive, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tr("Find ring"))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { bleClient?.userDisconnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Disconnect"))
                    }
                } else if (bleClient?.hasLastKnownRing == true &&
                    bleState.connectionState != RingConnectionState.RECONNECTING
                ) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { bleClient.userConnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Reconnect last ring"))
                    }
                }
            }
        }

        if (device != null) {
            BatteryHistorySection(db)
        }

        if (device != null) {
            BackgroundSyncCard()
        }

        if (device != null) {
            QuietHoursCard()
        }

        if (device != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("Ring management"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Forget unbinds the ring and removes it from the app so it can be paired " +
                            "elsewhere. The app stays connected automatically the rest of the time."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showForgetRing = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Filled.DeleteForever, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Forget Ring"))
                    }

                    // Factory reset — destructive, ring-side wipe. Only for connected rings that
                    // support it (Colmi). Syncs latest history first so nothing is lost.
                    if (isConnected && supportsFactoryReset) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr("Factory reset erases all data stored on the ring itself and resets " +
                                "it to factory state. Your synced data in the app is kept."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            enabled = !resetting,
                            onClick = { showFactoryReset = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (resetting) (resetStatus.ifEmpty { tr("Resetting…") }) else tr("Factory Reset Ring"))
                        }
                    }
                }
            }
        }
    }

    if (showForgetRing) {
        AlertDialog(
            onDismissRequest = { showForgetRing = false },
            title = { Text(tr("Forget ring?")) },
            text = {
                Text(
                    tr("This unbinds the ring and removes it from the app. Your synced data is " +
                        "kept, but you'll need to pair the ring again to keep syncing.")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showForgetRing = false
                    scope.launch {
                        // Cancel background sync before unpair
                        RingSyncWorker.cancel(context)
                        // Send ring-side unbind, then disconnect & clear DB
                        if (coordinator != null && isConnected) {
                            // Runs on the coordinator's long-lived scope, so the row is
                            // cleared even if the user leaves this screen meanwhile.
                            // Clearing it emits null through currentFlow(), which
                            // updates the UI reactively.
                            coordinator.forgetRing {
                                db.deviceDao().clear()
                            }
                        } else {
                            bleClient?.forget()
                            db.deviceDao().clear()
                        }
                    }
                }) { Text(tr("Forget"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showForgetRing = false }) { Text(tr("Cancel")) }
            },
        )
    }

    if (showFactoryReset) {
        AlertDialog(
            onDismissRequest = { showFactoryReset = false },
            title = { Text(tr("Factory reset ring?")) },
            text = {
                Text(
                    tr("This erases all data stored on the ring and resets it to factory " +
                        "state. We'll sync its latest data into the app first, but this " +
                        "can't be undone. The ring will need to be re-paired afterward.")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showFactoryReset = false
                    resetting = true
                    resetStatus = tr("Syncing latest data…")
                    RingSyncWorker.cancel(context)
                    // onCleared runs on the coordinator's long-lived scope: the reset
                    // takes up to ~30s, and the device row must be cleared even if the
                    // user navigates away (which cancels this screen's own scope).
                    coordinator?.factoryResetRing(
                        onProgress = { resetStatus = it },
                    ) {
                        db.deviceDao().clear()
                        resetting = false
                    }
                }) { Text(tr("Reset ring"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showFactoryReset = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

/**
 * Opt-in exemption from battery optimisation, so the ~30-minute [RingSyncWorker] runs on time.
 *
 * Without it Android defers that job while the phone sits idle (Doze, app-standby buckets, and
 * harder on some OEMs), so overnight syncs can be hours apart. A persistent foreground-service
 * notification would not fix that: it keeps the process alive but Doze still defers the job, and
 * the ring ends the link itself ~20 s after a sync anyway (see IdleLinkPolicy). The exemption is
 * what lets the scheduled sync run. The ring logs everything to its own memory meanwhile, so
 * this changes how fresh the app is, never whether data is kept.
 */
@Composable
private fun BackgroundSyncCard() {
    val context = LocalContext.current
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager }
    var exempt by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(tr("Reliable background sync"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (exempt) {
                    tr("On. PulseLoop syncs your ring about every 30 minutes, even while the phone is idle.")
                } else {
                    tr("Android may delay background syncs by hours while your phone is idle. Your ring " +
                        "keeps recording either way; allowing this keeps the app up to date.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val intent = if (exempt) {
                        // Turning it back off is only possible from the system list.
                        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    } else {
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    }
                    runCatching { context.startActivity(intent) }
                        .onFailure {
                            runCatching {
                                context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            }
                        }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (exempt) tr("Manage in Android settings") else tr("Allow background sync"))
            }
        }
    }
}

/** 24h/7d windows for the battery drainage chart (iOS #61b — only these two ranges). */
private enum class BatteryHistoryRange(val label: String, val spanMs: Long) {
    DAY("24h", 24 * 3600_000L),
    WEEK("7d", 7 * 24 * 3600_000L),
}

/** Fixed 0–100 zone coloring for the battery line — mirrors iOS `colorForValue`
 *  (≤20 danger, ≤50 warning, else success). Zone boundaries use an exclusive upper bound
 *  (`ZoneLineChart` matches `lower <= value < upper`), so 21/51 sit just above each cut. */
/** A getter, so the zone names follow a language change without a restart. */
private val batteryZones: List<MetricZone> get() = listOf(
    MetricZone("critical", tr("Critical"), null, 21.0, ZoneSeverity.CRITICAL, VitalColorToken.Red, tr("Battery critically low")),
    MetricZone("low", tr("Low"), 21.0, 51.0, ZoneSeverity.WATCH, VitalColorToken.Amber, tr("Battery low")),
    MetricZone("good", tr("Good"), 51.0, null, ZoneSeverity.OPTIMAL, VitalColorToken.Mint, tr("Battery healthy")),
)

/**
 * Battery drainage history (iOS #61b `BatteryHistorySection`): a 24h/7d segmented range picker over
 * a fixed 0–100 [ZoneLineChart]. Reads [com.pulseloop.data.entity.BatterySampleEntity] rows written by
 * [com.pulseloop.service.EventPersistenceSubscriber]'s throttled battery log.
 */
@Composable
private fun BatteryHistorySection(db: PulseLoopDatabase) {
    var range by remember { mutableStateOf(BatteryHistoryRange.DAY) }
    // Reactive on the device row so a fresh battery reading refetches the window without a manual pull.
    val device by remember(db) { db.deviceDao().currentFlow() }.collectAsState(initial = null)
    // null until the first read: "Not enough data yet" then only shows when it is true. A range
    // switch keeps the previous series on screen while the new one loads.
    var samples by remember { mutableStateOf<List<VitalSample>?>(null) }

    LaunchedEffect(range, device?.batteryPercent, device?.updatedAt) {
        val end = System.currentTimeMillis()
        val start = end - range.spanMs
        samples = db.batterySampleDao().samplesBetween(start, end)
            .map { VitalSample(timestampMs = it.timestamp, value = it.percent.toDouble()) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Battery history"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow {
                    BatteryHistoryRange.entries.forEachIndexed { i, r ->
                        SegmentedButton(
                            selected = range == r,
                            onClick = { range = r },
                            shape = SegmentedButtonDefaults.itemShape(i, BatteryHistoryRange.entries.size),
                        ) { Text(r.label) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            val loaded = samples
            if (loaded == null) {
                // Chart-height placeholder so the card doesn't grow when the samples land.
                Spacer(Modifier.height(160.dp))
            } else if (loaded.size < 2) {
                Text(
                    tr("Not enough data yet — battery history builds up as the ring reports its level."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ZoneLineChart(
                    samples = loaded,
                    zones = batteryZones,
                    yDomain = 0.0..100.0,
                    accent = PulseColors.success,
                    height = 160.dp,
                    // Day boundaries on the 7 d view, six-hour marks on 24 h (issue #65).
                    verticalGridlines = com.pulseloop.service.BatteryProjection.gridlines(
                        startMs = loaded.first().timestampMs,
                        endMs = loaded.last().timestampMs,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                BatteryRuntimeEstimate(loaded)
            }
        }
    }
}

// MARK: - Privacy & Data

/** Destructive data-action options for the Privacy & Data screen. */
private sealed class ResetAction {
    data object UnpairRing : ResetAction()
    data object ResetAppData : ResetAction()
    data object UnpairAndReset : ResetAction()

    val title: String get() = when (this) {
        is UnpairRing -> tr("Unpair ring?")
        is ResetAppData -> tr("Reset app data?")
        is UnpairAndReset -> tr("Unpair ring & reset app data?")
    }
    val message: String get() = when (this) {
        is UnpairRing -> tr("Unpair your ring? The ring will forget this phone; your data stays.")
        is ResetAppData -> tr("This permanently erases all your data — metrics, sleep, activity, coach history, settings, and saved API keys — and can't be undone.")
        is UnpairAndReset -> tr("This unpairs your ring, then permanently erases all your data — metrics, sleep, activity, coach history, settings, and saved API keys — and can't be undone.")
    }
    val confirmLabel: String get() = when (this) {
        is UnpairRing -> tr("Unpair ring")
        is ResetAppData -> tr("Reset app data")
        is UnpairAndReset -> tr("Unpair & reset")
    }
}

/**
 * Privacy & Data detail screen (iOS PrivacyDataView): grouped sections for diagnostics export,
 * destructive App-data reset actions (Unpair Ring, Reset App Data, Unpair & Reset),
 * data backup (export/import as JSON), and demo-data controls. Reflects the app's
 * transparency/privacy ethos — everything here is local and explicit.
 */
@Composable
fun PrivacyDataSettingsScreen(
    onBack: () -> Unit,
    coordinator: RingSyncCoordinator? = null,
    bleClient: RingBLEClient? = null,
    onNavigateToOnboarding: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSeedDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    /** Which destructive App-data action is awaiting confirmation. */
    var pendingReset by remember { mutableStateOf<ResetAction?>(null) }

    // Data export/import state.
    var exportInProgress by remember { mutableStateOf(false) }
    var importInProgress by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showImportSuccess by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportConfirm = true
        }
    }

    fun clearAllPreferences() {
        listOf("pulseloop_secure", "ring_ble", "pulseloop_prefs").forEach { name ->
            context.getSharedPreferences(name, 0).edit().clear().apply()
        }
        // Strava OAuth tokens live in their own EncryptedSharedPreferences file, so the loop above
        // misses them — a "Reset App Data" that leaves the account connected is not a reset. iOS
        // deletes the equivalent Keychain entry alongside the coach API keys for the same reason.
        runCatching { com.pulseloop.strava.StravaTokenStore(context).clear() }
    }

    fun performUnpair() {
        scope.launch {
            RingSyncWorker.cancel(context)
            coordinator?.forgetRing { PulseLoopDatabase.getInstance(context).deviceDao().clear() }
                ?: run {
                    bleClient?.forget()
                    PulseLoopDatabase.getInstance(context).deviceDao().clear()
                }
        }
    }

    fun performResetAppData() {
        scope.launch {
            PulseLoopDatabase.getInstance(context).nukeAllTables()
            clearAllPreferences()
            onNavigateToOnboarding()
        }
    }

    fun performUnpairAndReset() {
        scope.launch {
            RingSyncWorker.cancel(context)
            if (coordinator != null) {
                coordinator.forgetRing { }
                PulseLoopDatabase.getInstance(context).nukeAllTables()
            } else {
                bleClient?.forget()
                PulseLoopDatabase.getInstance(context).nukeAllTables()
            }
            clearAllPreferences()
            onNavigateToOnboarding()
        }
    }

    SettingsSubScreen(title = tr("Privacy & Data"), onBack = onBack) {
        // Data Backup — export/import full app data as JSON.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Data Backup"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Export all your data to a single JSON file for backup, device migration, or external analysis. Import restores everything — replacing all current data."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            exportInProgress = true
                            scope.launch {
                                try {
                                    val db = PulseLoopDatabase.getInstance(context)
                                    val uri = com.pulseloop.data.DataArchiveService.exportToFile(context, db)
                                    if (uri != null) {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, tr("Export PulseLoop Data")))
                                    }
                                } catch (_: Exception) {
                                    statusMessage = tr("Export failed")
                                }
                                exportInProgress = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !exportInProgress,
                    ) {
                        if (exportInProgress) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (exportInProgress) tr("Exporting…") else tr("Export All Data"))
                    }
                    OutlinedButton(
                        onClick = {
                            filePicker.launch(arrayOf("application/json"))
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !importInProgress,
                    ) {
                        if (importInProgress) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (importInProgress) tr("Importing…") else tr("Import Data"))
                    }
                }
            }
        }

        AutoBackupCard()

        // App data — destructive reset/restore actions.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("App Data"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Destructive actions: unpair the ring, factory-reset app data, or both."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { pendingReset = ResetAction.UnpairRing },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.BluetoothDisabled, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Unpair Ring"))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { pendingReset = ResetAction.ResetAppData },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.DeleteForever, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Reset App Data"))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { pendingReset = ResetAction.UnpairAndReset },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("Unpair & Reset"))
                }
            }
        }

        // Demo data — Android-only (iOS seeds via the Simulator's SeedData).
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Demo Data"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Explore the app without a ring. Seeds activity, HR, SpO2, sleep, and a coach conversation."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showSeedDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Reseed Demo Data"))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showClearDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Clear Demo Data"), color = PulseColors.danger)
                }
            }
        }

        // Diagnostics export.
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                var maskSensitive by remember { mutableStateOf(true) }
                Text(tr("Diagnostics"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Export app, device, and wearable logs as JSON for bug reports."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.switchRow(checked = maskSensitive, onCheckedChange = { maskSensitive = it }), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Anonymize export"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (maskSensitive)
                                tr("Removes health values, ring serial & MAC addresses. Keeps models, opcodes & errors.")
                            else
                                tr("OFF — includes full unmasked BLE frames and health values (for protocol debugging only)."),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (maskSensitive) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error,
                        )
                    }
                    Switch(checked = maskSensitive, onCheckedChange = null)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val db = PulseLoopDatabase.getInstance(context)
                            try {
                                val intent = com.pulseloop.diagnostics.DiagnosticsExporter
                                    .shareIntent(context, db, mask = maskSensitive)
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (maskSensitive) tr("Export Diagnostics") else tr("Export Full (Unmasked)"))
                }
            }
        }
    }

    // Confirmation/alert dialogs — mutually exclusive via when chain (prevent stacking).
    when {
        statusMessage != null -> {
            val msg = statusMessage!!
            AlertDialog(
                onDismissRequest = { statusMessage = null },
                title = { Text(tr("Error")) },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = { statusMessage = null }) { Text("OK") }
                },
            )
        }

        showImportSuccess -> AlertDialog(
            onDismissRequest = { showImportSuccess = false },
            title = { Text(tr("Import Complete")) },
            text = { Text(tr("Your data has been restored from the backup.")) },
            confirmButton = {
                TextButton(onClick = { showImportSuccess = false }) { Text("OK") }
            },
        )

        showImportConfirm -> AlertDialog(
            onDismissRequest = { showImportConfirm = false; pendingImportUri = null },
            title = { Text(tr("Replace all data?")) },
            text = {
                Text(tr("This permanently deletes everything currently in the app and replaces it with the contents of this file. This can't be undone."))
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirm = false
                    val uri = pendingImportUri ?: return@TextButton
                    pendingImportUri = null
                    importInProgress = true
                    scope.launch {
                        try {
                            val db = PulseLoopDatabase.getInstance(context)
                            com.pulseloop.data.DataArchiveService.importFile(context, uri, db)
                            showImportSuccess = true
                        } catch (_: Exception) {
                            statusMessage = tr("Import failed — the file may be corrupt or from a newer version.")
                        }
                        importInProgress = false
                    }
                }) {
                    Text(tr("Delete old data & import"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false; pendingImportUri = null }) { Text(tr("Cancel")) }
            },
        )

        pendingReset != null -> {
            val action = pendingReset!!
            AlertDialog(
                onDismissRequest = { pendingReset = null },
                title = { Text(action.title) },
                text = { Text(action.message) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingReset = null
                        when (action) {
                            is ResetAction.UnpairRing -> performUnpair()
                            is ResetAction.ResetAppData -> performResetAppData()
                            is ResetAction.UnpairAndReset -> performUnpairAndReset()
                        }
                    }) {
                        Text(action.confirmLabel, color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingReset = null }) { Text(tr("Cancel")) }
                },
            )
        }

        showSeedDialog -> AlertDialog(
            onDismissRequest = { showSeedDialog = false },
            title = { Text(tr("Reseed Demo Data?")) },
            text = { Text(tr("This will replace all existing demo data. Your synced ring data — including sleep history — will not be affected.")) },
            confirmButton = {
                TextButton(onClick = {
                    showSeedDialog = false
                    scope.launch {
                        DemoDataSeeder.seed(PulseLoopDatabase.getInstance(context))
                        com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                    }
                }) { Text(tr("Reseed")) }
            },
            dismissButton = {
                TextButton(onClick = { showSeedDialog = false }) { Text(tr("Cancel")) }
            },
        )

        showClearDialog -> AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(tr("Clear Demo Data?")) },
            text = {
                Text(
                    tr("Removes all demo measurements, activity, sleep, and the demo device. " +
                    "Synced ring data is not affected."),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    scope.launch {
                        DemoDataSeeder.clear(PulseLoopDatabase.getInstance(context))
                        com.pulseloop.widgets.WidgetSnapshotPublisher.publish(context)
                    }
                }) { Text(tr("Clear"), color = PulseColors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

// MARK: - About

/**
 * About detail screen (iOS AboutSettingsView): version (7 quick taps unlock Developer options,
 * with escalating haptics + progress ring), app description, update checker, source link,
 * and license. Demo data + diagnostics moved to the Privacy & Data screen (iOS hub parity).
 */
@Composable
fun AboutSettingsScreen(onOpenDebug: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStore = remember { ApiKeyStore(context) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastVersionTap by remember { mutableLongStateOf(0L) }
    var developerUnlocked by remember { mutableStateOf(keyStore.developerUnlocked) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastToken by remember { mutableIntStateOf(0) }

    // 0…1 progress toward unlock, used to build an accent tint/border as taps accumulate.
    val tapProgress = if (developerUnlocked) 0f
        else (versionTapCount.coerceAtMost(DEVELOPER_TAP_THRESHOLD - 1).toFloat() / (DEVELOPER_TAP_THRESHOLD - 1))

    fun showToast(message: String) {
        toastToken += 1
        toast = message
    }
    LaunchedEffect(toastToken) {
        if (toast != null) {
            delay(1_600)
            toast = null
        }
    }

    fun registerVersionTap() {
        val now = System.currentTimeMillis()
        // Android-style: 7 quick taps unlock; a >2s pause between taps resets the count.
        if (lastVersionTap > 0 && now - lastVersionTap > 2_000) versionTapCount = 0
        lastVersionTap = now

        if (developerUnlocked) {
            showToast(tr("Developer options are already enabled."))
            return
        }

        versionTapCount += 1
        // Escalating haptics: light for the first taps, heavier close to the unlock.
        haptics.performHapticFeedback(
            if (versionTapCount > 3) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
        )
        val remaining = DEVELOPER_TAP_THRESHOLD - versionTapCount
        if (remaining <= 0) {
            developerUnlocked = true
            keyStore.developerUnlocked = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            showToast(tr("You are now a developer!"))
            onOpenDebug()
        } else if (remaining <= 3) {
            showToast(trf("You're {0} step{1} away from Developer options.", remaining, if (remaining == 1) "" else "s"))
        }
    }

    SettingsSubScreen(
        title = tr("About"),
        onBack = onBack,
        bottomOverlay = {
            toast?.let { message ->
                Text(
                    message,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = PulseColors.textPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp)
                        .clip(CircleShape)
                        .background(PulseColors.elevated)
                        .border(1.dp, PulseColors.borderSubtle, CircleShape)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        },
    ) {
        // Version row: tap 7× to unlock Developer options; a tint/border builds with progress.
        Card(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { registerVersionTap() }
                .border(1.5.dp, PulseColors.accent.copy(alpha = tapProgress * 0.9f), RoundedCornerShape(4.dp)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .dither(PulseColors.accent, tapProgress * 0.22f)
                    .padding(16.dp),
            ) {
                Text(tr("Version"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    trf("PulseLoop v{0} ({1})", com.pulseloop.BuildConfig.VERSION_NAME, com.pulseloop.BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("PulseLoop"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("An LLM-native health app that turns a cheap Bluetooth smart ring into a real, " +
                        "conversational health tracker. It talks to the ring directly over Bluetooth — no " +
                        "vendor cloud, no account — and layers an AI coach on top of your own data."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                com.pulseloop.update.CheckForUpdatesButton()
            }
        }

        // Project — source link + license (iOS AboutSettingsView "Project" section).
        Card(
            Modifier.fillMaxWidth().clickable {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                } catch (_: Exception) {
                    // No browser available — nothing to open.
                }
            },
        ) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Source on GitHub"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        REPO_URL.removePrefix("https://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Filled.OpenInNew, null, Modifier.size(16.dp), tint = PulseColors.textMuted)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("License"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("Open-source health tracker. Ported from iOS.\nCC BY 4.0 · Free to share and " +
                        "adapt, including commercially, with appropriate credit."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

    }
}

private const val DEVELOPER_TAP_THRESHOLD = 7
private const val REPO_URL = "https://github.com/foureight84/PulseLoop"

// MARK: - Nutrition

@Composable
fun NutritionSettingsScreen(onBack: () -> Unit, onNavigateToNutrition: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    var goal by remember { mutableStateOf<UserGoalEntity?>(null) }
    var goalLoaded by remember { mutableStateOf(false) }
    var goalReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(goalReload) { goal = db.userGoalDao().get(); goalLoaded = true }

    SettingsSubScreen(title = tr("Nutrition"), onBack = onBack) {
        CoachManagedCard(com.pulseloop.service.GoalSync.NUTRITION_FIELDS) { goalReload++ }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth()
                    .switchRow(checked = goal?.nutritionEnabled == true, enabled = goalLoaded, onCheckedChange = { enabled ->
                            val g = goal ?: return@switchRow
                            scope.launch {
                                goal = g.copy(nutritionEnabled = enabled).also { db.userGoalDao().upsert(it) }
                            }
                        }), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Enable Nutrition Tracking"), fontWeight = FontWeight.Medium)
                        Text(tr("Log meals and track calories & macros"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = goal?.nutritionEnabled == true, enabled = goalLoaded, onCheckedChange = null)
                }
            }
        }

        if (goal?.nutritionEnabled == true) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr("Daily Goals"), fontWeight = FontWeight.SemiBold)
                    NutrientStepper(tr("Calories (kcal)"), goal?.intakeCalories ?: 2000.0, 50.0, 100.0, 10000.0) { v ->
                        scope.launch { goal = goal?.copy(intakeCalories = v)?.also { db.userGoalDao().upsert(it); com.pulseloop.service.GoalSync.noteEdits(context, it) } }
                    }
                    NutrientStepper(tr("Protein (g)"), goal?.intakeProteinG ?: 150.0, 5.0, 0.0, 500.0) { v ->
                        scope.launch { goal = goal?.copy(intakeProteinG = v)?.also { db.userGoalDao().upsert(it); com.pulseloop.service.GoalSync.noteEdits(context, it) } }
                    }
                    NutrientStepper(tr("Carbs (g)"), goal?.intakeCarbsG ?: 250.0, 5.0, 0.0, 800.0) { v ->
                        scope.launch { goal = goal?.copy(intakeCarbsG = v)?.also { db.userGoalDao().upsert(it); com.pulseloop.service.GoalSync.noteEdits(context, it) } }
                    }
                    NutrientStepper(tr("Fat (g)"), goal?.intakeFatG ?: 65.0, 5.0, 0.0, 300.0) { v ->
                        scope.launch { goal = goal?.copy(intakeFatG = v)?.also { db.userGoalDao().upsert(it); com.pulseloop.service.GoalSync.noteEdits(context, it) } }
                    }
                    OutlinedButton(onClick = {
                        val g = goal ?: return@OutlinedButton
                        scope.launch {
                            val cal = g.intakeCalories ?: 2000.0
                            goal = g.copy(intakeProteinG = cal * 0.30 / 4.0, intakeCarbsG = cal * 0.40 / 4.0, intakeFatG = cal * 0.30 / 9.0)
                                .also { db.userGoalDao().upsert(it) }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("Balance Macros (30/40/30)"))
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("Quick Access"), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onNavigateToNutrition,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("Open Nutrition Log"))
                    }
                }
            }
        }
    }
}

@Composable
private fun NutrientStepper(label: String, value: Double, step: Double, min: Double, max: Double, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (value > min) onChange((value - step).coerceAtLeast(min)) }) { Text("−", fontSize = 18.sp) }
            Text("${value.roundToInt()}", fontWeight = FontWeight.Medium)
            TextButton(onClick = { if (value < max) onChange((value + step).coerceAtMost(max)) }) { Text("+", fontSize = 18.sp) }
        }
    }
}

// MARK: - Strava

@Composable
fun StravaSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { com.pulseloop.strava.StravaTokenStore(context) }
    val isConfigured = com.pulseloop.strava.StravaAuth.isConfigured
    var isConnected by remember { mutableStateOf(tokenStore.isConnected) }
    var athleteName by remember { mutableStateOf(tokenStore.get()?.athleteName) }
    var isUploading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    // The OAuth callback lands in MainActivity, which writes tokens (or a failure message) into
    // the store. Poll for either for 60 s after entering the screen, then stop.
    LaunchedEffect(Unit) {
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            tokenStore.lastError()?.let { error ->
                statusMessage = error
                tokenStore.clearLastError()
                return@LaunchedEffect
            }
            val tokens = tokenStore.get()
            if (tokens != null && !isConnected) {
                isConnected = true
                athleteName = tokens.athleteName
                statusMessage = null
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(1000)
        }
    }

    SettingsSubScreen(title = tr("Strava"), onBack = onBack) {
        if (!isConfigured) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("Strava Not Configured"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Add stravaClientId and stravaClientSecret to local.properties to enable Strava integration."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@SettingsSubScreen
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Strava Integration"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isConnected) trf("Connected as {0}. Workouts sync automatically when finished.", athleteName)
                    else "Connect your Strava account to automatically upload workouts with GPS routes and heart rate data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (isConnected) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                isUploading = true
                                scope.launch {
                                    try {
                                        val db = PulseLoopDatabase.getInstance(context)
                                        val count = com.pulseloop.strava.StravaUploader.uploadAuto(db, tokenStore)
                                        // uploadAuto records why it stopped, if it stopped.
                                        statusMessage = tokenStore.lastError()?.also { tokenStore.clearLastError() }
                                            ?: when (count) {
                                                0 -> tr("Nothing new to upload")
                                                1 -> tr("Uploaded 1 workout")
                                                else -> trf("Uploaded {0} workouts", count)
                                            }
                                    } catch (e: Exception) {
                                        statusMessage = trf("Upload failed: {0}", e.message ?: tr("unknown error"))
                                    }
                                    isUploading = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isUploading,
                        ) {
                            Text(if (isUploading) tr("Syncing…") else tr("Sync Now"))
                        }
                        OutlinedButton(
                            onClick = { confirmDisconnect = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Text(tr("Disconnect"))
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            statusMessage = null
                            tokenStore.clearLastError()
                            // Implicit ACTION_VIEW on /oauth/mobile/authorize: the Strava app takes
                            // it when installed, the browser otherwise (Strava's Android docs).
                            val authUrl = com.pulseloop.strava.StravaAuth.generateAuthUrl(tokenStore)
                            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(authUrl))
                            try {
                                context.startActivity(intent)
                                statusMessage = tr("Authorize in your browser — you'll return to PulseLoop automatically.")
                            } catch (_: Exception) {
                                statusMessage = tr("Could not open browser")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("Connect Strava"))
                    }
                }
            }
        }

        if (isConnected) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("Auto Upload"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Finished workouts are automatically uploaded to Strava. GPS routes, heart rate data, and sport type are included.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        statusMessage?.let { msg ->
            Card(
                Modifier.fillMaxWidth(),
                // containerColor alone leaves contentColor Unspecified, so the Text falls back to
                // LocalContentColor (dark) and renders near-invisible on this dark card.
                colors = CardDefaults.cardColors(
                    containerColor = PulseColors.cardSoft,
                    contentColor = PulseColors.textPrimary,
                ),
            ) {
                Text(msg, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(tr("Disconnect Strava?")) },
            text = { Text(tr("PulseLoop will stop uploading workouts. Workouts already on Strava stay there.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    scope.launch {
                        // Revoke on Strava's side first, so PulseLoop also disappears
                        // from the athlete's "My Apps" list — clearing local tokens
                        // alone leaves the authorization live forever.
                        tokenStore.get()?.let {
                            runCatching { com.pulseloop.strava.StravaAuth.deauthorize(it) }
                        }
                        tokenStore.clear()
                        isConnected = false
                        athleteName = null
                        statusMessage = tr("Disconnected from Strava")
                    }
                }) { Text(tr("Disconnect"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

// MARK: - Health Connect

/**
 * Health Connect export settings — the Android analogue of iOS' Apple Health settings
 * (docs/health-connect-integration.md, Phase 0/1). Availability, permissions, and preferences;
 * the Phase 1 worker owns the actual export. Granting permissions and answering the backfill
 * dialog both enqueue the (debounced, hard-gated) export worker.
 */
@Composable
fun HealthConnectSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { HealthConnectPrefsStore.get(context) }
    val prefs by store.prefs.collectAsState()
    // Re-read on every ON_RESUME (review pass 5): "Install / Update Health Connect" sends the
    // user to Play and back, and a `remember {}` snapshot kept rendering the unavailable card —
    // with LaunchedEffect(availability) never re-firing — until they left the screen and
    // returned. Keying on it makes the whole screen recover by itself.
    val lifecycleOwner = LocalLifecycleOwner.current
    var availability by remember { mutableStateOf(HealthConnectSdk.availability(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) availability = HealthConnectSdk.availability(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = prefs.lastGrantedPermissions.toSet()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    // Any leftover export state is what "Remove PulseLoop data" would actually clear.
    val hasExportState = prefs.lastSyncAt != null ||
        HealthConnectWatermarks.Key.values().any { store.currentWatermarks.get(it) != null }
    // Backfill dialog derived from persisted state (review MINOR): a rotation or process death
    // while it is up would otherwise destroy a `remember`ed flag WITHOUT running
    // onDismissRequest, stranding enabled=true + backfillChoice=NOT_ASKED — the hard-gated
    // "Connected but nothing exports" state with no re-offer. Deriving it the way the revocation
    // offer does makes recreation re-show it and makes the explicit dismiss redundant. Also gated
    // on Health Connect being AVAILABLE (review pass 3): the old imperative flag could only be set
    // from the permission-launcher callback, reachable only in the AVAILABLE branch, so a
    // device whose provider is uninstalled / needs an update never saw this dialog on top of the
    // "needs an update" card.
    val showBackfillDialog = availability == HealthConnectAvailability.AVAILABLE &&
        prefs.enabled && prefs.isConnected &&
        prefs.backfillChoice == HealthConnectPrefs.BackfillChoice.NOT_ASKED
    // Phase 6: full-revocation offer + "remove PulseLoop data" confirmation.
    var showRevokeResetDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }

    // Master toggle → Health Connect permission sheet. Partial grants are first-class:
    // any granted permission counts as connected.
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { grantedPermissions ->
        val got = HealthConnectPermissionReconcile.storedSetOf(grantedPermissions).toSet()
        // Phase 6: diff against the previously stored grant. A grow resets the watermarks of the
        // groups the new permissions belong to, so the newly grantable types backfill their history
        // (their shared group watermark is already ahead of their rows). A full revoke offers a
        // watermark reset below.
        val outcome = HealthConnectPermissionReconcile.reconcile(
            store.current.lastGrantedPermissions.toSet(), got, store,
        )
        store.update {
            it.copy(
                enabled = got.isNotEmpty(),
                lastGrantedPermissions = got.sorted(),
                revocationOfferDismissed = if (got.isEmpty()) it.revocationOfferDismissed else false,
            )
        }
        statusMessage = if (got.isEmpty()) {
            tr("No Health Connect permissions granted — nothing to export.")
        } else {
            trf("Connected. {0} of {1} permission types granted.", got.size, HealthConnectPermissions.all.size)
        }
        // First-enable backfill dialog: the derived showBackfillDialog above now turns true from
        // the persisted state (enabled + connected + NOT_ASKED) this callback just set, so there
        // is nothing to set imperatively. While NOT_ASKED the Phase 1 worker's hard gate keeps the
        // export from running, so the enqueue below is a no-op until the choice is made.
        // Grant trigger (Phase 1, extended in Phase 6): a grant — including a grow-reset — is the
        // moment an export should attempt to run. Debounced 15 s + REPLACE, so cheap on re-grants.
        if (got.isNotEmpty()) HealthConnectExportWorker.enqueue(context)
        // hadSync guard (mirrors the state-based offer below): only offer a reset when there is
        // prior export state to actually reset. A grant-then-immediate-revoke with no export yet
        // has empty watermarks + null lastSyncAt, so the offer would be a no-op with misleading
        // "re-exports your history" wording.
        if (outcome.allRevoked && !store.current.revocationOfferDismissed) {
            val cur = store.current
            val hadSync = cur.lastSyncAt != null ||
                HealthConnectWatermarks.Key.values().any { store.currentWatermarks.get(it) != null }
            if (hadSync) showRevokeResetDialog = true
        }
    }

    // Phase 6: on open, diff the live granted set against the stored one to catch out-of-band
    // changes (a revocation in system Settings, a grant made elsewhere). A grow resets the affected
    // watermarks and enqueues a pass. (onAppStart does the same on activity resume; this is the
    // safety net for a change that landed after resume, and the home of the revocation offer.)
    LaunchedEffect(availability) {
        if (availability != HealthConnectAvailability.AVAILABLE) return@LaunchedEffect
        val last = store.current.lastGrantedPermissions.toSet()
        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() ?: return@LaunchedEffect
        val live = runCatching { client.permissionController.getGrantedPermissions() }.getOrNull() ?: return@LaunchedEffect
        val granted = HealthConnectPermissionReconcile.storedSetOf(live).toSet()
        val outcome = HealthConnectPermissionReconcile.reconcile(last, granted, store)
        if (granted != last) store.update { it.copy(lastGrantedPermissions = granted.sorted()) }
        if (outcome.grewGroups.isNotEmpty()) {
            HealthConnectExportWorker.enqueue(context)
            // A re-grant (grow) clears the one-shot revocation-offer flag so a future full
            // revocation offers again.
            if (store.current.revocationOfferDismissed) store.update { it.copy(revocationOfferDismissed = false) }
        }
        // Full-revocation offer (Gadgetbridge pattern) — state-based, not diff-based: onAppStart
        // may already have reconciled and stored the (now empty) live set before this screen
        // opened, making the diff above empty-to-empty so allRevoked never fires. Offer when the
        // master is on, nothing is granted now, a prior sync exists (something to reset), and the
        // user has not already dismissed the offer.
        val cur = store.current
        val hadSync = cur.lastSyncAt != null ||
            HealthConnectWatermarks.Key.values().any { store.currentWatermarks.get(it) != null }
        if (cur.enabled && granted.isEmpty() && hadSync && !cur.revocationOfferDismissed) {
            showRevokeResetDialog = true
        }
    }

    SettingsSubScreen(title = tr("Health Connect"), onBack = onBack) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(tr("Health Connect"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("Export ring data to Health Connect so other apps and dashboards can show it. Exporting only writes. PulseLoop reads Health Connect only for features you turn on: body data in your profile, Use without a ring, and the accuracy check."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                when (availability) {
                    HealthConnectAvailability.UNAVAILABLE -> {
                        Text(
                            tr("Health Connect isn't available on this device. Install the Health Connect app to use this feature."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { openHealthConnectPlayStore(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Install Health Connect"))
                        }
                    }

                    HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED -> {
                        Text(
                            tr("The Health Connect app on this device needs an update before it can be used."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { openHealthConnectPlayStore(context) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Update Health Connect"))
                        }
                    }

                    HealthConnectAvailability.AVAILABLE -> {
                        Row(Modifier.fillMaxWidth()
                            .switchRow(checked = prefs.enabled, onCheckedChange = { on ->
                                    if (on) {
                                        permissionLauncher.launch(HealthConnectPermissions.all)
                                    } else {
                                        store.update { it.copy(enabled = false) }
                                    }
                                }), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("Export PulseLoop data"), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (prefs.isConnected) tr("Connected") else tr("Not connected"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = prefs.enabled, onCheckedChange = null)
                        }
                        // Always reachable (review pass 5). Two states have no other way out:
                        // once every permission is revoked the app can no longer delete what it
                        // wrote (deletion needs WRITE), and after two denials the platform stops
                        // showing the permission sheet at all, so the master switch silently does
                        // nothing. Both are resolved in the Health Connect app.
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { openHealthConnectApp(context) },
                            modifier = Modifier.align(Alignment.Start),
                        ) { Text(tr("Open Health Connect")) }
                        if (!prefs.isConnected) {
                            Text(
                                tr("Manage or re-grant permissions, and review or delete PulseLoop's data, in the Health Connect app."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (availability == HealthConnectAvailability.AVAILABLE && prefs.isConnected) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("Data Types"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("Exported only when switched on. A row stays dim until its permission is granted."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    // Accuracy check: rows whose records the reference device also writes are held
                    // back while a check runs (docs/accuracy-check-plan.md). The switch still works;
                    // the hold applies whatever it says, so it's shown as a note, not a disabled row.
                    val accuracyPaused by produceState(emptySet<com.pulseloop.health.accuracy.ExportUnit>()) {
                        value = runCatching { com.pulseloop.health.accuracy.AccuracyCheckController.pausedNow(context) }.getOrDefault(emptySet())
                    }
                    val rows = listOf(
                        HealthConnectPermissions.DataTypeRow.HEART_RATE to "Heart rate",
                        HealthConnectPermissions.DataTypeRow.OXYGEN_SATURATION to "Oxygen saturation (SpO₂)",
                        HealthConnectPermissions.DataTypeRow.HEART_RATE_VARIABILITY to "Heart rate variability (HRV)",
                        HealthConnectPermissions.DataTypeRow.BODY_TEMPERATURE to "Body temperature",
                        HealthConnectPermissions.DataTypeRow.SLEEP to "Sleep",
                        HealthConnectPermissions.DataTypeRow.STEPS_AND_ACTIVITY to "Steps & activity",
                        HealthConnectPermissions.DataTypeRow.WORKOUTS to "Workouts",
                        HealthConnectPermissions.DataTypeRow.NUTRITION to "Nutrition",
                        // Phase 5 (beyond iOS).
                        HealthConnectPermissions.DataTypeRow.BLOOD_PRESSURE to "Blood pressure",
                        HealthConnectPermissions.DataTypeRow.BLOOD_GLUCOSE to "Blood glucose",
                        HealthConnectPermissions.DataTypeRow.RESPIRATORY_RATE to "Respiratory rate",
                        HealthConnectPermissions.DataTypeRow.VO2_MAX to "VO\u2082 max",
                        HealthConnectPermissions.DataTypeRow.RESTING_HEART_RATE to "Resting heart rate",
                    )
                    for ((row, label) in rows) {
                        val rowPerms = HealthConnectPermissions.permissionsForRow(row)
                        val rowGranted = rowPerms.isEmpty() || rowPerms.any { it in granted }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .switchRow(checked = prefs.toggleFor(row), enabled = rowGranted, onCheckedChange = { on ->
                                    store.update { p -> p.withToggleFor(row, on) }
                                    // Re-enabling a VITALS kind must backfill the readings recorded while it
                                    // was off: the VITALS group watermark is driven by the *enabled* kinds
                                    // and advances past a disabled kind's rows, so without this reset the
                                    // off-period data sits below the watermark and never re-selects. This is
                                    // the toggle-side equivalent of the permission grow-reset. The single-
                                    // type groups (sleep/activity/workouts/nutrition/resting HR) freeze their
                                    // watermark while off and backfill on re-enable, so they need no reset.
                                    if (on && HealthConnectPermissionReconcile.groupFor(row) == HealthConnectWatermarks.Key.VITALS) {
                                        store.resetWatermarks(setOf(HealthConnectWatermarks.Key.VITALS))
                                        HealthConnectExportWorker.enqueue(context)
                                    }
                                }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                if (!rowGranted) {
                                    Text(
                                        tr("Awaiting permission"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val heldUnits = accuracyPaused.filter { it.row == row }
                                if (heldUnits.isNotEmpty()) {
                                    Text(
                                        if (row == HealthConnectPermissions.DataTypeRow.STEPS_AND_ACTIVITY) {
                                            tr("Paused by the accuracy check: ") + heldUnits.joinToString(", ") { it.metric.label.lowercase() }
                                        } else tr("Paused by the accuracy check"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PulseColors.warning,
                                    )
                                }
                            }
                            Switch(checked = prefs.toggleFor(row), enabled = rowGranted, onCheckedChange = null)
                        }
                        if (row == HealthConnectPermissions.DataTypeRow.HEART_RATE_VARIABILITY) {
                            // Plan §7 open item: the rings' HRV metric is vendor-undocumented, and
                            // Health Connect has no SDNN type — we export it as RMSSD.
                            Text(
                                tr("HRV is exported as RMSSD — Health Connect has no SDNN type, and the rings' metric is vendor-undocumented."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

        }

        // Shown while there is anything to report or remove — deliberately NOT gated on
        // isConnected (review pass 5). Revoking every permission in system Settings used to hide
        // this whole section, which is exactly when a user wants to clear what was already
        // exported; the card now stays, and says plainly what it can and cannot do without
        // permissions.
        if (availability == HealthConnectAvailability.AVAILABLE && (prefs.isConnected || hasExportState)) {
            Card(
                Modifier.fillMaxWidth(),
                // containerColor alone leaves contentColor Unspecified, so the Text falls back to
                // LocalContentColor (dark) and renders near-invisible on this dark card.
                colors = CardDefaults.cardColors(
                    containerColor = PulseColors.cardSoft,
                    contentColor = PulseColors.textPrimary,
                ),
            ) {
                val syncedAt = prefs.lastSyncAt
                Text(
                    if (syncedAt != null) {
                        // The label promises a time, so show one (review pass 5): the summary
                        // alone read as "Last sync: skipped: …" with no indication of when.
                        trf("Last sync {0} — ", DeviceHeroStatus.relativeShort(syncedAt, System.currentTimeMillis())) +
                            (prefs.lastSyncSummary ?: "unknown")
                    } else {
                        tr("No export has run yet. With the master switch on and a backfill choice made, PulseLoop exports automatically.")
                    },
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // Phase 6: "Remove PulseLoop data from Health Connect" (iOS removeAllExportedData parity).
            // Deletes only the records this app wrote, then clears every watermark + the last-sync
            // stamp so the export state matches an empty Health store.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(tr("Remove exported data"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("Delete every record PulseLoop wrote to Health Connect and reset the export to start fresh. Your ring data in PulseLoop is untouched."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!prefs.isConnected) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tr("Deleting needs write permission, and PulseLoop has none right now — re-grant above, or delete the data in the Health Connect app."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val removing = prefs.removalStatus == HealthConnectPrefs.REMOVAL_IN_PROGRESS
                    Button(
                        onClick = { showRemoveDialog = true },
                        enabled = !removing,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (removing) tr("Removing…") else tr("Remove PulseLoop data")) }
                }
            }
        }

        // Removal runs in a worker, so its outcome arrives here through the store rather than
        // through this screen's own state — it survives navigating away and process death.
        prefs.removalStatus?.takeIf { it != HealthConnectPrefs.REMOVAL_IN_PROGRESS }?.let { msg ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = PulseColors.cardSoft,
                    contentColor = PulseColors.textPrimary,
                ),
            ) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { store.update { it.copy(removalStatus = null) } }, modifier = Modifier.widthIn(max = 120.dp)) {
                        Text(tr("Dismiss"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        statusMessage?.let { msg ->
            Card(
                Modifier.fillMaxWidth(),
                // containerColor alone leaves contentColor Unspecified, so the Text falls back to
                // LocalContentColor (dark) and renders near-invisible on this dark card.
                colors = CardDefaults.cardColors(
                    containerColor = PulseColors.cardSoft,
                    contentColor = PulseColors.textPrimary,
                ),
            ) {
                Text(msg, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (showBackfillDialog) {
        AlertDialog(
            // Dismissing (back / outside tap) is a Cancel: iOS's equivalent sets masterEnabled =
            // false. Turning the master off flips the derived showBackfillDialog above back to
            // false (it no longer holds enabled + connected + NOT_ASKED), which also keeps the
            // on-screen state honest — re-enabling re-offers the dialog.
            onDismissRequest = { store.update { it.copy(enabled = false) } },
            title = { Text(tr("How much history should we export?")) },
            // No "change this later" promise: there is no UI to change backfillChoice short of
            // "Remove PulseLoop data", which resets it to NOT_ASKED and re-offers this dialog.
            text = { Text(tr("Exporting all history can take a while on the first run.")) },
            confirmButton = {
                TextButton(onClick = {
                    store.update { it.copy(backfillChoice = HealthConnectPrefs.BackfillChoice.EXPORT_ALL) }
                    HealthConnectExportWorker.enqueue(context) // answer the gate → run the pass
                }) { Text(tr("Sync all history")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    store.update { it.copy(backfillChoice = HealthConnectPrefs.BackfillChoice.EXPORT_NEW_ONLY) }
                    HealthConnectExportWorker.enqueue(context) // stamps watermarks, exports nothing
                }) { Text(tr("Only new data from now on")) }
            },
        )
    }

    // Phase 6: full-revocation offer (Gadgetbridge HealthConnectResetDialogFragment pattern). A
    // later re-grant is a "grow" from empty, so the automatic grow-reset re-exports regardless;
    // this lets the user reset immediately and clears the last-sync stamp too.
    if (showRevokeResetDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeResetDialog = false; store.update { it.copy(revocationOfferDismissed = true) } },
            title = { Text(tr("Health Connect permissions revoked")) },
            text = { Text(tr("PulseLoop no longer has any Health Connect permissions. Reset the export so a later re-grant re-exports your history?")) },
            confirmButton = {
                TextButton(onClick = {
                    store.clearWatermarks()
                    store.update { it.copy(lastSyncAt = null, lastSyncSummary = null, revocationOfferDismissed = true) }
                    showRevokeResetDialog = false
                    statusMessage = tr("Export reset — re-grant permissions to sync again.")
                }) { Text(tr("Reset export")) }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeResetDialog = false; store.update { it.copy(revocationOfferDismissed = true) } }) { Text(tr("Not now")) }
            },
        )
    }

    // Phase 6: "Remove PulseLoop data" confirmation. Deletion is per record type over the full
    // time range (only types still granted), then all watermarks + the last-sync stamp clear.
    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(tr("Remove PulseLoop data?")) },
            text = { Text(tr("This deletes every record PulseLoop wrote to Health Connect and resets the export. It cannot be undone, and your ring data in PulseLoop is not affected.")) },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = false
                    // Enqueued, not launched in this screen's scope (review pass 5): a back-press
                    // mid-delete used to cancel the removal between record types, leaving some
                    // deleted, the rest alive, and the watermarks never cleared — unrepairable
                    // from a write-only client. The worker reports back via prefs.removalStatus.
                    HealthConnectRemovalWorker.enqueue(context)
                }) { Text(tr("Remove")) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) { Text(tr("Cancel")) }
            },
        )
    }
}

/**
 * Opens PulseLoop's own page inside the Health Connect app — permissions plus Health Connect's
 * own "delete app data". This is the way out of the two states PulseLoop cannot fix itself: after
 * the platform stops showing the permission sheet (two denials) the master switch silently does
 * nothing, and once every permission is revoked the app can no longer delete what it wrote
 * (deletion needs WRITE). The client builds the right intent for both the API 34+ platform module
 * and the pre-34 APK; if it cannot be resolved at all, fall back to the store listing.
 */
private fun openHealthConnectApp(context: Context) {
    try {
        context.startActivity(HealthConnectClient.getHealthConnectManageDataIntent(context))
    } catch (_: Exception) {
        openHealthConnectPlayStore(context)
    }
}

private fun openHealthConnectPlayStore(context: Context) {
    // API < 34: Health Connect is a separate APK from Play (plan §2, caveat 1).
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))
    try {
        context.startActivity(market)
    } catch (_: Exception) {
        // Devices without Play (e.g. F-Droid) at least get the web listing.
        runCatching { context.startActivity(web) }
    }
}


/**
 * "About 1d 16h left — draining 2.4 %/h", or an honest silence (issue #65).
 *
 * Fitted over the current discharge run rather than the whole window, because a week of history
 * almost always contains a charge — see [com.pulseloop.service.BatteryProjection]. Shown as an
 * estimate, in those words, because that is what it is: a straight line through past drain, which
 * says nothing about a day spent measuring more often than the last one.
 */
@Composable
private fun BatteryRuntimeEstimate(samples: List<VitalSample>) {
    val estimate = remember(samples) {
        com.pulseloop.service.BatteryProjection.estimate(
            samples.map { com.pulseloop.service.BatteryProjection.Sample(it.timestampMs, it.value) }
        )
    }
    if (estimate == null) {
        Text(
            tr("Not enough of a trend to estimate runtime yet — it appears after a few hours of discharge."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column {
        Text(
            trf("About {0} left", estimate.label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            tr("Estimated from the current discharge at %.1f %%/h.").format(estimate.percentPerHour),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Shows which of this screen's goals the coach goal sets, and lets the user hand them back to it or
 * take them over. Nothing renders without an active coach goal. [onChanged] reloads the screen's
 * values after a sync rewrote them.
 */
@Composable
private fun CoachManagedCard(fields: Set<com.pulseloop.service.GoalSync.Field>, onChanged: () -> Unit) {
    val context = LocalContext.current
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()
    val goal by remember { db.coachGoalDao().activeFlow() }.collectAsState(initial = null)
    var refresh by remember { mutableIntStateOf(0) }
    val managed = remember(refresh, goal) { com.pulseloop.service.GoalSync.managed(context).intersect(fields) }
    val following = remember(refresh, goal) { com.pulseloop.service.GoalSync.following(context).intersect(fields) }
    val g = goal ?: return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.switchRow(checked = following.size == fields.size, onCheckedChange = { on ->
                        com.pulseloop.service.GoalSync.setFollowing(context, fields, on)
                        scope.launch {
                            if (on) com.pulseloop.service.GoalSync.sync(context, db, force = true)
                            refresh++
                            onChanged()
                        }
                    }), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(trf("Coach goal: {0}", g.description), fontWeight = FontWeight.Medium)
                    Text(
                        if (managed.isEmpty()) tr("Your goal doesn't set any of these.")
                        else tr("Set by your goal: ") + managed.joinToString(", ") { it.label } + ". Change one here and it stops following.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = following.size == fields.size, onCheckedChange = null)
            }
            Text(tr("Let my goal manage these"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}


/** Scheduled backups into a folder the user picks (see data/AutoBackup.kt). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoBackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(com.pulseloop.data.AutoBackup.isEnabled(context)) }
    var folderName by remember { mutableStateOf(com.pulseloop.data.AutoBackup.folderName(context)) }
    var interval by remember { mutableStateOf(com.pulseloop.data.AutoBackup.intervalDays(context)) }
    var status by remember { mutableStateOf(backupStatus(context)) }
    var running by remember { mutableStateOf(false) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            com.pulseloop.data.AutoBackup.setFolder(context, uri)
            folderName = com.pulseloop.data.AutoBackup.folderName(context)
            if (!enabled) { enabled = true; com.pulseloop.data.AutoBackup.setEnabled(context, true) }
            status = backupStatus(context)
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("Automatic backups"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        tr("Saves the same file as Export to a folder you choose, and keeps the latest 5."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = {
                    if (it && com.pulseloop.data.AutoBackup.folder(context) == null) picker.launch(null)
                    else { enabled = it; com.pulseloop.data.AutoBackup.setEnabled(context, it) }
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    trf("Folder: {0}", folderName ?: tr("not chosen")),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { picker.launch(null) }, modifier = Modifier.widthIn(max = 120.dp)) {
                    Text(if (folderName == null) tr("Choose") else tr("Change"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Daily", 7 to "Weekly").forEach { (d, label) ->
                    FilterChip(
                        selected = interval == d,
                        onClick = { interval = d; com.pulseloop.data.AutoBackup.setIntervalDays(context, d) },
                        label = { Text(tr(label)) },
                    )
                }
            }
            Text(status, style = MaterialTheme.typography.bodySmall,
                color = if (com.pulseloop.data.AutoBackup.lastError(context) != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                enabled = !running && folderName != null,
                onClick = {
                    running = true
                    scope.launch {
                        com.pulseloop.data.AutoBackup.runNow(context)
                        status = backupStatus(context)
                        running = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (running) tr("Backing up…") else tr("Back up now")) }
        }
    }
}

private fun backupStatus(context: android.content.Context): String {
    com.pulseloop.data.AutoBackup.lastError(context)?.let { return it }
    val last = com.pulseloop.data.AutoBackup.lastAt(context)
    if (last <= 0) return "No backup yet."
    val fmt = java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault())
    return "Last backup: ${fmt.format(java.util.Date(last))}"
}
