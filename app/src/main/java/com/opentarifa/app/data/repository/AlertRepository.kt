package com.opentarifa.app.data.repository

import com.opentarifa.app.data.local.AlertChannel
import com.opentarifa.app.data.local.AlertDao
import com.opentarifa.app.data.local.AlertEntity
import com.opentarifa.app.data.local.AlertScope
import com.opentarifa.app.data.local.AlertType
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

class AlertRepository(private val alertDao: AlertDao) {

    /** Todas las alertas guardadas, de cualquier tipo/canal/estado (pantalla de gestión de Ajustes). */
    fun observeAll(): Flow<List<AlertEntity>> = alertDao.observeAll()

    /** Alertas Tipo A (hora fija, puntuales, "solo para hoy") activas para [date]. */
    fun observeActiveFixedHourAlerts(date: LocalDate): Flow<List<AlertEntity>> =
        alertDao.observeActive(AlertType.FIXED_HOUR.name, AlertScope.ONCE.name, date.toString())

    suspend fun createFixedHourAlert(date: LocalDate, hour: Int, channel: AlertChannel): AlertEntity {
        val alert = AlertEntity(
            type = AlertType.FIXED_HOUR.name,
            scope = AlertScope.ONCE.name,
            date = date.toString(),
            hour = hour,
            activeDays = null,
            channel = channel.name,
            isEnabled = true,
            createdAt = Instant.now().toString()
        )
        val id = alertDao.insert(alert)
        return alert.copy(id = id)
    }

    /**
     * Alerta Tipo B (recurrente: "más barata/cara del día"), sin cálculo ni
     * disparo real todavía — solo el registro y su aparición en el listado
     * de gestión. [type] debe ser [AlertType.CHEAPEST_TODAY] o [AlertType.PRICIEST_TODAY].
     */
    suspend fun createRecurringAlert(
        type: AlertType,
        activeDays: Set<DayOfWeek>,
        channel: AlertChannel,
        name: String?
    ): AlertEntity {
        val alert = AlertEntity(
            type = type.name,
            scope = AlertScope.RECURRING.name,
            date = null,
            hour = null,
            activeDays = activeDays.joinToString(",") { it.name },
            channel = channel.name,
            name = name?.takeIf { it.isNotBlank() },
            isEnabled = true,
            createdAt = Instant.now().toString()
        )
        val id = alertDao.insert(alert)
        return alert.copy(id = id)
    }

    /** Alertas Tipo B activas (cualquier tipo/canal/días); el filtrado por día de la semana lo hace el llamante. */
    suspend fun getActiveRecurringAlerts(): List<AlertEntity> = alertDao.getActiveByScope(AlertScope.RECURRING.name)

    /** Marca que ya se creó el evento de calendario de [date] para esta alerta (control de duplicados). */
    suspend fun markCalendarEventCreated(alert: AlertEntity, date: LocalDate) =
        alertDao.update(alert.copy(lastCalendarEventDate = date.toString()))

    suspend fun deleteAlert(alert: AlertEntity) = alertDao.delete(alert)

    /** Reinserta una alerta eliminada (deshacer desde el Snackbar de "Gestionar notificaciones"). */
    suspend fun restore(alert: AlertEntity): AlertEntity {
        val restored = alert.copy(id = 0)
        val id = alertDao.insert(restored)
        return restored.copy(id = id)
    }

    /** Desactiva una alerta Tipo B (recurrente) desde la pantalla de gestión, sin borrarla. */
    suspend fun disable(alert: AlertEntity) = alertDao.update(alert.copy(isEnabled = false))

    /**
     * Reactiva una alerta Tipo B previamente desactivada. Por sí sola no reprograma su alarma —
     * quien llama debe disparar [com.opentarifa.app.notifications.scheduleTodaysRecurringAlerts]
     * a continuación si hoy está entre sus días activos (mismo patrón que al crear una alerta).
     */
    suspend fun enable(alert: AlertEntity) = alertDao.update(alert.copy(isEnabled = true))
}
