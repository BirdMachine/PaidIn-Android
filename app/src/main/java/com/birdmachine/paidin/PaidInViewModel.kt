package com.birdmachine.paidin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class PaidInViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("paidin", 0)

    private val _jobs = MutableStateFlow(SampleData.jobs.map { job ->
        val saved = prefs.getString("status_${job.id}", null)
        job.copy(status = saved?.let { runCatching { ReviewStatus.valueOf(it) }.getOrNull() } ?: job.status)
    })
    val jobs: StateFlow<List<Job>> = _jobs

    private val _rules = MutableStateFlow(SampleData.rules)
    val rules: StateFlow<List<MarketRule>> = _rules

    private val _apiUrl = MutableStateFlow(prefs.getString("api_url", "http://10.0.2.2:8000") ?: "http://10.0.2.2:8000")
    val apiUrl: StateFlow<String> = _apiUrl

    private val _localLocation = MutableStateFlow(prefs.getString("local_location", "Pittsburgh, PA") ?: "Pittsburgh, PA")
    val localLocation: StateFlow<String> = _localLocation

    private val _localRadiusMiles = MutableStateFlow(prefs.getInt("local_radius_miles", 25))
    val localRadiusMiles: StateFlow<Int> = _localRadiusMiles

    fun setStatus(id: String, status: ReviewStatus) {
        _jobs.update { list -> list.map { if (it.id == id) it.copy(status = status) else it } }
        prefs.edit().putString("status_$id", status.name).apply()
    }

    fun upsertRule(rule: MarketRule) {
        _rules.update { list -> list.map { if (it.id == rule.id) rule else it } }
    }

    fun setApiUrl(value: String) {
        _apiUrl.value = value
        prefs.edit().putString("api_url", value).apply()
    }

    fun setLocalSearch(location: String, radiusMiles: Int) {
        _localLocation.value = location
        _localRadiusMiles.value = radiusMiles.coerceIn(1, 250)
        prefs.edit()
            .putString("local_location", location)
            .putInt("local_radius_miles", _localRadiusMiles.value)
            .apply()
    }

    fun importSharedText(text: String) {
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        if (_jobs.value.any { it.importedUrl == url }) return
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: "Shared listing"
        val job = Job(
            id = "shared-${url.hashCode()}",
            title = "Shared job — pending extraction",
            company = host,
            location = "Unknown",
            remoteStatus = "Unknown",
            salaryMin = null,
            salaryMax = null,
            source = "Android Share",
            sourceIdLabel = "Imported URL",
            sourceIdValue = url,
            description = "Queued from Android's Share sheet. Server extraction can fill this in when sync is connected.",
            skills = emptyList(),
            score = 50,
            importedUrl = url,
        )
        _jobs.update { listOf(job) + it }
    }
}
