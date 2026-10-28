package me.parham1995.notes.markdown

import java.util.Locale

/**
 * What a status means to the Tasks plugin, whatever its character: `[r]` in
 * a vault that calls it "In Review" is under way exactly as `[/]` is.
 */
enum class StatusType {
    TODO,
    IN_PROGRESS,
    DONE,
    CANCELLED,

    /** A list item drawn as a task that is not something to do. */
    NON_TASK,
    ;

    /** Done, cancelled, or not a thing to do at all; the rest is still open. */
    val isClosed: Boolean get() = this == DONE || this == CANCELLED || this == NON_TASK

    /**
     * The coarse state the reader and the index draw and count by. A non-task
     * is closed, so it counts as cancelled there -- it never sits in a list of
     * things to do.
     */
    val state: TaskState
        get() =
            when (this) {
                TODO -> TaskState.UNCHECKED
                IN_PROGRESS -> TaskState.IN_PROGRESS
                DONE -> TaskState.CHECKED
                CANCELLED, NON_TASK -> TaskState.CANCELLED
            }

    companion object {
        /** `TODO`, `in_progress`, `in progress`; null for anything else. */
        fun parse(name: String): StatusType? {
            val wanted = name.trim().uppercase(Locale.ROOT).replace(' ', '_')
            return entries.firstOrNull { it.name == wanted }
        }
    }
}

/**
 * The character the app writes for a state when it has no other to go on:
 * a row built from a state alone, as the older callers make them.
 */
val TaskState.symbol: Char
    get() =
        when (this) {
            TaskState.CHECKED -> 'x'
            TaskState.CANCELLED -> '-'
            TaskState.IN_PROGRESS -> '/'
            TaskState.UNCHECKED, TaskState.NONE -> ' '
        }

/** One status as the Tasks plugin's settings describe it. */
data class TaskStatus(
    val symbol: Char,
    val name: String,
    /** What a click on the checkbox moves it to, in the plugin. */
    val next: Char,
    val type: StatusType,
) {
    val isOpen: Boolean get() = !type.isClosed

    /** One of the four every vault has, which the app draws without naming. */
    val isDefault: Boolean get() = TaskStatuses.DEFAULT_STATUSES.any { it.symbol == symbol && it.type == type }
}

/**
 * A vault's task statuses: the Tasks plugin's core ones, then its custom ones,
 * read from `.obsidian/plugins/obsidian-tasks-plugin/data.json`.
 *
 * The same rules as Daftar for Linux and the Mac, which read the same vault: a
 * task cannot be open on the phone and closed at the desk.
 */
class TaskStatuses private constructor(
    /** Every status, in the settings' order, each symbol once. */
    val all: List<TaskStatus>,
) {
    private val bySymbol = all.associateBy { it.symbol }

    /**
     * What [symbol] means here. `X` is done unless the settings say otherwise,
     * and a symbol they do not list is still a thing to do, named by itself
     * in brackets -- both the plugin's own rules.
     */
    fun of(symbol: Char): TaskStatus =
        bySymbol[symbol]
            ?: if (symbol == 'X') UPPER_X else TaskStatus(symbol, "[$symbol]", 'x', StatusType.TODO)

    fun isOpen(symbol: Char): Boolean = of(symbol).isOpen

    override fun equals(other: Any?): Boolean = other is TaskStatuses && other.all == all

    override fun hashCode(): Int = all.hashCode()

    override fun toString(): String = "TaskStatuses($all)"

    companion object {
        /** The four every vault has, whatever its settings say or fail to. */
        internal val DEFAULT_STATUSES =
            listOf(
                TaskStatus(' ', "Todo", 'x', StatusType.TODO),
                TaskStatus('x', "Done", ' ', StatusType.DONE),
                TaskStatus('/', "In Progress", 'x', StatusType.IN_PROGRESS),
                TaskStatus('-', "Cancelled", ' ', StatusType.CANCELLED),
            )

        /** `X` is done too, unless the settings say what it is; not listed, so no picker offers it twice. */
        private val UPPER_X = TaskStatus('X', "Done", ' ', StatusType.DONE)

        /** For a vault with no settings, or settings that could not be read. */
        val DEFAULT: TaskStatuses = of(emptyList(), emptyList())

        /**
         * The settings' [core] statuses, then any of the four defaults
         * neither list has, then the [custom] ones. The first of a symbol
         * listed twice wins.
         */
        fun of(
            core: List<TaskStatus>,
            custom: List<TaskStatus>,
        ): TaskStatuses {
            val listed = (core + custom).map { it.symbol }.toSet()
            val out = LinkedHashMap<Char, TaskStatus>()
            (core + DEFAULT_STATUSES.filter { it.symbol !in listed } + custom).forEach {
                out.putIfAbsent(
                    it.symbol,
                    it,
                )
            }
            return TaskStatuses(out.values.toList())
        }
    }
}
