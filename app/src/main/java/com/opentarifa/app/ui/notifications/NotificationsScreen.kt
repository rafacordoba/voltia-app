package com.opentarifa.app.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opentarifa.app.data.local.AlertChannel
import com.opentarifa.app.data.local.AlertEntity
import com.opentarifa.app.data.local.AlertScope
import com.opentarifa.app.data.local.AlertType
import com.opentarifa.app.data.local.NotificationPreferencesRepository
import com.opentarifa.app.data.local.alertTypeLabel
import com.opentarifa.app.data.local.OpenTarifaDatabase
import com.opentarifa.app.data.remote.NetworkModule
import com.opentarifa.app.data.repository.AlertRepository
import com.opentarifa.app.data.repository.PvpcRepository
import com.opentarifa.app.notifications.AlarmScheduler
import com.opentarifa.app.notifications.scheduleTodaysRecurringAlerts
import com.opentarifa.app.ui.pvpc.priceCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Gestión de alertas: lista todo lo guardado en la tabla "alerts" (Tipo A
 * puntuales creadas desde Hoy y Tipo B recurrentes creadas aquí), con
 * interruptor para desactivarlas, gesto de deslizar para eliminarlas, y un
 * botón flotante para crear alertas Tipo B nuevas. Las Tipo B creadas aquí
 * todavía no tienen cálculo diario ni disparo real — solo se guardan y se
 * listan.
 */
