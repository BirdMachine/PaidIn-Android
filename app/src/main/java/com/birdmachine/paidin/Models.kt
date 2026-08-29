package com.birdmachine.paidin

enum class ReviewStatus { NEW, SAVED, APPROVED, REJECTED }
enum class RuleKind { QUALIFIER, DISQUALIFIER, PREFERENCE }

data class Job(
    val id: String,
    val title: String,
    val company: String,
    val location: String,
    val remoteStatus: String,
    val salaryMin: Int?,
    val salaryMax: Int?,
    val source: String,
    val sourceIdLabel: String,
    val sourceIdValue: String,
    val description: String,
    val skills: List<String>,
    val score: Int,
    val status: ReviewStatus = ReviewStatus.NEW,
    val importedUrl: String? = null,
)

data class MarketRule(
    val id: String,
    val name: String,
    val kind: RuleKind,
    val field: String,
    val operator: String,
    val value: String,
    val allowUnknown: Boolean = true,
    val enabled: Boolean = true,
)
