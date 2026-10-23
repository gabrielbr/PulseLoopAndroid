package com.pulseloop.ui.screens

import com.pulseloop.ui.i18n.trf

import com.pulseloop.ui.i18n.tr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseloop.coach.tools.NutritionTools
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.dao.MealTotals
import com.pulseloop.data.entity.MealEntryEntity
import com.pulseloop.data.entity.UserGoalEntity
import com.pulseloop.nutrition.FoodProductCache
import com.pulseloop.nutrition.NutritionMath
import com.pulseloop.nutrition.OpenFoodFactsClient
import com.pulseloop.nutrition.asFoodProduct
import com.pulseloop.settings.ApiKeyStore
import com.pulseloop.ui.theme.PulseColors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionScreen(
    onBack: () -> Unit,
    /** Open straight into a way of logging: "photo", "barcode" or "add" (Today card, icon shortcut). */
    start: String? = null,
    /** Opens the coach with a message, to discuss the next-meal idea. Null hides that button. */
    onDiscuss: ((String) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }

    var dayOffset by remember { mutableIntStateOf(0) }
    val calendar = remember { Calendar.getInstance() }
    val todayStart = remember(dayOffset) {
        calendar.apply { timeInMillis = System.currentTimeMillis(); add(Calendar.DAY_OF_YEAR, -dayOffset) }.run {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
    }
    val dateLabel = remember(todayStart) {
        java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.US).format(java.util.Date(todayStart))
    }

    var meals by remember { mutableStateOf<List<MealEntryEntity>>(emptyList()) }
    var totals by remember { mutableStateOf<List<MealTotals>>(emptyList()) }
    var goal by remember { mutableStateOf<UserGoalEntity?>(null) }

    var suggestion by remember { mutableStateOf<com.pulseloop.service.MealSuggester.Suggestion?>(null) }

    // Until the day and the goal are read, the gauge would show "0 kcal of 2000" and then jump.
    // The previous day's values stay up while a new day loads (reload never clears them).
    var dayLoaded by remember { mutableStateOf(false) }
    var goalLoaded by remember { mutableStateOf(false) }
    fun reload() {
        scope.launch {
            meals = db.mealEntryDao().byDay(todayStart)
            totals = db.mealEntryDao().dayTotals(todayStart)
            suggestion = runCatching { com.pulseloop.service.MealSuggester.load(db) }.getOrNull()
            dayLoaded = true
        }
    }
    LaunchedEffect(Unit) { goal = db.userGoalDao().get(); goalLoaded = true }
    LaunchedEffect(dayOffset) { reload() }

    var showAddDialog by remember { mutableStateOf(false) }
    var mealToDelete by remember { mutableStateOf<MealEntryEntity?>(null) }
    var analysisRequest by remember { mutableStateOf<AnalysisRequest?>(null) }

    // iOS gates the Photo/Describe pills on coach + cloud provider + a photo-analysis
    // sub-toggle (NutritionView.swift:36-48). Android has no on-device provider mode and no
    // photo-analysis pref, so both pills gate on the coach master toggle alone (reported
    // divergence in ios-sync.md).
    val keyStore = remember(context) { ApiKeyStore(context) }
    // Read up front, not in an effect: starting false popped the Photo/Describe row in a frame later.
    var coachEnabled by remember { mutableStateOf(keyStore.coachEnabled) }
    var startScanner by remember { mutableStateOf(false) }
    LaunchedEffect(start) {
        when (start) {
            // Photo analysis needs the coach's AI; without it the manual form is the way in.
            "photo" -> if (keyStore.coachEnabled) analysisRequest = AnalysisRequest(startWithCamera = true) else showAddDialog = true
            "barcode" -> { startScanner = true; showAddDialog = true }
            "add" -> showAddDialog = true
        }
    }

    Scaffold(
        containerColor = PulseColors.background,
        // Insets are already applied by the route wrapper (see SettingsSubScreen).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(tr("Nutrition")) },
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
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(16.dp),
        ) {
            // Day navigation — left goes to older dates (higher offset), right goes to today (offset 0).
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { dayOffset++ }) { Icon(Icons.Filled.ChevronLeft, tr("Previous day")) }
                    Text(dateLabel, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    IconButton(onClick = { if (dayOffset > 0) dayOffset-- }, enabled = dayOffset > 0) { Icon(Icons.Filled.ChevronRight, tr("Next day")) }
                }
            }

            // Calorie gauge
            item {
                val totalCal = totals.sumOf { it.totalCal }
                val goalCal = goal?.intakeCalories ?: 2000.0
                val pct = if (goalCal > 0) (totalCal / goalCal).coerceAtMost(1.0) else 0.0
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = PulseColors.card)) {
                    // Invisible rather than absent while loading, so the card keeps its height.
                    Column(
                        Modifier.padding(20.dp).then(if (dayLoaded && goalLoaded) Modifier else Modifier.alpha(0f)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${totalCal.roundToInt()} kcal", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Text(trf("of {0}", goalCal.roundToInt()), color = PulseColors.textMuted)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { pct.toFloat() },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = PulseColors.calories,
                            trackColor = PulseColors.cardSoft,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MacroBar(tr("Protein"), totals.sumOf { it.totalP }, goal?.intakeProteinG ?: 150.0, PulseColors.heartRate, Modifier.weight(1f))
                            MacroBar(tr("Carbs"), totals.sumOf { it.totalC }, goal?.intakeCarbsG ?: 250.0, PulseColors.calories, Modifier.weight(1f))
                            MacroBar(tr("Fat"), totals.sumOf { it.totalF }, goal?.intakeFatG ?: 65.0, PulseColors.warning, Modifier.weight(1f))
                        }
                    }
                }
            }

            // Logging is why people open this screen, so its buttons sit under the day's total,
            // not below every meal of the day.
            // AI log actions (iOS logActions, NutritionView.swift:114-125): Photo opens the
            // analysis sheet in camera mode, Describe in describe mode.
            if (coachEnabled) {
                item {
                    // Half-width buttons: tighter padding and up to two lines for a long translation,
                    // equal heights so a wrapped label doesn't leave the pair misaligned.
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { analysisRequest = AnalysisRequest(startWithCamera = true) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Photo"), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                        OutlinedButton(
                            onClick = { analysisRequest = AnalysisRequest(startWithCamera = false) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Describe"), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            // Add button
            item {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Log Meal"))
                }
            }

            // Next-meal idea, once a third of the day's target is eaten (today only).
            val eatenToday = totals.sumOf { it.totalCal }
            val targetToday = goal?.intakeCalories ?: 2000.0
            val idea = suggestion
            if (dayOffset == 0 && idea != null && com.pulseloop.service.MealSuggester.shouldShow(targetToday, eatenToday)) {
                item {
                    NextMealCard(
                        idea = idea,
                        onLog = {
                            scope.launch {
                                val entry = MealEntryEntity(
                                    date = todayStart, timestamp = System.currentTimeMillis(),
                                    name = idea.items.joinToString(" + ") { it.name },
                                    mealTypeRaw = idea.slot,
                                    calories = idea.totalKcal.toDouble(),
                                    proteinG = idea.items.sumOf { it.proteinG }.toDouble(),
                                    carbsG = idea.items.sumOf { it.carbsG }.toDouble(),
                                    fatG = idea.items.sumOf { it.fatG }.toDouble(),
                                    sourceRaw = NutritionTools.sourceRawManual,
                                    notes = tr("From the next-meal suggestion"),
                                )
                                db.mealEntryDao().upsert(entry)
                                com.pulseloop.service.MealJournal.afterLog(db, entry)
                                reload()
                            }
                        },
                        onDiscuss = onDiscuss?.let { discuss ->
                            {
                                discuss(
                                    trf("For my next meal ({0}) you suggested: ", idea.slot) +
                                        idea.items.joinToString(", ") { "${it.name} (${it.portion})" } +
                                        trf(", about {0} kcal. Can we look at alternatives?", idea.totalKcal)
                                )
                            }
                        },
                    )
                }
            }

            // Meals grouped by type
            val mealTypes = listOf("breakfast", "lunch", "dinner", "snack")
            for (type in mealTypes) {
                val typeMeals = meals.filter { it.mealTypeRaw == type }
                if (typeMeals.isNotEmpty()) {
                    item {
                        Text(tr(type.replaceFirstChar { it.uppercase() }), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = PulseColors.textSecondary)
                    }
                    items(typeMeals, key = { it.id }) { meal ->
                        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = PulseColors.card)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(meal.name, fontWeight = FontWeight.Medium)
                                    Text("${meal.calories.roundToInt()} kcal · P${meal.proteinG.roundToInt()} C${meal.carbsG.roundToInt()} F${meal.fatG.roundToInt()}", fontSize = 12.sp, color = PulseColors.textMuted)
                                }
                                IconButton(onClick = { mealToDelete = meal }) {
                                    Icon(Icons.Filled.Delete, trf("Delete {0}", meal.name), tint = PulseColors.danger, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

        }
    }

    mealToDelete?.let { meal ->
        AlertDialog(
            onDismissRequest = { mealToDelete = null },
            title = { Text(tr("Delete meal?")) },
            text = { Text(trf("\"{0}\" will be removed from this day's log.", meal.name)) },
            confirmButton = {
                TextButton(onClick = {
                    mealToDelete = null
                    scope.launch {
                        db.mealEntryDao().deleteById(meal.id)
                        com.pulseloop.service.DrinksRepository.onMealDeleted(db, meal.id)
                        reload()
                    }
                }) { Text(tr("Delete"), color = PulseColors.danger) }
            },
            dismissButton = { TextButton(onClick = { mealToDelete = null }) { Text(tr("Cancel")) } },
        )
    }

    if (showAddDialog) {
        MealLogDialog(
            startWithScanner = startScanner,
            onDismiss = { showAddDialog = false; startScanner = false },
            onSave = { save ->
                scope.launch {
                    val entry = MealEntryEntity(
                        date = todayStart, timestamp = System.currentTimeMillis(),
                        name = save.name, mealTypeRaw = save.type,
                        calories = save.calories, proteinG = save.proteinG,
                        carbsG = save.carbsG, fatG = save.fatG,
                        sourceRaw = save.sourceRaw,
                        offProductCode = save.offProductCode,
                        servingDescription = save.servingDescription,
                        servingGrams = save.servingGrams,
                        quantity = save.quantity,
                        userEdited = save.userEdited,
                    )
                    db.mealEntryDao().upsert(entry)
                    com.pulseloop.service.MealJournal.afterLog(db, entry)
                    reload()
                }
                showAddDialog = false
                startScanner = false
            },
        )
    }

    analysisRequest?.let { request ->
        // iOS: .sheet(item: $analysisSheet) { MealAnalysisSheet(day:startWithCamera:) }
        // (NutritionView.swift:106-108).
        MealAnalysisDialog(
            dayMillis = todayStart,
            startWithCamera = request.startWithCamera,
            onDismiss = { analysisRequest = null },
            onSaved = {
                analysisRequest = null
                reload()
            },
        )
    }
}

/** One AI-analysis sheet request (iOS AnalysisRequest, NutritionView.swift:29-32). */
private data class AnalysisRequest(val startWithCamera: Boolean)

/**
 * Everything [MealLogDialog] hands back on save — the manual-form fields plus the provenance
 * fields a database product pick carries (iOS MealEntrySource raw values; persisted raw,
 * append never rename).
 */
data class MealLogSave(
    val name: String,
    val type: String,
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val sourceRaw: String = NutritionTools.sourceRawManual,
    val offProductCode: String? = null,
    val servingDescription: String? = null,
    val servingGrams: Double? = null,
    val quantity: Double = 1.0,
    /** Numbers changed after a database prefill — iOS flips userEdited (MealLogSheet.swift:611). */
    val userEdited: Boolean = false,
)

@Composable
private fun MacroBar(label: String, value: Double, goal: Double, color: Color, modifier: Modifier = Modifier) {
    val pct = if (goal > 0) (value / goal).coerceAtMost(1.0) else 0.0
    // Equal thirds, so a long translated macro name ellipsizes instead of crowding its neighbours.
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = PulseColors.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${value.roundToInt()}g", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(
            progress = { pct.toFloat() },
            modifier = Modifier.width(60.dp).height(4.dp),
            color = color, trackColor = PulseColors.cardSoft,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealLogDialog(
    onDismiss: () -> Unit,
    onSave: (MealLogSave) -> Unit,
    /** Open with the barcode scanner already up (the Today card's Barcode button). */
    startWithScanner: Boolean = false,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("snack") }
    var cal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && (cal.toDoubleOrNull() ?: 0.0) > 0

    // ── Barcode scan → Open Food Facts lookup (iOS MealLogSheet.swift:149-261) ──
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PulseLoopDatabase.getInstance(context) }
    val offClient = remember { OpenFoodFactsClient() }
    var showScanner by remember { mutableStateOf(startWithScanner) }
    var lookingUp by remember { mutableStateOf(false) }
    var scanNotice by remember { mutableStateOf<String?>(null) }
    var pendingProduct by remember { mutableStateOf<PendingBarcodeProduct?>(null) }

    /** iOS ManualMealForm.fieldText (MealLogSheet.swift:582-584): whole or one decimal. */
    fun fieldText(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
        else String.format(java.util.Locale.US, "%.1f", value)

    fun lookupBarcode(code: String) {
        scope.launch {
            lookingUp = true
            scanNotice = null
            try {
                // Cache-first (iOS lookupBarcode, MealLogSheet.swift:244-249): a rescanned
                // product never hits the API again.
                val cached = FoodProductCache.byCode(db.foodProductDao(), code)
                val product = cached?.asFoodProduct()
                    ?: offClient.product(code)?.also {
                        FoodProductCache.upsertCached(db.foodProductDao(), it)
                    }
                if (product == null) {
                    // iOS MealLogSheet.swift:128-130.
                    scanNotice = tr("Product not found for that barcode. Enter it manually instead.")
                } else {
                    // Clear on success too: two scans in quick succession run two lookups, and
                    // an earlier-started "not found" completing after a later success would
                    // otherwise leave a stale notice over a prefilled form.
                    scanNotice = null
                    // iOS ServingPickerView defaults (MealLogSheet.swift:420, 461-462): one
                    // serving when OFF resolves a serving quantity, otherwise a 100 g basis.
                    val grams = product.servingQuantityG ?: 100.0
                    fun scaled(per100g: Double) = NutritionMath.scaled(per100g = per100g, grams = grams)
                    pendingProduct = PendingBarcodeProduct(
                        code = product.code,
                        gramsBasis = grams,
                        kcal = scaled(product.energyKcal100g),
                        protein = scaled(product.protein100g),
                        carbs = scaled(product.carbs100g),
                        fat = scaled(product.fat100g),
                        servingDescription = product.servingSizeText ?: "${grams.roundToInt()} g",
                    )
                    name = if (product.brand != null) "${product.name} (${product.brand})" else product.name
                    cal = fieldText(scaled(product.energyKcal100g))
                    protein = fieldText(scaled(product.protein100g))
                    carbs = fieldText(scaled(product.carbs100g))
                    fat = fieldText(scaled(product.fat100g))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // iOS MealLogSheet.swift:258-260 catch-all.
                scanNotice = tr("Couldn't look up that barcode. Check your connection or enter the meal manually.")
            } finally {
                lookingUp = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Log Meal")) },
        text = {
            // Scrolls so the fields stay reachable on short screens and with the keyboard up.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (lookingUp) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = { showScanner = true }, enabled = !lookingUp) {
                        Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Scan barcode"))
                    }
                }
                scanNotice?.let {
                    // iOS inlineNotice copy (MealLogSheet.swift:128-130 / :259).
                    Text(it, fontSize = 12.sp, color = PulseColors.warning)
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(tr("Name")) }, modifier = Modifier.fillMaxWidth(), maxLines = 3, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("breakfast", "lunch", "dinner", "snack").forEach { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.replaceFirstChar { it.uppercase() }) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = cal, onValueChange = { cal = decimalInput(it) ?: cal }, label = { Text("kcal", maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    OutlinedTextField(value = protein, onValueChange = { protein = decimalInput(it) ?: protein }, label = { Text(tr("Protein (g)"), maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = carbs, onValueChange = { carbs = decimalInput(it) ?: carbs }, label = { Text(tr("Carbs (g)"), maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    OutlinedTextField(value = fat, onValueChange = { fat = decimalInput(it) ?: fat }, label = { Text(tr("Fat (g)"), maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val kcal = cal.toDoubleOrNull() ?: 0.0
                val pp = pendingProduct
                onSave(if (pp == null) {
                    MealLogSave(
                        name = name.trim(), type = type, calories = kcal,
                        proteinG = protein.toDoubleOrNull() ?: 0.0,
                        carbsG = carbs.toDoubleOrNull() ?: 0.0,
                        fatG = fat.toDoubleOrNull() ?: 0.0,
                    )
                } else {
                    // Editing a database entry's numbers flips userEdited
                    // (iOS MealLogSheet.swift:596-611).
                    val edited = kcal != pp.kcal ||
                        (protein.toDoubleOrNull() ?: 0.0) != pp.protein ||
                        (carbs.toDoubleOrNull() ?: 0.0) != pp.carbs ||
                        (fat.toDoubleOrNull() ?: 0.0) != pp.fat
                    MealLogSave(
                        name = name.trim(), type = type, calories = kcal,
                        proteinG = protein.toDoubleOrNull() ?: 0.0,
                        carbsG = carbs.toDoubleOrNull() ?: 0.0,
                        fatG = fat.toDoubleOrNull() ?: 0.0,
                        sourceRaw = NutritionTools.sourceRawOffBarcode,
                        offProductCode = pp.code,
                        servingDescription = pp.servingDescription,
                        servingGrams = pp.gramsBasis,
                        quantity = 1.0,
                        userEdited = edited,
                    )
                })
            }, enabled = valid) { Text(tr("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )

    if (showScanner) {
        // iOS .sheet(isPresented: $showScanner) { BarcodeScannerSheet { code in ... } }
        // (MealLogSheet.swift:149-154): the scan closes the scanner and drives the lookup.
        BarcodeScannerDialog(
            onScan = { code ->
                showScanner = false
                lookupBarcode(code)
            },
            onDismiss = { showScanner = false },
        )
    }
}

/** Keeps a numeric field to digits and one decimal point (comma accepted), as MealAnalysisSheet's NumberField does; null rejects the edit. */
private fun decimalInput(raw: String): String? {
    val cleaned = raw.replace(',', '.').filter { it.isDigit() || it == '.' }
    return if (cleaned.count { it == '.' } <= 1) cleaned else null
}

/**
 * A scanned-and-resolved OFF product held until Save — carries the prefill snapshot so
 * post-prefill edits can flip userEdited, and the provenance fields the row records.
 */
private data class PendingBarcodeProduct(
    val code: String,
    /** Per-serving grams when OFF resolves a serving, else the 100 g basis (see lookup). */
    val gramsBasis: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val servingDescription: String?,
)

/** The next-meal idea: a plate that fits what's left of today, to log as is or discuss with the coach. */
@Composable
private fun NextMealCard(
    idea: com.pulseloop.service.MealSuggester.Suggestion,
    onLog: () -> Unit,
    onDiscuss: (() -> Unit)?,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = PulseColors.card),
        border = androidx.compose.foundation.BorderStroke(1.dp, PulseColors.accent),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(trf("NEXT MEAL IDEA · {0}", idea.slot.uppercase()), fontSize = 11.sp, letterSpacing = 1.4.sp, color = PulseColors.accent)
            if (idea.items.isEmpty()) {
                Text(idea.note ?: "", fontSize = 14.sp, color = PulseColors.textPrimary)
            } else {
                idea.items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PulseColors.textPrimary)
                            Text("${item.english} · ${item.portion}", fontSize = 12.sp, color = PulseColors.textMuted)
                        }
                        Text("${item.kcal} kcal", fontSize = 13.sp, color = PulseColors.textSecondary)
                    }
                }
                Text(
                    trf("≈ {0} kcal · {1} g protein · leaves {2} kcal", idea.totalKcal, idea.totalProteinG, idea.leavesKcal),
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = PulseColors.textPrimary,
                )
                if (idea.fromPreferences.isNotEmpty()) {
                    Text(tr("Your preference: ") + idea.fromPreferences.joinToString("; "), fontSize = 12.sp, color = PulseColors.accent)
                }
                idea.note?.let { Text(it, fontSize = 12.sp, color = PulseColors.textSecondary) }
                // Same treatment as the Photo/Describe pair: two lines allowed, equal heights.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLog, modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(tr("Log this"), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                    if (onDiscuss != null) {
                        OutlinedButton(onClick = onDiscuss, modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(tr("Other ideas"), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
