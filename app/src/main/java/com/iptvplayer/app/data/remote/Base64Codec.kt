package com.iptvplayer.app.data.remote

/**
 * Tiny dependency-free Base64 decoder (used for Xtream EPG titles which are
 * base64-encoded). Returns the input unchanged when it cannot be decoded —
 * some providers send plain text.
 */
object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decodeToString(input: String): String {
        val cleaned = input.filter { !it.isWhitespace() }
        if (cleaned.isEmpty()) return input
        val pad = cleaned.count { it == '=' }
        val body = cleaned.filter { it != '=' }
        if (body.length % 4 == 1) return input
        for (c in body) if (ALPHABET.indexOf(c) < 0) return input

        val out = ByteArray(body.length * 6 / 8)
        var buffer = 0
        var bits = 0
        var idx = 0
        for (c in body) {
            buffer = (buffer shl 6) or ALPHABET.indexOf(c)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[idx++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        if (pad > 2) return input
        return String(out, 0, idx, Charsets.UTF_8)
    }
}
