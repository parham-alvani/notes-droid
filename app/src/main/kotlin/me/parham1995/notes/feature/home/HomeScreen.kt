package me.parham1995.notes.feature.home

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.parham1995.notes.R
import me.parham1995.notes.obsidian.Beautitab
import me.parham1995.notes.obsidian.Home
import me.parham1995.notes.obsidian.PartOfDay
import me.parham1995.notes.ui.icon.LucideGlyph
import me.parham1995.notes.ui.inScript
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/**
 * The page for no note open: the phone's start screen, and a tablet's pane
 * beside the list until a note is picked. It replaces Obsidian's Beautitab
 * new tab and follows its settings in the vault, as the Mac's home does.
 */
@Composable
fun HomeScreen(
    onOpenNote: (id: Long, heading: String?) -> Unit,
    onSearch: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val picture by viewModel.picture.collectAsStateWithLifecycle()
    val now by rememberMinute()
    HomePage(
        state = state,
        picture = picture,
        now = now,
        onOpenNote = onOpenNote,
        onSearch = onSearch,
    )
}

/** The time, turned over at the top of each minute rather than polled. */
@Composable
private fun rememberMinute() =
    produceState(LocalDateTime.now()) {
        while (true) {
            val next = value.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
            delay(ChronoUnit.MILLIS.between(LocalDateTime.now(), next).coerceAtLeast(0) + 1)
            value = LocalDateTime.now()
        }
    }

/** The page itself, given everything it shows. */
@Composable
internal fun HomePage(
    state: HomeUiState,
    picture: ImageBitmap?,
    now: LocalDateTime,
    onOpenNote: (id: Long, heading: String?) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag(HOME_TAG)) {
        if (picture != null) {
            Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().testTag(PICTURE_TAG),
            )
            Scrim()
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        // At least the window's height, so a short page sits in its middle.
                        .heightIn(min = maxHeight)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            ) {
                if (settings.showTime || settings.showGreeting) Clock(settings, now, wide)
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (settings.showSearch) SearchField(onSearch)
                    val lists =
                        listOfNotNull(
                            state.recent.takeIf { it.isNotEmpty() }?.let { recent ->
                                ListCard(
                                    title = stringResource(R.string.drawer_recent),
                                    icon = "clock",
                                    rows =
                                        recent.map {
                                            HomeRow(
                                                it.title.ifBlank { it.name },
                                                it.path.substringBeforeLast('/', ""),
                                            ) { onOpenNote(it.id, null) }
                                        },
                                )
                            },
                            state.bookmarks.takeIf { it.isNotEmpty() }?.let { marks ->
                                ListCard(
                                    title = stringResource(R.string.bookmarks_title),
                                    icon = "bookmark",
                                    rows =
                                        marks.map {
                                            HomeRow(
                                                it.title,
                                                it.heading.orEmpty(),
                                            ) { onOpenNote(it.noteId, it.heading) }
                                        },
                                )
                            },
                        )
                    if (wide && lists.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            lists.forEach { NoteCard(it, Modifier.weight(1f)) }
                        }
                    } else {
                        lists.forEach { NoteCard(it, Modifier.fillMaxWidth()) }
                    }
                    state.quote?.let { QuoteCard(it) }
                }
            }
        }
    }
}

/**
 * The page's own background colour over the picture, thin at the top and
 * thick at the bottom, so the time reads on a bright sky and the lists on
 * anything -- in either theme, because it is the theme's colour.
 */
@Composable
private fun Scrim() {
    val base = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(base.copy(alpha = 0.2f), base.copy(alpha = 0.35f), base.copy(alpha = 0.7f)),
                ),
            ),
    )
}

