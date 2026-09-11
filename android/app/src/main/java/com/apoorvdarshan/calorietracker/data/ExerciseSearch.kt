package com.apoorvdarshan.calorietracker.data

/** Tokenized AND search for the exercise library, with optional query aliases. */
object ExerciseSearch {
    private val aliases = mapOf(
        "cable pushdown" to "Triceps_Pushdown",
        "triceps cable pushdown" to "Triceps_Pushdown",
        "tricep cable pushdown" to "Triceps_Pushdown",
        "tricep pushdown" to "Triceps_Pushdown",
    )

    fun tokens(query: String): List<String> =
        query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun matches(searchableText: String, query: String, exerciseId: String): Boolean {
        val normalizedQuery = query.trim().lowercase()
        val queryTokens = tokens(normalizedQuery)
        if (queryTokens.isEmpty()) return true

        aliases[normalizedQuery]?.let { aliasId ->
            if (aliasId == exerciseId) return true
        }

        return queryTokens.all { searchableText.contains(it) }
    }
}
