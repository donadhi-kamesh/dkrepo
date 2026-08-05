package com.dkrepo

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ReAnimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ReAnimeProvider())
        registerExtractorAPI(FlixCloud())
    }
}
