package ai.opencode.feature.session

import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.feature.composer.AttachmentPickerButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

// Port of iOS `Session/SessionView+PhotoSheet.swift` as Android presents it. iOS picks photos in
// the system PhotosPicker the + menu morphs into (or, under the Plus Menu Sheet experiment, its
// own photo sheet: the newest photos with the camera first, flights into the thumbnails, Back
// and Add along its foot). Android uses the system Photo Picker for both: no library permission,
// the newest photos first, up to four picks. Picks attach at once — images re-encoded inline,
// videos as uploads — through the screen's [SessionAttachmentImporter], whose results go to the
// reducer (`attachmentAdded`, `filesStaged`). The morph geometry, detents and photo flights are
// UIKit glass choreography with no Android counterpart.

/**
 * The photo picker, composed by `SessionView` while the + menu's Photos was chosen. It opens the
 * system Photo Picker once (it survives rotation without reopening), attaches what was picked,
 * and calls [onDismiss] when the picker closes, picked or not. Images and videos when the server
 * takes uploads, images only otherwise.
 *
 * @param importer the screen's importer ([rememberSessionAttachmentImporter] in `SessionView`), so
 *   an import keeps going after the picker is gone. The default lives only as long as this sheet.
 * @param onDismiss the picker closed: the + menu's Photos state goes back to closed.
 * @param onAdded `focusComposer`: the keyboard comes back once something attached (iOS
 *   `attachmentMenuRestoresFocus`).
 */
@Composable
fun SessionPhotoSheet(
    store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    importer: SessionAttachmentImporter = rememberSessionAttachmentImporter(store),
    onDismiss: () -> Unit = {},
    onAdded: (() -> Unit)? = null,
) {
    val supportsFileUploads by store.observe { it.supportsFileUploads }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnAdded by rememberUpdatedState(onAdded)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = AttachmentPickerButton.MAX_PHOTO_SELECTION),
    ) { uris ->
        if (uris.isNotEmpty()) importer.importPickedMedia(uris.map { it.toString() }, currentOnAdded)
        currentOnDismiss()
    }
    var launched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (launched) return@LaunchedEffect
        launched = true
        val media = if (supportsFileUploads) {
            ActivityResultContracts.PickVisualMedia.ImageAndVideo
        } else {
            ActivityResultContracts.PickVisualMedia.ImageOnly
        }
        try {
            launcher.launch(PickVisualMediaRequest(media))
        } catch (_: android.content.ActivityNotFoundException) {
            importer.attachmentError = "This image couldn’t be loaded. Try another photo."
            currentOnDismiss()
        }
    }
}
