package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.transcript.LocalTranscriptLayoutTuning
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.TranscriptMetrics
import ai.opencode.feature.transcript.markdown.InlineText
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock.Kind
import ai.opencode.server.model.AgentFileReference
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One rendered Markdown block (iOS `MarkdownBlockView`), shared by assistant replies,
 * compaction summaries, and Markdown files in the viewer. Rows read the server, directory and
 * `send` from `LocalTranscriptRowContext`: links and file links send `LinkTapped` /
 * `OpenFile`, images `PreviewImage`.
 *
 * @param arrivals streamed text that just arrived, which fades in (`offset == 0`: the whole
 *   block arrived and fades in whole).
 * @param isGrowingTail the reply's last block while it can still grow: a link still streaming
 *   in at its end keeps the globe.
 */
@Composable
fun MarkdownBlockView(
    block: OpenCodeMarkdownBlock,
    modifier: Modifier = Modifier,
    arrivals: List<TranscriptTextReveal.Arrival> = emptyList(),
    isGrowingTail: Boolean = false,
) {
    val reveal = LocalTranscriptTextReveal.current ?: remember { TranscriptTextReveal() }
    val whole = arrivals.firstOrNull { it.offset == 0 }
    val grown = arrivals.filter { it.offset > 0 }
    Column(modifier.fillMaxWidth().arrivalFade(whole, reveal)) {
        BlockContent(block.kind, grown, isGrowingTail)
    }
}

