package com.iptvplayer.app.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.iptvplayer.app.R

/**
 * Parental-control PIN dialog.
 *
 * @param onVerify receives the entered PIN and a callback with the result
 *   (the caller usually launches a coroutine and reports back).
 */
@Composable
fun PinDialog(
    onVerify: (String, (Boolean) -> Unit) -> Unit,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.pin_enter_title),
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = {
                    pin = it.filter { ch -> ch.isDigit() }.take(8)
                    error = false
                },
                label = { Text(stringResource(R.string.pin_label)) },
                isError = error,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                ),
                supportingText = if (error) {
                    ({ Text(stringResource(R.string.pin_wrong)) })
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (pin.length < 4) {
                        error = true
                    } else {
                        onVerify(pin) { ok ->
                            if (ok) onSuccess() else error = true
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
