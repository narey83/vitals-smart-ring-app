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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Which sheet is open, if any. */
enum class Sheet { None, Interval, Goal, Export }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsSheet(
    sheet: Sheet,
    state: VitalsState,
    report: String,
    onInterval: (Int) -> Unit,
    onGoal: (Int) -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    if (sheet == Sheet.None) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = sheet == Sheet.Export),
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
                Sheet.Interval -> {
                    Title("Automatic readings")
                    Note("The ring measures on its own at this interval, whether or not the app is open.")
                    listOf(0 to "Off", 15 to "Every 15 minutes", 30 to "Every 30 minutes", 60 to "Every hour")
                        .forEach { (minutes, label) ->
                            Choice(label, state.interval == minutes, Ink.motion) { onInterval(minutes) }
                        }
                }
                Sheet.Goal -> {
                    Title("Daily step goal")
                    Note("Set on the ring as well as here, so both agree about the day.")
                    listOf(5_000, 7_500, 10_000, 12_500, 15_000, 20_000).forEach { goal ->
                        Choice("%,d steps".format(goal), state.stepGoal == goal, Ink.motion) { onGoal(goal) }
                    }
                }
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
                }
                Sheet.None -> Unit
            }
        }
    }
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
