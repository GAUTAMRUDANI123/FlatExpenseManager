package com.flatexpense.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flatexpense.ui.common.EmptyBox
import com.flatexpense.ui.common.ErrorBox
import com.flatexpense.ui.common.LoadingBox
import com.flatexpense.ui.common.StatRow
import com.flatexpense.ui.common.StatusChip
import com.flatexpense.ui.common.StatusColors
import com.flatexpense.ui.common.formatDate
import com.flatexpense.ui.common.formatMoney

// ---------------------------------------------------------------------------
// Expense list (section 12)
// ---------------------------------------------------------------------------

private val STATUS_FILTERS = listOf(
    null to "All",
    "pending" to "Pending",
    "approved" to "Approved",
    "rejected" to "Rejected"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpenseListScreen(viewModel: AppViewModel, onOpenExpense: (Long) -> Unit) {
    val state by viewModel.expenses.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<String?>(null) }

    val searching = search.isNotBlank()

    LaunchedEffect(filter, month) { viewModel.loadExpenses(filter) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = viewModel::setSearch,
            label = { Text("Search expenses") },
            placeholder = { Text("electricity, Gautam, groceries…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (searching) {
                    IconButton(onClick = { viewModel.setSearch("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // The month control is hidden while searching, because search
        // deliberately looks across every month — leaving it on screen would
        // suggest it still narrows the results.
        if (!searching) {
            MonthSelector(
                month = month,
                onPrevious = { viewModel.shiftMonth(-1) },
                onNext = { viewModel.shiftMonth(1) }
            )
        } else {
            Text(
                text = "Searching every month",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            STATUS_FILTERS.forEach { (value, label) ->
                FilterChip(
                    selected = filter == value,
                    onClick = { filter = value },
                    label = { Text(label) }
                )
            }
        }

        val expenses = state.data.orEmpty()
        when {
            state.loading && expenses.isEmpty() -> LoadingBox()
            state.error != null && expenses.isEmpty() ->
                ErrorBox(state.error!!, onRetry = { viewModel.loadExpenses(filter) })
            expenses.isEmpty() && searching -> EmptyBox("Nothing matches \"$search\".")
            expenses.isEmpty() -> EmptyBox("No expenses for this month.")
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(expenses, key = { it.id }) { expense ->
                    ExpenseRow(expense = expense, onClick = { onOpenExpense(expense.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pending approvals (Admin) — section 12
// ---------------------------------------------------------------------------

@Composable
fun PendingApprovalsScreen(viewModel: AppViewModel, onOpenExpense: (Long) -> Unit) {
    val state by viewModel.pending.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadPending() }

    val expenses = state.data.orEmpty()

    Column(Modifier.fillMaxSize()) {
        if (!session.isAdmin) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Only the Admin can approve or reject. You can see what is " +
                        "waiting, but the decision buttons are the Admin's.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        when {
            state.loading && expenses.isEmpty() -> LoadingBox()
            state.error != null && expenses.isEmpty() ->
                ErrorBox(state.error!!, onRetry = viewModel::loadPending)
            expenses.isEmpty() -> EmptyBox("Nothing is waiting for approval.")
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(expenses, key = { it.id }) { expense ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = expense.description,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatMoney(expense.amount),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${expense.categoryName} · Paid by ${expense.paidBy.name} · " +
                                    "Split to ${expense.splitTo.name} · ${formatDate(expense.expenseDate)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onOpenExpense(expense.id) }) {
                                    Text("Details")
                                }
                                if (session.isAdmin) {
                                    Button(onClick = { viewModel.approve(expense.id) }) {
                                        Text("Approve")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Expense details (section 12)
// ---------------------------------------------------------------------------

@Composable
fun ExpenseDetailScreen(
    viewModel: AppViewModel,
    expenseId: Long,
    onEdit: (Long) -> Unit = {}
) {
    val state by viewModel.detail.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    var rejecting by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var cancelReason by remember { mutableStateOf("") }

    LaunchedEffect(expenseId) { viewModel.loadDetail(expenseId) }

    when {
        state.loading && state.data == null -> LoadingBox()
        state.error != null && state.data == null ->
            ErrorBox(state.error!!, onRetry = { viewModel.loadDetail(expenseId) })
        state.data != null -> {
            val detail = state.data!!
            val expense = detail.expense

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = expense.description,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = expense.categoryName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusChip(expense.status)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = formatMoney(expense.amount),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        StatRow("Paid by", expense.paidBy.name)
                        StatRow("Split to", expense.splitTo.name)
                        StatRow("Date", formatDate(expense.expenseDate))
                        StatRow("Added by", expense.createdBy.name)
                        if (expense.approvedBy != null) {
                            StatRow(
                                label = if (expense.status == "rejected") "Rejected by" else "Approved by",
                                value = expense.approvedBy!!.name
                            )
                        }
                        if (!expense.rejectionReason.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Reason: ${expense.rejectionReason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusColors.rejected
                            )
                        }
                    }
                }

                if (detail.canApprove && session.isAdmin) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { viewModel.approve(expense.id) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Approve") }
                        OutlinedButton(
                            onClick = { rejecting = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("Reject") }
                    }
                }

                // The server allows the author or the Admin to edit anything
                // that is not already cancelled, and lets only the Admin cancel
                // something still Pending or Approved. Mirrored here so the
                // buttons are absent rather than failing when pressed.
                val mine = expense.createdBy.id == session.userId
                val canEdit = (mine || session.isAdmin) && expense.status != "cancelled"
                val canCancel = session.isAdmin &&
                    (expense.status == "pending" || expense.status == "approved")

                if (canEdit || canCancel) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (canEdit) {
                            OutlinedButton(
                                onClick = { onEdit(expense.id) },
                                modifier = Modifier.weight(1f)
                            ) { Text("Edit") }
                        }
                        if (canCancel) {
                            OutlinedButton(
                                onClick = { cancelling = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("Cancel expense") }
                        }
                    }
                }

                if (detail.audit.isNotEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text(
                                text = "History",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            detail.audit.forEach { entry ->
                                Row(Modifier.padding(vertical = 5.dp)) {
                                    Text(
                                        text = entry.action.replace('_', ' ')
                                            .replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "by ${entry.actor.name}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (rejecting) {
        AlertDialog(
            onDismissRequest = { rejecting = false },
            title = { Text("Reject this expense") },
            text = {
                Column {
                    Text(
                        text = "A rejected expense stays in history but is left out of " +
                            "approved totals. A reason is required.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Reason") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.reject(expenseId, reason)
                        rejecting = false
                        reason = ""
                    },
                    enabled = reason.isNotBlank()
                ) { Text("Reject") }
            },
            dismissButton = {
                TextButton(onClick = { rejecting = false }) { Text("Cancel") }
            }
        )
    }

    if (cancelling) {
        AlertDialog(
            onDismissRequest = { cancelling = false },
            title = { Text("Cancel this expense") },
            text = {
                Column {
                    Text(
                        text = "Cancelling withdraws the expense and removes it from " +
                            "approved totals. It stays in history and cannot be edited " +
                            "afterwards. A reason is required.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = { cancelReason = it },
                        label = { Text("Reason") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.cancelExpense(expenseId, cancelReason)
                        cancelling = false
                        cancelReason = ""
                    },
                    enabled = cancelReason.isNotBlank()
                ) { Text("Cancel expense") }
            },
            dismissButton = {
                TextButton(onClick = { cancelling = false }) { Text("Keep it") }
            }
        )
    }
}
