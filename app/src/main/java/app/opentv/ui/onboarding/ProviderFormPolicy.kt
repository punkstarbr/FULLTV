package app.opentv.ui.onboarding

import app.opentv.data.model.SourceKind

internal enum class ProviderFormIssue {
    URL_REQUIRED,
    USERNAME_REQUIRED,
    PASSWORD_REQUIRED,
    MAC_REQUIRED,
    YOUTUBE_SEARCH_INVALID,
}

/** Keeps required-field rules out of Compose so they can be checked with ordinary JVM tests. */
internal object ProviderFormPolicy {
    fun issues(
        kind: SourceKind,
        url: String,
        username: String,
        password: String,
        mac: String,
        validYouTubeSearchUrl: Boolean,
    ): Set<ProviderFormIssue> = buildSet {
        if (url.isBlank()) add(ProviderFormIssue.URL_REQUIRED)
        when (kind) {
            SourceKind.XTREAM -> {
                if (username.isBlank()) add(ProviderFormIssue.USERNAME_REQUIRED)
                if (password.isBlank()) add(ProviderFormIssue.PASSWORD_REQUIRED)
            }
            SourceKind.M3U -> Unit
            SourceKind.YOUTUBE_LIVE_SEARCH -> {
                if (url.isNotBlank() && !validYouTubeSearchUrl) {
                    add(ProviderFormIssue.YOUTUBE_SEARCH_INVALID)
                }
            }
            SourceKind.STALKER -> if (mac.isBlank()) add(ProviderFormIssue.MAC_REQUIRED)
        }
    }
}
