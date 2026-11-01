package app.opentv.core

/** Language preference defaults and migration rules, kept Android-free for straightforward testing. */
internal object LanguageTagPolicy {
    const val DEFAULT_LANGUAGE_TAG = "pt-BR"

    /** A missing preference defaults to Brazil; blank is an explicit "follow system" choice. */
    fun fromStoredValue(storedTag: String?): String = when (storedTag) {
        null -> DEFAULT_LANGUAGE_TAG
        "pt" -> "pt-PT" // Migrate the previous generic Portuguese option to Portugal.
        else -> storedTag
    }
}
