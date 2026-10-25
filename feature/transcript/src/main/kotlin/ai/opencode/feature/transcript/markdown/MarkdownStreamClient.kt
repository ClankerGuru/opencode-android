package ai.opencode.feature.transcript.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The parsed blocks of an assistant text part (iOS parses at decode and keeps
 * `[OpenCodeMarkdownBlock]` on the item; Android items keep raw text, so rows ask here).
 * Skeleton: the incremental `MarkdownStream` port replaces the body with a cached stream lookup.
 */
@Composable
fun rememberMarkdownBlocks(partID: String, text: String): List<OpenCodeMarkdownBlock> =
    remember(partID, text) { OpenCodeMarkdownBlock.blocks(text) }
