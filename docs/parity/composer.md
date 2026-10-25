# Parity: composer

Module: `:feature:composer`. iOS folders: `OpenCode/Composer/`. 48 files, 26,082 lines.

Status values: `todo`, `partial: <what is missing>`, `done`, `n/a: <reason and what it maps to>`. Fill in the Kotlin file(s) as you port; see [README.md](README.md).

| iOS file | Lines | Kotlin file(s) | Status | Notes |
| --- | ---: | --- | --- | --- |
| `OpenCode/Composer/AgentPicker.swift` | 146 | | todo | |
| `OpenCode/Composer/AttachmentCameraLenses.swift` | 234 | | todo | |
| `OpenCode/Composer/AttachmentCameraView.swift` | 985 | | todo | |
| `OpenCode/Composer/AttachmentFileType.swift` | 116 | `core/.../files/AttachmentFileType.kt` | done | moved to `:core` by the system area (the share target needs it); composer uses it from there |
| `OpenCode/Composer/AttachmentMenu.swift` | 1086 | | todo | |
| `OpenCode/Composer/AttachmentPicker.swift` | 426 | | todo | |
| `OpenCode/Composer/AutocompleteFeature.swift` | 663 | | todo | |
| `OpenCode/Composer/AutocompleteModels.swift` | 773 | | todo | |
| `OpenCode/Composer/AutocompleteSimulation.swift` | 1446 | | todo | |
| `OpenCode/Composer/AutocompleteTouchTracker.swift` | 50 | | todo | |
| `OpenCode/Composer/AutocompleteTunables.swift` | 152 | | todo | |
| `OpenCode/Composer/AutocompleteViews.swift` | 932 | | todo | |
| `OpenCode/Composer/ComposerFileAttachment.swift` | 342 | | todo | |
| `OpenCode/Composer/ComposerFileImport.swift` | 129 | | todo | |
| `OpenCode/Composer/ComposerImageImport.swift` | 209 | | todo | |
| `OpenCode/Composer/ComposerMorph.swift` | 1417 | | todo | |
| `OpenCode/Composer/ComposerPersistence.swift` | 1925 | | todo | |
| `OpenCode/Composer/ComposerTextInput.swift` | 1213 | | todo | |
| `OpenCode/Composer/DictationAudio.swift` | 796 | | todo | |
| `OpenCode/Composer/DictationClient.swift` | 954 | | todo | |
| `OpenCode/Composer/FileAttachmentViews.swift` | 204 | | todo | |
| `OpenCode/Composer/FormModels.swift` | 186 | | todo | |
| `OpenCode/Composer/InlinePhotoSelection.swift` | 85 | | todo | |
| `OpenCode/Composer/LiveDictation.swift` | 362 | | todo | |
| `OpenCode/Composer/MicPressGesture.swift` | 161 | | todo | |
| `OpenCode/Composer/ModelPicker/ModelLevelScale.swift` | 51 | | todo | |
| `OpenCode/Composer/ModelPicker/ModelListContent.swift` | 352 | | todo | |
| `OpenCode/Composer/ModelPicker/ModelPickerOverlays.swift` | 2425 | | todo | |
| `OpenCode/Composer/ModelPicker/ModelPickerSections.swift` | 438 | | todo | |
| `OpenCode/Composer/ModelPicker/ModelPickerSheet.swift` | 163 | | todo | |
| `OpenCode/Composer/ModelPicker/ModelVisibility.swift` | 108 | `server/.../model/ModelVisibility.kt` | done | moved down to `:server` (settings › Models and the pickers both read it); `modelVisibilityByServer` key in its companion |
| `OpenCode/Composer/ModelPicker/ShowAllModelsRow.swift` | 29 | | todo | |
| `OpenCode/Composer/PastedText.swift` | 180 | | todo | |
| `OpenCode/Composer/PastedTextViews.swift` | 207 | | todo | |
| `OpenCode/Composer/PermissionPanelParts.swift` | 192 | | todo | |
| `OpenCode/Composer/PlusMenu/PhotoFlight.swift` | 1013 | | todo | |
| `OpenCode/Composer/PlusMenu/PhotoLibraryClient.swift` | 563 | | todo | |
| `OpenCode/Composer/PlusMenu/PhotoReturnTrace.swift` | 65 | | todo | |
| `OpenCode/Composer/PlusMenu/PlusCameraSurface.swift` | 107 | | todo | |
| `OpenCode/Composer/PlusMenu/PlusPhotoSheet.swift` | 1021 | | todo | |
| `OpenCode/Composer/PlusMenu/RecentPhotoPeek.swift` | 306 | | todo | |
| `OpenCode/Composer/PromptOutbox.swift` | 898 | | todo | |
| `OpenCode/Composer/QueuedMessagesSheet.swift` | 319 | | todo | |
| `OpenCode/Composer/RequestAnswer.swift` | 402 | | todo | |
| `OpenCode/Composer/RequestDockView.swift` | 1006 | | todo | |
| `OpenCode/Composer/RequestReviewView.swift` | 211 | | todo | |
| `OpenCode/Composer/SharedLocation.swift` | 932 | | todo | |
| `OpenCode/Composer/UndeliveredPrompts.swift` | 102 | | todo | |
