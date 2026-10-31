package io.github.romanvht.byedpi.data

data class StrategyResult(
    val command: String,
    var successCount: Int = 0,
    var totalRequests: Int = 0,
    var currentProgress: Int = 0,
    var isCompleted: Boolean = false,
    val siteResults: MutableList<SiteResult> = mutableListOf(),
    var isExpanded: Boolean = false,
    // Average latency of successful requests in ms, -1 when unknown. Used to rank
    // strategies with equal success by speed (lower is better).
    var avgLatencyMs: Long = -1L
) {
    val successPercentage: Int
        get() = if (totalRequests > 0) (successCount * 100) / totalRequests else 0
}

data class SiteResult(
    val site: String,
    val successCount: Int,
    val totalCount: Int,
    val avgLatencyMs: Long = -1L
)
