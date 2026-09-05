package com.birdmachine.paidin

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Tab(val label: String) { RADAR("Radar"), RULES("Market Dial"), SETTINGS("Settings") }

@Composable
fun PaidInApp(vm: PaidInViewModel) {
    var tab by remember { mutableStateOf(Tab.RADAR) }
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val apiUrl by vm.apiUrl.collectAsStateWithLifecycle()
    val scoutToken by vm.scoutToken.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val syncMessage by vm.syncMessage.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0077C8),
                        Color(0xFF005B9F),
                        Color(0xFF00366D),
                        Color(0xFF001B4D),
                    )
                )
            )
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                GlassBar {
                    NavigationBar(containerColor = Color.Transparent) {
                        NavigationBarItem(selected = tab == Tab.RADAR, onClick = { tab = Tab.RADAR }, icon = { Icon(Icons.Default.Radar, null) }, label = { Text("Radar") })
                        NavigationBarItem(selected = tab == Tab.RULES, onClick = { tab = Tab.RULES }, icon = { Icon(Icons.Default.Tune, null) }, label = { Text("Dial") })
                        NavigationBarItem(selected = tab == Tab.SETTINGS, onClick = { tab = Tab.SETTINGS }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
                    }
                }
            }
        ) { padding ->
            when (tab) {
                Tab.RADAR -> RadarScreen(
                    jobs = jobs,
                    syncing = syncing,
                    syncMessage = syncMessage,
                    onRefresh = vm::refreshScout,
                    onScan = vm::runScoutNow,
                    onStatus = vm::setStatus,
                    modifier = Modifier.padding(padding),
                )
                Tab.RULES -> RulesScreen(rules, vm::upsertRule, Modifier.padding(padding))
                Tab.SETTINGS -> SettingsScreen(
                    apiUrl = apiUrl,
                    scoutToken = scoutToken,
                    onApiUrl = vm::setApiUrl,
                    onScoutToken = vm::setScoutToken,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun RadarScreen(
    jobs: List<Job>,
    syncing: Boolean,
    syncMessage: String,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
    onStatus: (String, ReviewStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strong = jobs.count { it.score >= 80 }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("PaidIn Scout", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text("Wide Net. Clear Signal. 🐬", color = Color(0xFFD9FAFF), fontWeight = FontWeight.SemiBold)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("${jobs.size}", "discovered", Modifier.weight(1f))
                Metric("$strong", "strong fits", Modifier.weight(1f))
                Metric("${jobs.count { it.status == ReviewStatus.SAVED }}", "saved", Modifier.weight(1f))
            }
        }
        item {
            GlassPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color.White, Color(0xFF39E9FF), Color(0xFF0067C9)))).border(1.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Radar, null, tint = Color(0xFF003C73), modifier = Modifier.size(36.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Cloud Market Radar", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(syncMessage, color = Color(0xFFD5F7FF))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onScan, enabled = !syncing) {
                        Icon(Icons.Default.TravelExplore, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (syncing) "Working…" else "Run Scout")
                    }
                    FilledTonalButton(onClick = onRefresh, enabled = !syncing) {
                        Icon(Icons.Default.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Refresh")
                    }
                }
            }
        }
        items(jobs, key = { it.id }) { job -> JobCard(job, onStatus) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier = Modifier) = GlassPanel(modifier) {
    Text(value, fontSize = 26.sp, fontWeight = FontWeight.Black)
    Text(label, fontSize = 12.sp, color = Color(0xFFD8F9FF))
}

@Composable
private fun JobCard(job: Job, onStatus: (String, ReviewStatus) -> Unit) {
    val context = LocalContext.current
    GlassPanel {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(58.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFB8FF42), Color(0xFF21E6D8), Color(0xFF168EEB)))).border(1.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Text("${job.score}%", fontWeight = FontWeight.Black, color = Color(0xFF003450))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(job.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(job.company, color = Color(0xFFB5FAFF), fontWeight = FontWeight.SemiBold)
                Text("${job.location} • ${job.remoteStatus}", color = Color(0xFFE0FAFF), fontSize = 13.sp)
                job.salaryMax?.let { max ->
                    Text("\$${job.salaryMin ?: 0}–\$$max", color = Color(0xFFCAFF69), fontWeight = FontWeight.Bold)
                }
                job.fitSummary?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFE9FFAF), fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text(job.description, maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = .92f))
                if (job.skills.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    Text(job.skills.joinToString("  •  "), fontSize = 12.sp, color = Color(0xFFD6FAFF))
                }
                Spacer(Modifier.height(6.dp))
                Text("${job.source} · ${job.sourceIdLabel}: ${job.sourceIdValue}", fontSize = 11.sp, color = Color(0xFFB9EFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilledTonalIconButton(onClick = { onStatus(job.id, ReviewStatus.SAVED) }) { Icon(Icons.Default.Bookmark, "Save") }
            FilledTonalIconButton(onClick = { onStatus(job.id, ReviewStatus.APPROVED) }) { Icon(Icons.Default.Check, "Approve") }
            FilledTonalIconButton(onClick = { onStatus(job.id, ReviewStatus.REJECTED) }) { Icon(Icons.Default.Close, "Reject") }
            job.importedUrl?.let { url ->
                FilledTonalIconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Icon(Icons.Default.OpenInNew, "Open") }
            }
            Spacer(Modifier.weight(1f))
            AssistChip(onClick = {}, label = { Text(job.status.name.lowercase().replaceFirstChar { it.uppercase() }) })
        }
    }
}