@Composable
private fun BlockContent(kind: Kind, grown: List<TranscriptTextReveal.Arrival>, isGrowingTail: Boolean) {
    val style = currentMarkdownStyle()
    val colors = style.colors
    val gutter = LocalTranscriptLayoutTuning.current.contentGutter.dp
    val handoff = LocalMarkdownMediaHandoff.current
    val send = LocalTranscriptRowContext.current.send
    when (kind) {
        is Kind.NumberedItem -> ListItem(kind, kind.marker, 0, kind.text, grown, isGrowingTail)
        is Kind.ListItem -> ListItem(kind, kind.marker, kind.level, kind.text, grown, isGrowingTail)
        is Kind.Blockquote -> Box(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        colors.secondaryText.copy(alpha = colors.secondaryText.alpha * 0.5f),
                        Offset.Zero, Size(3.dp.toPx(), size.height), CornerRadius(2.dp.toPx()),
                    )
                }
                .padding(start = TranscriptMetrics.standard, top = TranscriptMetrics.compact, bottom = TranscriptMetrics.compact),
        ) {
            BlockText(kind, kind.text, style.bodyStyle, colors.secondaryText, grown, isGrowingTail, tonesBody = false)
        }
        is Kind.Paragraph -> BlockText(kind, kind.text, style.bodyStyle, style.bodyColor, grown, isGrowingTail)
        Kind.Divider ->
            // Runs the screen's full width, like the code and table bands, with the block gap on each side.
            HairlineRule(Modifier.bleedHorizontal(gutter).clearAndSetSemantics {})
        is Kind.PullRequest -> PullRequestCard(kind.title) { send(TranscriptAction.LinkTapped(kind.url)) }
        is Kind.Files -> handoff.files?.invoke(kind.files) ?: FileRows(kind.files) { send(TranscriptAction.OpenFile(it)) }
        // A Markdown image outside the session's folder loads through that folder's location once
        // it's on screen; a file that can't be shown inline falls back to its card.
        is Kind.Image -> MarkdownImage(kind.source, kind.alt, fallbackFile = AgentFileReference.fromLink(kind.source))
        is Kind.Heading -> BlockText(
            kind, kind.text, style.headingStyle(kind.level), colors.strongText, grown, isGrowingTail,
            headingLevel = kind.level, modifier = Modifier.semantics { heading() },
        )
        is Kind.Code -> {
            val text = remember(kind.code, colors) { PresentedText.plain(MarkdownPresentation.code(kind.code, colors)) }
            MarkdownBand {
                QuoteSelectionContainer(kind) {
                    ArrivingText(text, grown, MarkdownStyle.codeTextStyle(style.tuning.codeLeading), colors.text, softWrap = false)
                }
            }
        }
        is Kind.Table -> MarkdownTable(kind.header, kind.rows)
        is Kind.Math -> MathDisplayView(kind.math)
        is Kind.Mermaid -> {
            val mermaid = handoff.mermaid
            if (mermaid != null) {
                mermaid(kind.source)
            } else {
                // Until media's diagram renderer lands, the fence shows as code.
                val text = remember(kind.source, colors) {
                    PresentedText.plain(MarkdownPresentation.code(OpenCodeMarkdownBlock.HighlightedCode(kind.source, "mermaid"), colors))
                }
                MarkdownBand {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        ArrivingText(text, emptyList(), MarkdownStyle.codeTextStyle(style.tuning.codeLeading), colors.text, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * A block's inline text: presented (session chips, Link Icons, math), selectable with Quote,
 * fading in what arrived, reporting its session IDs and its icons' first fetch.
 */
@Composable
private fun BlockText(
    kind: Kind,
    inline: InlineText,
    textStyle: TextStyle,
    color: Color,
    grown: List<TranscriptTextReveal.Arrival>,
    isGrowingTail: Boolean,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    headingLevel: Int? = null,
    tonesBody: Boolean = true,
) {
    val style = currentMarkdownStyle()
    val density = LocalDensity.current
    val sessionLinks = LocalSessionLinks.current
    val linkStyle = LocalInlineLinkStyle.current
    val handoff = LocalMarkdownMediaHandoff.current
    val listener = rememberLinkListener()
    val options = PresentationOptions(
        foreground = color,
        textSize = textStyle.fontSize,
        secondary = secondary,
        headingLevel = headingLevel,
        tonesBody = tonesBody,
        linkStyle = linkStyle,
        isGrowing = isGrowingTail,
    )
    val presented = remember(inline, options, sessionLinks.targets, style.colors, density, handoff.siteIcon) {
        MarkdownPresentation.present(inline, options, style, sessionLinks.targets, density, listener, handoff.siteIcon)
    }
    QuoteSelectionContainer(kind, modifier) {
        ArrivingText(presented, presented.mapArrivals(grown), textStyle, color)
    }
    ReportsSessionLinks(presented.sessionIDs)
    val fetchable = presented.fetchableLinks
    if (fetchable.isNotEmpty() && handoff.inlineLinksAppeared != null) {
        LaunchedEffect(fetchable) { handoff.inlineLinksAppeared.invoke(fetchable, sessionLinks.row) }
    }
}

/** The marker hangs, so wrapped lines align with the text, not under it. */
@Composable
private fun ListItem(kind: Kind, marker: String, level: Int, text: InlineText, grown: List<TranscriptTextReveal.Arrival>, isGrowingTail: Boolean) {
    val style = currentMarkdownStyle()
    val colors = style.colors
    val done = marker == OpenCodeMarkdownBlock.doneTask
    val isTask = done || marker == OpenCodeMarkdownBlock.openTask
    val bodyStyle = style.bodyStyle
    val iconSize = with(LocalDensity.current) { (style.tuning.bodySize * 1.2f).sp.toDp() }
    Row(
        Modifier.fillMaxWidth().padding(start = MarkdownStyle.listIndent * level).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.widthIn(min = 20.dp).alignByBaseline(), contentAlignment = Alignment.TopEnd) {
            if (isTask) {
                // An unseen line of body text carries the first baseline the icon sits on.
                Text(" ", style = bodyStyle, color = Color.Transparent)
                OpenCodeIcon(
                    if (done) SFSymbol.CheckmarkSquareFill else SFSymbol.Square,
                    contentDescription = if (done) "Done" else "Not done",
                    size = iconSize,
                    tint = colors.secondaryText,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            } else {
                Text(marker, style = if (marker == "•") bodyStyle else style.listNumberStyle, color = colors.secondaryText)
            }
        }
        Box(Modifier.weight(1f).alignByBaseline()) {
            BlockText(
                kind, text, bodyStyle, if (done) colors.secondaryText else style.bodyColor, grown, isGrowingTail,
                secondary = done,
            )
        }
    }
}

/** iOS's pull request link card: purple branch glyph, the title, and an outward arrow. */
@Composable
private fun PullRequestCard(title: String, onOpen: () -> Unit) {
    val colors = currentMarkdownStyle().colors
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
    val purple = if (colors.isDark) Color(0xFFBF5AF2) else Color(0xFFAF52DE)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TranscriptMetrics.minimumHitSide)
            .clip(shape)
            .background(colors.userBubble, shape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = TranscriptMetrics.standard),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OpenCodeIcon(SFSymbol.ArrowTrianglePull, contentDescription = null, size = 18.dp, tint = purple)
        Text(
            title,
            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(0.0001f))
        OpenCodeIcon(SFSymbol.ArrowUpRight, contentDescription = null, size = 14.dp, tint = colors.tertiaryText)
    }
}

/** Plain file rows until media's `AgentFileCards` arrive ([MarkdownMediaHandoff.files]). */
@Composable
private fun FileRows(files: List<AgentFileReference>, onOpen: (AgentFileReference) -> Unit) {
    val colors = currentMarkdownStyle().colors
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
    Column(verticalArrangement = Arrangement.spacedBy(TranscriptMetrics.clustered)) {
        for (file in files) {
            Row(
                Modifier
                    .widthIn(max = 320.dp)
                    .heightIn(min = TranscriptMetrics.minimumHitSide)
                    .clip(shape)
                    .background(colors.userBubble, shape)
                    .clickable { onOpen(file) }
                    .semantics { contentDescription = file.name }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OpenCodeIcon(file.symbolName, contentDescription = null, size = 20.dp, tint = colors.secondaryText)
                Column {
                    Text(file.name, style = OpenCodeTextStyles.subheadline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(file.typeLabel, style = OpenCodeTextStyles.footnote, color = colors.secondaryText, maxLines = 1)
                }
            }
        }
    }
}
