package ai.opencode.core.ui

/**
 * The OpenCode desktop/web palette (`packages/ui` theme `oc-2`, its `--v2-*` tokens), used for
 * [AppTheme.OpenCode] instead of the iOS-generated palette. Neutral and monochrome like the
 * desktop app: the accent is the desktop's contrast fill (`--v2-background-bg-contrast` /
 * `--v2-background-bg-icon-button-contrast`), blue is kept for links.
 *
 * Desktop token → field:
 * canvas `bg-base`, canvasWeak `bg-deep` (sidebar), codeBand `bg-layer-01`, userBubble
 * `bg-layer-02`, surfaceStrong `bg-layer-03`, text/strong `text-base`, textWeak `text-muted`,
 * textTertiary `text-faint` (darkened in light mode to pass WCAG AA), border `border-base`
 * (flattened), borderStrong `border-strong`, link `text-accent`, syntax `--syntax-*`, diff
 * `--v2-state-*-success/danger`.
 */
internal object BrandPalette {
    private fun hex(value: String, alpha: Double = 1.0): ThemeRGBA {
        val v = value.removePrefix("#").toLong(16)
        return ThemeRGBA(((v shr 16) and 0xFF) / 255.0, ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0, alpha)
    }

    val opencode = ThemeDefinition(
        id = AppTheme.OpenCode,
        title = "OpenCode",
        light = ThemeVariant(
            canvas = hex("#FFFFFF"),
            canvasWeak = hex("#FAFAFA"),
            codeBand = hex("#FAFAFA"),
            userBubble = hex("#F2F2F2"),
            surfaceStrong = hex("#EEEEEE"),
            text = hex("#161616"),
            bodyText = hex("#242424"),
            textStrong = hex("#0A0A0A"),
            textWeak = hex("#5C5C5C"),
            textTertiary = hex("#767676"),
            border = hex("#E6E6E6"),
            borderStrong = hex("#CCCCCC"),
            accent = hex("#242424"),
            link = hex("#3B5CF6"),
            syntaxKeyword = hex("#C83D8B"),
            syntaxString = hex("#198B43"),
            syntaxNumber = hex("#007B80"),
            syntaxComment = hex("#5C5C5C"),
            syntaxType = hex("#5230C2"),
            diffAddedFill = hex("#E7F9EA"),
            diffRemovedFill = hex("#FCECEB"),
            glassDiffAddedFill = hex("#198B43", 0.12),
            glassDiffRemovedFill = hex("#B82D35", 0.10),
            diffAddedText = hex("#198B43"),
            diffRemovedText = hex("#B82D35"),
            swatchCanvas = hex("#FFFFFF"),
            swatchRing = hex("#DBDBDB"),
            swatchAccent = hex("#242424"),
        ),
        dark = ThemeVariant(
            canvas = hex("#161616"),
            canvasWeak = hex("#101010"),
            codeBand = hex("#1C1C1C"),
            userBubble = hex("#2E2E2E"),
            surfaceStrong = hex("#3A3A3A"),
            text = hex("#EDEDED"),
            bodyText = hex("#DDDDDD"),
            textStrong = hex("#FAFAFA"),
            textWeak = hex("#AEAEAE"),
            textTertiary = hex("#8A8A8A"),
            border = hex("#2D2D2D"),
            borderStrong = hex("#454545"),
            accent = hex("#DBDBDB"),
            link = hex("#A2BCFF"),
            syntaxKeyword = hex("#F799C6"),
            syntaxString = hex("#96E3A6"),
            syntaxNumber = hex("#93E9F6"),
            syntaxComment = hex("#AEAEAE"),
            syntaxType = hex("#9E99F7"),
            diffAddedFill = hex("#14361D"),
            diffRemovedFill = hex("#461516"),
            glassDiffAddedFill = hex("#6BD586", 0.14),
            glassDiffRemovedFill = hex("#F17471", 0.14),
            diffAddedText = hex("#6BD586"),
            diffRemovedText = hex("#F17471"),
            swatchCanvas = hex("#161616"),
            swatchRing = hex("#454545"),
            swatchAccent = hex("#DBDBDB"),
        ),
    )
}