@Composable
private fun RulesScreen(rules: List<MarketRule>, onUpdate: (MarketRule) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Market Dial", fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("Negotiate with the market instead of hard-coding your mood.", color = Color(0xFFD7F8FF))
        }
        items(rules, key = { it.id }) { rule -> RuleEditor(rule, onUpdate) }
    }
}

@Composable
private fun RuleEditor(rule: MarketRule, onUpdate: (MarketRule) -> Unit) {
    var value by remember(rule.id, rule.value) { mutableStateOf(rule.value) }
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("${rule.kind.name.lowercase()} · ${rule.field} · ${rule.operator}", color = Color(0xFFBDF5FF), fontSize = 12.sp)
            }
            Switch(checked = rule.enabled, onCheckedChange = { onUpdate(rule.copy(enabled = it)) })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Value / range / options") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = rule.allowUnknown, onCheckedChange = { onUpdate(rule.copy(allowUnknown = it)) })
            Text("Allow unknown")
            Spacer(Modifier.weight(1f))
            Button(onClick = { onUpdate(rule.copy(value = value)) }) { Text("Apply") }
        }
    }
}

@Composable
private fun SettingsScreen(
    apiUrl: String,
    scoutToken: String,
    onApiUrl: (String) -> Unit,
    onScoutToken: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftUrl by remember(apiUrl) { mutableStateOf(apiUrl) }
    var draftToken by remember(scoutToken) { mutableStateOf(scoutToken) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.Black)
        }
        item {
            GlassPanel {
                Text("Cloud Scout", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("The hosted Scout keeps hunting even when Mallard and your phone are asleep. The Android app is just one client of the same feed.", color = Color(0xFFD5F7FF))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = draftUrl, onValueChange = { draftUrl = it }, label = { Text("Scout base URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draftToken,
                    onValueChange = { draftToken = it },
                    label = { Text("Scout access token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    onApiUrl(draftUrl.trim())
                    onScoutToken(draftToken.trim())
                }) { Text("Save Scout connection") }
            }
        }
        item {
            GlassPanel {
                Text("Share-sheet intake", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("In any browser: Share → PaidIn. The URL becomes a local pending-extraction job immediately.", color = Color(0xFFD5F7FF))
            }
        }
    }
}

@Composable
private fun GlassBar(content: @Composable () -> Unit) {
    Surface(color = Color(0x660047A0), tonalElevation = 0.dp) { content() }
}

@Composable
private fun GlassPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0x66E8FFFF), Color(0x3311BDE8), Color(0x55004AAB))))
            .border(1.dp, Color.White.copy(alpha = .78f), RoundedCornerShape(24.dp))
            .padding(16.dp),
        content = content
    )
}
