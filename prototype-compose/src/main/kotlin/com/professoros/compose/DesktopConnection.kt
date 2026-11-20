package com.professoros.compose

import com.professoros.service.PortalPublishingClient

internal object DesktopConnection {
    private val environmentOrigin = System.getenv("PROFESSOR_OS_SUPABASE_URL")?.takeUnless(String::isBlank)
    private val environmentKey = System.getenv("PROFESSOR_OS_SUPABASE_PUBLISHABLE_KEY")?.takeUnless(String::isBlank)
    private val overrideConfigured = environmentOrigin != null || environmentKey != null
    val origin: String? = if (overrideConfigured) environmentOrigin else System.getProperty("professoros.supabase.url")
    private val key = if (overrideConfigured) environmentKey else System.getProperty("professoros.supabase.publishableKey")

    fun client() = PortalPublishingClient(origin,key)
}
