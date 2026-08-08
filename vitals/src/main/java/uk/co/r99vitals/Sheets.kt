package uk.co.r99vitals

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which sheet is open, if any. */
enum class Sheet { None, Export, Calibrate }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsSheet(
    sheet: Sheet,
    state: VitalsState,
    report: String,
    onShare: () -> Unit,
    onHealth: () -> Unit,
    healthLabel: String,
    onCalibrate: (Int, Int) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    if (sheet == Sheet.None) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = sheet != Sheet.Calibrate),
        containerColor = Ink.card,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.width(36.dp).height(4.dp).clip(CircleShape)
                        .background(Ink.muted.copy(alpha = 0.4f))
                )
            }
        }
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
            when (sheet) {
                Sheet.Export -> {
                    Title("Your readings")
                    Note("Kept on this phone. Sharing is the only way any of it leaves.")
                    Text(
                        report,
                        color = Ink.text,
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                    )
                    Spacer(Modifier.height(18.dp))
                    Choice("Export as a spreadsheet", false, Ink.oxygen) { onShare() }
                    Choice(healthLabel, false, Ink.motion) { onHealth() }
                }
                Sheet.Calibrate -> CalibrateSheet(state, onCalibrate)
                Sheet.None -> Unit
            }
        }
    }
}

/**
 * A real cuff reading, entered once, so the ring can calibrate its own pulse-wave estimate
 * against it — see [Ring.calibratePressure]. Seeded from the last reading shown rather than
 * blank, since that is the number closest to hand to correct.
 */
@Composable
private fun CalibrateSheet(state: VitalsState, onCalibrate: (Int, Int) -> Unit) {
    var systolic by remember { mutableStateOf(state.systolic ?: 120) }
    var diastolic by remember { mutableStateOf(state.diastolic ?: 80) }
    Title("Calibrate blood pressure")
    Note(
        "Take a reading with a real cuff, then enter it here. The ring uses it to correct its " +
            "own pulse-wave estimate — this is not itself a measurement."
    )
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
        Picker(systolic, 60..250, { "$it" }, Modifier.width(110.dp)) { systolic = it }
        Text("/", color = Ink.muted, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 4.dp))
        Picker(diastolic, 40..150, { "$it" }, Modifier.width(110.dp)) { diastolic = it }
    }
    Spacer(Modifier.height(18.dp))
    Choice("Save calibration", false, Ink.pressure) { onCalibrate(systolic, diastolic) }
}

@Composable
private fun Title(text: String) {
    Text(text, color = Ink.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Note(text: String) {
    Text(text, color = Ink.muted, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun Choice(label: String, chosen: Boolean, accent: Color, onPick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(if (chosen) accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickableNoRippleShared(onPick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = if (chosen) accent else Ink.text, fontSize = 16.sp)
        if (chosen) Icon(Icons.Rounded.Check, null, tint = accent, modifier = Modifier.size(20.dp))
    }
}
