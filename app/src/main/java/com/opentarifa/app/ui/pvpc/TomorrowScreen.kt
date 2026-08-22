package com.opentarifa.app.ui.pvpc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.opentarifa.app.data.model.HourlyPrice
import com.opentarifa.app.ui.theme.OpenTarifaTheme
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TomorrowScreen(modifier: Modifier = Modifier, viewModel: TomorrowViewModel = viewModel()) {
    val uiState = viewModel.uiState

    // Recarga en cada entrada a la pestaña (no solo en la creación del ViewModel, que sobrevive
    // a la navegación): con LaunchedEffect(Unit) esto se dispara de nuevo cada vez que este
    // composable vuelve a entrar en composición, tanto la primera vez como en cualquier
    // reentrada. Solo si NO hay ya precios cargados (Success): los precios de un día, una vez
    // publicados, no cambian, así que reentrar en la pestaña con Success no debe disparar una
    // llamada de red ni el indicador de carga — para eso está el pull-to-refresh manual, que no
    // pasa por aquí (ver onRefresh en PullToRefreshBox) y sigue funcionando siempre.
    LaunchedEffect(Unit) {
        if (uiState !is TomorrowUiState.Success) {
            viewModel.loadPrices()
        }
    }

    PullToRefreshBox(
        isRefreshing = viewModel.isRefreshing,
        onRefresh = viewModel::loadPrices,
        modifier = modifier.fillMaxSize()
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (uiState) {
                is TomorrowUiState.Loading -> LoadingContent()
                is TomorrowUiState.Error -> ErrorContent(message = uiState.message)
                is TomorrowUiState.NotPublishedYet -> NotPublishedYetContent(
                    isRetrying = viewModel.isRefreshing,
                    onRetry = viewModel::loadPrices
                )
                is TomorrowUiState.Success -> TomorrowList(prices = uiState.prices)
            }
        }
    }
}

@Composable
private fun TomorrowList(prices: List<HourlyPrice>) {
    val priceValues = prices.map { it.priceEurPerKwh }
    val minPrice = priceValues.minOrNull() ?: 0.0
    val maxPrice = priceValues.maxOrNull() ?: 0.0
    val averagePrice = if (priceValues.isEmpty()) 0.0 else priceValues.sum() / priceValues.size
    val categories = priceValues.map { priceCategory(it, minPrice, maxPrice) }
    val extremes = findExtremes(priceValues, minPrice, maxPrice)
    val deltas = computeDeltas(priceValues)

    Column(modifier = Modifier.fillMaxSize()) {
        TomorrowHeader(averagePrice = averagePrice)
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(prices) { index, price ->
                HourPriceRow(
                    price = price,
                    category = categories[index],
                    extreme = extremes[index],
                    delta = deltas[index],
                    // No hay "hora actual" en el día siguiente: ninguna fila se resalta.
                    isCurrentHour = false
                )
            }
        }
    }
}

/**
 * Cabecera de Mañana: mismo patrón visual que la de Hoy (fondo tonal
 * redondeado + número grande), pero con el tono de marca (`primaryContainer`)
 * en vez del color de categoría de precio — así queda claramente diferenciada
 * de Hoy sin invadir el lenguaje semántico verde/naranja/rojo, que se
 * reserva solo para el nivel de precio de cada hora. Muestra la media del
 * día en vez de un "precio actual" (que no existe para un día futuro).
 */
@Composable
private fun TomorrowHeader(averagePrice: Double) {
    val containerColor = MaterialTheme.colorScheme.primaryContainer
    val onContainerColor = MaterialTheme.colorScheme.onPrimaryContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = containerColor,
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
            )
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatFullDate(LocalDate.now(MadridZone).plusDays(1)),
                style = TypeBodyM,
                color = onContainerColor.copy(alpha = 0.8f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(text = "Mañana", style = TypeLabelM, color = onContainerColor)
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formatPriceValue(averagePrice),
                style = TypeHeaderPrice,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "€/kWh · media",
                style = TypeLabelL,
                color = onContainerColor.copy(alpha = 0.9f),
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

@Composable
private fun NotPublishedYetContent(isRetrying: Boolean = false, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Sin esto, el swipe de PullToRefreshBox (que envuelve esta pantalla) no llega a
            // ningún NestedScrollConnection: un Column estático no tiene nada que consumir o
            // propagar. El tamaño sigue fijado por fillMaxSize antes de esto, así que el
            // centrado del contenido no cambia.
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = "Precios de mañana aún no publicados",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = "REE publica los precios del día siguiente sobre las 20:30h. Vuelve a intentarlo más tarde.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        OutlinedButton(
            onClick = onRetry,
            enabled = !isRetrying,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(text = "Reintentar", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun TomorrowListPreview() {
    OpenTarifaTheme {
        TomorrowList(
            prices = listOf(
                HourlyPrice("00-01h", 0, 0.18318),
                HourlyPrice("01-02h", 1, 0.18153),
                HourlyPrice("09-10h", 9, 0.04377)
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun NotPublishedYetPreview() {
    OpenTarifaTheme {
        NotPublishedYetContent(onRetry = {})
    }
}
