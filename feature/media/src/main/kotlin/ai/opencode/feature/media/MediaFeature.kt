package ai.opencode.feature.media

import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.ifLet

/**
 * The media area's root reducer: hosts the full-screen transcript image viewer
 * ([TranscriptImageViewerFeature]) as a presentation, one at a time; a request while one is up is
 * ignored. [MediaView] provides [LocalMediaPresenter], through which thumbnails ask for it.
 * The viewer's Draw on Image adds to the prompt that opened it ([State.addDrawing]), set with the
 * viewer and cleared with it.
 */
class MediaFeature : Reducer<MediaFeature.State, MediaFeature.Action> {
    data class State(
        /** The transcript image viewer, while it's up. */
        val viewer: TranscriptImageViewerFeature.State? = null,
        /** The prompt [viewer] was opened for; null when it can't draw. */
        val addDrawing: ImageDrawingAdd? = null,
    )

    sealed interface Action {
        /** The view appeared (TCA `.task`). */
        data object Task : Action

        /** Open a thumbnail's images full screen; [addDrawing] lets them be drawn on. */
        data class PresentImage(val preview: TranscriptImagePreview, val addDrawing: ImageDrawingAdd? = null) : Action

        /** The viewer's actions; [PresentationAction.Dismiss] once its flight home has landed. */
        data class Viewer(val action: PresentationAction<TranscriptImageViewerFeature.Action>) : Action
    }

    private object ViewerSlot

    private val core = Reducer<State, Action> { state, action ->
        when (action) {
            Action.Task -> Reduced(state)
            is Action.PresentImage ->
                if (state.viewer != null || action.preview.pages.isEmpty()) {
                    Reduced(state)
                } else {
                    Reduced(
                        state.copy(
                            viewer = TranscriptImageViewerFeature.State(action.preview, allowsDrawing = action.addDrawing != null),
                            addDrawing = action.addDrawing,
                        ),
                    )
                }
            is Action.Viewer ->
                if (action.action == PresentationAction.Dismiss) Reduced(state.copy(addDrawing = null)) else Reduced(state)
        }
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = ifLet(
        id = ViewerSlot,
        parent = core,
        state = { it.viewer },
        setState = { state, viewer -> state.copy(viewer = viewer) },
        action = { (it as? Action.Viewer)?.action },
        embed = { Action.Viewer(it) },
        child = TranscriptImageViewerFeature(state.addDrawing),
        childID = { it.preview.id },
    ).reduce(state, action)
}
