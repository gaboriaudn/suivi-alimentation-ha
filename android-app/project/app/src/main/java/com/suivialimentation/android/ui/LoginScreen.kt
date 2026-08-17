package com.suivialimentation.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    authenticating: Boolean,
    error: String?,
    oauthConfigured: Boolean,
    onLogin: (String) -> Unit,
    onCancel: () -> Unit = {},
) {
    var instanceUrl by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Suivi Alimentation", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Connexion sécurisée à votre Home Assistant")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = instanceUrl,
            onValueChange = { instanceUrl = it },
            label = { Text("Adresse Home Assistant") },
            placeholder = { Text("https://home.example.fr") },
            enabled = !authenticating,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { onLogin(instanceUrl) },
            enabled = !authenticating && instanceUrl.isNotBlank() && oauthConfigured,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (authenticating) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
            } else {
                Text("Se connecter avec Home Assistant")
            }
        }
        if (authenticating) {
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Annuler") }
        }
        if (!oauthConfigured) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Le client OAuth doit d'abord être configuré dans gradle.properties (HA_OAUTH_CLIENT_ID).",
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
