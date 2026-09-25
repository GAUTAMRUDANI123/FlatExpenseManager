package com.flatexpense.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flatexpense.data.api.ExpenseDto
import com.flatexpense.ui.common.EmptyBox
import com.flatexpense.ui.common.ErrorBox
import com.flatexpense.ui.common.LoadingBox
import com.flatexpense.ui.common.StatRow
import com.flatexpense.ui.common.StatusChip
import com.flatexpense.ui.common.StatusColors
import com.flatexpense.ui.common.statusTextColor
import com.flatexpense.ui.common.formatDate
import com.flatexpense.ui.common.AppCard
import com.flatexpense.ui.common.CategoryAvatar
import com.flatexpense.ui.common.SectionCard
import com.flatexpense.ui.common.formatMoney
import com.flatexpense.ui.theme.MoneyHero
import com.flatexpense.ui.common.formatMonth

@Composable
fun DashboardScreen(
    viewModel: AppViewModel,
    onOpenExpense: (Long) -> Unit
) {
    val state by viewModel.dashboard.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()

    when {
        state.loading && state.data == null -> LoadingBox()
        state.error != null && state.data == null ->
            ErrorBox(state.error!!, onRetry = viewModel::loadDashboard)
        state.data != null -> {
            val data = state.data!!
            LazyColumn(
                // Bottom room so the floating button never has the last row
                // trapped underneath it.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    MonthSelector(
                        month = month,
                        onPrevious = { viewModel.shiftMonth(-1) },
                        onNext = { viewModel.shiftMonth(1) }
                    )
                }

                // The balance is the one number the flat opens the app for, so
                // it gets the only filled surface on the screen. Everything
                // below is a white card, which leaves this reading as the
                // headline without needing a larger font to say so.
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.AccountBalanceWallet,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Recorded balance",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = formatMoney(data.balance),
                                style = MoneyHero,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Contributions received, less approved expenses",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                // Two figures people check constantly, pulled out of the
                // cards below so they can be read without scrolling.
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MiniStat(
                            label = "Approved",
                            value = formatMoney(data.expenses.approvedTotal),
                            count = data.expenses.approvedCount,
                            color = statusTextColor("approved"),
                            modifier = Modifier.weight(1f)
                        )
                        MiniStat(
                            label = "Awaiting",
                            value = formatMoney(data.expenses.pendingTotal),
                            count = data.expenses.pendingCount,
                            color = statusTextColor("pending"),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    SectionCard("Contributions") {
                        val expected = data.contributions.expected.toDoubleOrNull() ?: 0.0
                        val received = data.contributions.received.toDoubleOrNull() ?: 0.0
                        val fraction = if (expected > 0) (received / expected).toFloat() else 0f

                        StatRow("Expected", formatMoney(data.contributions.expected))
                        StatRow(
                            "Received",
                            formatMoney(data.contributions.received),
                            valueColor = statusTextColor("approved")
                        )
                        StatRow(
                            "Pending",
                            formatMoney(data.contributions.pending),
                            valueColor = statusTextColor("pending")
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }

                val spendingCategories = data.byCategory
                    .filter { (it.total.toDoubleOrNull() ?: 0.0) > 0 }
                if (spendingCategories.isNotEmpty()) {
                    item {
                        SectionCard("Where it went") {
                            val max = spendingCategories
                                .mapNotNull { it.total.toDoubleOrNull() }
                                .maxOrNull() ?: 0.0
                            // Categories with nothing spent are dropped rather
                            // than drawn as twelve empty tracks, which is what
                            // the list used to look like for most of a month.
                            spendingCategories.forEach { category ->
                                val value = category.total.toDoubleOrNull() ?: 0.0
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CategoryAvatar(
                                        slug = category.icon,
                                        name = category.name,
                                        size = 32.dp
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                category.name,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                formatMoney(category.total),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Spacer(Modifier.height(5.dp))
                                        LinearProgressIndicator(
                                            progress = {
                                                if (max > 0) (value / max).toFloat() else 0f
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp)),
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    SectionCard("Member payment status") {
                        data.memberStatus.forEach { member ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = member.name + if (member.isAdmin) "  (Admin)" else "",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "${formatMoney(member.paidAmount)} of ${formatMoney(member.expectedAmount)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                StatusChip(member.status)
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Recent expenses",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (data.recentExpenses.isEmpty()) {
                    item { EmptyBox("No expenses recorded yet.") }
                } else {
                    items(data.recentExpenses, key = { it.id }) { expense ->
                        ExpenseRow(expense = expense, onClick = { onOpenExpense(expense.id) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun MonthSelector(month: String, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous month")
        }
        Text(
            text = formatMonth(month),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next month")
        }
    }
}

/** A single figure with its own colour, for the pair under the balance. */
@Composable
fun MiniStat(
    label: String,
    value: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = if (count == 1) "1 expense" else "$count expenses",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ExpenseRow(expense: ExpenseDto, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The category glyph is the anchor: a list of expenses is scanned for
        // "the electricity one", and a shape finds that faster than reading
        // every line. The status dot rides on the corner so the row still
        // carries its state without a second column of chips.
        Box {
            CategoryAvatar(slug = expense.categoryIcon, name = expense.categoryName)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusColors.of(expense.status))
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = expense.description,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${expense.paidBy.name} · ${formatDate(expense.expenseDate)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatMoney(expense.amount),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            StatusChip(expense.status)
        }
    }
}
