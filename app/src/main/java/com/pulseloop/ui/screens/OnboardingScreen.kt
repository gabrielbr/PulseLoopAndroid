package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.i18n.tr

import com.pulseloop.ui.theme.dither
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.pulseloop.R
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.data.entity.UserProfileEntity
import com.pulseloop.ring.RingBLEClient
import com.pulseloop.service.RingSyncCoordinator
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.settings.UnitSystem
import com.pulseloop.ui.components.PrimaryButton
import com.pulseloop.ui.components.SecondaryButton
import com.pulseloop.ui.onboarding.GoalDraft
import com.pulseloop.ui.onboarding.GoalEditor
import com.pulseloop.ui.onboarding.OnboardingProgressStore
import com.pulseloop.ui.onboarding.OnboardingStep
import com.pulseloop.ui.onboarding.ProfileDraft
import com.pulseloop.ui.onboarding.ProfileEditor
import com.pulseloop.ui.theme.PulseColors
import com.pulseloop.ui.theme.ditherFade
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The 5-step first-launch onboarding flow — ported from OnboardingFlowView.swift (iOS #48):
 * welcome → ring pairing → profile → goals → baseline education. Progress persists via
 * [OnboardingProgressStore] so a killed app relaunches on the same step.
 */
@Composable
fun OnboardingScreen(
    db: PulseLoopDatabase,
    bleClient: RingBLEClient,
    apiKeyStore: ApiKeyStore,
    coordinator: RingSyncCoordinator? = null,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progressStore = remember {
        OnboardingProgressStore(context.getSharedPreferences("pulseloop_onboarding", Context.MODE_PRIVATE))
    }
    var path by remember { mutableStateOf(progressStore.loadPath()) }
    val step = path.lastOrNull() ?: OnboardingStep.WELCOME
    // "I wear an Oura…": the RING step becomes the Health Connect source picker instead of pairing.
    var useHealthConnect by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    fun push(next: OnboardingStep) {
        if (path.lastOrNull() == next) return
        path = path + next
        progressStore.savePath(path)
    }

    fun goBack() {
        if (path.size <= 1) return
        path = path.dropLast(1)
        progressStore.savePath(path)
    }

    // System back walks the step stack before it leaves the flow.
    BackHandler(enabled = path.size > 1) { goBack() }

    fun finish() {
        scope.launch {
            val profile = db.userProfileDao().get() ?: UserProfileEntity()
            db.userProfileDao().upsert(
                profile.copy(
                    onboardingCompleted = true,
                    baselineCompleted = true,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            apiKeyStore.onboardingCompleted = true
            progressStore.clear()
            onFinished()
        }
    }

    // BLE permissions gate on "Get started" (preserved from the previous onboarding screen).
    // A denial used to leave the button silently doing nothing; it now explains and offers a way out.
    var blePermissionDenied by remember { mutableStateOf(false) }
    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (blePermissions.all { results[it] == true }) {
            blePermissionDenied = false
            push(OnboardingStep.RING)
        } else {
            blePermissionDenied = true
        }
    }

    fun getStarted() {
        val granted = blePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) push(OnboardingStep.RING) else permissionLauncher.launch(blePermissions)
    }

    Column(
        // No statusBars padding here: the route wrapper (PulseLoopApp's paddedComposable) has
        // already inset this subtree by the outer Scaffold's system bars. Applying it again is
        // what left a tall dead band above "Step 1 of 5".
        Modifier
            .fillMaxSize()
            .background(PulseColors.background),
    ) {
        OnboardingTopBar(
            step = step,
            canGoBack = path.size > 1,
            showsSkip = step == OnboardingStep.PROFILE,
            onBack = ::goBack,
            onSkip = { push(OnboardingStep.GOALS) },
        )

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val enter = tween<Float>(com.pulseloop.ui.theme.Motion.EMPHASIS_MS, easing = com.pulseloop.ui.theme.Motion.ease)
                (fadeIn(enter) + slideInHorizontally(tween(com.pulseloop.ui.theme.Motion.EMPHASIS_MS, easing = com.pulseloop.ui.theme.Motion.ease)) { it / 4 })
                    .togetherWith(fadeOut(tween(com.pulseloop.ui.theme.Motion.STANDARD_MS)))
            },
            label = "onboardingStep",
            modifier = Modifier.weight(1f),
        ) { current ->
            when (current) {
                OnboardingStep.WELCOME -> WelcomeStep(
                    getStarted = ::getStarted,
                    exploreWithoutRing = { push(OnboardingStep.PROFILE) },
                    useOtherDevice = { useHealthConnect = true; push(OnboardingStep.RING) },
                    permissionDenied = blePermissionDenied,
                    openAppSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
                OnboardingStep.RING -> if (useHealthConnect) HealthConnectSourceStep(
                    next = { push(OnboardingStep.PROFILE) },
                ) else PairingScreen(
                    bleClient = bleClient,
                    onConnected = {
                        scope.launch {
                            delay(700) // let the success animation land (iOS advanceAfterConnection)
                            if (path.lastOrNull() == OnboardingStep.RING) push(OnboardingStep.PROFILE)
                        }
                    },
                    onSkip = { push(OnboardingStep.PROFILE) },
                )
                OnboardingStep.PROFILE -> ProfileStep(
                    db = db,
                    apiKeyStore = apiKeyStore,
                    coordinator = coordinator,
                    next = { push(OnboardingStep.GOALS) },
                )
                OnboardingStep.GOALS -> GoalsStep(
                    db = db,
                    apiKeyStore = apiKeyStore,
                    coordinator = coordinator,
                    next = { push(OnboardingStep.BASELINE) },
                )
                OnboardingStep.BASELINE -> BaselineStep(
                    source = when {
                        useHealthConnect -> BaselineSource.HEALTH_CONNECT
                        bleClient.hasLastKnownRing -> BaselineSource.RING
                        else -> BaselineSource.NONE
                    },
                    finish = ::finish,
                )
            }
        }
    }
}

