// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.data

import kotlinx.serialization.json.Json

val SharedJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}
