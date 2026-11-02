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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MVP UI v0.3: прогресс-текст этапов (определяю IP / airplane ON / OFF / жду сеть),
 * мгновенный IP по открытию, история в LazyColumn. Тяжёлое — ALWAYS на Dispatchers.IO.
 */
class MainActivity : ComponentActivity() {

    private data class UiState(
        val ipBefore: String? = null,
        val ipAfter: String? = null,
        val lastMode: String = "",
        val busy: Boolean = false,
        val busyMsg: String = "",
        val rootOk: Boolean? = null,
        val error: String? = null
    )

    private val state = mutableStateOf(UiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Root() } }

        // Прогресс-каллбек Engine → busyMsg (только UI, из IO через Main-switch внутри колбекa).
        Engine.onProgress = { msg ->
            // Engine вызывает Колбек из IO-потока — безопасно переключаем.
            lifecycleScope.launch(Dispatchers.Main) {
                state.value = state.value.copy(busyMsg = msg)
            }
        }

        // Мгновенный показ root-кеша (Engine уже начал проверку в RotipApp.onCreate):
        state.value = state.value.copy(rootOk = if (Engine.rootUnknown()) null else Engine.hasRoot())

        lifecycleScope.launch(Dispatchers.IO) {
            Engine.initState(applicationContext)   // cooldown-память из persist-файла
            val ip = Engine.fetchIp(applicationContext)
            withContext(Dispatchers.Main) {
                state.value = state.value.copy(ipBefore = ip, rootOk = Engine.hasRoot())
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
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
                    false -> "нет (выдай root в ReSukiSU)"
                    null -> "проверяю..."
                },
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                text = "IP текущий: ${ui.ipBefore ?: (if (ui.busy) "пересчитываю..." else "нет данных")}",
                style = MaterialTheme.typography.titleMedium
            )

            if (ui.busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(ui.busyMsg.ifEmpty { "работаю..." }, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Spacer(Modifier.height(20.dp))
            }

            Button(
                onClick = {
                    state.value = state.value.copy(busy = true, busyMsg = "старт...", error = null)
                    lifecycleScope.launch(Dispatchers.IO) {
                        val r = Engine.rotate(applicationContext)
                        withContext(Dispatchers.Main) {
                            state.value = state.value.copy(
                                busy = false,
                                busyMsg = "",
                                ipBefore = r.ipAfter ?: r.ipBefore,
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

            if (ui.error != null) {
                Text("Ошибка: ${ui.error}", style = MaterialTheme.typography.bodySmall)
            }

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
                            "${if (r.sameIp) "same" else "new "} ${r.modeUsed ?: "-"} ${r.elapsedMs / 1000}s",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
