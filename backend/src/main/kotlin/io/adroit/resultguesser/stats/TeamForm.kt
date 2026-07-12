package io.adroit.resultguesser.stats

import kotlinx.serialization.Serializable

/** Aggregated recent-form goal rates for a team. */
@Serializable
data class TeamForm(
    val teamName: String,
    val matches: Int,
    val avgScored: Double,
    val avgConceded: Double,
)
