package me.parham1995.notes.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class RepoDto(
    @SerialName("full_name") val fullName: String,
    @SerialName("default_branch") val defaultBranch: String,
    val private: Boolean,
    @SerialName("pushed_at") val pushedAt: String? = null,
    /**
     * What this credential may do here, as GitHub itself reports it.
     *
     * Absent when the request was unauthenticated, which is the same as no
     * write for our purposes.
     */
    val permissions: PermissionsDto? = null,
)

@Serializable
internal data class PermissionsDto(
    val pull: Boolean = false,
    val push: Boolean = false,
    val admin: Boolean = false,
)

@Serializable
internal data class RefDto(
    val ref: String,
    val `object`: RefObjectDto,
)

@Serializable
internal data class RefObjectDto(
    val sha: String,
    val type: String,
)

@Serializable
internal data class TreeDto(
    val sha: String,
    val tree: List<TreeEntryDto>,
    /**
     * GitHub caps a recursive tree at 100,000 entries / 7MB. A vault of a few
     * thousand files is nowhere near that, but a truncated response would
     * silently look like a vault with missing notes, so it is checked.
     */
    val truncated: Boolean = false,
)

@Serializable
internal data class TreeEntryDto(
    val path: String,
    val mode: String,
    val type: String,
    val sha: String? = null,
    val size: Long? = null,
)

@Serializable
internal data class CompareDto(
    @SerialName("total_commits") val totalCommits: Int = 0,
    val files: List<CompareFileDto> = emptyList(),
)

@Serializable
internal data class CompareFileDto(
    val filename: String,
    val status: String,
    val sha: String? = null,
    @SerialName("previous_filename") val previousFilename: String? = null,
)

@Serializable
internal data class ContentsDto(
    val path: String,
    val sha: String,
    val content: String = "",
    /** `base64`, or `none` when the file is over a megabyte and was not inlined. */
    val encoding: String = "base64",
    val size: Long = 0,
)

@Serializable
internal data class PutContentsDto(
    val message: String,
    val content: String,
    val branch: String,
    /** Omitted when creating the file; required, and checked, when replacing one. */
    val sha: String? = null,
    val committer: PersonDto,
    val author: PersonDto,
)

@Serializable
internal data class PersonDto(
    val name: String,
    val email: String,
)

@Serializable
internal data class CommitResultDto(
    val commit: CommitShaDto,
    val content: ContentShaDto? = null,
)

@Serializable
internal data class CommitShaDto(
    val sha: String,
)

@Serializable
internal data class ContentShaDto(
    val sha: String,
)
