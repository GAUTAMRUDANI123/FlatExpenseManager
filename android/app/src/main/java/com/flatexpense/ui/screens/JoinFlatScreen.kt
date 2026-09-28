package com.flatexpense.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flatexpense.data.api.FlatSummaryDto
import com.flatexpense.ui.common.AppCard

/**
 * Signing up by asking to join a flat that already exists.
 *
 * The flat is found by typing its name rather than picked from a list of every
 * flat on the server — the point is to confirm the one you were told about,
 * not to browse who else is here.
 */
@Composable
fun JoinFlatScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val auth by viewModel.auth.collectAsStateWithLifecycle()
    val results by viewModel.flatSearch.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var flatQuery by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<FlatSummaryDto?>(null) }
    var message by remember { mutableStateOf("") }

    val canSubmit = name.isNotBlank() && email.isNotBlank() &&
        password.length >= 8 && chosen != null && !auth.loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Join a flat", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Your flat's Admin approves the request before you can see anything.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("At least 8 characters") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(4.dp))

        if (chosen == null) {
            OutlinedTextField(
                value = flatQuery,
                onValueChange = {
                    flatQuery = it
                    viewModel.searchFlats(it)
                },
                label = { Text("Which flat?") },
                placeholder = { Text("Start typing its name") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                supportingText = {
                    Text(
                        if (flatQuery.trim().length in 1..2) "Type at least three letters"
                        else "Ask your Admin for the exact name"
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            if (results.isNotEmpty()) {
                AppCard {
                    results.forEachIndexed { index, flat ->
                        if (index > 0) HorizontalDivider()
                        Text(
                            text = flat.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { chosen = flat }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            } else if (flatQuery.trim().length >= 3 && !auth.loading) {
                Text(
                    text = "No flat by that name. Check the spelling with your Admin — " +
                        "it has to match what they called it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = chosen!!.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Asking to join this flat",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        chosen = null
                        flatQuery = ""
                        viewModel.searchFlats("")
                    }) { Text("Change") }
                }
            }

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Note for the Admin (optional)") },
                placeholder = { Text("Which room you're in") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (auth.error != null) {
            Text(
                text = auth.error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                viewModel.requestToJoin(name, email, password, chosen!!, message)
            },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (auth.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Ask to join")
            }
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back to sign in")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Shown between asking and being let in. The account exists and the password
 * works, so the only honest thing to show is that someone else has to act —
 * and a way to check whether they have.
 */
@Composable
fun AwaitingApprovalScreen(
    viewModel: AppViewModel,
    flatName: String,
    declined: Boolean,
    onStartOver: () -> Unit
) {
    val auth by viewModel.auth.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(
            imageVector = Icons.Filled.HourglassEmpty,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = if (declined) "Request declined" else "Waiting for the Admin",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (declined) {
                "The Admin of $flatName did not approve this request. Check with " +
                    "them directly — they may have expected a different email."
            } else {
                "Your account is created, but the Admin of $flatName has to let " +
                    "you in before you can see any expenses. Give them a nudge."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (!declined) {
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Already approved? Sign in to check.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
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
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            if (auth.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = auth.error!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.refreshPendingJoin(email, password) },
                enabled = email.isNotBlank() && password.isNotBlank() && !auth.loading,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (auth.loading) "Checking…" else "Check again") }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onStartOver, modifier = Modifier.fillMaxWidth()) {
            Text("Back to sign in")
        }
        Spacer(Modifier.height(48.dp))
    }
}
