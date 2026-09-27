package com.flatexpense.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flatexpense.ui.common.formatDate
import java.util.Calendar
import java.util.TimeZone

/**
 * The filter sheet for the expense list.
 *
 * Edits a local copy and only applies on the button. Filtering live on every
 * chip tap would fire a request per tap and, worse, reshuffle the list under
 * the user while they were still deciding what to narrow it to.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpenseFilterSheet(
    viewModel: AppViewModel,
    onDismiss: () -> Unit
) {
    val applied by viewModel.filters.collectAsStateWithLifecycle()
    val membersState by viewModel.members.collectAsStateWithLifecycle()
    val categoriesState by viewModel.categories.collectAsStateWithLifecycle()

    val members = membersState.data.orEmpty().filter { it.status == "active" }
    val categories = categoriesState.data.orEmpty()
    val headings = categories.filter { it.isHeading }

    var draft by remember { mutableStateOf(applied) }
    var picking by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text("Filter expenses", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(18.dp))

            // --- who paid ----------------------------------------------------
            FilterHeading("Paid by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                members.forEach { member ->
                    FilterChip(
                        selected = draft.paidBy == member.id,
                        // Tapping the selected chip clears it. Without that the
                        // only way out of a filter is the Clear all button,
                        // which throws away the other four as well.
                        onClick = {
                            draft = draft.copy(
                                paidBy = if (draft.paidBy == member.id) null else member.id
                            )
                        },
                        label = { Text(member.name) }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // --- category ----------------------------------------------------
            FilterHeading("Category")
            Text(
                text = "Choosing a heading includes everything filed under it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            headings.forEach { heading ->
                val children = categories.filter { it.parentId == heading.id }
                Text(
                    text = heading.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The heading itself is selectable, listed as "All ..." so
                    // it is obvious it means the whole group rather than
                    // expenses filed against the heading alone.
                    FilterChip(
                        selected = draft.categoryId == heading.id,
                        onClick = {
                            draft = draft.copy(
                                categoryId = if (draft.categoryId == heading.id) null else heading.id
                            )
                        },
                        label = { Text("All ${heading.name.lowercase()}") }
                    )
                    children.forEach { child ->
                        FilterChip(
                            selected = draft.categoryId == child.id,
                            onClick = {
                                draft = draft.copy(
                                    categoryId = if (draft.categoryId == child.id) null else child.id
                                )
                            },
                            label = { Text(child.name) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // --- amount ------------------------------------------------------
            FilterHeading("Amount")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.minAmount.orEmpty(),
                    onValueChange = { draft = draft.copy(minAmount = it.ifBlank { null }) },
                    label = { Text("Least") },
                    prefix = { Text("₹") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = draft.maxAmount.orEmpty(),
                    onValueChange = { draft = draft.copy(maxAmount = it.ifBlank { null }) },
                    label = { Text("Most") },
                    prefix = { Text("₹") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(18.dp))

            // --- dates -------------------------------------------------------
            FilterHeading("Dates")
            Text(
                text = "A range replaces the month at the top of the list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { picking = "from" },
                    modifier = Modifier.weight(1f)
                ) { Text(draft.from?.let { formatDate(it) } ?: "From") }
                OutlinedButton(
                    onClick = { picking = "to" },
                    modifier = Modifier.weight(1f)
                ) { Text(draft.to?.let { formatDate(it) } ?: "To") }
            }
            if (draft.hasDateRange) {
                TextButton(onClick = { draft = draft.copy(from = null, to = null) }) {
                    Text("Clear dates")
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { draft = ExpenseFilters() },
                    modifier = Modifier.weight(1f)
                ) { Text("Clear all") }
                Button(
                    onClick = {
                        viewModel.setFilters(draft.normalised())
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (draft.active == 0) "Show all" else "Apply (${draft.active})")
                }
            }
        }
    }

    if (picking != null) {
        val existing = if (picking == "from") draft.from else draft.to
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = existing?.let { isoToUtcMillis(it) }
                ?: System.currentTimeMillis()
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val iso = utcMillisToIso(millis)
                        draft = if (picking == "from") draft.copy(from = iso)
                        else draft.copy(to = iso)
                    }
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun FilterHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

/**
 * Tidies a draft before it is applied: blank amounts dropped, and a backwards
 * range swapped rather than refused, since picking the dates in the wrong
 * order is a slip and returning nothing looks like a bug.
 */
private fun ExpenseFilters.normalised(): ExpenseFilters {
    val min = minAmount?.trim()?.toDoubleOrNull()
    val max = maxAmount?.trim()?.toDoubleOrNull()
    val swapAmounts = min != null && max != null && min > max
    val swapDates = from != null && to != null && from > to
    return copy(
        minAmount = (if (swapAmounts) max else min)?.let { String.format(java.util.Locale.US, "%.2f", it) },
        maxAmount = (if (swapAmounts) min else max)?.let { String.format(java.util.Locale.US, "%.2f", it) },
        from = if (swapDates) to else from,
        to = if (swapDates) from else to
    )
}

private fun isoToUtcMillis(iso: String): Long {
    val parts = iso.split("-").mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return System.currentTimeMillis()
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(parts[0], parts[1] - 1, parts[2])
    }.timeInMillis
}

private fun utcMillisToIso(millis: Long): String {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
    return "%04d-%02d-%02d".format(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH)
    )
}
