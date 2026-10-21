// Modified for Maxit, 2026-10-20.

package com.maximiliano.maxit.tools

internal fun String.decodeHtmlEntities(): String = this
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
