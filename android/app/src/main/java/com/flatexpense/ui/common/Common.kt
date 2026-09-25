package com.flatexpense.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

private val rupees: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 2
}

/**
 * Formats the server's fixed-2dp decimal string. The string is parsed only at
 * this last step, purely for display grouping.
 */
fun formatMoney(amount: String?): String {
    val value = amount?.toBigDecimalOrNull() ?: return "₹0.00"
    return rupees.format(value)
}

/** "2026-09-14" -> "14 Sep 2026". Falls back to the raw text if unparseable. */
fun formatDate(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    val date = iso.take(10).split("-")
    if (date.size != 3) return iso
    val months = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )
    val monthIndex = date[1].toIntOrNull()?.minus(1) ?: return iso
    if (monthIndex !in months.indices) return iso
    return "${date[2].trimStart('0')} ${months[monthIndex]} ${date[0]}"
}

/** "2026-09" -> "September 2026" */
fun formatMonth(month: String?): String {
    if (month.isNullOrBlank()) return "—"
    val parts = month.take(7).split("-")
    if (parts.size != 2) return month
    val names = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )
    val index = parts[1].toIntOrNull()?.minus(1) ?: return month
    if (index !in names.indices) return month
    return "${names[index]} ${parts[0]}"
}

/**
 * Money states, held deliberately apart from the brand ramp so that an
 * approved green can never be confused with a themed accent. Each is paired
 * with a word wherever it appears — colour never carries the meaning alone.
 */
object StatusColors {
    val pending = Color(0xFFB45309)
    val approved = Color(0xFF047857)
    val rejected = Color(0xFFB91C1C)
    val cancelled = Color(0xFF64748B)
    val partial = Color(0xFF1D4ED8)

    fun of(status: String): Color = when (status.lowercase()) {
        "approved", "paid", "closed" -> approved
        "rejected" -> rejected
        "cancelled" -> cancelled
        "partial" -> partial
        else -> pending
    }

    /** Lighter steps of the same hues, for chip and icon backgrounds. */
    fun containerOf(status: String): Color = when (status.lowercase()) {
        "approved", "paid", "closed" -> Color(0xFFD1FAE5)
        "rejected" -> Color(0xFFFEE2E2)
        "cancelled" -> Color(0xFFE2E8F0)
        "partial" -> Color(0xFFDBEAFE)
        else -> Color(0xFFFEF3C7)
    }

    // Steps chosen for a dark surface. The light-mode greens and ambers are
    // deep enough to read as near-black once the background goes dark, which
    // made a received amount almost invisible on the very screen people check
    // it on.
    private val approvedDark = Color(0xFF34D399)
    private val pendingDark = Color(0xFFFBBF24)
    private val rejectedDark = Color(0xFFF87171)
    private val cancelledDark = Color(0xFF94A3B8)
    private val partialDark = Color(0xFF60A5FA)

    fun ofDark(status: String): Color = when (status.lowercase()) {
        "approved", "paid", "closed" -> approvedDark
        "rejected" -> rejectedDark
        "cancelled" -> cancelledDark
        "partial" -> partialDark
        else -> pendingDark
    }
}

/**
 * The status colour to draw *text* in, picked for the surface actually behind
 * it. Use this anywhere a figure is coloured by its state.
 */
@Composable
fun statusTextColor(status: String): Color =
    if (androidx.compose.foundation.isSystemInDarkTheme()) StatusColors.ofDark(status)
    else StatusColors.of(status)

@Composable
fun approvedTextColor(): Color = statusTextColor("approved")

@Composable
fun pendingTextColor(): Color = statusTextColor("pending")

@Composable
fun StatusChip(status: String, modifier: Modifier = Modifier) {
    val color = StatusColors.of(status)
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (dark) StatusColors.ofDark(status).copy(alpha = 0.18f)
                else StatusColors.containerOf(status)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = status.replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (dark) StatusColors.ofDark(status) else color
        )
    }
}

/**
 * Maps the `icon` slug the server stores on each category to a real glyph.
 * The column has been in the schema from the start and was never shown; an
 * expense list is far quicker to scan by shape than by reading every line.
 */
fun categoryIcon(slug: String?, name: String? = null): ImageVector {
    val key = (slug ?: name ?: "").lowercase()
    return when {
        key.contains("grocer") -> Icons.Filled.ShoppingCart
        key.contains("vegetable") -> Icons.Filled.Eco
        key.contains("milk") -> Icons.Filled.LocalDrink
        key.contains("electric") -> Icons.Filled.Bolt
        key.contains("internet") || key.contains("wifi") -> Icons.Filled.Wifi
        key.contains("rent") -> Icons.Filled.Home
        key.contains("gas") -> Icons.Filled.LocalFireDepartment
        key.contains("clean") -> Icons.Filled.CleaningServices
        key.contains("household") -> Icons.Filled.Chair
        key.contains("water") -> Icons.Filled.WaterDrop
        key.contains("maintenance") || key.contains("repair") -> Icons.Filled.Build
        else -> Icons.Filled.Receipt
    }
}

/** A category glyph in a tinted round tile — the anchor for every expense row. */
@Composable
fun CategoryAvatar(
    slug: String?,
    name: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = categoryIcon(slug, name),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

/**
 * The standard card for this app: white on the tinted background, one radius,
 * a hairline instead of a heavy shadow. Screens stack these rather than each
 * inventing its own container.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

/** A card with a heading above its content. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (trailing != null) trailing()
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorBox(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

/**
 * An empty state with a glyph. A bare line of grey text in the middle of a
 * blank screen reads as something having gone wrong; a drawn placeholder reads
 * as "there is nothing here yet", which is usually the truth.
 */
@Composable
fun EmptyBox(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Inbox
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Label/value line used across the dashboard and report cards. */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    emphasised: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (emphasised) MaterialTheme.typography.titleMedium
            else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Medium,
            color = valueColor
        )
    }
}
