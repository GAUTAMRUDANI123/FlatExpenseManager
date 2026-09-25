package com.flatexpense.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flatexpense.data.api.MemberDto
import com.flatexpense.ui.common.EmptyBox
import com.flatexpense.ui.common.ErrorBox
import com.flatexpense.ui.common.LoadingBox
import com.flatexpense.ui.common.StatRow
import com.flatexpense.ui.common.StatusChip
import com.flatexpense.ui.common.StatusColors
import com.flatexpense.ui.common.formatMoney

// ---------------------------------------------------------------------------
// Monthly contributions (section 4, Table 2)
// ---------------------------------------------------------------------------

@Composable
fun ContributionsScreen(viewModel: AppViewModel) {
    val state by viewModel.contributions.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()

    var recordingFor by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var amountText by remember { mutableStateOf("") }
    var settingExpected by remember { mutableStateOf(false) }
    var expectedText by remember { mutableStateOf("") }

    LaunchedEffect(month) { viewModel.loadContributions() }

    val data = state.data

    Column(Modifier.fillMaxSize()) {
        MonthSelector(
            month = month,
            onPrevious = { viewModel.shiftMonth(-1) },
            onNext = { viewModel.shiftMonth(1) }
        )

        when {
            state.loading && data == null -> LoadingBox()
            state.error != null && data == null ->
                ErrorBox(state.error!!, onRetry = viewModel::loadContributions)
            data != null -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            StatRow("Expected", formatMoney(data.totals.expected), emphasised = true)
                            StatRow(
                                "Received",
                                formatMoney(data.totals.received),
                                valueColor = StatusColors.approved
                            )
                            StatRow(
                                "Still pending",
                                formatMoney(data.totals.pending),
                                valueColor = StatusColors.pending
                            )
                        }
                    }
                }

                if (session.isAdmin) {
                    item {
                        OutlinedButton(
                            onClick = { settingExpected = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Set this month's amount for everyone")
                        }
                    }
                }

                items(data.contributions, key = { it.userId }) { contribution ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = contribution.name +
                                        if (contribution.isAdmin) "  (Admin)" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${formatMoney(contribution.paidAmount)} of " +
                                        formatMoney(contribution.expectedAmount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                StatusChip(contribution.status)
                                if (session.isAdmin) {
                                    TextButton(onClick = {
                                        recordingFor = contribution.userId to contribution.name
                                        amountText = contribution.expectedAmount
                                    }) {
                                        Text("Record")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    recordingFor?.let { (userId, name) ->
        AlertDialog(
            onDismissRequest = { recordingFor = null },
            title = { Text("Record payment from $name") },
            text = {
                Column {
                    Text(
                        text = "Enter what actually landed in the Admin's account.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text("Amount paid") },
                        prefix = { Text("₹ ") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = amountText.trim().toDoubleOrNull()
                        if (value != null && value >= 0) {
                            viewModel.recordContribution(
                                userId,
                                String.format(java.util.Locale.US, "%.2f", value)
                            )
                        }
                        recordingFor = null
                    },
                    enabled = amountText.trim().toDoubleOrNull()?.let { it >= 0 } == true
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { recordingFor = null }) { Text("Cancel") }
            }
        )
    }

    if (settingExpected) {
        AlertDialog(
            onDismissRequest = { settingExpected = false },
            title = { Text("Monthly contribution") },
            text = {
                Column {
                    Text(
                        text = "Sets the expected amount for every active member this month.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = expectedText,
                        onValueChange = { expectedText = it },
                        label = { Text("Amount per member") },
                        prefix = { Text("₹ ") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        parseAmount(expectedText)?.let { viewModel.setExpectedForAll(it) }
                        settingExpected = false
                        expectedText = ""
                    },
                    enabled = parseAmount(expectedText) != null
                ) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { settingExpected = false }) { Text("Cancel") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Monthly report (section 10)
// ---------------------------------------------------------------------------

@Composable
fun ReportsScreen(viewModel: AppViewModel) {
    val state by viewModel.report.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()

    LaunchedEffect(month) { viewModel.loadReport() }

    val data = state.data

    Column(Modifier.fillMaxSize()) {
        MonthSelector(
            month = month,
            onPrevious = { viewModel.shiftMonth(-1) },
            onNext = { viewModel.shiftMonth(1) }
        )

        when {
            state.loading && data == null -> LoadingBox()
            state.error != null && data == null ->
                ErrorBox(state.error!!, onRetry = viewModel::loadReport)
            data != null -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SectionCard("Contributions") {
                        StatRow("Expected", formatMoney(data.contributions.expected))
                        StatRow("Paid", formatMoney(data.contributions.received))
                        StatRow("Pending", formatMoney(data.contributions.pending))
                    }
                }

                item {
                    SectionCard("Expenses by status") {
                        StatRow(
                            "Approved (${data.expenseTotals.counts.approved})",
                            formatMoney(data.expenseTotals.byStatus.approved),
                            valueColor = StatusColors.approved,
                            emphasised = true
                        )
                        StatRow(
                            "Pending (${data.expenseTotals.counts.pending})",
                            formatMoney(data.expenseTotals.byStatus.pending),
                            valueColor = StatusColors.pending
                        )
                        StatRow(
                            "Rejected (${data.expenseTotals.counts.rejected})",
                            formatMoney(data.expenseTotals.byStatus.rejected),
                            valueColor = StatusColors.rejected
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Rejected expenses stay in history but are left out of " +
                                "approved totals.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    SectionCard("Recorded balance") {
                        StatRow(
                            "Available",
                            formatMoney(data.balance),
                            emphasised = true
                        )
                    }
                }

                item {
                    SectionCard("By category") {
                        val rows = data.byCategory.filter { (it.total.toDoubleOrNull() ?: 0.0) > 0 }
                        if (rows.isEmpty()) {
                            Text(
                                "Nothing approved this month.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        rows.forEach { StatRow(it.name, formatMoney(it.total)) }
                    }
                }

                item {
                    SectionCard("By who paid") {
                        data.byPaidBy.forEach { StatRow(it.name, formatMoney(it.total)) }
                    }
                }

                item {
                    SectionCard("By split to") {
                        data.bySplitTo.forEach { StatRow(it.name, formatMoney(it.total)) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Categories (Admin) — section 8
// ---------------------------------------------------------------------------

@Composable
fun CategoriesScreen(viewModel: AppViewModel) {
    val state by viewModel.categories.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.loadCategories(includeInactive = true) }

    val categories = state.data.orEmpty()

    Column(Modifier.fillMaxSize()) {
        if (session.isAdmin) {
            Button(
                onClick = { adding = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) { Text("Add category") }
        }

        when {
            state.loading && categories.isEmpty() -> LoadingBox()
            state.error != null && categories.isEmpty() ->
                ErrorBox(state.error!!, onRetry = { viewModel.loadCategories(true) })
            categories.isEmpty() -> EmptyBox("No categories yet.")
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(categories, key = { it.id }) { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(category.name, style = MaterialTheme.typography.bodyLarge)
                            if (!category.isActive) {
                                Text(
                                    text = "Hidden from new expenses",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (session.isAdmin) {
                            Switch(
                                checked = category.isActive,
                                onCheckedChange = {
                                    viewModel.setCategoryActive(category.id, it)
                                }
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New category") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addCategory(newName)
                        adding = false
                        newName = ""
                    },
                    enabled = newName.isNotBlank()
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } }
        )
    }
}

// ---------------------------------------------------------------------------
// Members (Admin) — section 12
// ---------------------------------------------------------------------------

@Composable
fun MembersScreen(viewModel: AppViewModel) {
    val state by viewModel.members.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var promoting by remember { mutableStateOf<MemberDto?>(null) }

    LaunchedEffect(Unit) { viewModel.loadMembers() }

    val members = state.data.orEmpty()

    Column(Modifier.fillMaxSize()) {
        if (session.isAdmin) {
            Column(Modifier.padding(16.dp)) {
                Button(
                    onClick = { adding = true },
                    enabled = members.count { it.status == "active" } < 5,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Add member") }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${members.count { it.status == "active" }} of 5 members",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when {
            state.loading && members.isEmpty() -> LoadingBox()
            state.error != null && members.isEmpty() ->
                ErrorBox(state.error!!, onRetry = viewModel::loadMembers)
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(members, key = { it.id }) { member ->
                    val inactive = member.status != "active"
                    var menuOpen by remember(member.id) { mutableStateOf(false) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = member.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = if (inactive) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = member.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (member.isAdmin) {
                            Spacer(Modifier.width(8.dp))
                            StatusChip("Admin")
                        }
                        if (inactive) {
                            Spacer(Modifier.width(8.dp))
                            StatusChip("Inactive")
                        }

                        // The Admin cannot act on their own row: the server
                        // refuses to deactivate the Admin or transfer the role
                        // to whoever already holds it.
                        if (session.isAdmin && !member.isAdmin) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "Actions for ${member.name}"
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false }
                                ) {
                                    if (!inactive) {
                                        DropdownMenuItem(
                                            text = { Text("Make Admin") },
                                            onClick = {
                                                menuOpen = false
                                                promoting = member
                                            }
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (inactive) "Reactivate" else "Deactivate")
                                        },
                                        onClick = {
                                            menuOpen = false
                                            viewModel.setMemberActive(member.id, inactive)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a flatmate") },
            text = {
                Column {
                    Text(
                        text = "You are creating their account. Give them the password and " +
                            "ask them to change it after signing in.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Temporary password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("At least 8 characters") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addMember(name, email, password) {
                            adding = false
                            name = ""; email = ""; password = ""
                        }
                    },
                    enabled = name.isNotBlank() && email.isNotBlank() && password.length >= 8
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } }
        )
    }

    promoting?.let { target ->
        AlertDialog(
            onDismissRequest = { promoting = null },
            title = { Text("Make ${target.name} the Admin?") },
            text = {
                Text(
                    text = "There is only ever one Admin. ${target.name} will take over " +
                        "approving expenses and recording contributions, and you will " +
                        "become an ordinary member — you will not be able to undo this " +
                        "yourself.",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.transferAdmin(target.id) { promoting = null }
                }) { Text("Transfer") }
            },
            dismissButton = {
                TextButton(onClick = { promoting = null }) { Text("Cancel") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Profile / group settings (section 12)
// ---------------------------------------------------------------------------

@Composable
fun ProfileScreen(viewModel: AppViewModel) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    var apiBase by remember(session.apiBase) { mutableStateOf(session.apiBase) }
    var changingPassword by remember { mutableStateOf(false) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    fun clearPasswordFields() {
        currentPassword = ""; newPassword = ""; confirmPassword = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = session.userName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (session.isAdmin) "Admin of ${session.groupName}"
                    else "Member of ${session.groupName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = "Server",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiBase,
                    onValueChange = { apiBase = it },
                    label = { Text("API address") },
                    singleLine = true,
                    supportingText = {
                        Text("10.0.2.2 from the emulator; the laptop's LAN IP from a phone")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.setApiBase(apiBase)
                        viewModel.notify("Server address saved")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = "Security",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Change the password you sign in with. If the Admin created " +
                        "your account, replace the temporary password they gave you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { changingPassword = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Change password") }
            }
        }

        Button(
            onClick = viewModel::signOut,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Sign out") }
    }

    if (changingPassword) {
        val tooShort = newPassword.isNotEmpty() && newPassword.length < 8
        val mismatch = confirmPassword.isNotEmpty() && confirmPassword != newPassword
        val canSubmit = currentPassword.isNotBlank() &&
            newPassword.length >= 8 &&
            confirmPassword == newPassword

        AlertDialog(
            onDismissRequest = {
                changingPassword = false
                clearPasswordFields()
            },
            title = { Text("Change password") },
            text = {
                Column {
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = { currentPassword = it },
                        label = { Text("Current password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("New password") },
                        singleLine = true,
                        isError = tooShort,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = { Text("At least 8 characters") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text("Confirm new password") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            if (mismatch) Text("The two passwords do not match")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.changePassword(currentPassword, newPassword) {
                            changingPassword = false
                            clearPasswordFields()
                        }
                    },
                    enabled = canSubmit
                ) { Text("Change") }
            },
            dismissButton = {
                TextButton(onClick = {
                    changingPassword = false
                    clearPasswordFields()
                }) { Text("Cancel") }
            }
        )
    }
}
