package me.parham1995.notes.widget

import android.app.Application
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import me.parham1995.notes.R
import me.parham1995.notes.data.PinnedNote
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What the pinned notes widget actually puts on the home screen.
 *
 * The RemoteViews are applied to real views here, because every way this can
 * go wrong compiles: a container left hidden, a collection handed to the one
 * that is not shown, a card that opens nothing.
 */
@RunWith(RobolectricTestRunner::class)
class PinnedNotesWidgetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val notes =
        listOf(
            PinnedNote(11, 1, "Lists/Shopping.md", "Shopping", "☐ bread\n☑ milk"),
            PinnedNote(12, 1, "یادداشت.md", "یادداشت", "سلام دنیا"),
        )

    private fun draw(
        pinned: List<PinnedNote>,
        columns: Int = 1,
        scrolls: Boolean = true,
    ): View =
        PinnedNotesWidget()
            .build(context, pinned, columns, scrolls)
            .apply(context, FrameLayout(context))

    @Test
    fun `nothing pinned says how to pin something`() {
        val root = draw(emptyList())

        val empty = root.findViewById<TextView>(R.id.pinned_empty)
        assertThat(empty.visibility).isEqualTo(View.VISIBLE)
        assertThat(empty.text.toString()).isEqualTo(context.getString(R.string.widget_pinned_empty))
        assertThat(root.findViewById<View>(R.id.pinned_list).visibility).isEqualTo(View.GONE)
    }

    // The collection's contents cannot be looked at from here: applied outside
    // a widget host, Android 14 leaves a RemoteCollectionItems adapter unset.
    // What can go wrong here is which container is shown, and that is asked.
    @Test
    fun `a narrow widget lists its pins`() {
        val root = draw(notes, columns = 1)

        val list = root.findViewById<View>(R.id.pinned_list)
        assertThat(list.visibility).isEqualTo(View.VISIBLE)
        assertThat(root.findViewById<View>(R.id.pinned_grid).visibility).isEqualTo(View.GONE)
        assertThat(root.findViewById<View>(R.id.pinned_empty).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `a wide widget lays them out in a grid`() {
        val root = draw(notes, columns = 2)

        val grid = root.findViewById<View>(R.id.pinned_grid)
        assertThat(grid.visibility).isEqualTo(View.VISIBLE)
        assertThat(root.findViewById<View>(R.id.pinned_list).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `without a collection, each card is drawn and opens its own note`() {
        val root = draw(notes, scrolls = false)

        val cards = root.findViewById<LinearLayout>(R.id.pinned_cards)
        assertThat(cards.visibility).isEqualTo(View.VISIBLE)
        assertThat(cards.childCount).isEqualTo(2)

        val first = cards.getChildAt(0)
        assertThat(first.findViewById<TextView>(R.id.card_title).text.toString()).isEqualTo("Shopping")
        assertThat(first.findViewById<TextView>(R.id.card_body).text.toString()).isEqualTo("☐ bread\n☑ milk")

        cards
            .getChildAt(1)
            .findViewById<View>(R.id.card)
            .performClick()
        val started = shadowOf(context as Application).nextStartedActivity
        assertThat(started.getLongExtra(RecentNotesWidget.EXTRA_NOTE, 0)).isEqualTo(12L)
    }

    @Test
    fun `columns follow the width`() {
        assertThat(columnsForWidth(0)).isEqualTo(1)
        assertThat(columnsForWidth(250)).isEqualTo(1)
        assertThat(columnsForWidth(380)).isEqualTo(2)
    }

    @Test
    fun `a fixed column holds what fits, and never none`() {
        assertThat(cardsForHeight(0)).isEqualTo(1)
        assertThat(cardsForHeight(180)).isEqualTo(1)
        assertThat(cardsForHeight(500)).isEqualTo(3)
        assertThat(cardsForHeight(3000)).isEqualTo(6)
    }
}
