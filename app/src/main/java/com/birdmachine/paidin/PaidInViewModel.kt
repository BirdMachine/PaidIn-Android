package com.birdmachine.paidin

import android.app.Application
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
import java.net.URI
import java.net.URL

class PaidInViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("paidin", 0)

    private val initialJobs = prefs.getString("scout_cache", null)
        ?.let { runCatching { parseScoutJobs(it) }.getOrNull() }
        ?.takeIf { it.isNotEmpty() }
        ?: SampleData.jobs

    private val _jobs = MutableStateFlow(initialJobs)
    val jobs: StateFlow<List<Job>> = _jobs

    private val _rules = MutableStateFlow(SampleData.rules)
    val rules: StateFlow<List<MarketRule>> = _rules

    private val _apiUrl = MutableStateFlow(
        prefs.getString("api_url", "https://paidin-scout.YOUR-SUBDOMAIN.workers.dev")
            ?: "https://paidin-scout.YOUR-SUBDOMAIN.workers.dev"
    )
    val apiUrl: StateFlow<String> = _apiUrl

    private val _scoutToken = MutableStateFlow(prefs.getString("scout_token", "") ?: "")
    val scoutToken: StateFlow<String> = _scoutToken

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    private val _syncMessage = MutableStateFlow("Cloud Scout not connected yet")
    val syncMessage: StateFlow<String> = _syncMessage

    fun setStatus(id: String, status: ReviewStatus) {
        _jobs.update { list -> list.map { if (it.id == id) it.copy(status = status) else it } }
        prefs.edit().putString("status_$id", status.name).apply()

        if (_scoutToken.value.isNotBlank() && _apiUrl.value.startsWith("https://")) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    request(
                        method = "PATCH",
                        path = "/api/jobs/${java.net.URLEncoder.encode(id, Charsets.UTF_8.name())}/status",
                        body = JSONObject().put("status", status.name).toString(),
                    )
                }.onFailure { _syncMessage.value = "Saved locally; cloud update failed" }
            }
        }
    }

    fun upsertRule(rule: MarketRule) {
        _rules.update { list -> list.map { if (it.id == rule.id) rule else it } }
    }

    fun setApiUrl(value: String) {
        _apiUrl.value = value.trim().removeSuffix("/")
        prefs.edit().putString("api_url", _apiUrl.value).apply()
    }

    fun setScoutToken(value: String) {
        _scoutToken.value = value.trim()
        prefs.edit().putString("scout_token", _scoutToken.value).apply()
    }

    fun refreshScout() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            _syncMessage.value = "Refreshing Scout feed…"
            try {
                refreshScoutInternal()
            } catch (error: Throwable) {
                _syncMessage.value = error.message ?: "Scout refresh failed"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun runScoutNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            _syncMessage.value = "Scout is searching the live market…"
            try {
                withContext(Dispatchers.IO) { request("POST", "/api/scan") }
                refreshScoutInternal()
            } catch (error: Throwable) {
                _syncMessage.value = error.message ?: "Scout scan failed"
            } finally {
                _syncing.value = false
            }
        }
    }

    private suspend fun refreshScoutInternal() {
        val payload = withContext(Dispatchers.IO) { request("GET", "/api/jobs") }
        val remote = parseScoutJobs(payload)
        prefs.edit().putString("scout_cache", payload).apply()
        _jobs.value = remote
        _syncMessage.value = "Cloud Scout synced · ${remote.size} listings"
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val base = _apiUrl.value.trim().removeSuffix("/")
        val token = _scoutToken.value.trim()
        require(base.startsWith("https://")) { "Scout server must use HTTPS" }
        require(token.isNotBlank()) { "Add your Scout access token in Settings" }

        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
            error(message.ifBlank { "Scout server returned HTTP $code" })
        }
        return text
    }

    fun importSharedText(text: String) {
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        if (_jobs.value.any { it.importedUrl == url }) return
        val host = runCatching { URI(url).host }.getOrNull() ?: "Shared listing"
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
            description = "Queued from Android's Share sheet. Cloud extraction can fill this in later.",
            skills = emptyList(),
            score = 50,
            importedUrl = url,
        )
        _jobs.update { listOf(job) + it }
    }
}

private fun parseScoutJobs(payload: String): List<Job> {
    val root = JSONObject(payload)
    val array = root.optJSONArray("jobs") ?: JSONArray()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val skillsArray = item.optJSONArray("skills") ?: JSONArray()
            val skills = buildList {
                for (skillIndex in 0 until skillsArray.length()) add(skillsArray.optString(skillIndex))
            }
            val status = runCatching {
                ReviewStatus.valueOf(item.optString("status", "NEW"))
            }.getOrDefault(ReviewStatus.NEW)
            val sourceUrl = item.optString("sourceUrl")
            add(
                Job(
                    id = item.getString("id"),
                    title = item.optString("title", "Untitled role"),
                    company = item.optString("company", "Unknown"),
                    location = item.optString("location", "Unknown"),
                    remoteStatus = item.optString("remoteStatus", "Unknown"),
                    salaryMin = item.nullableInt("salaryMin"),
                    salaryMax = item.nullableInt("salaryMax"),
                    source = item.optString("source", "Web"),
                    sourceIdLabel = "Listing",
                    sourceIdValue = sourceUrl,
                    description = item.optString("description"),
                    skills = skills,
                    score = item.optInt("score", 50),
                    status = status,
                    importedUrl = sourceUrl.takeIf { it.startsWith("http") },
                    fitSummary = item.optString("fitSummary").takeIf { it.isNotBlank() },
                )
            )
        }
    }
}

private fun JSONObject.nullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return runCatching { getInt(key) }.getOrNull()
}
