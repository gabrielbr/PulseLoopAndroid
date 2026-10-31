package com.pulseloop.ui

import com.pulseloop.ui.i18n.tr
import com.pulseloop.ui.i18n.trf

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.pulseloop.ui.theme.DitherTransitionBox
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pulseloop.AppServices
import com.pulseloop.coach.orchestration.CoachOrchestrator
import com.pulseloop.coach.openai.OpenAIResponsesClient
import com.pulseloop.coach.tools.*
import com.pulseloop.diagnostics.DiagnosticsRepository
import com.pulseloop.diagnostics.DiagnosticsViewModel
import com.pulseloop.service.*
import com.pulseloop.ui.components.AppHeader
import com.pulseloop.ui.screens.*
import com.pulseloop.ui.theme.PulseLoopTheme
import com.pulseloop.ui.viewmodels.*
import com.pulseloop.util.TimeUtil
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import dev.chrisbanes.haze.rememberHazeState

/** Grace period after the app is backgrounded before the live ring link is dropped for battery.
 *  Long enough that a quick app-switch (glance at a notification) doesn't thrash the connection. */
private const val BACKGROUND_DISCONNECT_GRACE_MS = 45_000L

/**
 * Root composable — ported from PulseLoopApp.swift + RootViews.swift.
 * Wires BLE, persistence, coach, and all ViewModels at app startup.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun PulseLoopApp() {
    PulseLoopTheme {
        val context = LocalContext.current

        // ── Singletons ───────────────────────────────────────────────────
        // Process-scoped (issue #113): this composition is rebuilt with the Activity on every
        // configuration change, and a second RingBLEClient would find the first still holding
        // the ring. See AppServices.
        val services = remember { AppServices.get(context) }
        val db = services.db
        val bleClient = services.bleClient
        val apiKeyStore = services.apiKeyStore
        val coordinator = services.coordinator
        val liveWorkout = services.liveWorkout
        val persistence = services.persistence
        val batteryAlerts = services.batteryAlerts
        val providerStore = services.providerStore
        val summaryCoordinator = services.summaryCoordinator
        val checkinDataTrigger = services.checkinDataTrigger
        // Whether this composition is the process's first. A later one (a recreated Activity)
        // finds the ring already wired and connected, so it skips the one-shot startup below and
        // must not treat its first ON_START as already handled by it.
        val isFirstComposition = remember { services.claimStartup() }

        // ── Coach wiring ─────────────────────────────────────────────────
        // Both the client AND the feature flags are resolved per turn through
        // CoachClientResolver (OpenAI / Gemini / OpenRouter per the provider
        // settings), so key/model/provider changes take effect on the next turn
        // without rebuilding the orchestrator — a frozen flags snapshot would
        // keep coachEnabled=false after a key is pasted, or send a stale model
        // slug to a newly selected provider, until process restart.
        // One Open Food Facts client for the lifetime of composition: the nutrition tools
        // (iOS #96 `search_food_database`) reach it through the per-turn ToolExecutionContext.
        // toolContextFactory is called once per turn, so remembering the client keeps the
        // OkHttp stack off the per-turn rebuild path.
        val foodClient = remember { com.pulseloop.nutrition.OpenFoodFactsClient() }

        val coachOrchestrator = remember {
            CoachOrchestrator(
                com.pulseloop.coach.config.CoachClientResolver.clientFactory(providerStore, apiKeyStore),
                flagsProvider = {
                    val resolution = com.pulseloop.coach.config.CoachClientResolver.resolve(providerStore, apiKeyStore)
                    val providerSettings = providerStore.snapshot()
                    val activeModel = com.pulseloop.coach.config.CoachClientResolver.activeModel(
                        providerSettings, apiKeyStore.model,
                    )
                    CoachFeatureFlags(
                        coachEnabled = apiKeyStore.coachEnabled && resolution.key != null,
                        webSearchEnabled = apiKeyStore.webSearchEnabled,
                        writeToolsEnabled = false,  // safe default
                        liveMeasurementsEnabled = true,
                        model = activeModel,
                        settings = com.pulseloop.coach.config.CoachClientResolver.coachSettings(providerSettings, activeModel),
                        providerMode = providerSettings.providerMode,
                    )
                },
                toolContextFactory = { flags ->
                    ToolExecutionContext(
                        db = db,
                        flags = flags,
                        coordinator = coordinator,
                        foodClient = foodClient,
                        appContext = context.applicationContext,
                    )
                },
            )
        }

        // ── ViewModels ───────────────────────────────────────────────────
        val todayVM = remember { TodayViewModel(db, apiKeyStore) }
        val vitalsVM = remember { VitalsViewModel(db, apiKeyStore) }
        val sleepVM = remember {
            val appContext = context.applicationContext
            SleepViewModel(db, gapBridge = { com.pulseloop.service.SleepGapBridge.fromSettings(appContext) })
        }
        val activityVM = remember { ActivityViewModel(db) }
        val weatherContextService = remember { com.pulseloop.coach.context.WeatherContextService(context) }
        val coachVM = remember {
            CoachViewModel(
                db, coachOrchestrator,
                attachmentPayloads = { refs ->
                    com.pulseloop.coach.attachments.CoachAttachmentStore.payloads(context, refs)
                },
                environmentSnapshot = { weatherContextService.snapshot() },
            )
        }

        // Guarantee sleep is repopulated after every sync. The CONNECTED handler wipes sleep_sessions
        // to rebuild them from the ring (EventPersistenceSubscriber), but the full-sync SLEEP stage is
        // gated behind four earlier watchdog-skippable stages and often doesn't re-deliver — so the
        // Today tile / widgets can sit on "Sleep pending" until the user opens the Sleep screen, whose
        // onOpen fires the reliable sleep-only fetch (syncSleepNow). Fire that same fetch here on the
        // sync-completion edge — NOT on connect: syncSleepNow no-ops while a full sync is running
        // (ColmiSyncEngine guards stage != IDLE/DONE), so it must run once the pipeline reaches DONE
        // (syncProgress falls back to null). Its reply lands after the connect-clear, so it can't be
        // wiped; the upsert is idempotent, so a sync whose SLEEP stage did survive just refreshes.
        LaunchedEffect(Unit) {
            var wasSyncing = false
            coordinator.syncProgress.collect { progress ->
                val syncing = progress != null
                if (wasSyncing && !syncing) coordinator.syncSleepNow() // a full sync just finished
                wasSyncing = syncing
            }
        }

        // Finished accuracy checks scored under older rules are scored again once, here, while the
        // app is in the foreground (Health Connect reads need it). One that can't be read now is
        // retried on the next launch or when the accuracy screen opens.
        LaunchedEffect(Unit) {
            if (!isFirstComposition) return@LaunchedEffect
            services.startupScope.launch {
                // After startup's own work, so it doesn't compete with connecting the ring.
                kotlinx.coroutines.delay(10_000)
                runCatching { com.pulseloop.health.accuracy.AccuracyScorer.rescoreOutdated(context, db.accuracyDao().all()) }
            }
        }

        // ── Start services (one-shot per process) ────────────────────────
        LaunchedEffect(Unit) {
            if (!isFirstComposition) return@LaunchedEffect

            // Run in the process scope, not this effect's: the gate above is already claimed, so if
            // a rotation or theme change disposed this composition mid-startup, nothing would ever
            // finish it (issue #113 review).
            services.startupScope.launch {
                // Wire onConnected → run the startup/history sync only (matches iOS). Connecting
                // does NOT force a measurement; the ring's own periodic monitoring is pulled in via
                // history, and on-demand readings come from the Vitals "Measure" button.
                bleClient.onConnected = { coordinator.runStartupSequence() }

                // Wire firmware read → persist to DB
                bleClient.onFirmwareRead = { fw ->
                    kotlinx.coroutines.runBlocking {
                        val dev = db.deviceDao().current()
                        // Standard DIS (2A26/2A28) firmware is only a fallback. The official app
                        // displays the custom-protocol device-info version ("…V138"), not the DIS
                        // string, so never overwrite a value that already carries the "V" version.
                        if (dev != null && (dev.firmwareVersion.isNullOrBlank() || !dev.firmwareVersion!!.contains("V"))) {
                            db.deviceDao().upsert(dev.copy(firmwareVersion = fw, updatedAt = System.currentTimeMillis()))
                        }
                    }
                }

                // Start services
                persistence.start()
                batteryAlerts.start()
                coordinator.start()
                summaryCoordinator.start()
                checkinDataTrigger.start()

                // Stale-state guard: a persisted "CONNECTED"/"CONNECTING" must not survive a
                // process restart — the live GATT is gone, so the views would otherwise show a
                // false "Connected". Reset until a real connection re-confirms it.
                db.deviceDao().current()?.let { dev ->
                    if (dev.stateRaw == "CONNECTED" || dev.stateRaw == "CONNECTING") {
                        db.deviceDao().upsert(dev.copy(stateRaw = "DISCONNECTED", updatedAt = System.currentTimeMillis()))
                    }
                }

                // Auto-reconnect to last-known ring if any
                if (bleClient.hasPermissions()) {
                    bleClient.connectLastKnown()
                }

                // Schedule periodic background sync (matches official app behavior)
                RingSyncWorker.schedule(context.applicationContext)
                // Daily guidance: unusual-signal alerts, weekly report, wind-down reminder.
                com.pulseloop.notifications.GuidanceWorker.schedule(context.applicationContext)
                com.pulseloop.health.HealthConnectBodyImport.schedule(context.applicationContext)
                com.pulseloop.health.source.HealthConnectSource.schedule(context.applicationContext)
                // The breastfeeding allowance grows at six months and ends at twelve.
                runCatching { com.pulseloop.ui.screens.syncCalorieGoal(context.applicationContext) }
                runCatching { com.pulseloop.health.ProfileAging.refresh(db) }

                // NOTE: demo data is never auto-seeded. A clean install starts empty so the UI
                // reflects only real ring data. Demo data is seeded exclusively via the
                // confirmation-gated "Reseed Demo Data" button in Settings.
            }
        }

        // ── Connection lifecycle on foreground/background ────────────────
        // ON_START: re-attempt the link. The OS may have torn the GATT down during Doze
        // (autoConnect=false won't recover it), so reconnect on every foreground so the user
        // never has to force-close to reconnect. The process's first ON_START is handled by the
        // LaunchedEffect above, so skip it here; a recreated Activity's first one is not.
        // ON_STOP: drop the live link a short grace period after backgrounding so the ring can
        // sleep (a held-open link keeps the ~17mAh ring awake and blocks its advertising). This
        // is a TRANSIENT disconnect — it does NOT set the user stay-off flag, so ON_START silently
        // reconnects; the 30-min RingSyncWorker covers data while backgrounded. Skipped entirely
        // while a workout is in progress, since that needs the live stream. The grace period is
        // cancelled if the app returns to the foreground first, so quick app-switches don't thrash.
        val lifecycleOwner = LocalLifecycleOwner.current
        val bgDisconnectScope = rememberCoroutineScope()
        DisposableEffect(lifecycleOwner) {
            var isFirstStart = isFirstComposition
            var bgDisconnectJob: Job? = null
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        bgDisconnectJob?.cancel(); bgDisconnectJob = null
                        todayVM.refreshCurrentDay()
                        activityVM.refreshCurrentDay()
                        sleepVM.refreshReferenceNight()
                        if (isFirstStart) isFirstStart = false else bleClient.reconnectIfNeeded()
                        // No ring, Health Connect instead: foreground reads get the larger quota.
                        com.pulseloop.health.source.HealthConnectSource.runNow(context.applicationContext)
                    }
                    Lifecycle.Event.ON_STOP -> {
                        if (liveWorkout.state.value.activeSession != null) return@LifecycleEventObserver
                        bgDisconnectJob?.cancel()
                        // Default dispatcher so the timer fires while backgrounded (a UI-frame
                        // clock would stall until the next foreground frame).
                        bgDisconnectJob = bgDisconnectScope.launch(Dispatchers.Default) {
                            delay(BACKGROUND_DISCONNECT_GRACE_MS)
                            if (liveWorkout.state.value.activeSession == null) bleClient.disconnect()
                        }
                    }
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                bgDisconnectJob?.cancel()
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // Keep an app left open overnight on the current local-day Room query. Rechecking at most
        // once a minute also catches wall-clock or timezone changes without a lifecycle edge.
        LaunchedEffect(todayVM, activityVM, sleepVM) {
            while (true) {
                delay(TimeUtil.millisUntilNextLocalDay().coerceAtMost(60_000L))
                todayVM.refreshCurrentDay()
                activityVM.refreshCurrentDay()
                sleepVM.refreshReferenceNight()
            }
        }

        // ── Navigation ───────────────────────────────────────────────────
        // Tabs: Today, Vitals, Activity, Sleep, Nutrition. Nutrition took the Coach's place: seeing
        // what you ate, and adding a meal to an earlier day, used to be three taps deep. The coach
        // is one tap from every tab instead, at the top of the Actions sheet.
        val navController = rememberNavController()

        // First-launch gate (iOS #48): show the onboarding flow until it's completed.
        // Existing installs that already have a completed profile or a paired ring are
        // marked complete silently so an app update never re-onboards them.
        // Screens asked for from outside (icon shortcut, meal-reminder notification).
        LaunchedEffect(Unit) {
            com.pulseloop.ui.LaunchRequests.route.collect { route ->
                if (route != null && apiKeyStore.onboardingCompleted) {
                    navController.navigate(route) { launchSingleTop = true }
                    com.pulseloop.ui.LaunchRequests.route.value = null
                }
            }
        }

        LaunchedEffect(Unit) {
            if (!apiKeyStore.onboardingCompleted) {
                val profileDone = db.userProfileDao().get()?.onboardingCompleted == true
                val hasRing = db.deviceDao().current() != null || bleClient.hasLastKnownRing ||
                    com.pulseloop.health.source.HealthConnectSource.isEnabled(context)
                if (profileDone || hasRing) {
                    apiKeyStore.onboardingCompleted = true
                } else {
                    navController.navigate("onboarding") { launchSingleTop = true }
                }
            }
        }

        val tabs = listOf(
            Tab("today", tr("Today"), Icons.Filled.Adjust, Icons.Outlined.Adjust),
            Tab("vitals", tr("Vitals"), Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder),
            Tab("activity", tr("Activity"), Icons.Filled.MonitorHeart, Icons.Outlined.MonitorHeart),
            Tab("sleep", tr("Sleep"), Icons.Filled.Bedtime, Icons.Outlined.Bedtime),
            Tab("nutrition", tr("Nutrition"), Icons.Filled.Restaurant, Icons.Outlined.Restaurant),
        )

        // ── Self-update: release-only. Checked on every return to foreground (ON_START also
        // fires on cold start — lifecycle observers replay up to the current state), so a
        // long-running app notices a new release without a force-close. UpdateChecker's
        // 15-min throttle + ETag caching bound the GitHub traffic; remembering the dismissed
        // versionCode keeps the frequent checks from re-nagging about the same release within
        // a session. Settings also has a manual "Check for updates".
        var pendingUpdate by remember { mutableStateOf<com.pulseloop.update.UpdateInfo?>(null) }
        // rememberSaveable so a dismissed release stays dismissed across config changes
        // (rotation) — plain remember would reset and let the throttle-expired foreground
        // check re-nag about the same version.
        var dismissedUpdateCode by rememberSaveable { mutableStateOf<Int?>(null) }
        val updateScope = rememberCoroutineScope()
        val updateLifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(updateLifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    updateScope.launch {
                        val info = (com.pulseloop.update.UpdateChecker.check(context)
                            as? com.pulseloop.update.UpdateCheckResult.UpdateAvailable)?.info
                        if (info != null && info.versionCode != dismissedUpdateCode) {
                            pendingUpdate = info
                        }
                    }
                }
            }
            updateLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { updateLifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        // From 600dp (landscape phones, tablets, unfolded foldables) the tabs move to a
        // NavigationRail at the side, as Material's adaptive navigation asks; the bottom bar
        // stays for compact portrait.
        val useRail = LocalConfiguration.current.screenWidthDp >= 600
        val tabRoutes = remember(tabs) { tabs.map { it.route }.toSet() }

        // The header's health line (HealthHeadline). Held here, not in the top bar, so it
        // survives the coach screen swapping the header out; read off the main thread every
        // few minutes and again when lab results change. The last line stays up while the
        // next is read, so the header never blanks.
        var headline by remember { mutableStateOf<com.pulseloop.service.HealthHeadline.Headline?>(null) }
        val labsKey by remember(db) {
            kotlinx.coroutines.flow.combine(db.labDao().reportsFlow(), db.labDao().resultsFlow()) { reports, results ->
                com.pulseloop.service.HealthHeadline.labsOutside(reports, results) * 100_000 + reports.size
            }
        }.collectAsState(initial = -1)
        LaunchedEffect(labsKey) {
            while (true) {
                runCatching { com.pulseloop.service.HealthHeadline.load(db) }.getOrNull()?.let { headline = it }
                delay(5 * 60_000L)
            }
        }
        val profileName by remember(db) {
            db.userProfileDao().getFlow().map { it?.name }
        }.collectAsState(initial = null)

        // iOS-style frosted tab bar (.ultraThinMaterial parity): the scrollable tabs
        // render UNDER the bar (hazeSource on the NavHost) and the bar blurs whatever
        // is behind it. Real RenderEffect blur on Android 12+, scrim fallback below.
        // Custom style instead of HazeMaterials.ultraThin: the material's tint over
        // the near-black palette reads almost opaque; a lighter tint + a faint white
        // lift keeps the blurred content visible like the iOS bar.
        val hazeState = rememberHazeState()
        val glassStyle = dev.chrisbanes.haze.HazeStyle(
            backgroundColor = com.pulseloop.ui.theme.PulseColors.background,
            tints = listOf(
                dev.chrisbanes.haze.HazeTint(com.pulseloop.ui.theme.PulseColors.background.copy(alpha = 0.42f)),
                dev.chrisbanes.haze.HazeTint(Color.White.copy(alpha = 0.04f)),
            ),
            blurRadius = 24.dp,
            noiseFactor = 0.02f,
        )

        // "Actions" on every tab: recording something is the most frequent job in the app, so it
        // sits in the same place whichever tab is open, and the coach is the sheet's first row.
        val fabEntry by navController.currentBackStackEntryAsState()
        val showLogFab = fabEntry?.destination?.route in tabRoutes
        Scaffold(
            containerColor = com.pulseloop.ui.theme.PulseColors.background,
            floatingActionButton = {
                // Pops in on the tabs that have it and out on the ones that don't, rather than
                // blinking with the screen change.
                androidx.compose.animation.AnimatedVisibility(
                    visible = showLogFab,
                    enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut(),
                ) {
                    ExtendedFloatingActionButton(
                        onClick = { LaunchRequests.openLog.value = com.pulseloop.ui.screens.LogStep.MENU },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text(tr("Actions"), maxLines = 1) },
                        containerColor = com.pulseloop.ui.theme.PulseColors.accent,
                        contentColor = com.pulseloop.ui.theme.PulseColors.onAccent,
                    )
                }
            },
            topBar = {
                // Shared iOS-style header (PULSELOOP eyebrow + greeting + status pill) on tab
                // routes only; pushed screens (settings, details, pairing) bring their own bars.
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val route = navBackStackEntry?.destination?.route
                // The coach is no longer a tab but keeps the frosted header it had as one.
                val onTabRoute = route in tabRoutes || route == "coach"
                // Landscape keeps the header (compact, one row): it is the only route to
                // Settings and the ring, so dropping it stranded them.
                if (onTabRoute) {
                    Column(
                        Modifier
                            // iOS-style scroll-under: blur strongest at the status bar,
                            // fading to transparent at the header's bottom edge.
                            .hazeEffect(state = hazeState, style = glassStyle) {
                                progressive = dev.chrisbanes.haze.HazeProgressive.verticalGradient(
                                    startIntensity = 1f,
                                    endIntensity = 0f,
                                )
                            }
                            .windowInsetsPadding(WindowInsets.statusBars),
                    ) {
                        if (route == "coach") {
                            // Coach keeps its bespoke avatar header, rendered here as the same
                            // frosted glass bar the other tabs use so the chat scrolls under it
                            // (owner choice, issue #22).
                            var showCoachUsage by remember { mutableStateOf(false) }
                            CoachHeader(
                                onBack = { navController.popBackStack() },
                                onNewChat = { coachVM.newConversation() },
                                onOpenUsage = { showCoachUsage = true },
                            )
                            if (showCoachUsage) {
                                var usageData by remember {
                                    mutableStateOf<Pair<com.pulseloop.data.entity.CoachConversationEntity, List<com.pulseloop.data.entity.CoachMessageEntity>>?>(null)
                                }
                                LaunchedEffect(Unit) { usageData = coachVM.usageSnapshot() }
                                usageData?.let { (convo, messages) ->
                                    val providerSettings = providerStore.snapshot()
                                    com.pulseloop.ui.screens.CoachUsageSheet(
                                        conversation = convo,
                                        messages = messages,
                                        fallbackModel = com.pulseloop.coach.config.CoachClientResolver.activeModel(
                                            providerSettings, apiKeyStore.model,
                                        ),
                                        fallbackProviderMode = providerSettings.providerMode,
                                        onDismiss = { showCoachUsage = false },
                                    )
                                }
                            }
                        } else {
                            val ble by bleClient.state.collectAsState()
                            // remember the Flow: this lambda recomposes on every BLE state change, and a
                            // fresh Flow resubscribes from `null`, blinking the pill.
                            val storedDevice by remember(db) { db.deviceDao().currentFlow() }
                                .collectAsState(initial = null)
                            // Prefer live BLE state; fall back to the stored device so demo data
                            // shows its ring instead of a permanently blank pill (RootViews.swift).
                            val liveActive = ble.connectionState in setOf(
                                com.pulseloop.ring.RingConnectionState.CONNECTED,
                                com.pulseloop.ring.RingConnectionState.CONNECTING,
                                com.pulseloop.ring.RingConnectionState.RECONNECTING,
                                com.pulseloop.ring.RingConnectionState.SCANNING,
                            )
                            val hcSource by remember { com.pulseloop.health.source.HealthConnectSource.activeSource(context) }
                                .collectAsState()
                            AppHeader(
                                state = if (liveActive) ble.connectionState
                                    else storedDevice?.state ?: ble.connectionState,
                                batteryPercent = ble.batteryPercent ?: storedDevice?.batteryPercent,
                                onOpenSettings = { navController.navigate("settings") },
                                // The pill says what the ring is doing, so it opens the ring's
                                // screen; the gear beside it is the Settings hub.
                                onOpenRing = { navController.navigate("settings/wearable") { launchSingleTop = true } },
                                compact = isLandscape,
                                idle = ble.idleSince != null,
                                sourceLabel = hcSource?.let { com.pulseloop.health.source.HealthConnectSource.label(context, it) + tr(" · Health Connect") },
                                name = profileName,
                                headline = headline,
                                onOpenHeadline = { target -> navController.navigate(target) { launchSingleTop = true } },
                            )
                            // Thin sync-progress accent under the greeting while history streams in.
                            // Its 2dp are always reserved: appearing and disappearing changed the
                            // header's height, so every sync (each reconnect, every half hour)
                            // shifted the whole tab down and back up.
                            val syncPct = coordinator.syncProgress.collectAsState().value
                            Box(Modifier.fillMaxWidth().height(2.dp)) {
                                if (syncPct != null) {
                                    LinearProgressIndicator(
                                        progress = { (syncPct.coerceIn(0, 100)) / 100f },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                fun isSelected(tab: Tab) =
                    currentDestination?.hierarchy?.any { it.route == tab.route } == true
                val onTab: (Tab) -> Unit = { tab ->
                    // Pop everything above the start destination but keep it;
                    // launchSingleTop jumps back to the existing tab instance.
                    if (!isSelected(tab)) navController.navigate(tab.route) {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                }
                // Blur only where content scrolls under the bar (the tabs); a pushed screen sits
                // above an opaque bar anyway, so a per-frame blur there bought nothing.
                val barGlass = if (currentDestination?.route in tabRoutes) Modifier.hazeEffect(state = hazeState, style = glassStyle)
                    else Modifier.background(com.pulseloop.ui.theme.PulseColors.background)
                if (currentDestination?.route == "onboarding" || useRail) {
                    // The onboarding flow is full-screen chrome of its own — no tab bar; on wide
                    // windows the tabs live in the rail beside the content instead.
                } else if (isLandscape) {
                    // Landscape: a compact icon-only bar at ~half the standard height,
                    // so the short viewport keeps more room for content.
                    Surface(
                        color = Color.Transparent,
                        modifier = barGlass,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                                .height(48.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            tabs.forEach { tab ->
                                val selected = isSelected(tab)
                                IconButton(
                                    onClick = { onTab(tab) },
                                    modifier = Modifier.semantics {
                                        this.selected = selected
                                        role = Role.Tab
                                    },
                                ) {
                                    Icon(
                                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label,
                                        tint = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column {
                        HorizontalDivider(thickness = 1.dp, color = com.pulseloop.ui.theme.PulseColors.borderSubtle)
                        NavigationBar(
                            containerColor = Color.Transparent,
                            modifier = barGlass,
                        ) {
                            tabs.forEach { tab ->
                                val selected = isSelected(tab)
                                NavigationBarItem(
                                    icon = {
                                        Icon(
                                            if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            // The item's label already names the tab.
                                            contentDescription = null,
                                        )
                                    },
                                    // One line at the 12sp label size. Portuguese says "Vitais" here: "Sinais vitais"
                                    // wrapped to two lines and pushed its icon out of line. Items already
                                    // share the width equally (NavigationBarItem weights itself 1f).
                                    label = {
                                        Text(
                                            tab.label,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    selected = selected,
                                    onClick = { onTab(tab) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = com.pulseloop.ui.theme.PulseColors.onAccent,
                                        selectedTextColor = com.pulseloop.ui.theme.PulseColors.textPrimary,
                                        unselectedIconColor = com.pulseloop.ui.theme.PulseColors.textMuted,
                                        unselectedTextColor = com.pulseloop.ui.theme.PulseColors.textMuted,
                                        indicatorColor = com.pulseloop.ui.theme.PulseColors.accent,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            // The four scrollable tabs draw UNDER the glass top/bottom bars (they get the
            // bar heights as extra content padding instead); every other route keeps the
            // bars as an opaque floor/ceiling via this per-route padding wrapper.
            val topPad = padding.calculateTopPadding()
            val barPad = padding.calculateBottomPadding()
            // The tabs scroll their last card clear of the Actions button.
            val tabPad = barPad + 72.dp
            fun androidx.navigation.NavGraphBuilder.paddedComposable(
                route: String,
                content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
            ) = composable(route) { entry ->
                DitherTransitionBox { Box(Modifier.fillMaxSize().padding(padding)) { content(entry) } }
            }
            // Every destination takes part in the dither wipe (theme/DitherTransition.kt).
            fun androidx.navigation.NavGraphBuilder.screen(
                route: String,
                content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
            ) = composable(route) { entry ->
                DitherTransitionBox {
                    // On a tablet or a landscape phone a single column of cards stretched across
                    // the whole window; it is capped at 720dp and centred instead.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { content(entry) }
                    }
                }
            }
            val railEntry by navController.currentBackStackEntryAsState()
            val railDestination = railEntry?.destination
            Row(Modifier.fillMaxSize()) {
            if (useRail && railDestination?.route != "onboarding") {
                NavigationRail(
                    containerColor = com.pulseloop.ui.theme.PulseColors.background,
                    modifier = Modifier.fillMaxHeight().padding(top = topPad, bottom = barPad),
                ) {
                    tabs.forEach { tab ->
                        val selected = railDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                if (!selected) navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId)
                                    launchSingleTop = true
                                }
                            },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.unselectedIcon, contentDescription = null) },
                            label = { Text(tab.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = com.pulseloop.ui.theme.PulseColors.onAccent,
                                selectedTextColor = com.pulseloop.ui.theme.PulseColors.textPrimary,
                                unselectedIconColor = com.pulseloop.ui.theme.PulseColors.textMuted,
                                unselectedTextColor = com.pulseloop.ui.theme.PulseColors.textMuted,
                                indicatorColor = com.pulseloop.ui.theme.PulseColors.accent,
                            ),
                        )
                    }
                }
            }
            NavHost(
                navController = navController,
                startDestination = "today",
                modifier = Modifier.weight(1f).hazeSource(hazeState),
                enterTransition = { com.pulseloop.ui.theme.DitherTransition.enter },
                exitTransition = { com.pulseloop.ui.theme.DitherTransition.exit },
                popEnterTransition = { com.pulseloop.ui.theme.DitherTransition.enter },
                popExitTransition = { com.pulseloop.ui.theme.DitherTransition.exit },
            ) {
                screen("today") { TodayScreen(navController, todayVM, coordinator, vitalsVM, sleepVM, topBarPadding = topPad, bottomBarPadding = tabPad) }
                screen("vitals") { VitalsScreen(navController = navController, viewModel = vitalsVM, coordinator = coordinator, topBarPadding = topPad, bottomBarPadding = tabPad) }
                screen("sleep") { SleepScreen(navController = navController, viewModel = sleepVM, onOpen = { coordinator.syncSleepNow() }, topBarPadding = topPad, bottomBarPadding = tabPad) }
                screen("activity") { ActivityScreen(navController = navController, viewModel = activityVM, liveWorkout = liveWorkout, topBarPadding = topPad, bottomBarPadding = tabPad) }
                paddedComposable("activity_detail/{id}") { backStackEntry ->
                    val id = backStackEntry.arguments?.getString("id") ?: return@paddedComposable
                    WorkoutSummaryScreen(sessionId = id, onBack = { navController.popBackStack() })
                }
                paddedComposable("log_past_activity") {
                    LogPastActivityScreen(
                        onBack = { navController.popBackStack() },
                        onSaved = { id ->
                            navController.navigate("activity_detail/$id") {
                                popUpTo("activity")
                            }
                        },
                    )
                }
                // Coach draws full-bleed under the glass top/bottom bars (like the other tabs), so
                // the chat frosts under its header and the composer can clear the keyboard itself.
                screen("coach") {
                    CoachScreen(
                        navController = navController,
                        viewModel = coachVM,
                        topBarPadding = topPad,
                        bottomBarPadding = barPad,
                    )
                }
                paddedComposable("settings") { SettingsScreen(navController, bleClient, coordinator) }
                // Settings detail screens (iOS #49): each hub row pushes one of these.
                paddedComposable("settings/wearable") {
                    WearableSettingsScreen(
                        bleClient = bleClient,
                        coordinator = coordinator,
                        onAddRing = { navController.navigate("pairing") },
                        onBack = { navController.popBackStack() },
                    )
                }
                paddedComposable("settings/measurement") {
                    MeasurementSettingsScreen(coordinator, onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/coach") {
                    CoachSettingsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/checkins") {
                    CheckInsSettingsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/profile") {
                    ProfileSettingsScreen(coordinator, onBack = { navController.popBackStack() }, onOpenPostpartum = { navController.navigate("postpartum") })
                }
                paddedComposable("settings/physiology") {
                    PhysiologySettingsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/calibration") {
                    CalibrationSettingsScreen(coordinator, onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/goals") {
                    GoalsSettingsScreen(coordinator, onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/privacy") {
                    PrivacyDataSettingsScreen(
                        onBack = { navController.popBackStack() },
                        coordinator = coordinator,
                        bleClient = bleClient,
                        onNavigateToOnboarding = {
                            navController.navigate("onboarding") {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                    )
                }
                paddedComposable("settings/strava") {
                    StravaSettingsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/health-connect") {
                    HealthConnectSettingsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/hc-source") {
                    HealthConnectSourceScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/accuracy-check") {
                    AccuracyCheckSettingsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/nutrition") {
                    NutritionSettingsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToNutrition = { navController.navigate("nutrition") },
                    )
                }
                // "Other ideas" on the next-meal card hands the suggestion to the coach chat.
                val discussMeal: ((String) -> Unit)? = if (apiKeyStore.coachEnabled) { msg ->
                    com.pulseloop.ui.LaunchRequests.coachMessage.value = msg
                    navController.navigate("coach") { launchSingleTop = true }
                } else null
                // A tab: the shared header above it, and no back arrow of its own.
                // Scrolls under the glass bars like the other four tabs, with the same paddings.
                screen("nutrition") {
                    NutritionScreen(
                        onBack = { navController.popBackStack() }, onDiscuss = discussMeal, asTab = true,
                        topBarPadding = topPad, bottomBarPadding = tabPad,
                    )
                }
                // Straight into a way of logging (Today's food card, the "Log meal" shortcut).
                listOf("photo", "barcode", "add").forEach { start ->
                    paddedComposable("nutrition/$start") {
                        NutritionScreen(onBack = { navController.popBackStack() }, start = start, onDiscuss = discussMeal)
                    }
                }
                paddedComposable("drinks") {
                    com.pulseloop.ui.screens.DrinksScreen(
                        onBack = { navController.popBackStack() },
                        onBreathe = { navController.navigate("breathe") },
                        onCoach = discussMeal,
                    )
                }
                paddedComposable("settings/language") {
                    LanguageSettingsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("settings/about") {
                    AboutSettingsScreen(
                        onOpenDebug = { navController.navigate("debug") },
                        onBack = { navController.popBackStack() },
                    )
                }
                paddedComposable("debug") {
                    // A real ViewModel, not `remember`: its Room flows live in viewModelScope and must
                    // be cancelled when the screen leaves the back stack.
                    val debugVM: DiagnosticsViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            DiagnosticsViewModel(DiagnosticsRepository(db), bleClient, coordinator) as T
                    })
                    DebugScreen(debugVM, onBack = { navController.popBackStack() })
                }
                paddedComposable("journal") {
                    com.pulseloop.ui.screens.JournalScreen(
                        db = db,
                        onBack = { navController.popBackStack() },
                        onOpenDrinks = { navController.navigate("drinks") },
                    )
                }
                paddedComposable("activity_score") {
                    com.pulseloop.ui.screens.ActivityScoreScreen(
                        db = db,
                        onBack = { navController.popBackStack() },
                        onOpenActivity = { navController.navigate("activity") { launchSingleTop = true } },
                    )
                }
                paddedComposable("my_health") {
                    com.pulseloop.ui.screens.MyHealthScreen(
                        onBack = { navController.popBackStack() },
                        onBreathe = { navController.navigate("breathe") },
                        onWhatChanged = { navController.navigate("what_changed") },
                        onCycle = { navController.navigate("cycle") },
                        onMeds = { navController.navigate("meds") },
                        onPostpartum = { navController.navigate("postpartum") },
                        onLabReports = { navController.navigate("lab_reports") },
                    )
                }
                paddedComposable("lab_reports") {
                    com.pulseloop.ui.screens.LabReportsScreen(
                        onBack = { navController.popBackStack() },
                        onMarker = { key -> navController.navigate("lab_reports/marker/$key") },
                        onAskCoach = {
                            com.pulseloop.ui.LaunchRequests.coachMessage.value = tr("Make me a plan from my lab reports.")
                            navController.navigate("coach") { launchSingleTop = true }
                        },
                    )
                }
                paddedComposable("lab_reports/marker/{key}") { entry ->
                    val key = entry.arguments?.getString("key") ?: return@paddedComposable
                    com.pulseloop.ui.screens.LabMarkerScreen(
                        markerKey = key,
                        onBack = { navController.popBackStack() },
                        onAskCoach = { name ->
                            com.pulseloop.ui.LaunchRequests.coachMessage.value = trf("Make me a plan from my lab reports, starting with {0}.", name)
                            navController.navigate("coach") { launchSingleTop = true }
                        },
                    )
                }
                paddedComposable("postpartum") {
                    com.pulseloop.ui.screens.PostpartumScreen(
                        onBack = { navController.popBackStack() },
                        onOpenProfile = { navController.navigate("settings/profile") },
                    )
                }
                paddedComposable("meds") {
                    com.pulseloop.ui.screens.MedsScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("cycle") {
                    com.pulseloop.ui.screens.CycleScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("what_changed") {
                    com.pulseloop.ui.screens.WhatChangedScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("breathe") {
                    com.pulseloop.ui.screens.BreatheScreen(onBack = { navController.popBackStack() })
                }
                paddedComposable("readiness") {
                    com.pulseloop.ui.screens.ReadinessScreen(db = db, onBack = { navController.popBackStack() })
                }
                paddedComposable("vitals/{metric}") { backStackEntry ->
                    val metric = backStackEntry.arguments?.getString("metric") ?: return@paddedComposable
                    VitalDetailScreen(
                        metric = metric,
                        onBack = { navController.popBackStack() },
                        db = db,
                        apiKeyStore = apiKeyStore,
                    )
                }
                paddedComposable("onboarding") {
                    // The flow contains its own pairing step (iOS #48), so completion goes
                    // straight to the main app instead of the standalone pairing route.
                    OnboardingScreen(
                        db = db,
                        bleClient = bleClient,
                        apiKeyStore = apiKeyStore,
                        coordinator = coordinator,
                        onFinished = {
                            navController.navigate("today") {
                                popUpTo("onboarding") { inclusive = true }
                            }
                        },
                    )
                }
                paddedComposable("record") {
                    val workoutState = liveWorkout.state.collectAsState().value
                    // A finish from the notification action arrives through the command bus with
                    // no button handler to navigate from — route to the summary off the state
                    // signal instead (mirrors the onFinish path below).
                    LaunchedEffect(workoutState.finishedSessionId) {
                        workoutState.finishedSessionId?.let { id ->
                            navController.navigate("activity_detail/$id") {
                                popUpTo("record") { inclusive = true }
                            }
                        }
                    }
                    RecordScreen(
                        activityName = workoutState.activeSession?.type ?: tr("Workout"),
                        elapsedSeconds = workoutState.elapsedSeconds,
                        distanceMeters = workoutState.distanceMeters,
                        heartRate = workoutState.latestHeartRate,
                        heartRateAdjusted = workoutState.heartRateAdjusted,
                        spO2 = workoutState.latestSpO2,
                        isPaused = workoutState.isPaused,
                        useGps = workoutState.activeSession?.useGps ?: true,
                        hrZone = workoutState.hrZone,
                        units = apiKeyStore.resolvedUnitSystem,
                        onPause = {
                            workoutState.activeSession?.let { liveWorkout.pauseFromUi(it) }
                        },
                        onResume = {
                            workoutState.activeSession?.let { liveWorkout.resumeFromUi(it) }
                        },
                        onFinish = {
                            // No runBlocking on the main thread. The finishedSessionId effect above lands
                            // on the summary (iOS RecordSummaryView) once the finish is written; with no
                            // session there is nothing to finish, so just leave.
                            workoutState.activeSession?.let { liveWorkout.finishFromUi(it) }
                                ?: navController.popBackStack()
                        },
                    )
                }
                paddedComposable("pairing") {
                    PairingScreen(
                        bleClient = bleClient,
                        onConnected = { navController.popBackStack() },
                    )
                }
            }
            } // Row: rail + content
        }

        val logStep by LaunchRequests.openLog.collectAsState()
        logStep?.let { step ->
            fun close() { LaunchRequests.openLog.value = null }
            com.pulseloop.ui.screens.LogSheet(
                db = db,
                start = step,
                onDismiss = ::close,
                onCoach = { close(); navController.navigate("coach") { launchSingleTop = true } },
                onMeal = { start -> close(); navController.navigate("nutrition/$start") },
                onMoreDrinks = { close(); navController.navigate("drinks") },
                onMeds = { close(); navController.navigate("meds") },
                onPeriod = { close(); navController.navigate("cycle") },
                onWorkout = {
                    close()
                    LaunchRequests.openWorkoutPicker.value = true
                    navController.navigate("activity") {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                },
                onSeeEffects = { close(); navController.navigate("journal") },
            )
        }

        pendingUpdate?.let { info ->
            com.pulseloop.update.UpdateDialog(info) {
                // Remember what was dismissed: foreground checks now run every ON_START, and
                // without this the same release would re-prompt on every app switch.
                dismissedUpdateCode = info.versionCode
                pendingUpdate = null
            }
        }
    }
}

private data class Tab(
    val route: String,
    val label: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
)
