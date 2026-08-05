package com.dkrepo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ReAnimePlugin : Plugin() {
    override fun load(context: Context) {
        // Register the Re:ANIME Provider
        registerMainAPI(ReAnimeProvider())
    }
}