// MARK: - Top bar

@Composable
private fun OnboardingTopBar(
    step: OnboardingStep,
    canGoBack: Boolean,
    showsSkip: Boolean,
    onBack: () -> Unit,
    onSkip: () -> Unit,
) {
    val total = OnboardingStep.entries.size
    Column(
        Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                if (canGoBack) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr("Back"),
                            tint = PulseColors.textSecondary,
                        )
                    }
                }
            }
            Text(
                trf("Step {0} of {1}", step.index + 1, total),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = com.pulseloop.ui.theme.UbuntuMono,
                color = PulseColors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            // widthIn, not a fixed 60dp: a longer translated "Skip" grows the slot instead of
            // wrapping inside it. 48dp tall: the design system's minimum target.
            Box(Modifier.widthIn(min = 64.dp).heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
                if (showsSkip) {
                    Text(
                        tr("Skip"),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PulseColors.textSecondary,
                        maxLines = 1,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onSkip)
                            .wrapContentHeight()
                            .padding(horizontal = 12.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OnboardingStep.entries.forEach { item ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        // Progress is a state, not a tap target: no mint.
                        .background(if (item.index <= step.index) PulseColors.textPrimary else PulseColors.elevated),
                )
            }
        }
    }
}

// MARK: - Welcome

private data class WelcomeFeature(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val tint: Color,
)

