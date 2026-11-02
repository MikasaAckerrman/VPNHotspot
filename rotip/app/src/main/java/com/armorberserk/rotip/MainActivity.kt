package com.armorberserk.rotip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * MVP UI: big ROTATE button + status line + last history entries.
 * ViewModel так же усложняет MVP: держим state как mutableStateOf прямо в Activity.
 */
class MainActivity : ComponentActivity() {

    private data class UiState(
        val ipBefore: String? = null,
        val ipAfter: String? = null,
        val lastMode: String = "",
        val busy: Boolean = false,
        val rootOk: Boolean? = null,
        val error: String? = null
    )

    private val state = mutableStateOf(UiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Root()
            }
        }
        lifecycleScope.launch {
            val ip = Engine.fetchIp(applicationContext)
            state.value = state.value.copy(ipBefore = ip, rootOk = Engine.hasRoot())
        }
    }

    @Composable
    private fun Root() {
        val ui = state.value
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("RotIP", style = MaterialTheme.typography.headlineMedium)
            Text(
                "root: " + when (ui.rootOk) {
                    true -> "OK"
                    false -> "ПОТЕРЯН (нужно запустить root-сценарий)"
                    null -> "проверяем..."
                },
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "IP текущий: ${ui.ipBefore ?: "..."}",
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = {
                    state.value = state.value.copy(busy = true, error = null)
                    lifecycleScope.launch {
                        val r = Engine.rotate(applicationContext)
                        state.value = state.value.copy(
                            busy = false,
                            ipBefore = r.ipAfter,
                            ipAfter = r.ipAfter,
                            lastMode = r.modeUsed?.name ?: "",
                            error = r.error
                        )
                    }
                },
                enabled = !ui.busy
            ) {
                Text(if (ui.busy) "Ротирую..." else "РОТИРОВАТЬ IP")
            }

            if (ui.error != null) Text("Ошибка: ${ui.error}", style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(8.dp))
            Text("История:", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(Engine.history) { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${r.ipAfter ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${if (r.sameIp) "same" else "new "} ${r.modeUsed ?: "-"} ${r.elapsedMs}ms",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
