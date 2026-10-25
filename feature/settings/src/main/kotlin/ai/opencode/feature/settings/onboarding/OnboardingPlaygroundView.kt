package ai.opencode.feature.settings.onboarding

import ai.opencode.core.store.StoreOf
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** STUB: port of iOS `Settings/Onboarding/OnboardingPlaygroundView.swift` (being ported). */
@Composable
fun OnboardingPlaygroundView(
    store: StoreOf<OnboardingPlaygroundFeature.State, OnboardingPlaygroundFeature.Action>,
    onClose: () -> Unit,
) {
    Text("Onboarding")
}
