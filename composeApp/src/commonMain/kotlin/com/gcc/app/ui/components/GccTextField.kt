package com.gcc.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.TextMuted

@Composable
fun GccTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    isEmail: Boolean = false,
    isPhone: Boolean = false,
    error: String? = null,
    leadingIcon: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
) {
    val resolvedKeyboardOptions = when {
        isPassword -> KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        )
        isEmail -> KeyboardOptions(
            keyboardType = KeyboardType.Email,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        )
        isPhone -> KeyboardOptions(
            keyboardType = KeyboardType.Phone,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        )
        keyboardType != KeyboardType.Text -> KeyboardOptions(
            keyboardType = keyboardType,
            capitalization = capitalization,
        )
        else -> KeyboardOptions(capitalization = capitalization)
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = singleLine,
        isError = error != null,
        supportingText = if (error != null) {
            { Text(error, color = MaterialTheme.colorScheme.error) }
        } else null,
        visualTransformation = if (isPassword) PasswordVisualTransformation()
        else VisualTransformation.None,
        keyboardOptions = resolvedKeyboardOptions,
        leadingIcon = if (leadingIcon != null) {
            { Text(leadingIcon) }
        } else null,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = GccGreen,
            focusedLabelColor = GccGreen,
            cursorColor = GccGreen,
            unfocusedBorderColor = TextMuted.copy(alpha = 0.3f),
        ),
    )
}
