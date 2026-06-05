package net.portswigger.mcp.varlayer

/**
 * Classifies cookies into auth, tracking, and preference categories,
 * then produces a compact structured summary.
 *
 * Example output:
 *   "auth: session_id(64B) csrf_token(48B); tracking: _ga _gid mp_*; prefs: theme=dark lang=en"
 *
 * Classification heuristics:
 *   - Auth: names containing session, csrf, token, auth, or using __Host-/__Secure- prefix
 *   - Tracking: known analytics prefixes (_ga, _gid, _fbp, _gcl, mp_, intercom, posthog, mixpanel)
 *   - Prefs: everything else. Short values shown inline; long values show name only.
 */
object CookieSummarizer {

    fun summarize(cookieHeaderValue: String): String {
        val cookies = cookieHeaderValue.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val auth = mutableListOf<String>()
        val tracking = mutableListOf<String>()
        val prefs = mutableListOf<String>()

        for (cookie in cookies) {
            val eqIdx = cookie.indexOf('=')
            val name: String
            val value: String
            if (eqIdx > 0) {
                name = cookie.substring(0, eqIdx).trim()
                value = cookie.substring(eqIdx + 1).trim()
            } else {
                name = cookie.trim()
                value = ""
            }

            when {
                isAuthCookie(name) -> {
                    val sizeHint = if (value.length > 8) "(${value.length}B)" else ""
                    auth.add("$name$sizeHint")
                }
                isTrackingCookie(name) -> tracking.add(name)
                else -> {
                    // Short pref values shown inline for context; long ones just name
                    if (value.length in 1..20) prefs.add("$name=$value")
                    else prefs.add(name)
                }
            }
        }

        return buildString {
            if (auth.isNotEmpty()) append("auth: ${auth.joinToString(" ")}")
            if (tracking.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append("tracking: ${tracking.joinToString(" ")}")
            }
            if (prefs.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append("prefs: ${prefs.joinToString(" ")}")
            }
            if (isEmpty()) append("${cookies.size} cookie(s)")
        }
    }

    private fun isAuthCookie(name: String): Boolean {
        val l = name.lowercase()
        return l.contains("session") || l.contains("csrf") ||
               l.contains("token") || l.contains("auth") ||
               l.contains("jwt") || l.contains("sid") ||
               l.startsWith("__host-") || l.startsWith("__secure-")
    }

    private fun isTrackingCookie(name: String): Boolean {
        val l = name.lowercase()
        return l.startsWith("_ga") || l.startsWith("_gid") ||
               l.startsWith("_fbp") || l.startsWith("_fbc") ||
               l.startsWith("_gcl") || l.startsWith("mp_") ||
               l.contains("mixpanel") || l.contains("posthog") ||
               l.contains("intercom") || l.contains("amplitude") ||
               l.contains("hubspot") || l.startsWith("ph_")
    }
}
