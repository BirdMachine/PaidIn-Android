package com.birdmachine.paidin

import android.app.Application
import android.text.Html
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class PaidInViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("paidin", 0)

    private val _jobs = MutableStateFlow(loadPersistedJobs().ifEmpty { SampleData.jobs }.map(::restoreStatus))
    val jobs: StateFlow<List<Job>> = _jobs

    private val _rules = MutableStateFlow(SampleData.rules)
    val rules: StateFlow<List<MarketRule>> = _rules

    private val _apiUrl = MutableStateFlow(prefs.getString("api_url", "http://10.0.2.2:8000") ?: "http://10.0.2.2:8000")
    val apiUrl: StateFlow<String> = _apiUrl

    private val _localLocation = MutableStateFlow(prefs.getString("local_location", "Pittsburgh, PA") ?: "Pittsburgh, PA")
    val localLocation: StateFlow<String> = _localLocation

    private val _localRadiusMiles = MutableStateFlow(prefs.getInt("local_radius_miles", 25))
    val localRadiusMiles: StateFlow<Int> = _localRadiusMiles

    private val _scoutStatus = MutableStateFlow(
        if (prefs.contains("last_scout_at")) "Ready — showing saved live results" else "Ready for first live scan"
    )
    val scoutStatus: StateFlow<String> = _scoutStatus

    private val _scoutRunning = MutableStateFlow(false)
    val scoutRunning: StateFlow<Boolean> = _scoutRunning

    fun setStatus(id: String, status: ReviewStatus) {
        _jobs.update { list -> list.map { if (it.id == id) it.copy(status = status) else it } }
        prefs.edit().putString("status_$id", status.name).apply()
        persistJobs(_jobs.value)
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

    fun runScout() {
        if (_scoutRunning.value) return
        _scoutRunning.value = true
        _scoutStatus.value = "Scanning live remote listings…"

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { fetchRemotiveJobs() }
            }.onSuccess { fresh ->
                val priorById = _jobs.value.associateBy { it.id }
                val merged = fresh.map { job ->
                    val old = priorById[job.id]
                    if (old != null) job.copy(status = old.status) else restoreStatus(job)
                }
                _jobs.value = merged
                persistJobs(merged)
                prefs.edit().putLong("last_scout_at", System.currentTimeMillis()).apply()
                _scoutStatus.value = if (merged.isEmpty()) {
                    "Scan finished — no matching live jobs found"
                } else {
                    "Live scan complete — ${merged.size} jobs saved on-device"
                }
            }.onFailure { error ->
                _scoutStatus.value = "Scan failed: ${error.message ?: error.javaClass.simpleName}"
            }
            _scoutRunning.value = false
        }
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
        persistJobs(_jobs.value)
    }

    private fun fetchRemotiveJobs(): List<Job> {
        val searches = listOf("react", "frontend", "typescript")
        val seen = linkedMapOf<String, Job>()
        for (search in searches) {
            val endpoint = "https://remotive.com/api/remote-jobs?search=$search&limit=50"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "PaidIn-Android/0.1")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Remotive returned HTTP ${connection.responseCode}")
                }
                val payload = connection.inputStream.bufferedReader().use { it.readText() }
                val array = JSONObject(payload).optJSONArray("jobs") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val job = remotiveToJob(item) ?: continue
                    seen[job.id] = job
                }
            } finally {
                connection.disconnect()
            }
        }
        return seen.values
            .filter { it.score >= 45 }
            .sortedByDescending { it.score }
            .take(120)
    }

    private fun remotiveToJob(item: JSONObject): Job? {
        val sourceId = item.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = item.optString("title", "Unknown role")
        val company = item.optString("company_name", "Unknown company")
        val location = item.optString("candidate_required_location", "Remote")
        val url = item.optString("url").takeIf { it.isNotBlank() }
        val description = stripHtml(item.optString("description")).take(1200)
        val tagsJson = item.optJSONArray("tags")
        val tags = buildList {
            if (tagsJson != null) for (i in 0 until tagsJson.length()) add(tagsJson.optString(i))
        }.filter { it.isNotBlank() }.take(8)
        val salary = parseSalary(item.optString("salary"))
        val score = scoreJob(title, description, tags, location, salary.second)

        return Job(
            id = "remotive-$sourceId",
            title = title,
            company = company,
            location = location.ifBlank { "Remote" },
            remoteStatus = "Remote",
            salaryMin = salary.first,
            salaryMax = salary.second,
            source = "Remotive",
            sourceIdLabel = "Job ID",
            sourceIdValue = sourceId,
            description = description.ifBlank { "No description supplied." },
            skills = tags,
            score = score,
            importedUrl = url,
        )
    }

    private fun scoreJob(title: String, description: String, tags: List<String>, location: String, salaryMax: Int?): Int {
        val haystack = (title + " " + description + " " + tags.joinToString(" ")).lowercase()
        var score = 40
        if ("react" in haystack) score += 22
        if ("front-end" in haystack || "frontend" in haystack || "front end" in haystack) score += 18
        if ("typescript" in haystack) score += 10
        if ("javascript" in haystack) score += 5
        if ("accessibility" in haystack || "a11y" in haystack) score += 5
        if ("python" in haystack || "flask" in haystack) score += 4
        if ("senior" in title.lowercase() || "staff" in title.lowercase()) score += 5
        if ("manager" in title.lowercase() || "director" in title.lowercase() || "people manager" in haystack) score -= 35
        val loc = location.lowercase()
        if ("usa" in loc || "united states" in loc || "worldwide" in loc || "anywhere" in loc) score += 4
        if (salaryMax != null && salaryMax >= 90_000) score += 4
        return score.coerceIn(0, 99)
    }

    private fun parseSalary(raw: String): Pair<Int?, Int?> {
        if (raw.isBlank()) return null to null
        val nums = Regex("(?:USD|\\$)?\\s*([0-9]{2,3}(?:,[0-9]{3})+|[0-9]{5,6})")
            .findAll(raw)
            .mapNotNull { it.groupValues.getOrNull(1)?.replace(",", "")?.toIntOrNull() }
            .toList()
        return when {
            nums.size >= 2 -> nums.minOrNull() to nums.maxOrNull()
            nums.size == 1 -> nums.first() to nums.first()
            else -> null to null
        }
    }

    private fun stripHtml(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY)
        .toString()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun restoreStatus(job: Job): Job {
        val saved = prefs.getString("status_${job.id}", null)
        return job.copy(status = saved?.let { runCatching { ReviewStatus.valueOf(it) }.getOrNull() } ?: job.status)
    }

    private fun persistJobs(list: List<Job>) {
        val array = JSONArray()
        list.forEach { job ->
            array.put(JSONObject().apply {
                put("id", job.id)
                put("title", job.title)
                put("company", job.company)
                put("location", job.location)
                put("remoteStatus", job.remoteStatus)
                put("salaryMin", job.salaryMin ?: JSONObject.NULL)
                put("salaryMax", job.salaryMax ?: JSONObject.NULL)
                put("source", job.source)
                put("sourceIdLabel", job.sourceIdLabel)
                put("sourceIdValue", job.sourceIdValue)
                put("description", job.description)
                put("skills", JSONArray(job.skills))
                put("score", job.score)
                put("status", job.status.name)
                put("importedUrl", job.importedUrl ?: JSONObject.NULL)
            })
        }
        prefs.edit().putString("cached_jobs", array.toString()).apply()
    }

    private fun loadPersistedJobs(): List<Job> {
        val raw = prefs.getString("cached_jobs", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val skillsArray = o.optJSONArray("skills") ?: JSONArray()
                    val skills = buildList {
                        for (j in 0 until skillsArray.length()) add(skillsArray.optString(j))
                    }
                    add(
                        Job(
                            id = o.getString("id"),
                            title = o.optString("title", "Unknown role"),
                            company = o.optString("company", "Unknown company"),
                            location = o.optString("location", "Unknown"),
                            remoteStatus = o.optString("remoteStatus", "Unknown"),
                            salaryMin = if (o.isNull("salaryMin")) null else o.optInt("salaryMin"),
                            salaryMax = if (o.isNull("salaryMax")) null else o.optInt("salaryMax"),
                            source = o.optString("source", "Saved"),
                            sourceIdLabel = o.optString("sourceIdLabel", "ID"),
                            sourceIdValue = o.optString("sourceIdValue", "unknown"),
                            description = o.optString("description", ""),
                            skills = skills,
                            score = o.optInt("score", 50),
                            status = runCatching { ReviewStatus.valueOf(o.optString("status", "NEW")) }.getOrDefault(ReviewStatus.NEW),
                            importedUrl = if (o.isNull("importedUrl")) null else o.optString("importedUrl").takeIf { it.isNotBlank() },
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
