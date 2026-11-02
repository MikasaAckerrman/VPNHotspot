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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // РЕВЬЮ 05.11 (юзер: «долгая проверка рута»): Shell.getShell() — СИНХРОННАЯ
        // долгая операция (су-spawn, 0..15 с). НЕЛЬЗЯ вызывать в Main-диспередe!
        // lifecycleScope.launch{} по умолчанию Main → зависание UI + ANR-риск.
        // Фикс: все тяжёлые операции на Dispatchers.IO, апдейт state через Main.
        lifecycleScope.launch(Dispatchers.IO) {
            Engine.initState(applicationContext)   // cooldown-память из persist-файла
            val ip = Engine.fetchIp(applicationContext)
            withContext(Dispatchers.Main) {
                state.value = state.value.copy(ipBefore = ip, rootOk = Engine.hasRoot())
            }
        }
        // Отдельная фоновая root-проверка: RootApp уже стартовал checkRootBlocking
        // в RotipApp.onCreate на IO — здесь только читаем кэш (мгновенно).
        lifecycleScope.launch(Dispatchers.IO) {
            // Страховка: если RotipApp-проверка ещё не завершилась — досинхронизируем тут.
            if (Engine.rootUnknown()) Engine.checkRootBlocking()
            withContext(Dispatchers.Main) {
                state.value = state.value.copy(rootOk = Engine.hasRoot())
            }
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
                    // РЕВЬЮ 05.11: Heavy rotate (Shell + сеть, до ~70 c) — строго на IO-потоке.
                    lifecycleScope.launch(Dispatchers.IO) {
                        val r = Engine.rotate(applicationContext)
                        withContext(Dispatchers.Main) {
                            state.value = state.value.copy(
                                busy = false,
                                ipBefore = r.ipAfter,
                                ipAfter = r.ipAfter,
                                lastMode = r.modeUsed?.name ?: "",
                                error = r.error,
                                rootOk = Engine.hasRoot()
                            )
                        }
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
