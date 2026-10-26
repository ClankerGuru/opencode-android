package ai.opencode.feature.composer

import ai.opencode.feature.composer.plusmenu.PlusCameraPresentation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of iOS `AttachmentCameraActivityTests.swift`. iOS's `handleScenePhase(.inactive/.active)`
 * change nothing; Android's presentation only hears the background (`ON_STOP` →
 * [PlusCameraPresentation.handleBackground]), so those steps assert that nothing moved.
 */
class AttachmentCameraActivityTests {
    @Test fun hiddenAndBackgroundCamerasNeverActivate() {
        for (authorized in listOf(false, true)) {
            for (requests in listOf(false, true)) {
                assertFalse(AttachmentCameraActivity(visible = false, sceneActive = true, authorized = authorized, requestsAccess = requests).shouldActivate)
                assertFalse(AttachmentCameraActivity(visible = true, sceneActive = false, authorized = authorized, requestsAccess = requests).shouldActivate)
            }
        }
    }

    @Test fun tileOnlyStartsWithExistingPermission() {
        assertFalse(AttachmentCameraActivity(visible = true, sceneActive = true, authorized = false, requestsAccess = false).shouldActivate)
        assertTrue(AttachmentCameraActivity(visible = true, sceneActive = true, authorized = true, requestsAccess = false).shouldActivate)
    }

    @Test fun expandedCameraMayRequestPermission() {
        assertTrue(AttachmentCameraActivity(visible = true, sceneActive = true, authorized = false, requestsAccess = true).shouldActivate)
    }

    @Test fun leavingSheetResetsCameraPresentation() {
        val camera = PlusCameraPresentation()
        camera.expanded = true
        camera.mounted = true
        camera.reset()
        assertFalse(camera.expanded)
        assertFalse(camera.mounted)
        assertEquals(Rect.Zero, camera.tile)
    }

    @Test fun plusCameraOnlyActivatesWhenExpandedAndSheetIsOpen() {
        val camera = PlusCameraPresentation()
        camera.mounted = true
        camera.viewport = Rect(Offset(0f, 400f), Size(430f, 500f))
        camera.tile = Rect(Offset(8f, 400f), Size(140f, 140f))

        // Opening the photo sheet with the camera button visible does not start the camera.
        assertFalse(camera.shouldActivateCamera(sheetIsOpen = false))
        assertFalse(camera.shouldActivateCamera(sheetIsOpen = true))

        // Explicitly tapping the camera button expands the viewfinder and activates capture.
        camera.expanded = true
        assertFalse(camera.shouldActivateCamera(sheetIsOpen = false))
        assertTrue(camera.shouldActivateCamera(sheetIsOpen = true))

        // Collapsing the viewfinder or closing the sheet immediately deactivates capture.
        camera.expanded = false
        assertFalse(camera.shouldActivateCamera(sheetIsOpen = true))
    }

    @Test fun permissionAlertInactivePhaseKeepsViewfinderExpandedAndResumesOnActive() {
        val camera = PlusCameraPresentation()
        camera.mounted = true
        camera.expanded = true

        // 1. User taps camera tile while authorization is notDetermined:
        // viewfinder expands and requests access.
        assertTrue(camera.shouldActivateCamera(sheetIsOpen = true))
        assertTrue(
            AttachmentCameraActivity(
                visible = camera.shouldActivateCamera(sheetIsOpen = true),
                sceneActive = true,
                authorized = false,
                requestsAccess = camera.expanded,
            ).shouldActivate,
        )

        // 2. The system permission dialog pauses the activity (iOS `.inactive`):
        // PlusCameraPresentation stays expanded while AttachmentCameraActivity pauses.
        assertTrue(camera.expanded)
        assertTrue(camera.shouldActivateCamera(sheetIsOpen = true))
        assertFalse(
            AttachmentCameraActivity(
                visible = camera.shouldActivateCamera(sheetIsOpen = true),
                sceneActive = false,
                authorized = false,
                requestsAccess = camera.expanded,
            ).shouldActivate,
        )

        // 3. User taps Allow and the activity resumes (iOS `.active`):
        // viewfinder is still expanded and AttachmentCameraActivity immediately activates.
        assertTrue(camera.expanded)
        assertTrue(
            AttachmentCameraActivity(
                visible = camera.shouldActivateCamera(sheetIsOpen = true),
                sceneActive = true,
                authorized = true,
                requestsAccess = camera.expanded,
            ).shouldActivate,
        )

        // 4. Going to the background collapses the viewfinder.
        camera.handleBackground()
        assertFalse(camera.expanded)
        assertFalse(camera.shouldActivateCamera(sheetIsOpen = true))
    }
}
