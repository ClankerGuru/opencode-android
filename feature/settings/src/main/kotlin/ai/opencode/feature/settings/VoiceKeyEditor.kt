package ai.opencode.feature.settings

import ai.opencode.core.diagnostics.HidesFromScreenshots
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.voice.VoiceProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * iOS `VoiceKeyEditor`: the selected model's provider key, its reveal button and Verify, under
 * the Key row. Kept out of every screenshot (iOS `.hidesFromScreenshots()`).
 */
@Composable
internal fun VoiceKeyEditor(state: VoiceSettingsFeature.State, voice: VoiceStore, settings: (HomeSettingsFeature.Action) -> Unit) {
    val key = state.voiceEngine.apiKey(state.openAIAPIKey, state.xaiAPIKey).orEmpty()
    val provider = state.activeProvider
    val stacked = isAccessibilitySize()
    // iOS `.hidesFromScreenshots()` on the editor: the key is a credential.
    HidesFromScreenshots()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val keyInput = @Composable { fieldModifier: Modifier ->
            OutlinedTextField(
                value = key,
                onValueChange = { value ->
                    voice.send(VoiceSettingsFeature.Action.ApiKeyEdited(value))
                    val saved = value.trim().ifEmpty { null }
                    when (provider) {
                        VoiceProvider.openAI -> settings(HomeSettingsFeature.Action.OpenAIAPIKeyChanged(saved))
                        VoiceProvider.xAI -> settings(HomeSettingsFeature.Action.XaiAPIKeyChanged(saved))
                    }
                },
                placeholder = { Text(provider.keyPlaceholder) },
                singleLine = true,
                visualTransformation = if (state.revealsKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                textStyle = if (state.revealsKey) OpenCodeTextStyles.subheadline.copy(fontFamily = FontFamily.Monospace) else OpenCodeTextStyles.body,
                trailingIcon = {
                    Box(
                        Modifier
                            .clickable(role = Role.Button) { voice.send(VoiceSettingsFeature.Action.ToggleRevealKeyTapped) }
                            .openCodeTapTarget()
                            .semantics { contentDescription = if (state.revealsKey) "Hide key" else "Show key" },
                        contentAlignment = Alignment.Center,
                    ) {
                        OpenCodeIcon(if (state.revealsKey) SFSymbol.EyeSlash else SFSymbol.Eye, contentDescription = null, tint = OpenCodeTheme.colors.secondaryText)
                    }
                },
                modifier = fieldModifier,
            )
        }
        val verifyButton = @Composable {
            FilledTonalButton(
                onClick = { voice.send(VoiceSettingsFeature.Action.VerifyKeyTapped) },
                enabled = state.activeAPIKey.isNotEmpty() && !state.isCheckingKey,
            ) { Text("Verify") }
        }
        if (stacked) {
            // iOS at accessibility sizes: Verify goes under the field.
            keyInput(Modifier.fillMaxWidth())
            verifyButton()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keyInput(Modifier.weight(1f))
                verifyButton()
            }
        }
        state.keyVerificationError?.let { error ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OpenCodeIcon(SFSymbol.ExclamationmarkCircleFill, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.destructive)
                // iOS `.textSelection(.enabled)`.
                SelectionContainer {
                    Text(error, style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.destructive)
                }
            }
        }
    }
}
