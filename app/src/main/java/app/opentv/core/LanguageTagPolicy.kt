package app.opentv.core

/** Language preference defaults and migration rules, kept Android-free for straightforward testing. */
internal object LanguageTagPolicy {
    const val DEFAULT_LANGUAGE_TAG = "pt-BR"

    private val supportedTags = setOf("", "en", "pt-BR", "pt-PT")

    /** A missing preference defaults to Brazil; blank is an explicit "follow system" choice. */
    fun fromStoredValue(storedTag: String?): String {
        if (storedTag == null) return DEFAULT_LANGUAGE_TAG
        if (storedTag == "pt") return "pt-PT" // Migrate the previous generic Portuguese option to Portugal.
        return storedTag.takeIf { it in supportedTags }
            ?: "" // Retired partial locales follow the system and resolve to complete resources.
    }
}
