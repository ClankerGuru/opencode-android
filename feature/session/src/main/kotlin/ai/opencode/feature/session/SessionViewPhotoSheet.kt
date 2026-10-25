package ai.opencode.feature.session

import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.feature.composer.AttachmentPickerButton
import ai.opencode.feature.composer.ComposerImageSource
import ai.opencode.feature.composer.plusmenu.PhotoFlights
import ai.opencode.feature.composer.plusmenu.PlusCameraPresentation
import ai.opencode.feature.composer.plusmenu.PlusPhotoLibrary
import ai.opencode.feature.composer.plusmenu.PlusPhotoSheet
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
        if (uris.isNotEmpty()) importer.importPickedMedia(uris, currentOnAdded)
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

/**
 * The New Plus Menu's photo sheet (iOS `PlusPhotoSheet` as `SessionView+PhotoSheet` presents it,
 * Experimental): the composer's [PlusPhotoSheet] over the + menu, the camera tile first, then the
 * newest photos. Add attaches the picks in tap order through the screen's importer; a photo from
 * the sheet's camera attaches at once. Videos picked here attach as their first frame.
 *
 * Android: the photos land without the flights into their thumbnails (`PhotoFlights` isn't driven
 * here), and the sheet is a modal bottom sheet rather than the + menu's morphing surface.
 *
 * @param onBack ‹ Back: the + menu again.
 * @param onDismiss swiped or tapped away: the menu closes too.
 * @param onClosed Add or a capture closed it.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SessionPlusPhotoSheet(
    importer: SessionAttachmentImporter,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    onAdded: (() -> Unit)?,
    onClosed: () -> Unit,
) {
    val library = remember { PlusPhotoLibrary() }
    val flights = remember { PhotoFlights() }
    val camera = remember { PlusCameraPresentation() }
    DisposableEffect(library) { onDispose { library.stop() } }
    PlusPhotoSheet(
        library = library,
        flights = flights,
        camera = camera,
        onBack = onBack,
        onAdd = { ids ->
            if (ids.isNotEmpty()) {
                importer.startImageImport(ids.map { id -> ComposerImageSource.Loader { library.imageData(id) } }, onAdded = onAdded)
            }
            onClosed()
        },
        onCapture = { data ->
            importer.captureCameraPhoto(data, onAdded)
            onClosed()
        },
        onDismiss = onDismiss,
    )
}