@Composable
private fun Clock(
    settings: Beautitab,
    now: LocalDateTime,
    wide: Boolean,
) {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val twentyFour = settings.twentyFourHour ?: DateFormat.is24HourFormat(context)
    // A glow of the page's own colour, as on the Mac: the scrim is thin at the
    // top, and a bright sky behind the time would otherwise wash it out.
    val glow = Shadow(MaterialTheme.colorScheme.background.copy(alpha = 0.7f), blurRadius = 16f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        if (settings.showTime) {
            Text(
                now.format(DateTimeFormatter.ofPattern(if (twentyFour) "HH:mm" else "h:mm a", locale)),
                style =
                    MaterialTheme.typography.displayLarge.copy(
                        fontSize = if (wide) 112.sp else 88.sp,
                        lineHeight = if (wide) 120.sp else 96.sp,
                        fontWeight = FontWeight.Light,
                        shadow = glow,
                    ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                now.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)),
                style = MaterialTheme.typography.titleMedium.copy(shadow = glow),
                // Not the theme's faint variant: on a picture that was too faint to read.
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            )
        }
        if (settings.showGreeting) {
            val part = PartOfDay.of(now.hour)
            Text(
                Home.greeting(settings.greetingText, stringResource(part.word), stringResource(part.greeting)),
                style =
                    MaterialTheme.typography.headlineSmall
                        .inScript()
                        .copy(shadow = glow),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = if (settings.showTime) 12.dp else 0.dp),
            )
        }
    }
}

/**
 * A field that hands what is typed to the search screen: the app's own
 * search is where words are found, so the page does not grow a second one.
 */
@Composable
private fun SearchField(onSearch: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        onSearch(text.trim())
        text = ""
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = CARD_ALPHA),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            LucideGlyph(
                name = "search",
                size = 20.dp,
                contentDescription = stringResource(R.string.nav_search),
                modifier = Modifier.clip(CircleShape).clickable(onClick = submit),
            )
            Box(Modifier.weight(1f).padding(start = 12.dp)) {
                val style =
                    MaterialTheme.typography.bodyLarge.inScript().copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                if (text.isEmpty()) {
                    Text(
                        stringResource(R.string.home_search_hint),
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag(SEARCH_TAG),
                )
            }
        }
    }
}

private class HomeRow(
    val title: String,
    /** Faint after the title: the folder, or the heading a bookmark names. */
    val detail: String,
    val onClick: () -> Unit,
)

private class ListCard(
    val title: String,
    val icon: String,
    val rows: List<HomeRow>,
)

@Composable
private fun NoteCard(
    card: ListCard,
    modifier: Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = CARD_ALPHA),
        modifier = modifier,
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 6.dp).semantics { heading() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LucideGlyph(name = card.icon, size = 16.dp, tint = MaterialTheme.colorScheme.primary)
                Text(
                    card.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            card.rows.forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = row.onClick)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.bodyLarge.inScript(),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (row.detail.isNotEmpty()) {
                        Text(
                            row.detail,
                            style = MaterialTheme.typography.bodySmall.inScript(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteCard(quote: Beautitab.Quote) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = CARD_ALPHA),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "“${quote.text}”",
                style = MaterialTheme.typography.titleMedium.inScript(),
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (quote.author.isNotEmpty()) {
                Text(
                    "— ${quote.author}",
                    style = MaterialTheme.typography.bodyMedium.inScript(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private val PartOfDay.greeting: Int
    get() =
        when (this) {
            PartOfDay.MORNING -> R.string.home_good_morning
            PartOfDay.AFTERNOON -> R.string.home_good_afternoon
            PartOfDay.EVENING -> R.string.home_good_evening
        }

/** The part of the day as `{{greeting}}` in the person's own greeting says it. */
private val PartOfDay.word: Int
    get() =
        when (this) {
            PartOfDay.MORNING -> R.string.home_morning
            PartOfDay.AFTERNOON -> R.string.home_afternoon
            PartOfDay.EVENING -> R.string.home_evening
        }

/** Wide enough for the lists side by side and a larger clock: a tablet, or a phone on its side. */
private val WIDE = 600.dp

/** The cards let the picture through, a little. */
private const val CARD_ALPHA = 0.82f

internal const val HOME_TAG = "home"
internal const val PICTURE_TAG = "home-picture"
internal const val SEARCH_TAG = "home-search"
