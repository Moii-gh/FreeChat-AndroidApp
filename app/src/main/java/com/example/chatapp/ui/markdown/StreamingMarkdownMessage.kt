package com.example.chatapp.ui.markdown

import android.graphics.Color
import android.widget.LinearLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration

@Composable
internal fun StreamingMarkdownMessage(
    markdown: String,
    isFinal: Boolean,
    modifier: Modifier = Modifier,
    linkColor: Int = Color.rgb(10, 132, 255),
    onOpenLink: (String) -> Unit
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { factoryContext ->
            LinearLayout(factoryContext).apply {
                orientation = LinearLayout.VERTICAL
            }
        },
        update = { contentArea ->
            val renderer = contentArea.getTag(com.example.chatapp.R.id.streaming_markdown_renderer)
                as? AndroidStreamingMarkdownRenderer
                ?: AndroidStreamingMarkdownRenderer(
                    context = context,
                    contentArea = contentArea,
                    markwon = Markwon.builder(context)
                        .usePlugin(object : AbstractMarkwonPlugin() {
                            override fun configureTheme(builder: io.noties.markwon.core.MarkwonTheme.Builder) {
                                builder.linkColor(linkColor)
                                    .isLinkUnderlined(true)
                            }

                            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                                builder.linkResolver { _, link -> onOpenLink(link) }
                            }
                        })
                        .build(),
                    onOpenLink = onOpenLink,
                    linkColor = linkColor
                ).also {
                    contentArea.setTag(com.example.chatapp.R.id.streaming_markdown_renderer, it)
                }
            renderer.render(markdown, isFinal)
        }
    )
}