@Composable
fun NotificationsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alertRepository = remember { AlertRepository(OpenTarifaDatabase.getInstance(context).alertDao()) }
    val pvpcRepository = remember {
        PvpcRepository(NetworkModule.reeApiService, OpenTarifaDatabase.getInstance(context).priceHistoryDao())
    }
    val notificationPreferencesRepository = remember { NotificationPreferencesRepository(context) }
    val alertsFlow = remember(alertRepository) { alertRepository.observeAll() }
    // Antes se ocultaban las alertas con canal CALENDAR_EVENT puro (se asumía que el calendario
    // era la única fuente de verdad y no había nada que gestionar aquí), pero con Bug 2 arreglado
    // el evento de calendario ya se crea/gestiona de forma fiable desde esta misma lista — se
    // aplica el mismo criterio a CALENDAR_EVENT y BOTH: se muestran todas.
    val alerts by alertsFlow.collectAsState(initial = emptyList())
    val defaultChannel by notificationPreferencesRepository.defaultChannel.collectAsState(initial = AlertChannel.SYSTEM_NOTIFICATION)

    var showAddSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    suspend fun deleteWithUndo(alert: AlertEntity) {
        AlarmScheduler.cancel(context, alert.id)
        alertRepository.deleteAlert(alert)

        val undoJob = scope.launch {
            delay(5000)
            snackbarHostState.currentSnackbarData?.dismiss()
        }
        val result = snackbarHostState.showSnackbar(
            message = "Alerta eliminada",
            actionLabel = "Deshacer",
            withDismissAction = true,
            // Indefinite + dismiss manual tras 5s (ver undoJob): SnackbarDuration no tiene un
            // valor de 5s propio (solo Short ~4s / Long ~10s).
            duration = androidx.compose.material3.SnackbarDuration.Indefinite
        )
        undoJob.cancel()

        if (result == SnackbarResult.ActionPerformed) {
            val restored = alertRepository.restore(alert)
            rescheduleAfterUndo(context, alertRepository, pvpcRepository, restored)
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Nueva alerta")
            }
        }
    ) { innerPadding ->
        if (alerts.isEmpty()) {
            EmptyNotificationsState(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(alerts, key = { it.id }) { alert ->
                    AlertRow(
                        alert = alert,
                        onToggleEnabled = { checked ->
                            scope.launch {
                                if (checked) {
                                    // Solo Tipo B llega aquí: Tipo A (ONCE) se elimina al
                                    // desactivarse (rama de abajo) y ya no vuelve a aparecer en
                                    // esta lista para poder reactivarse.
                                    alertRepository.enable(alert)
                                    // Mismo patrón que al crear una alerta nueva (ver onSave más
                                    // abajo): si hoy está entre sus días activos, reprograma ya
                                    // con los precios de hoy en vez de esperar al próximo ciclo
                                    // del worker. scheduleTodaysRecurringAlerts respeta el guard
                                    // de hora ya pasada, así que no hace falta comprobarlo aquí.
                                    val today = LocalDate.now(ZoneId.of("Europe/Madrid"))
                                    val activeToday = alert.activeDays.isNullOrBlank() ||
                                        alert.activeDays.split(",").any { runCatching { DayOfWeek.valueOf(it) }.getOrNull() == today.dayOfWeek }
                                    if (activeToday) {
                                        val todayPrices = runCatching { pvpcRepository.getTodayPrices() }.getOrDefault(emptyList())
                                        scheduleTodaysRecurringAlerts(context, alertRepository, today, todayPrices)
                                    }
                                } else {
                                    AlarmScheduler.cancel(context, alert.id)
                                    // Tipo A (ONCE) puntual, ya no aporta nada al desactivarse: se
                                    // elimina en vez de dejarla "Inactiva" para siempre. Tipo B
                                    // (RECURRING) es una regla persistente, así que sigue solo
                                    // desactivándose.
                                    if (alert.scope == AlertScope.ONCE.name) {
                                        alertRepository.deleteAlert(alert)
                                    } else {
                                        alertRepository.disable(alert)
                                    }
                                }
                            }
                        },
                        onDelete = {
                            scope.launch { deleteWithUndo(alert) }
                        }
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddRecurringAlertSheet(
            defaultChannel = defaultChannel,
            onDismiss = { showAddSheet = false },
            onSave = { type, days, channel, name ->
                scope.launch {
                    alertRepository.createRecurringAlert(type, days, channel, name)
                    // Si hoy está entre los días activos, no esperar al próximo ciclo del worker
                    // (8:00/mañana): calcular y programar ya con los precios de hoy.
                    val today = LocalDate.now(ZoneId.of("Europe/Madrid"))
                    if (days.isEmpty() || today.dayOfWeek in days) {
                        val todayPrices = runCatching { pvpcRepository.getTodayPrices() }.getOrDefault(emptyList())
                        scheduleTodaysRecurringAlerts(context, alertRepository, today, todayPrices)
                    }
                }
                showAddSheet = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertRow(alert: AlertEntity, onToggleEnabled: (Boolean) -> Unit, onDelete: () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd || value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Eliminar alerta",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = alertTitle(alert), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = alertSubtitle(alert),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Tipo A se elimina al desactivarse (ver onToggleEnabled), así que una fila
                // "Inactiva" con el switch en off solo puede corresponder a una Tipo B — siempre
                // interactivo: reactivarla reprograma la alarma vía scheduleTodaysRecurringAlerts
                // (ver onToggleEnabled), que ya sabe recalcular la hora más barata/cara del día.
                Switch(
                    checked = alert.isEnabled,
                    onCheckedChange = onToggleEnabled
                )
            }
        }
    }
}

private fun alertChannelLabel(channel: String): String = when (runCatching { AlertChannel.valueOf(channel) }.getOrNull()) {
    AlertChannel.SYSTEM_NOTIFICATION -> "Notificación del sistema"
    AlertChannel.CALENDAR_EVENT -> "Evento de calendario"
    AlertChannel.BOTH -> "Ambos"
    null -> channel
}

private fun dayShortLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "L"
    DayOfWeek.TUESDAY -> "M"
    DayOfWeek.WEDNESDAY -> "X"
    DayOfWeek.THURSDAY -> "J"
    DayOfWeek.FRIDAY -> "V"
    DayOfWeek.SATURDAY -> "S"
    DayOfWeek.SUNDAY -> "D"
}

/** "L-M-X-J-V" a partir de "MONDAY,TUESDAY,..."; null/vacío = todos los días. */
private fun formatActiveDays(activeDays: String?): String {
    if (activeDays.isNullOrBlank()) return "Todos los días"
    return activeDays.split(",")
        .mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }
        .sortedBy { it.value }
        .joinToString("-") { dayShortLabel(it) }
}

private fun alertTitle(alert: AlertEntity): String =
    alert.name?.takeIf { it.isNotBlank() } ?: alertTypeLabel(alert.type)

private fun alertSubtitle(alert: AlertEntity): String {
    val typePart = if (!alert.name.isNullOrBlank()) alertTypeLabel(alert.type) else null
    val hourPart = alert.hour?.let { "%02d:00".format(it) }
    val daysPart = if (alert.scope == AlertScope.RECURRING.name) formatActiveDays(alert.activeDays) else null
    val statePart = if (!alert.isEnabled) "Inactiva" else null
    return listOfNotNull(typePart, hourPart, daysPart, alertChannelLabel(alert.channel), statePart)
        .joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddRecurringAlertSheet(
    defaultChannel: AlertChannel,
    onDismiss: () -> Unit,
    onSave: (type: AlertType, days: Set<DayOfWeek>, channel: AlertChannel, name: String?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedType by remember { mutableStateOf(AlertType.CHEAPEST_TODAY) }
    var selectedDays by remember { mutableStateOf(emptySet<DayOfWeek>()) }
    var selectedChannel by remember { mutableStateOf(defaultChannel) }
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(text = "Nueva alerta recurrente", style = MaterialTheme.typography.titleLarge)

            Text(
                text = "Tipo",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                FilterChip(
                    selected = selectedType == AlertType.CHEAPEST_TODAY,
                    onClick = { selectedType = AlertType.CHEAPEST_TODAY },
                    label = { Text("Más barata del día") }
                )
                FilterChip(
                    selected = selectedType == AlertType.PRICIEST_TODAY,
                    onClick = { selectedType = AlertType.PRICIEST_TODAY },
                    label = { Text("Más cara del día") }
                )
            }

            Text(
                text = "Días activos",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                DayOfWeek.entries.forEach { day ->
                    FilterChip(
                        selected = day in selectedDays,
                        onClick = {
                            selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day
                        },
                        label = { Text(dayShortLabel(day)) }
                    )
                }
            }

            Text(
                text = "Canal",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp)
            )
            ChannelRadioOption(
                label = "Notificación del sistema",
                selected = selectedChannel == AlertChannel.SYSTEM_NOTIFICATION,
                onClick = { selectedChannel = AlertChannel.SYSTEM_NOTIFICATION }
            )
            ChannelRadioOption(
                label = "Evento de calendario",
                selected = selectedChannel == AlertChannel.CALENDAR_EVENT,
                onClick = { selectedChannel = AlertChannel.CALENDAR_EVENT }
            )
            ChannelRadioOption(
                label = "Ambos",
                selected = selectedChannel == AlertChannel.BOTH,
                onClick = { selectedChannel = AlertChannel.BOTH }
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre (opcional)") },
                placeholder = { Text("p.ej. Lavadora fin de semana") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
            )

            Button(
                onClick = { onSave(selectedType, selectedDays, selectedChannel, name) },
                enabled = selectedDays.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Text("Guardar")
            }
            if (selectedDays.isEmpty()) {
                Text(
                    text = "Selecciona al menos un día",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ChannelRadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun EmptyNotificationsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.NotificationsNone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = "Sin alertas todavía",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = "Las alertas te avisan cuando el precio de la luz llega a una hora que te interesa, por notificación o evento de calendario. Actívalas desde la campana de cada hora en la pestaña Hoy, o crea una recurrente con el botón +.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * Reprograma una alerta restaurada tras pulsar "Deshacer" en el Snackbar de borrado: Tipo A
 * (ONCE) necesita el precio/categoría de su hora exacta (no se guardan en [AlertEntity], se
 * recalculan a partir de los precios de ese día); Tipo B (RECURRING) reutiliza el mismo cálculo
 * diario que el worker, solo si hoy está entre sus días activos.
 */
private suspend fun rescheduleAfterUndo(
    context: android.content.Context,
    alertRepository: AlertRepository,
    pvpcRepository: PvpcRepository,
    alert: AlertEntity
) {
    if (!AlarmScheduler.canScheduleExactAlarms(context)) return
    val channel = runCatching { AlertChannel.valueOf(alert.channel) }.getOrDefault(AlertChannel.SYSTEM_NOTIFICATION)

    if (alert.scope == AlertScope.ONCE.name) {
        val date = alert.date?.let(LocalDate::parse) ?: return
        val hour = alert.hour ?: return
        val prices = runCatching { pvpcRepository.getPricesForDate(date) }.getOrDefault(emptyList())
        val price = prices.firstOrNull { it.hourStart == hour } ?: return
        val priceValues = prices.map { it.priceEurPerKwh }
        val category = priceCategory(price.priceEurPerKwh, priceValues.min(), priceValues.max())
        AlarmScheduler.schedule(context, alert.id, date, hour, price.priceEurPerKwh, category, channel)
    } else {
        val today = LocalDate.now(ZoneId.of("Europe/Madrid"))
        val todayPrices = runCatching { pvpcRepository.getTodayPrices() }.getOrDefault(emptyList())
        scheduleTodaysRecurringAlerts(context, alertRepository, today, todayPrices)
    }
}
