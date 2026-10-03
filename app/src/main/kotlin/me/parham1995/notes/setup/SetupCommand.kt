package me.parham1995.notes.setup

import me.parham1995.notes.data.SyncTransport

/**
 * What `adb shell am broadcast` may ask the app to do, parsed from the
 * intent's extras before anything is touched.
 *
 * Pure, so the parsing is tested without a device. Every argument is a
 * string extra: `--es cmd add-vault --es owner x --es repo y`. A vault is
 * named by its label, its repository name or its numeric id, whichever
 * is easiest to type.
 */
sealed interface SetupCommand {
    data object Status : SetupCommand

    data class AddVault(
        val owner: String,
        val repo: String,
        val branch: String?,
        val name: String,
        val transport: SyncTransport?,
    ) : SetupCommand

    data class RemoveVault(
        val vault: String,
    ) : SetupCommand

    data class SetTransport(
        val vault: String,
        val transport: SyncTransport,
    ) : SetupCommand

    /** The vault's public key line, generating the key first when there is none, or always with [replace]. */
    data class Key(
        val vault: String,
        val replace: Boolean,
    ) : SetupCommand

    data class TestKey(
        val vault: String,
    ) : SetupCommand

    data class SetToken(
        val token: String,
    ) : SetupCommand

    data class SetAuthor(
        val name: String,
        val email: String,
    ) : SetupCommand

    data object Sync : SetupCommand

    /** Switches the daily digest on and posts it now, for seeing it without waiting for the hour. */
    data object Digest : SetupCommand

    companion object {
        /** The command the extras describe, or an error naming what is missing. */
        fun parse(extras: Map<String, String?>): Result<SetupCommand> {
            fun need(key: String): String =
                extras[key]?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("missing --es $key")

            fun transport(raw: String?): SyncTransport? =
                when (raw?.trim()?.lowercase()) {
                    null, "" -> null
                    "ssh" -> SyncTransport.SSH
                    "rest" -> SyncTransport.REST
                    else -> throw IllegalArgumentException("transport is ssh or rest")
                }
            return runCatching {
                when (val cmd = extras["cmd"]?.trim()?.lowercase()) {
                    null, "", "status" -> Status
                    "add-vault" ->
                        AddVault(
                            need("owner"),
                            need("repo"),
                            extras["branch"]?.trim()?.ifEmpty {
                                null
                            },
                            extras["name"]?.trim().orEmpty(),
                            transport(extras["transport"]),
                        )
                    "remove-vault" -> RemoveVault(need("vault"))
                    "set-transport" -> SetTransport(need("vault"), transport(need("transport"))!!)
                    "key" ->
                        Key(
                            need("vault"),
                            replace =
                                extras["replace"]?.trim()?.lowercase() in setOf("1", "true", "yes"),
                        )
                    "test-key" -> TestKey(need("vault"))
                    "set-token" -> SetToken(need("token"))
                    "set-author" -> SetAuthor(need("name"), need("email"))
                    "sync" -> Sync
                    "digest" -> Digest
                    else -> throw IllegalArgumentException("unknown cmd '$cmd'; one of $COMMANDS")
                }
            }
        }

        const val COMMANDS =
            "status, add-vault, remove-vault, set-transport, key, test-key, set-token, set-author, sync, digest"
    }
}
