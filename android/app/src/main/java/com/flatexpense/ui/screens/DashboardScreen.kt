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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.flatexpense.ui.common.formatDate
import com.flatexpense.ui.common.formatMoney
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    MonthSelector(
                        month = month,
                        onPrevious = { viewModel.shiftMonth(-1) },
                        onNext = { viewModel.shiftMonth(1) }
                    )
                }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(
                                text = "Recorded balance",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatMoney(data.balance),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Contributions received, less approved expenses",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                            valueColor = StatusColors.approved
                        )
                        StatRow(
                            "Pending",
                            formatMoney(data.contributions.pending),
                            valueColor = StatusColors.pending
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

                item {
                    SectionCard("Expenses this month") {
                        StatRow(
                            "Approved (${data.expenses.approvedCount})",
                            formatMoney(data.expenses.approvedTotal),
                            valueColor = StatusColors.approved,
                            emphasised = true
                        )
                        StatRow(
                            "Awaiting approval (${data.expenses.pendingCount})",
                            formatMoney(data.expenses.pendingTotal),
                            valueColor = StatusColors.pending
                        )
                    }
                }

                if (data.byCategory.isNotEmpty()) {
                    item {
                        SectionCard("Category-wise spending") {
                            val max = data.byCategory
                                .mapNotNull { it.total.toDoubleOrNull() }
                                .maxOrNull() ?: 0.0
                            data.byCategory.forEach { category ->
                                val value = category.total.toDoubleOrNull() ?: 0.0
                                Column(Modifier.padding(vertical = 5.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(category.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            formatMoney(category.total),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = {
                                            if (max > 0) (value / max).toFloat() else 0f
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )
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

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
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
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(StatusColors.of(expense.status))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = expense.description,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${expense.categoryName} · Paid by ${expense.paidBy.name} · ${formatDate(expense.expenseDate)}",
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
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            StatusChip(expense.status)
        }
    }
}
