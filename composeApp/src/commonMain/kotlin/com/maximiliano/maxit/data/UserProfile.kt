package com.maximiliano.maxit.data

import kotlinx.serialization.Serializable

const val MAX_PROFILE_NAME_CHARS = 200

@Serializable
data class UserProfile(
    // Profile metadata only: never pass this field to prompt construction.
    val fullName: String = "",
    val preferredName: String = "",
) {
    init {
        require(
            listOf(fullName, preferredName).all { name ->
                name.length <= MAX_PROFILE_NAME_CHARS && name.none { it.isISOControl() }
            },
        ) { "Profile names must be single-line text of at most $MAX_PROFILE_NAME_CHARS characters" }
    }
}
