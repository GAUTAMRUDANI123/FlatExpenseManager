package com.flatexpense.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun LoginScreen(viewModel: AppViewModel) {
    val state by viewModel.auth.collectAsStateWithLifecycle()

    var isRegistering by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var apiBase by remember { mutableStateOf("") }
    var showApiField by remember { mutableStateOf(false) }

    val canSubmit = email.isNotBlank() && password.length >= 8 &&
        (!isRegistering || (name.isNotBlank() && groupName.isNotBlank()))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Flat Common\nExpense Manager",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isRegistering) "Create your flat and become its Admin"
            else "Sign in to your flat",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        if (isRegistering) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Flat name") },
                placeholder = { Text("Flat 302, Sunrise Apartments") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            supportingText = if (isRegistering) {
                { Text("At least 8 characters") }
            } else null,
            modifier = Modifier.fillMaxWidth()
        )

        if (showApiField) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = apiBase,
                onValueChange = { apiBase = it },
                label = { Text("API address") },
                placeholder = { Text("http://192.168.1.5:4000/") },
                singleLine = true,
                supportingText = { Text("Use 10.0.2.2 on the emulator, the laptop's LAN IP on a phone") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                if (showApiField && apiBase.isNotBlank()) viewModel.setApiBase(apiBase)
                if (isRegistering) {
                    viewModel.register(name, email, password, groupName)
                } else {
                    viewModel.login(email, password)
                }
            },
            enabled = canSubmit && !state.loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(if (isRegistering) "Create flat" else "Sign in")
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = {
            isRegistering = !isRegistering
            viewModel.clearAuthError()
        }) {
            Text(
                if (isRegistering) "I already have an account"
                else "Create a new flat instead"
            )
        }

        TextButton(onClick = { showApiField = !showApiField }) {
            Text(
                text = if (showApiField) "Hide server settings" else "Server settings",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
