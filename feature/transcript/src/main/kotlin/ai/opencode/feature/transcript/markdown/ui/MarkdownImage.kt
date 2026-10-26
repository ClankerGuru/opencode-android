package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.user.TranscriptDragPayload
import ai.opencode.feature.transcript.user.transcriptDragSource
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Markdown image (iOS `MarkdownImage`). A web image loads through Coil; a `data:` image or a
 * file outside the session's folder loads through the server client's Markdown image reader
 * (`OpenCodeClient.markdownImage`, desktop's `readLocalImage`) once it's on screen. Tapping it
 * opens the zoom viewer; one that didn't load says so, and a tap tries again.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarkdownImage(
    source: String,
    alt: String,
    modifier: Modifier = Modifier,
    server: OpenCodeServer? = LocalTranscriptRowContext.current.server,
    directory: String? = LocalTranscriptRowContext.current.directory,
    /** The local file a failed load shows as a card instead. */
    fallbackFile: AgentFileReference? = null,
) {
    val send = LocalTranscriptRowContext.current.send
    val colors = OpenCodeTheme.colors
    val context = LocalContext.current
    val client = remember { dependency(OpenCodeClient) }
    // A `data:` source can be megabytes, so the key is built once per source.
    val cacheKey = remember(server, directory, source) {
        server?.let { OpenCodeClient.markdownImageKey(it, directory, source) } ?: source
    }
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<ImageLoad>(ImageLoad.Loading, cacheKey, attempt) {
        value = ImageLoad.Loading
        val loaded: ImageBitmap? = withContext(Dispatchers.IO) {
            runCatching {
                if (source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)) {
                    val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(source).build())
                    (result as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
                } else {
                    val bytes = client.cachedMarkdownImage(cacheKey)
                        ?: server?.let { client.markdownImage(it, directory, source, null) }
                    bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }
            }.getOrNull()
        }
        value = if (loaded != null) ImageLoad.Loaded(loaded) else ImageLoad.Failed
    }
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
    when (val load = state) {
        is ImageLoad.Loaded -> {
            val label = alt.ifEmpty { "Image" }
            Box(
                modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .transcriptDragSource(
                        TranscriptDragPayload.Image(
                            load.image,
                            name = fallbackFile?.name ?: alt.ifEmpty { "image" },
                            file = fallbackFile,
                            server = server,
                            directory = directory,
                        ),
                        cornerRadius = TranscriptMetrics.cardCornerRadius,
                    )
                    .clip(shape)
                    .background(colors.userBubble, shape)
                    .clickable { send(TranscriptAction.PreviewImage(TranscriptImagePreview(cacheKey, load.image.asAndroidBitmap(), label))) }
                    .semantics { contentDescription = "Open ${alt.ifEmpty { "image" }}" },
                contentAlignment = Alignment.Center,
            ) {
                Image(load.image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = 360.dp))
            }
        }
        ImageLoad.Failed -> FailedImageCard(
            name = fallbackFile?.path?.substringAfterLast('/') ?: alt.ifEmpty { "Image" },
            fallbackFile = fallbackFile,
            onRetry = { attempt += 1 },
            modifier = modifier,
        )
        ImageLoad.Loading -> Box(
            modifier.fillMaxWidth().heightIn(min = 120.dp).background(colors.userBubble, shape),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    }
}

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data object Failed : ImageLoad
    class Loaded(val image: ImageBitmap) : ImageLoad
}

/**
 * An image that didn't load says so: a broken-photo glyph, its name, and “Couldn’t load
 * image”. A tap tries again; holding offers Try Again and Open (the viewer explains what went
 * wrong) when it's a file.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailedImageCard(name: String, fallbackFile: AgentFileReference?, onRetry: () -> Unit, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    val send = LocalTranscriptRowContext.current.send
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier
                .width(260.dp)
                .heightIn(min = 48.dp)
                .clip(shape)
                .background(colors.userBubble, shape)
                // iOS `accessibilityHint("Tries again")`: TalkBack reads “Double-tap to try again”.
                .combinedClickable(onClickLabel = "Try again", onClick = onRetry, onLongClick = { menu = true })
                .semantics {
                    contentDescription = name
                    stateDescription = "Couldn’t load image"
                }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OpenCodeIcon(SFSymbol.PhotoBadgeExclamationmark, contentDescription = null, tint = colors.secondaryText)
            Column(Modifier.weight(1f)) {
                Text(name, style = OpenCodeTextStyles.subheadline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Couldn’t load image", style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
            }
            OpenCodeIcon(SFSymbol.ArrowClockwise, contentDescription = null, tint = colors.tertiaryText)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Try Again") },
                leadingIcon = { OpenCodeIcon(SFSymbol.ArrowClockwise, contentDescription = null) },
                onClick = { menu = false; onRetry() },
            )
            if (fallbackFile != null) {
                DropdownMenuItem(
                    text = { Text("Open") },
                    leadingIcon = { OpenCodeIcon(SFSymbol.DocTextMagnifyingglass, contentDescription = null) },
                    onClick = { menu = false; send(TranscriptAction.OpenFile(fallbackFile)) },
                )
            }
        }
    }
}
