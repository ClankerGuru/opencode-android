package ai.opencode.core.ui

import androidx.compose.runtime.Composable

/**
 * iOS `AlertTextFields`: text fields in alerts (Rename Session, Rename Server, Paste the Code)
 * show a clear button. iOS installs one appearance rule for every alert's text field; Compose
 * has no such rule, so each alert's field passes [clearButton] as its `trailingIcon`.
 */
object AlertTextFields {
    /** The field's trailing clear (×) button, shown while the field holds text. */
    fun clearButton(value: String, onClear: () -> Unit): (@Composable () -> Unit)? {
        if (value.isEmpty()) {
            return null
        }
        return {
            OpenCodeIconButton(
                SFSymbol.XmarkCircleFill,
                contentDescription = "Clear text",
                onClick = onClear,
                style = OpenCodeIconButtonStyle.Standard,
                iconSize = OpenCodeIconSize.small,
            )
        }
    }
}
