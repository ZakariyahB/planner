package app.planner.gcal

import java.security.MessageDigest
import java.util.UUID

/** Source of ids for ChangeSets, changes and tasks. Tests pass a counter so output is deterministic. */
fun interface IdSource {
    fun next(): String

    companion object {
        val Random: IdSource = IdSource { UUID.randomUUID().toString() }

        fun counting(prefix: String = "id"): IdSource {
            var n = 0
            return IdSource { "$prefix-${++n}" }
        }
    }
}

/**
 * Client-generated Google event ids, so a retried insert is a no-op rather than a duplicate.
 * Google requires base32hex characters (lowercase a-v and digits 0-9), 5 to 1024 long.
 */
object EventIds {
    private const val ALPHABET = "0123456789abcdefghijklmnopqrstuv"
    private val VALID = Regex("^[0-9a-v]{5,1024}$")

    fun isValid(id: String): Boolean = VALID.matches(id)

    /** A stable id for [seed] (the same seed always gives the same id): 26 base32hex characters of its SHA-256. */
    fun from(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return encode(digest).take(26)
    }

    fun random(): String = from(UUID.randomUUID().toString())

    private fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = ((buffer shl 8) or (b.toInt() and 0xff)) and 0xffff
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}
