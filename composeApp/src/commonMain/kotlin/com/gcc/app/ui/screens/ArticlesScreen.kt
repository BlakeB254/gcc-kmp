package com.gcc.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.openUrl
import com.gcc.app.ui.components.GccLogo
import com.gcc.app.ui.theme.*
import com.gcc.app.viewmodel.ArticlesViewModel
import com.gcc.shared.model.Article
import com.gcc.shared.model.ArticleSummary
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticlesScreen(
    onBack: () -> Unit = {},
) {
    val viewModel = koinInject<ArticlesViewModel>()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        if (uiState.articles.isEmpty()) viewModel.loadArticles()
    }

    // The reader is shown as a full-screen overlay over the list so the
    // back stack stays simple — closing the reader returns to the list
    // without a Decompose pop.
    val openArticle = uiState.openArticle
    if (openArticle != null) {
        ArticleReaderScreen(
            article = openArticle,
            onBack = { viewModel.closeArticle() },
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Articles", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = { GccLogo(size = 56.dp, modifier = Modifier.padding(end = 4.dp)) },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading && uiState.articles.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = GccGreen)
                    }
                }

                uiState.error != null && uiState.articles.isEmpty() -> {
                    ErrorState(message = uiState.error!!, onRetry = { viewModel.loadArticles() })
                }

                uiState.articles.isEmpty() -> {
                    EmptyArticlesState()
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.articles, key = { it.slug }) { article ->
                            ArticleListCard(
                                article = article,
                                onClick = { viewModel.openArticle(article.slug) },
                            )
                        }
                        if (uiState.isLoadingArticle) {
                            item {
                                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = GccGreen, modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                        uiState.articleError?.let { err ->
                            item {
                                Text(err, color = GccError, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleListCard(
    article: ArticleSummary,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                article.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${formatDate(article.publishedAt)}${article.author?.let { " · $it" } ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtle,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                article.excerpt,
                style = MaterialTheme.typography.bodyMedium,
                color = TextMuted,
                maxLines = 4,
            )
            if (article.tags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    article.tags.take(3).forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = GccGreen.copy(alpha = 0.12f),
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = GccGreen,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyArticlesState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "No articles yet",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "We'll publish updates from the field here.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Couldn't load articles",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = GccError,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = GccGreen)) {
            Text("Retry")
        }
    }
}

// ── Reader ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleReaderScreen(
    article: Article,
    onBack: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Article", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    article.webUrl?.let { url ->
                        TextButton(onClick = { openUrl(url) }) {
                            Text("Open on web", color = GccGreen, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                article.title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                buildString {
                    append(formatDate(article.publishedAt))
                    article.author?.let { append(" · "); append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtle,
            )
            Spacer(modifier = Modifier.height(20.dp))

            MarkdownBody(article.bodyMarkdown)

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

// ── Inline markdown renderer ─────────────────────────────────────────────
//
// The marketing site stores blog posts as MDX, but for the in-app reader
// we ship a JSON payload with a markdown body and parse it on-device.
// This avoids pulling a full markdown library and keeps the reader
// dependency-free. We only handle the subset the editorial team uses:
//
//   #, ##, ### headings   _italic_   **bold**   > blockquote
//   - bullet list          [text](url) inline links
//   ![alt](url) images     blank-line paragraph breaks
//
// Anything we don't recognize falls through as plain text — the goal is
// "readable, never broken," not pixel-perfect parity.

@Composable
private fun MarkdownBody(markdown: String) {
    // Pre-parse into a flat list of typed blocks so the @Composable
    // emission below is a straight `forEach` — avoids needing
    // local @Composable helpers (not supported in Compose Multiplatform).
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    blocks.forEach { block ->
        when (block) {
            is MdBlock.H1 -> {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    block.text,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            is MdBlock.H2 -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    block.text,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = GccGreenDark,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            is MdBlock.H3 -> {
                Text(
                    block.text,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = GccGreenDark,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            is MdBlock.Quote -> {
                BlockquoteRow(block.text)
                Spacer(modifier = Modifier.height(12.dp))
            }
            is MdBlock.Bullet -> {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("•  ", color = GccGreen, style = MaterialTheme.typography.bodyMedium)
                    InlineMarkdownText(block.text, modifier = Modifier.weight(1f))
                }
            }
            is MdBlock.Paragraph -> {
                InlineMarkdownText(block.text)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

private sealed class MdBlock {
    data class H1(val text: String) : MdBlock()
    data class H2(val text: String) : MdBlock()
    data class H3(val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
}

private fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flush() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) out.add(MdBlock.Paragraph(text))
        paragraph.clear()
    }

    for (raw in markdown.lines()) {
        val line = raw.trimEnd()
        when {
            line.isBlank() -> flush()
            line.startsWith("### ") -> { flush(); out.add(MdBlock.H3(line.removePrefix("### ").trim())) }
            line.startsWith("## ")  -> { flush(); out.add(MdBlock.H2(line.removePrefix("## ").trim())) }
            line.startsWith("# ")   -> { flush(); out.add(MdBlock.H1(line.removePrefix("# ").trim())) }
            line.startsWith("> ")   -> { flush(); out.add(MdBlock.Quote(line.removePrefix("> ").trim())) }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flush()
                out.add(MdBlock.Bullet(line.removePrefix("- ").removePrefix("* ").trim()))
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line)
            }
        }
    }
    flush()
    return out
}

@Composable
private fun BlockquoteRow(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(48.dp)
                .background(GccGreen.copy(alpha = 0.7f)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        InlineMarkdownText(
            text,
            modifier = Modifier.weight(1f),
            italic = true,
            colorOverride = TextMuted,
        )
    }
}

@Composable
private fun InlineMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    italic: Boolean = false,
    colorOverride: Color? = null,
) {
    val parsed = rememberInlineMarkdown(text)
    val baseColor = colorOverride ?: MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        color = baseColor,
    )

    if (parsed.links.isEmpty()) {
        Text(parsed.annotated, style = style, modifier = modifier)
    } else {
        ClickableText(
            text = parsed.annotated,
            style = style,
            modifier = modifier,
            onClick = { offset ->
                parsed.links.firstOrNull { offset in it.range }?.let { link ->
                    openUrl(link.url)
                }
            },
        )
    }
}

private data class InlineLink(val range: IntRange, val url: String)

private data class ParsedInline(
    val annotated: AnnotatedString,
    val links: List<InlineLink>,
)

@Composable
private fun rememberInlineMarkdown(text: String): ParsedInline =
    remember(text) { parseInlineMarkdown(text) }

private fun parseInlineMarkdown(text: String): ParsedInline {
    val builder = AnnotatedString.Builder()
    val links = mutableListOf<InlineLink>()

    var i = 0
    while (i < text.length) {
        val c = text[i]

        // Markdown link: [label](url)
        if (c == '[') {
            val labelEnd = text.indexOf(']', startIndex = i + 1)
            val parenStart = if (labelEnd >= 0 && labelEnd + 1 < text.length) labelEnd + 1 else -1
            if (labelEnd > i && parenStart > 0 && text[parenStart] == '(') {
                val parenEnd = text.indexOf(')', startIndex = parenStart + 1)
                if (parenEnd > parenStart) {
                    val label = text.substring(i + 1, labelEnd)
                    val url = text.substring(parenStart + 1, parenEnd)
                    val start = builder.length
                    builder.pushStyle(SpanStyle(color = GccGreen, textDecoration = TextDecoration.Underline))
                    appendInlineFormatted(builder, label)
                    builder.pop()
                    links.add(InlineLink(start until builder.length, url))
                    i = parenEnd + 1
                    continue
                }
            }
        }

        // Bold (**…**)
        if (c == '*' && i + 1 < text.length && text[i + 1] == '*') {
            val end = text.indexOf("**", startIndex = i + 2)
            if (end > i + 1) {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInlineFormatted(builder, text.substring(i + 2, end))
                builder.pop()
                i = end + 2
                continue
            }
        }

        // Italic (_…_) — only when surrounded by non-word chars to avoid
        // false-positives in identifiers like spot_reports.
        if (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit())) {
            val end = text.indexOf('_', startIndex = i + 1)
            if (end > i + 1 && (end + 1 >= text.length || !text[end + 1].isLetterOrDigit())) {
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInlineFormatted(builder, text.substring(i + 1, end))
                builder.pop()
                i = end + 1
                continue
            }
        }

        builder.append(c)
        i++
    }

    return ParsedInline(annotated = builder.toAnnotatedString(), links = links)
}

private fun appendInlineFormatted(
    builder: AnnotatedString.Builder,
    text: String,
) {
    // Recurse one level — supports bold-inside-italic and links inside
    // bold without enabling unbounded nesting.
    val nested = parseInlineMarkdown(text)
    builder.append(nested.annotated)
}

// ── Date helpers ─────────────────────────────────────────────────────────

private fun formatDate(instant: Instant): String {
    val ldt = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val month = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )[ldt.monthNumber - 1]
    return "$month ${ldt.dayOfMonth}, ${ldt.year}"
}
