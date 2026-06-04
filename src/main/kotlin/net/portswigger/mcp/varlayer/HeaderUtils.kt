package net.portswigger.mcp.varlayer

/**
 * Helpers for parsing and rewriting HTTP header lines inside textual data
 * (e.g. proxy-history dumps, raw request blobs).
 *
 * Why a hand-rolled line scanner rather than a full HTTP parser? The textual
 * output from Burp tools mixes request blocks, response blocks, separators,
 * and human-readable annotations. A line-oriented rewrite is robust to all
 * of that — it touches only well-formed header lines and leaves everything
 * else byte-identical.
 */
object HeaderUtils {

    /** One line plus its trailing separator. Captures CRLF, LF, CR, or EOF. */
    private val LINE_RE = Regex("""([^\r\n]*)(\r\n|\n|\r|$)""")

    /** RFC-style header name pattern. Strict to avoid false positives like "URL: ..." in prose. */
    private val HEADER_NAME_RE = Regex("""^[A-Za-z][A-Za-z0-9-]*$""")

    /**
     * Walk through `text` line by line. For each line that looks like an
     * HTTP header (`Name: value`), invoke `transform(name, value)`. If the
     * callback returns a non-null replacement, emit `Name: replacement`;
     * otherwise emit the original line unchanged.
     *
     * Original line endings are preserved.
     */
    fun rewriteHeaders(text: String, transform: (name: String, value: String) -> String?): String {
        val sb = StringBuilder(text.length + 64)
        for (m in LINE_RE.findAll(text)) {
            val line = m.groupValues[1]
            val sep = m.groupValues[2]
            if (line.isEmpty() && sep.isEmpty()) break  // EOF sentinel from $ in regex

            val colon = line.indexOf(':')
            if (colon < 1) {
                sb.append(line).append(sep); continue
            }
            val name = line.substring(0, colon).trim()
            if (!HEADER_NAME_RE.matches(name)) {
                sb.append(line).append(sep); continue
            }
            val value = line.substring(colon + 1).trim()
            val replacement = transform(name, value)
            if (replacement == null) {
                sb.append(line).append(sep)
            } else {
                sb.append(name).append(": ").append(replacement).append(sep)
            }
        }
        return sb.toString()
    }
}
