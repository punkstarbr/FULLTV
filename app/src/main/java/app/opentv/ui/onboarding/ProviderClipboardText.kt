package app.opentv.ui.onboarding

/** Clipboard rules for provider fields. Credentials retain spaces; line breaks are removed at edges. */
internal object ProviderClipboardText {
    fun normalize(raw: String?, preserveWhitespace: Boolean): String? {
        raw ?: return null
        val value = if (preserveWhitespace) raw.trim('\r', '\n') else raw.trim()
        return value.takeIf { it.isNotEmpty() }
    }
}
