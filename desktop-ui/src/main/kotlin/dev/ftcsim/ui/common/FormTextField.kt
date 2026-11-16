package dev.ftcsim.ui.common

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A single line of text to edit, with what is wrong with it said under it, with an icon as well as colour, or else a
 * hint. A field that cannot be edited ([enabled] false) still shows its value.
 */
@Composable
fun FormTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    problem: String?,
    hint: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = problem != null,
        supportingText = { Text(problem ?: hint.orEmpty()) },
        trailingIcon = { if (problem != null) Icon(AppIcons.Error, contentDescription = "Problem", tint = MaterialTheme.colorScheme.error) },
        modifier = modifier,
    )
}
