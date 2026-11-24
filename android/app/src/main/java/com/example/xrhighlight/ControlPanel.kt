package com.example.xrhighlight

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ControlPanel(
    state: AppState,
    onToggleRun: () -> Unit,
    onApplyLabels: () -> Unit,
    onVoiceInput: () -> Unit,
    onClearHighlights: () -> Unit,
    onTestPanel: () -> Unit = {},
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
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
                    TextButton(onClick = onTestPanel) { Text("テスト枠") }
                }

                LabeledSlider(
                    "信頼度しきい値", state.confidence, 0.05f..0.9f, "%.2f",
                ) { state.confidence = it }

                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = { state.serverUrl = it },
                    label = { Text("推論サーバー (ws://host:8765/ws)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onToggleRun) { Text(if (state.running) "停止" else "開始") }

                StatusBlock(state)

                HorizontalDivider()
                CalibrationBlock(state)
            }
        }
    }
}

@Composable
private fun StatusBlock(state: AppState) {
    val ok = Color(0xFF66DD88)
    val ng = Color(0xFFFF7766)
    Text(state.xrStatus, style = MaterialTheme.typography.bodySmall)
    Text(state.cameraStatus, style = MaterialTheme.typography.bodySmall)
    Text(
        state.connectionMessage,
        color = if (state.connected) ok else ng,
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "%.1f fps | round trip %.0f ms | inference %.0f ms | highlights %d".format(
            state.fps, state.latencyMs, state.inferMs, state.visibleHighlights,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.lastDetections.isNotEmpty()) {
        Text(
            state.lastDetections.joinToString { "${it.label} ${(it.score * 100).toInt()}%" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CalibrationBlock(state: AppState) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "▼ キャリブレーション" else "▶ キャリブレーション")
    }
    if (!expanded) return
    Text(
        "ハイライトが実物とずれる場合に調整します。机上の物体を1つ検出させ、枠が重なるように合わせてください。",
        style = MaterialTheme.typography.bodySmall,
    )
    val c = state.calibration
    LabeledSlider("Yaw (左右) °", c.yawDeg, -15f..15f, "%.1f") { state.calibration = c.copy(yawDeg = it) }
    LabeledSlider("Pitch (上下) °", c.pitchDeg, -15f..15f, "%.1f") { state.calibration = c.copy(pitchDeg = it) }
    LabeledSlider("Roll °", c.rollDeg, -10f..10f, "%.1f") { state.calibration = c.copy(rollDeg = it) }
    LabeledSlider("FOV スケール", c.fovScale, 0.7f..1.3f, "%.3f") { state.calibration = c.copy(fovScale = it) }
    LabeledSlider("カメラ位置 X (m)", c.offsetX, -0.1f..0.1f, "%.3f") { state.calibration = c.copy(offsetX = it) }
    LabeledSlider("カメラ位置 Y (m)", c.offsetY, -0.1f..0.1f, "%.3f") { state.calibration = c.copy(offsetY = it) }
    LabeledSlider("既定距離 (m, 面が無い時)", c.defaultDistance, 0.3f..5f, "%.2f") {
        state.calibration = c.copy(defaultDistance = it)
    }
    TextButton(onClick = { state.calibration = Calibration() }) { Text("リセット") }
}

@Composable
private fun LabeledSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$title: ${format.format(value)}", modifier = Modifier.width(220.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}
