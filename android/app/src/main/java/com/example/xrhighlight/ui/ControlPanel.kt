package com.example.xrhighlight.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.xrhighlight.AppState

private val OK = Color(0xFF66DD88)
private val NG = Color(0xFFFF7766)

@Composable
fun ControlPanel(
    state: AppState,
    onToggleRun: () -> Unit,
    onApplyLabels: () -> Unit,
    onVoiceInput: () -> Unit,
    onClearHighlights: () -> Unit,
    onResetCalibration: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("XR Object Highlighter", style = MaterialTheme.typography.headlineSmall)

                OutlinedTextField(
                    value = state.labelsText,
                    onValueChange = { state.labelsText = it },
                    label = { Text("ハイライトする物体（カンマ区切り）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApplyLabels) { Text("ラベル適用") }
                    OutlinedButton(onClick = onVoiceInput) { Text("🎤 音声で指定") }
                    TextButton(onClick = onClearHighlights) { Text("ハイライト消去") }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("信頼度しきい値: %.2f".format(state.confidence), modifier = Modifier.width(200.dp))
                    Slider(
                        value = state.confidence,
                        onValueChange = { state.confidence = it },
                        valueRange = 0.05f..0.9f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = { state.serverUrl = it },
                    label = { Text("推論サーバー (ws://host:8765/ws)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onToggleRun) { Text(if (state.running) "停止" else "開始") }

                Status(state)
                HorizontalDivider()
                Calibration(state, onResetCalibration)
            }
        }
    }
}

@Composable
private fun Status(state: AppState) {
    val small = MaterialTheme.typography.bodySmall
    Text(state.xrStatus, style = small)
    Text(state.cameraStatus, style = small)
    Text(state.connectionMessage, color = if (state.connected) OK else NG, style = small)
    Text(
        "%.1f fps | round trip %.0f ms | inference %.0f ms | objects %d".format(
            state.fps, state.latencyMs, state.inferMs, state.visibleObjects,
        ),
        style = small,
    )
    if (state.lastDetections.isNotEmpty()) {
        Text(state.lastDetections.joinToString { "${it.label} ${(it.score * 100).toInt()}%" }, style = small)
    }
}

@Composable
private fun Calibration(state: AppState, onReset: () -> Unit) {
    val c = state.calibration
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("自動位置合わせ", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = onReset, modifier = Modifier.padding(start = 12.dp)) { Text("やり直す") }
    }
    val text = when {
        c == null -> "データ収集中… 開始後、頭をゆっくり上下左右に動かして周囲を見回してください"
        !c.converged -> "推定中（%d ペア, 品質 %.2f, 誤差 %.1f px）… 上下にも頭を動かしてください"
            .format(c.pairs, c.quality, c.rmsPx)
        else -> "✓ 完了  FOV %.1f°×%.1f° | 角度補正 p%.1f° y%.1f° r%.1f° | 時刻 %+.0f ms | 誤差 %.1f px".format(
            c.hfovDeg, c.vfovDeg, c.eulerDeg[0], c.eulerDeg[1], c.eulerDeg[2], c.timeOffsetMs, c.rmsPx,
        )
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (c?.converged == true) OK else Color.Unspecified)
}
