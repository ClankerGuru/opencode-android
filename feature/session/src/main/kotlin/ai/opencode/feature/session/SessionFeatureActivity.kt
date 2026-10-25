package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State

// `SessionFeature.State` members iOS declares in the activity files
// (`Session/AgentActivity/ShellGlyph.swift`).

/** The session's shells are read in its own directory. */
val State.shellGlyphProbe: ShellGlyphProbe?
    get() = session?.let { ShellGlyphProbe(server = server, directory = it.directory) }
