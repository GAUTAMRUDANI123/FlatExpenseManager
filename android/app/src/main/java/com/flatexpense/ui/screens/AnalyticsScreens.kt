package com.flatexpense.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flatexpense.ui.common.CategoryBars
import com.flatexpense.ui.common.ChartCard
import com.flatexpense.ui.common.EmptyBox
import com.flatexpense.ui.common.ErrorBox
import com.flatexpense.ui.common.LoadingBox
import com.flatexpense.ui.common.MonthlyColumns
import com.flatexpense.ui.common.StatRow
import com.flatexpense.ui.common.StatusChip
import com.flatexpense.ui.common.TrendLines
import com.flatexpense.ui.common.formatMoney
import com.flatexpense.ui.common.formatMonth

// ---------------------------------------------------------------------------
// Analytics — category breakdown and month-to-month comparison
// ---------------------------------------------------------------------------

@Composable
fun AnalyticsScreen(viewModel: AppViewModel) {
    val reportState by viewModel.report.collectAsStateWithLifecycle()
    val trendState by viewModel.trend.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()

    LaunchedEffect(month) {
        viewModel.loadReport()
        viewModel.loadTrend()
    }

    val report = reportState.data
    val trend = trendState.data

    when {
        reportState.loading && report == null -> LoadingBox()
        reportState.error != null && report == null ->
            ErrorBox(reportState.error!!, onRetry = { viewModel.loadReport() })
        report == null -> EmptyBox("No data for this month.")
        else -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MonthSelector(
                month = month,
                onPrevious = { viewModel.shiftMonth(-1) },
                onNext = { viewModel.shiftMonth(1) }
            )

            val categories = report.byCategory
                .map { Triple(it.name, it.total, it.total.toDoubleOrNull() ?: 0.0) }
                .filter { it.third > 0 }
                .sortedByDescending { it.third }

            ChartCard(
                title = "Where the money went",
                subtitle = "Approved expenses in ${formatMonth(month)}, largest first"
            ) {
                CategoryBars(categories)
            }

            val paidBy = report.byPaidBy
                .map { Triple(it.name, it.total, it.total.toDoubleOrNull() ?: 0.0) }
                .filter { it.third > 0 }
                .sortedByDescending { it.third }

            ChartCard(
                title = "Who paid",
                subtitle = "What each person laid out, before contributions are settled"
            ) {
                CategoryBars(paidBy)
            }

            if (trend != null && trend.months.isNotEmpty()) {
                ChartCard(
                    title = "Month to month",
                    subtitle = "Approved spending over the last ${trend.months.size} months"
                ) {
                    MonthlyColumns(
                        trend.months.map { it.month to (it.spent.toDoubleOrNull() ?: 0.0) }
                    )
                }

                ChartCard(
                    title = "Spending against contributions",
                    subtitle = "Both in rupees, on one scale"
                ) {
                    TrendLines(
                        trend.months.map {
                            Triple(
                                it.month,
                                it.spent.toDoubleOrNull() ?: 0.0,
                                it.received.toDoubleOrNull() ?: 0.0
                            )
                        }
                    )
                }

                MonthComparison(trend.months.map { it.month to (it.spent.toDoubleOrNull() ?: 0.0) })
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * This month against last. Stated as a sentence rather than left as two bars to
 * compare, because "up 18%" is the thing being asked and reading it off a chart
 * is work the screen can do instead.
 */
@Composable
private fun MonthComparison(months: List<Pair<String, Double>>) {
    if (months.size < 2) return
    val current = months.last()
    val previous = months[months.size - 2]

    val delta = current.second - previous.second
    val pct = if (previous.second > 0) delta / previous.second * 100.0 else null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = "Compared with ${formatMonth(previous.first)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            StatRow(formatMonth(previous.first), formatMoney(previous.second.toString()))
            StatRow(formatMonth(current.first), formatMoney(current.second.toString()))
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    previous.second == 0.0 && current.second == 0.0 -> "No spending in either month."
                    pct == null -> "Nothing was spent in ${formatMonth(previous.first)}, so there is nothing to compare against."
                    delta > 0 -> "Up ${formatMoney(delta.toString())} (${Math.round(pct)}%) on ${formatMonth(previous.first)}."
                    delta < 0 -> "Down ${formatMoney((-delta).toString())} (${Math.round(-pct)}%) on ${formatMonth(previous.first)}."
                    else -> "Exactly the same as ${formatMonth(previous.first)}."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Settlement — where each member stands this month
// ---------------------------------------------------------------------------

@Composable
fun SettlementScreen(viewModel: AppViewModel) {
    val state by viewModel.settlement.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()

    LaunchedEffect(month) { viewModel.loadSettlement() }

    val data = state.data

    when {
        state.loading && data == null -> LoadingBox()
        state.error != null && data == null ->
            ErrorBox(state.error!!, onRetry = viewModel::loadSettlement)
        data == null -> EmptyBox("Nothing to settle yet.")
        else -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            MonthSelector(
                month = month,
                onPrevious = { viewModel.shiftMonth(-1) },
                onNext = { viewModel.shiftMonth(1) }
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "The flat in ${formatMonth(data.month)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (data.isClosed) StatusChip("Closed")
                    }
                    Spacer(Modifier.height(10.dp))
                    StatRow("Expected in", formatMoney(data.totals.expected))
                    StatRow("Actually received", formatMoney(data.totals.paid))
                    StatRow("Still outstanding", formatMoney(data.totals.outstanding))
                    StatRow("Spent on the flat", formatMoney(data.totals.spent))
                }
            }

            data.members.forEach { member ->
                val net = member.net.toDoubleOrNull() ?: 0.0
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = member.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            if (member.isAdmin) StatusChip("Admin")
                        }
                        Spacer(Modifier.height(8.dp))
                        StatRow("Should contribute", formatMoney(member.expected))
                        StatRow("Has paid in", formatMoney(member.paid))
                        if ((member.outstanding.toDoubleOrNull() ?: 0.0) > 0) {
                            StatRow("Still owes", formatMoney(member.outstanding))
                        }
                        StatRow("Paid for things", formatMoney(member.spent))
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        // Said in words: a signed number on its own gets read
                        // the wrong way round about half the time.
                        Text(
                            text = when {
                                net > 0.005 -> "The flat owes ${member.name} ${formatMoney(net.toString())}"
                                net < -0.005 -> "${member.name} owes the flat ${formatMoney((-net).toString())}"
                                else -> "Square"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Text(
                text = "Only approved expenses count. Anything still awaiting approval " +
                    "is left out, because an unapproved claim is not yet a debt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Activity log
// ---------------------------------------------------------------------------

/** Turns an audit action into something a flatmate would recognise. */
private fun describe(action: String): String = when (action) {
    "created" -> "added an expense"
    "approved" -> "approved"
    "rejected" -> "rejected"
    "cancelled" -> "cancelled"
    "edited" -> "edited"
    "edited_reopened" -> "edited, sending it back for approval"
    "carried_over" -> "carried into the next month"
    "month_closed" -> "closed a month"
    "month_reopened" -> "reopened a month"
    else -> action.replace('_', ' ')
}

@Composable
fun ActivityScreen(viewModel: AppViewModel) {
    val state by viewModel.activity.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadActivity() }

    val entries = state.data?.activity.orEmpty()

    when {
        state.loading && entries.isEmpty() -> LoadingBox()
        state.error != null && entries.isEmpty() ->
            ErrorBox(state.error!!, onRetry = viewModel::loadActivity)
        entries.isEmpty() -> EmptyBox("Nothing has happened yet.")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(entries.size) { index ->
                val entry = entries[index]
                Column(Modifier.padding(vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = entry.actor.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = describe(entry.action),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (entry.expense != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${entry.expense.description} · ${formatMoney(entry.expense.amount)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (!entry.detail.isNullOrBlank() && entry.expense == null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = entry.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
