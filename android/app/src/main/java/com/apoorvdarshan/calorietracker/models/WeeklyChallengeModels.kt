package com.apoorvdarshan.calorietracker.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Serializable
enum class WeeklyChallengeCategory(val apiValue: String) {
    @SerialName("overall")
    OVERALL("overall"),

    @SerialName("activity")
    ACTIVITY("activity"),

    @SerialName("nutrition")
    NUTRITION("nutrition"),

    @SerialName("consistency")
    CONSISTENCY("consistency"),

    @SerialName("hydration")
    HYDRATION("hydration")
}

@Serializable
enum class ChallengePeriod(val wire: String) {
    @SerialName("daily") DAILY("daily"),
    @SerialName("weekly") WEEKLY("weekly"),
    @SerialName("monthly") MONTHLY("monthly");

    fun startFor(today: LocalDate): LocalDate = when (this) {
        DAILY -> today
        WEEKLY -> WeeklyChallengeWeek.startFor(today)
        MONTHLY -> today.withDayOfMonth(1)
    }
}

@Serializable
data class ChallengePeriodScore(
    val period: ChallengePeriod,
    val periodStart: String,
    val activityDays: Int,
    val nutritionDays: Int,
    val consistencyDays: Int,
    val hydrationDays: Int,
    val balancedDays: Int,
    val longestStreak: Int,
    val acceptedRules: Boolean
) {
    val points: Int get() = activityDays + nutritionDays + consistencyDays + hydrationDays +
        balancedDays + (longestStreak - 1).coerceAtLeast(0)
}

@Serializable
enum class WeeklyChallengeSocialPlatform(val apiValue: String) {
    @SerialName("x")
    X("x"),

    @SerialName("instagram")
    INSTAGRAM("instagram")
}

@Serializable
data class WeeklyChallengeProfileRequest(
    val displayName: String,
    val socialPlatform: WeeklyChallengeSocialPlatform? = null,
    val socialHandle: String? = null
)

@Serializable
data class WeeklyChallengeCreateProfileRequest(
    val displayName: String,
    val socialPlatform: WeeklyChallengeSocialPlatform? = null,
    val socialHandle: String? = null,
    val acceptedRules: Boolean,
    val eligibilityAccepted: Boolean
)

@Serializable
data class WeeklyChallengePublicProfile(
    val participantId: String,
    val displayName: String,
    val socialPlatform: WeeklyChallengeSocialPlatform? = null,
    val socialHandle: String? = null,
    val createdAt: String = "",
    val updatedAt: String = ""
)

/**
 * The complete and only health-related payload uploaded for a challenge week.
 * It intentionally contains no food names, meals, weights, workout details, or
 * individual Health Connect records.
 */
@Serializable
data class WeeklyChallengeAggregate(
    val weekStart: String,
    val overallPoints: Int,
    val activityDays: Int,
    val nutritionDays: Int,
    val consistencyDays: Int,
    val hydrationDays: Int,
    val activityKcal: Int
) {
    fun scoreFor(category: WeeklyChallengeCategory): Int = when (category) {
        WeeklyChallengeCategory.OVERALL -> overallPoints
        WeeklyChallengeCategory.ACTIVITY -> activityDays
        WeeklyChallengeCategory.NUTRITION -> nutritionDays
        WeeklyChallengeCategory.CONSISTENCY -> consistencyDays
        WeeklyChallengeCategory.HYDRATION -> hydrationDays
    }

    companion object {
        fun empty(weekStart: LocalDate): WeeklyChallengeAggregate = WeeklyChallengeAggregate(
            weekStart = weekStart.toString(),
            overallPoints = 0,
            activityDays = 0,
            nutritionDays = 0,
            consistencyDays = 0,
            hydrationDays = 0,
            activityKcal = 0
        )
    }
}

@Serializable
data class WeeklyChallengeLeaderboardRow(
    val rank: Int? = null,
    val participantId: String,
    val displayName: String,
    val socialPlatform: WeeklyChallengeSocialPlatform? = null,
    val socialHandle: String? = null,
    val score: Int,
    val overallPoints: Int,
    val activityDays: Int,
    val nutritionDays: Int,
    val consistencyDays: Int,
    val hydrationDays: Int,
    val activityKcal: Int,
    val updatedAt: String = "",
    val isViewer: Boolean = false,
    val balancedDays: Int? = null,
    val longestStreak: Int? = null
)

@Serializable
data class WeeklyChallengeLeaderboard(
    val weekStart: String,
    val category: WeeklyChallengeCategory,
    val updatedAt: String,
    val rankings: List<WeeklyChallengeLeaderboardRow> = emptyList(),
    /** Supplied even when the viewer's rank isn't in this page. */
    val viewer: WeeklyChallengeLeaderboardRow? = null,
    val offset: Int? = null,
    val totalCount: Int? = null,
    val period: ChallengePeriod? = null
)

@Serializable
enum class WeeklyChallengeReportReason(val apiValue: String) {
    @SerialName("inappropriate_name")
    INAPPROPRIATE_NAME("inappropriate_name"),

    @SerialName("impersonation")
    IMPERSONATION("impersonation"),

    @SerialName("spam")
    SPAM("spam"),

