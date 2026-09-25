package com.flatexpense.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flatexpense.ui.common.ErrorBox
import com.flatexpense.ui.common.LoadingBox
import com.flatexpense.ui.common.formatDate
import java.util.Calendar
import java.util.TimeZone

/**
 * Section 5 / Table 3. Every field the spec marks required is required here,
 * and Split To opens pre-selected on the Admin — the rule the doc repeats most.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddExpenseScreen(viewModel: AppViewModel, onDone: () -> Unit) {
    val categoriesState by viewModel.categories.collectAsStateWithLifecycle()
    val membersState by viewModel.members.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()

    val categories = categoriesState.data.orEmpty()
    val members = membersState.data.orEmpty().filter { it.status == "active" }

    var categoryId by remember { mutableStateOf<Long?>(null) }
    var description by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var paidBy by remember { mutableStateOf<Long?>(null) }
    var splitTo by remember { mutableStateOf<Long?>(null) }
    var expenseDate by remember { mutableStateOf(todayIso()) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Defaults land once the lists arrive: Paid By is me (I am usually the one
    // adding what I just paid for), Split To is the Admin.
    LaunchedEffect(categories, members, session.userId) {
        if (categoryId == null) categoryId = categories.firstOrNull()?.id
        if (paidBy == null && session.userId > 0) paidBy = session.userId
        if (splitTo == null) splitTo = members.firstOrNull { it.isAdmin }?.id
    }

    val amountCents = parseAmount(amountText)
    val canSubmit = categoryId != null && description.isNotBlank() &&
        amountCents != null && paidBy != null && splitTo != null

    when {
        categoriesState.loading && categories.isEmpty() -> LoadingBox()
        categoriesState.error != null && categories.isEmpty() ->
            ErrorBox(categoriesState.error!!, onRetry = { viewModel.loadCategories() })
        else -> Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount") },
                placeholder = { Text("0.00") },
                prefix = { Text("₹ ") },
                singleLine = true,
                isError = amountText.isNotBlank() && amountCents == null,
                supportingText = {
                    if (amountText.isNotBlank() && amountCents == null) {
                        Text("Amount must be greater than zero")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                placeholder = { Text("Monthly groceries") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Category", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { category ->
                    FilterChip(
                        selected = category.id == categoryId,
                        onClick = { categoryId = category.id },
                        label = { Text(category.name) }
                    )
                }
            }

            Text("Paid by", style = MaterialTheme.typography.labelLarge)
            Text(
                text = "Who actually paid for this",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                members.forEach { member ->
                    FilterChip(
                        selected = member.id == paidBy,
                        onClick = { paidBy = member.id },
                        label = { Text(member.name) }
                    )
                }
            }

            Text("Split to", style = MaterialTheme.typography.labelLarge)
            Text(
                text = "The account this expense sits against. Not an equal split " +
                    "between members — the full amount is recorded here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                members.forEach { member ->
                    FilterChip(
                        selected = member.id == splitTo,
                        onClick = { splitTo = member.id },
                        label = { Text(member.name + if (member.isAdmin) " (Admin)" else "") }
                    )
                }
            }

            TextButton(onClick = { showDatePicker = true }) {
                Text("Date: " + formatDate(expenseDate))
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = "Submitted expenses start as Pending",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "The Admin reviews and approves or rejects it. Only approved " +
                            "expenses count towards monthly totals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            Button(
                onClick = {
                    viewModel.addExpense(
                        categoryId = categoryId!!,
                        description = description,
                        amount = amountCents!!,
                        paidBy = paidBy!!,
                        splitTo = splitTo!!,
                        expenseDate = expenseDate,
                        onDone = onDone
                    )
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Submit for approval")
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = isoToUtcMillis(expenseDate)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { expenseDate = utcMillisToIso(it) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** Returns a fixed-2dp string, or null when the text is not a usable amount. */
fun parseAmount(raw: String): String? {
    val cleaned = raw.trim().replace(",", "")
    if (cleaned.isEmpty()) return null
    val value = cleaned.toDoubleOrNull() ?: return null
    if (value <= 0.0 || value.isNaN() || value.isInfinite()) return null
    return String.format(java.util.Locale.US, "%.2f", value)
}

fun todayIso(): String {
    val cal = Calendar.getInstance()
    return "%04d-%02d-%02d".format(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH)
    )
}

/**
 * The Material date picker works in UTC midnight, so both conversions pin to
 * UTC. Using the device zone here would shift the date by a day either side.
 */
private fun isoToUtcMillis(iso: String): Long {
    val parts = iso.split("-").mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return System.currentTimeMillis()
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(parts[0], parts[1] - 1, parts[2])
    }
    return cal.timeInMillis
}

private fun utcMillisToIso(millis: Long): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
    return "%04d-%02d-%02d".format(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH)
    )
}
