package com.yodesla.omniverse.android.parental

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.yodesla.omniverse.designsystem.OmniButton
import com.yodesla.omniverse.designsystem.OmniSpacing
import com.yodesla.omniverse.designsystem.OmniTheme
import com.yodesla.omniverse.designsystem.requestFocusWhenReady

/**
 * PIN entry card over a scrim (UpdatePrompt style). 4 dots fill as digits are typed and
 * onDone fires automatically with the 4-digit PIN. The 1-9 / 0 / Delete grid is D-pad
 * navigable (initial focus on "5"); hardware number keys (KEYCODE_0..9), Del and
 * Back (cancel) are accepted from the TV remote.
 */
@Composable
fun PinPad(
    title: String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    error: String? = null,
) {
    val c = OmniTheme.colors
    val t = OmniTheme.type
    var pin by remember { mutableStateOf("") }
    val five = remember { FocusRequester() }
    val otherKeys = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    val digitKeys = remember { (7..16).map { Key(it) } } // KEYCODE_0..9
    val delKey = remember { Key(67) } // KEYCODE_DEL
    val backKey = remember { Key(4) } // KEYCODE_BACK

    LaunchedEffect(Unit) { five.requestFocusWhenReady() }
    LaunchedEffect(error) {
        if (error != null) {
            pin = ""
            five.requestFocusWhenReady()
        }
    }

    fun press(digit: Int) {
        if (pin.length >= 4) return
        pin = pin + digit.toString()
        if (pin.length == 4) onDone(pin)
    }

    fun delete() {
        if (pin.isNotEmpty()) pin = pin.dropLast(1)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.scrim)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    in digitKeys -> {
                        press(digitKeys.indexOf(e.key))
                        true
                    }
                    delKey -> {
                        delete()
                        true
                    }
                    backKey -> {
                        onCancel()
                        true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(min = 360.dp, max = 560.dp)
                .fillMaxWidth()
                .heightIn(min = 380.dp)
                .background(c.surface, RoundedCornerShape(OmniSpacing.cardCorner))
                .padding(OmniSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = t.headline, color = c.textPrimary)
            Spacer(Modifier.height(OmniSpacing.l))
            Row(
                horizontalArrangement = Arrangement.spacedBy(OmniSpacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(16.dp)
                            .background(if (i < pin.length) c.accent else c.elevated, RoundedCornerShape(50)),
                    )
                }
            }
            if (error != null) {
                Spacer(Modifier.height(OmniSpacing.m))
                Text(error, style = t.body, color = c.live)
            }
            Spacer(Modifier.height(OmniSpacing.xl))
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("0", getString(R.string.pinpad_delete)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(OmniSpacing.s)) {
                        row.forEach { label ->
                            OmniButton(
                                label = label,
                                onClick = {
                                    if (label.length == 1 && label[0].isDigit()) press(label[0] - '0') else delete()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .focusRequester(if (label == "5") five else otherKeys),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(OmniSpacing.xl))
            OmniButton(getString(R.string.pinpad_cancel), onCancel, Modifier.focusRequester(cancel))
        }
    }
}

@Composable
private fun getString(id: Int): String = LocalContext.current.getString(id)