@Composable
private fun WelcomeStep(
    getStarted: () -> Unit,
    exploreWithoutRing: () -> Unit,
    useOtherDevice: () -> Unit = {},
    permissionDenied: Boolean = false,
    openAppSettings: () -> Unit = {},
) {
    val features = listOf(
        WelcomeFeature(Icons.Filled.Paid, tr("No subscription"), tr("Own your ring data"), PulseColors.success),
        WelcomeFeature(Icons.Filled.Lock, tr("Privacy first"), tr("Data stays on device"), PulseColors.info),
        WelcomeFeature(Icons.Filled.AutoAwesome, tr("AI health coach"), tr("Learns your baseline"), PulseColors.hrv),
        WelcomeFeature(Icons.Filled.MonitorHeart, tr("See your vitals"), tr("HR, SpO₂, HRV & stress"), PulseColors.heartRate),
        WelcomeFeature(Icons.Filled.Bedtime, tr("Sleep tracking"), tr("Stages and trends"), PulseColors.sleep),
        WelcomeFeature(Icons.Filled.DirectionsRun, tr("Activity recording"), tr("Live workout tracking"), PulseColors.steps),
    )

    StepScaffold(
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (permissionDenied) {
                    Text(
                        tr("PulseLoop needs Bluetooth permission to find and connect to your ring. " +
                            "Allow it in Settings, or continue without a ring and pair one later."),
                        fontSize = 14.sp,
                        color = PulseColors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    // "Try again" re-checks the grant first, so it also carries on after the user
                    // allowed Bluetooth in Settings; without it a denial had no way back to pairing.
                    PrimaryButton(tr("Try again"), icon = Icons.Filled.ArrowForward, onClick = getStarted)
                    SecondaryButton(tr("Open settings"), onClick = openAppSettings)
                    SecondaryButton(tr("Continue without ring"), icon = Icons.Filled.GridView, onClick = exploreWithoutRing)
                } else {
                    PrimaryButton(tr("Get started"), icon = Icons.Filled.ArrowForward, onClick = getStarted)
                    SecondaryButton(tr("I wear an Oura or another device"), icon = Icons.Filled.Watch, onClick = useOtherDevice)
                    SecondaryButton(tr("Explore without ring"), icon = Icons.Filled.GridView, onClick = exploreWithoutRing)
                }
            }
        },
    ) {
        Image(
            painter = painterResource(R.drawable.pulseloop_logo),
            contentDescription = null,
            modifier = Modifier
                .size(92.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        StepHeader(
            title = tr("Set up PulseLoop"),
            subtitle = tr("Your health, on your terms — no subscription, no cloud."),
        )

        // Two-column feature grid (rows of two, since we're inside a scroll column).
        features.chunked(2).forEach { pair ->
            // Min height, not a fixed 118dp: two-line translated titles and subtitles overflowed it.
            // IntrinsicSize.Min + fillMaxHeight keeps both tiles in a row the same height.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { feature ->
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .heightIn(min = 118.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PulseColors.card)
                            .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .dither(feature.tint, 0.14f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(feature.icon, contentDescription = null, Modifier.size(20.dp), tint = feature.tint)
                        }
                        Text(
                            feature.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PulseColors.textPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                        Text(
                            feature.subtitle,
                            fontSize = 12.sp,
                            color = PulseColors.textMuted,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// MARK: - Profile

@Composable
private fun ProfileStep(
    db: PulseLoopDatabase,
    apiKeyStore: ApiKeyStore,
    coordinator: RingSyncCoordinator?,
    next: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<ProfileDraft?>(null) }

    LaunchedEffect(Unit) {
        if (draft == null) {
            val profile = db.userProfileDao().get()
            // A manually stored units preference wins over the locale default (iOS: profile units).
            val storedUnits = apiKeyStore.unitSystem?.let {
                try { UnitSystem.valueOf(it) } catch (_: Exception) { null }
            }
            draft = ProfileDraft.from(profile, existingUnits = storedUnits)
        }
    }

    StepScaffold(
        footer = {
            PrimaryButton(tr("Continue"), icon = Icons.Filled.ArrowForward, enabled = draft != null) {
                val current = draft ?: return@PrimaryButton
                scope.launch {
                    val existing = db.userProfileDao().get()
                    val entity = current.applyTo(existing ?: UserProfileEntity())
                    db.userProfileDao().upsert(entity)
                    apiKeyStore.unitSystem = current.units.name
                    coordinator?.applyUserProfileToRing()
                    next()
                }
            }
        },
    ) {
        StepHeader(
            title = tr("Profile"),
            subtitle = tr("Used to tune calories, activity goals, and summaries."),
        )
        draft?.let { current ->
            ProfileEditor(draft = current, onDraftChange = { draft = it })
        }
    }
}

// MARK: - Goals

@Composable
private fun GoalsStep(
    db: PulseLoopDatabase,
    apiKeyStore: ApiKeyStore,
    coordinator: RingSyncCoordinator?,
    next: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val units = remember { apiKeyStore.resolvedUnitSystem }
    var draft by remember { mutableStateOf<GoalDraft?>(null) }

    LaunchedEffect(Unit) {
        if (draft == null) {
            draft = GoalDraft.from(db.userGoalDao().get(), units)
        }
    }

    StepScaffold(
        footer = {
            PrimaryButton(tr("Save goals"), icon = Icons.Filled.Check, enabled = draft != null) {
                val current = draft ?: return@PrimaryButton
                scope.launch {
                    val existing = db.userGoalDao().get()
                    val saved = current.applyTo(existing ?: UserGoalEntity(), units, includeWeeklyWorkouts = false)
                    db.userGoalDao().upsert(saved)
                    // The one path every manual goal save takes: stops the field following the
                    // coach, sends the step goal to the ring and republishes the widget.
                    com.pulseloop.service.GoalSync.afterManualSave(context, saved, coordinator)
                    next()
                }
            }
        },
    ) {
        StepHeader(
            title = tr("Daily goals"),
            subtitle = tr("Start with recommended targets. You can change these anytime."),
        )
        draft?.let { current ->
            GoalEditor(
                draft = current,
                onDraftChange = { draft = it },
                units = units,
                includeWeeklyWorkouts = false,
            )
        }
    }
}

// MARK: - Baseline

private data class BaselineMilestone(
    val icon: ImageVector?,
    val title: String,
    val subtitle: String,
    val tint: Color,
)

/** Where the user's data will come from, so the finale doesn't promise a ring they skipped. */
private enum class BaselineSource { RING, HEALTH_CONNECT, NONE }

@Composable
private fun BaselineStep(source: BaselineSource, finish: () -> Unit) {
    // The baseline needs 14 nights (My Health's "Learning you"), not the 3–7 days iOS #75 said.
    val milestones = listOf(
        BaselineMilestone(null, tr("Today"), tr("Activity and live vitals, right away"), PulseColors.info),
        BaselineMilestone(Icons.Filled.Bedtime, tr("First sync"), tr("Sleep stages and nightly trends"), PulseColors.sleep),
        BaselineMilestone(Icons.AutoMirrored.Filled.TrendingUp, tr("After 14 nights"), tr("Your personalized baseline unlocks"), PulseColors.success),
    )
    val subtitle = when (source) {
        BaselineSource.RING -> tr("Wear your ring today. Your baseline builds from here.")
        BaselineSource.HEALTH_CONNECT -> tr("Your device's data comes in through Health Connect. Your baseline builds from here.")
        BaselineSource.NONE -> tr("Pair a ring from Settings whenever you're ready. Your baseline builds once it's on.")
    }
    val howItWorks = when (source) {
        BaselineSource.HEALTH_CONNECT -> tr("Keep wearing your device and let its app sync. Trends become personal after 14 nights of sleep.")
        else -> tr("Wear your ring during the day and sync after sleep. Trends become personal after 14 nights of sleep.")
    }

    // Celebratory finale (iOS #75): the medallion scales in on appear. reduceMotion-equivalent
    // isn't gated here — the spring settles quickly and Compose respects the system animator scale.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val medallionScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.3f,
        animationSpec = androidx.compose.animation.core.tween(com.pulseloop.ui.theme.Motion.EMPHASIS_MS, easing = com.pulseloop.ui.theme.Motion.ease),
        label = "medallionScale",
    )

    StepScaffold(
        footer = {
            PrimaryButton(tr("Start using PulseLoop"), icon = Icons.Filled.ArrowForward, onClick = finish)
        },
    ) {
        SuccessMedallion(scale = medallionScale)
        StepHeader(
            title = tr("You're all set"),
            subtitle = subtitle,
        )

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(PulseColors.card)
                .ditherFade(PulseColors.success, 0.14f, 0f)
                .border(1.dp, PulseColors.borderSubtle, RoundedCornerShape(8.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("PulseLoop learns your baseline over time"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = PulseColors.textPrimary,
                )
                Text(
                    howItWorks,
                    fontSize = 14.sp,
                    color = PulseColors.textSecondary,
                    lineHeight = 20.sp,
                )
            }

            milestones.forEach { milestone ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .dither(milestone.tint, 0.14f),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (milestone.icon != null) {
                            Icon(milestone.icon, contentDescription = null, Modifier.size(16.dp), tint = milestone.tint)
                        } else {
                            Text("1", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = milestone.tint)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            milestone.title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PulseColors.textPrimary,
                        )
                        Text(
                            milestone.subtitle,
                            fontSize = 14.sp,
                            color = PulseColors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

/** Animated success medallion: two concentric success rings around a check-seal badge, scaled in
 *  on appear (iOS #75 finale "Tier 1" medallion). */
@Composable
private fun SuccessMedallion(scale: Float) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(140.dp).scale(scale)) {
        Box(
            Modifier.size(140.dp).clip(CircleShape)
                .border(2.dp, PulseColors.success.copy(alpha = 0.12f), CircleShape),
        )
        Box(
            Modifier.size(104.dp).clip(CircleShape)
                .border(2.dp, PulseColors.success.copy(alpha = 0.25f), CircleShape),
        )
        Box(
            Modifier.size(96.dp).clip(CircleShape).dither(PulseColors.success, 0.14f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Verified,
                contentDescription = tr("Setup complete"),
                tint = PulseColors.success,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}

// MARK: - Shared step chrome

/** Scrolling step content above a pinned action footer (iOS `OnboardingActionFooter`). */
/** The RING step for someone without a PulseLoop ring: read their Oura (or other) app from Health Connect. */
@Composable
private fun HealthConnectSourceStep(next: () -> Unit) {
    StepScaffold(
        footer = { SecondaryButton(tr("Skip for now"), modifier = Modifier.fillMaxWidth(), onClick = next) },
    ) {
        Text(
            tr("Use your device's data"),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = PulseColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        HealthConnectSourceContent(onEnabled = next)
    }
}

@Composable
private fun StepScaffold(
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
        androidx.compose.material3.HorizontalDivider(thickness = 1.dp, color = PulseColors.borderSubtle)
        Box(
            Modifier
                .fillMaxWidth()
                .background(PulseColors.secondaryBackground)
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            footer()
        }
    }
}

/** Large centered step title + optional subtitle (iOS `CompactOnboardingHeader`). */
@Composable
private fun StepHeader(title: String, subtitle: String? = null) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = PulseColors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                fontSize = 16.sp,
                color = PulseColors.textSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp,
            )
        }
    }
}
