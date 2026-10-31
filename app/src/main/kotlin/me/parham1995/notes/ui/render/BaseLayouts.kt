package me.parham1995.notes.ui.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.parham1995.notes.R
import me.parham1995.notes.markdown.BaseCell
import me.parham1995.notes.markdown.BaseImage
import me.parham1995.notes.markdown.BaseLayout
import me.parham1995.notes.markdown.BaseTable
import me.parham1995.notes.ui.LocalReading
import me.parham1995.notes.ui.image.onPage
import me.parham1995.notes.ui.image.pageIsDark
import me.parham1995.notes.ui.image.rememberVaultImage
import me.parham1995.notes.ui.inScript

/*
 * A base's `cards` and `list` views, as the reader page draws them on the Mac
 * and Linux (`web/src/bases.js`): a card or an item is titled by its note,
 * and shows each other value that is not empty, named by its column.
 */

/**
 * A grid of cards, each at least `cardSize` wide and the row shared out
 * evenly, as the page's `minmax(size, 1fr)`, with the cover on top.
 */
@Composable
internal fun BaseCards(
    table: BaseTable,
    vaultId: Long?,
    onOpen: (path: String) -> Unit,
) {
    if (table.count == 0) {
        Note(stringResource(R.string.base_empty), error = false)
        return
    }
    val scale = LocalReading.current.textScale
    // Counted here, not in the grid: BoxWithConstraints composes its content
    // later, at measure, after the line below it has read the count.
    val groups = shownGroups(table)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(CARD_GAP)) {
        val across = ((maxWidth + CARD_GAP) / (table.layout.cardSize.dp * scale + CARD_GAP)).toInt().coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
            for ((group, rows) in groups) {
                group.name?.let { GroupHead(it, group.rows.size) }
                for (line in rows.chunked(across)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
                        line.forEach { Card(it, table, vaultId, onOpen, Modifier.weight(1f)) }
                        repeat(across - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    Hidden(table.count - groups.sumOf { it.second.size })
}

/** Each group with the rows of it drawn, [MAX_ROWS] in all. */
private fun shownGroups(table: BaseTable): List<Pair<BaseTable.Group, List<BaseTable.Row>>> {
    var left = MAX_ROWS
    return table.groups.mapNotNull { group ->
        if (left <= 0) return@mapNotNull null
        val rows = group.rows.take(left)
        left -= rows.size
        group to rows
    }
}

@Composable
private fun Card(
    row: BaseTable.Row,
    table: BaseTable,
    vaultId: Long?,
    onOpen: (path: String) -> Unit,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier.testTag("base-card"),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            row.image?.let { Cover(it, table.layout, vaultId) }
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title(row.path),
                    style = MaterialTheme.typography.titleSmall.inScript(),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(role = Role.Button) { onOpen(row.path) },
                )
                for ((at, cell) in values(row)) {
                    Text(
                        text = table.columns.getOrNull(at).orEmpty(),
                        style = MaterialTheme.typography.labelSmall.inScript(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = cellText(cell, onOpen),
                        style = MaterialTheme.typography.bodyMedium.inScript(),
                        textAlign = if (cell.numeric) TextAlign.End else TextAlign.Start,
                        maxLines = MAX_VALUE_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** A card's cover, at the view's `imageAspectRatio` (height over width) and `imageFit`. */
@Composable
private fun Cover(
    image: BaseImage,
    layout: BaseLayout,
    vaultId: Long?,
) {
    val box =
        Modifier
            .fillMaxWidth()
            .aspectRatio((1 / layout.imageAspectRatio).toFloat())
    val fit = if (layout.imageFit == "contain") ContentScale.Fit else ContentScale.Crop
    val behind = MaterialTheme.colorScheme.surfaceVariant
    when (image) {
        is BaseImage.Color -> hexColor(image.hex)?.let { Box(box.background(it)) }
        is BaseImage.Url ->
            AsyncImage(
                model = image.url,
                contentDescription = null,
                contentScale = fit,
                modifier = box.background(behind),
            )
        is BaseImage.File -> {
            if (vaultId == null) return
            val context = LocalContext.current
            val dark = pageIsDark()
            val loaded by rememberVaultImage(vaultId, image.path)
            Box(box.background(behind)) {
                loaded.file?.let {
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(context)
                                .data(it)
                                .onPage(dark)
                                .build(),
                        contentDescription = null,
                        contentScale = fit,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }
}

/**
 * The notes as a list: bulleted, numbered or bare (`markers`), each note's
 * values after its name joined by `separator`, or each on its own line under
 * it with `indentProperties`.
 */
@Composable
internal fun BaseList(
    table: BaseTable,
    onOpen: (path: String) -> Unit,
) {
    if (table.count == 0) {
        Note(stringResource(R.string.base_empty), error = false)
        return
    }
    val layout = table.layout
    val mark = layout.separator
    val between = if (mark == "," || mark == ";") "$mark " else " $mark "
    val muted = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))
    val markerWidth = MARKER * LocalReading.current.textScale
    val groups = shownGroups(table)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((group, rows) in groups) {
            group.name?.let { GroupHead(it, group.rows.size) }
            rows.forEachIndexed { number, row ->
                val values = values(row)
                Row(Modifier.fillMaxWidth().testTag("base-item")) {
                    val marker =
                        when (layout.markers) {
                            "number" -> "${number + 1}."
                            "none" -> null
                            else -> "•"
                        }
                    marker?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium.inScript(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(markerWidth),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        val line =
                            buildAnnotatedString {
                                withLink(LinkAnnotation.Clickable(row.path, link) { onOpen(row.path) }) {
                                    append(title(row.path))
                                }
                                if (!layout.indentProperties && values.isNotEmpty()) {
                                    append(" ")
                                    values.forEachIndexed { index, (_, cell) ->
                                        if (index > 0) withStyle(muted) { append(between) }
                                        append(cellText(cell, onOpen, link))
                                    }
                                }
                            }
                        Text(line, style = MaterialTheme.typography.bodyMedium.inScript())
                        if (layout.indentProperties) {
                            for ((at, cell) in values) {
                                Text(
                                    text =
                                        buildAnnotatedString {
                                            withStyle(muted) { append(table.columns.getOrNull(at).orEmpty()) }
                                            append(" ")
                                            append(cellText(cell, onOpen, link))
                                        },
                                    style = MaterialTheme.typography.bodySmall.inScript(),
                                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    Hidden(table.count - groups.sumOf { it.second.size })
}

@Composable
private fun GroupHead(
    name: String,
    count: Int,
) {
    Row(
        Modifier.padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall.inScript(),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Hidden(hidden: Int) {
    if (hidden > 0) Note(pluralStringResource(R.plurals.tasks_query_hidden, hidden, hidden), error = false)
}

@Composable
private fun cellText(
    cell: BaseCell,
    onOpen: (path: String) -> Unit,
    link: TextLinkStyles = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary)),
): AnnotatedString =
    buildAnnotatedString {
        val check = cell.check
        if (check != null) {
            append(if (check) "☑" else "☐")
        } else {
            for (part in cell.parts) {
                val path = part.path
                if (path == null) {
                    append(part.text)
                } else {
                    withLink(LinkAnnotation.Clickable(path, link) { onOpen(path) }) { append(part.text) }
                }
            }
        }
    }

/** The values a card or an item shows: not the note's own name, which is its title, and none empty. */
private fun values(row: BaseTable.Row): List<IndexedValue<BaseCell>> =
    row.cells.withIndex().filter { (_, cell) ->
        val self = cell.parts.singleOrNull()?.path == row.path
        val empty = cell.check == null && cell.parts.all { it.text.isBlank() }
        !self && !empty
    }

private fun title(path: String): String = path.substringAfterLast('/').removeSuffix(".md")

/** `#rgb`, `#rgba`, `#rrggbb` or `#rrggbbaa`, as CSS reads them. */
internal fun hexColor(hex: String): Color? {
    val short = hex.removePrefix("#")
    val digits = if (short.length <= SHORT_HEX) short.map { "$it$it" }.joinToString("") else short
    val value = digits.toLongOrNull(HEX) ?: return null
    return when (digits.length) {
        RGB -> Color(OPAQUE or value)
        RGBA -> Color(((value and BYTE) shl ALPHA_SHIFT) or (value ushr BYTE_BITS))
        else -> null
    }
}

private val CARD_GAP = 8.dp
private val MARKER = 22.dp
private const val MAX_VALUE_LINES = 3
private const val SHORT_HEX = 4
private const val HEX = 16
private const val RGB = 6
private const val RGBA = 8
private const val OPAQUE = 0xFF000000
private const val BYTE = 0xFFL
private const val BYTE_BITS = 8
private const val ALPHA_SHIFT = 24