    @SerialName("unsafe_content")
    UNSAFE_CONTENT("unsafe_content"),

    @SerialName("other")
    OTHER("other")
}

@Serializable
data class WeeklyChallengeReportRequest(
    val reportedParticipantId: String,
    val reason: WeeklyChallengeReportReason,
    val details: String? = null
)

object WeeklyChallengeWeek {
    fun startFor(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun endFor(weekStart: LocalDate): LocalDate = weekStart.plusDays(6)
}

object WeeklyChallengeProfileValidator {
    private val containsLetterOrNumber = Regex("[\\p{L}\\p{N}]")
    private val whitespace = Regex("[\\p{Z}\\s]+")
    private val emailLike = Regex("\\b[^\\s@]+@[^\\s@]+\\.[^\\s@]+\\b")
    private val urlLike = Regex(
        "(?:\\b(?:https?://|www\\.)|\\b[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+\\b)",
        RegexOption.IGNORE_CASE
    )
    private val xHandle = Regex("^[A-Za-z0-9_]{1,15}$")
    private val instagramHandle = Regex("^(?!.*\\.\\.)(?!.*\\.$)[A-Za-z0-9._]{1,30}$")
    private val reservedNameTokens = setOf("admin", "administrator", "moderator", "staff", "support")
    private val disallowedNameTokens = setOf(
        "bitch", "chink", "cunt", "faggot", "fuck", "kike", "kkk", "nazi", "nigger",
        "porn", "pornhub", "shit"
    )
    private val disallowedCompactPhrases = setOf("heilhitler", "whitepower")

    fun normalizedDisplayName(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).replace(whitespace, " ").trim()

    fun normalizedHandle(value: String): String = value.trim()

    fun validDisplayName(value: String): Boolean {
        val normalized = normalizedDisplayName(value)
        val codePointCount = normalized.codePointCount(0, normalized.length)
        if (codePointCount !in 2..40 ||
            !containsLetterOrNumber.containsMatchIn(normalized) ||
            !normalized.codePoints().allMatch { allowedDisplayCodePoint(it) } ||
            emailLike.containsMatchIn(normalized) ||
            urlLike.containsMatchIn(normalized)
        ) return false

        val tokens = normalized.lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
        val compact = tokens.joinToString("")
        val impersonatesFudAi = tokens.indices.any { index ->
            tokens[index] == "fud" && tokens.getOrNull(index + 1) == "ai"
        } || compact == "fudai"
        return !impersonatesFudAi &&
            tokens.none { it in reservedNameTokens } &&
            tokens.none { it in disallowedNameTokens } &&
            disallowedCompactPhrases.none { compact.contains(it) }
    }

    fun validHandle(platform: WeeklyChallengeSocialPlatform, value: String): Boolean {
        val normalized = normalizedHandle(value)
        return when (platform) {
            WeeklyChallengeSocialPlatform.X -> xHandle.matches(normalized)
            WeeklyChallengeSocialPlatform.INSTAGRAM -> instagramHandle.matches(normalized)
        }
    }

    fun request(
        displayName: String,
        platform: WeeklyChallengeSocialPlatform?,
        handle: String
    ): WeeklyChallengeProfileRequest? {
        val normalizedName = normalizedDisplayName(displayName)
        if (!validDisplayName(normalizedName)) return null
        if (platform == null) {
            return WeeklyChallengeProfileRequest(displayName = normalizedName)
        }
        val normalizedSocial = normalizedHandle(handle)
        if (!validHandle(platform, normalizedSocial)) return null
        return WeeklyChallengeProfileRequest(
            displayName = normalizedName,
            socialPlatform = platform,
            socialHandle = normalizedSocial
        )
    }

    fun createRequest(
        displayName: String,
        platform: WeeklyChallengeSocialPlatform?,
        handle: String,
        acceptedRules: Boolean,
        eligibilityAccepted: Boolean
    ): WeeklyChallengeCreateProfileRequest? {
        if (!acceptedRules || !eligibilityAccepted) return null
        val update = request(displayName, platform, handle) ?: return null
        return WeeklyChallengeCreateProfileRequest(
            displayName = update.displayName,
            socialPlatform = update.socialPlatform,
            socialHandle = update.socialHandle,
            acceptedRules = true,
            eligibilityAccepted = true
        )
    }

    private fun allowedDisplayCodePoint(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return Character.isLetterOrDigit(codePoint) ||
            type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            codePoint == ' '.code || codePoint == '.'.code || codePoint == '_'.code ||
            codePoint == '\''.code || codePoint == '’'.code || codePoint == '-'.code
    }
}

object WeeklyChallengeReportValidator {
    private val controlCharacters = Regex("\\p{C}+")
    private val whitespace = Regex("[\\p{Z}\\s]+")

    /** Mirrors the service's NFKC/control-character policy without exposing raw health data. */
    fun sanitizedInput(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(controlCharacters, " ")
            .replace(whitespace, " ")

    fun validDetails(value: String): Boolean {
        val sanitized = sanitizedInput(value).trim()
        return sanitized.codePointCount(0, sanitized.length) <= 300
    }

    fun requestDetails(value: String): String? =
        sanitizedInput(value).trim().takeIf { it.isNotEmpty() }
}
