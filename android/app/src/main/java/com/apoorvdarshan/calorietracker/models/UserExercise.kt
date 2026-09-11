package com.apoorvdarshan.calorietracker.models

import com.apoorvdarshan.calorietracker.data.ExerciseItem
import java.util.UUID

object UserExercise {
    const val ID_PREFIX = "user_exercise_"

    fun newId(): String = "$ID_PREFIX${UUID.randomUUID()}"

    fun isUserExercise(id: String): Boolean = id.startsWith(ID_PREFIX)

    fun photoFilename(exerciseId: String): String {
        val uuid = exerciseId.removePrefix(ID_PREFIX)
        return "exercise_$uuid.jpg"
    }

    /** Fresh on-disk name for a new/replaced custom exercise photo. */
    fun newPhotoFilename(): String = "exercise_${UUID.randomUUID()}.jpg"

    fun isUserPhotoFilename(filename: String): Boolean =
        filename.startsWith("exercise_") && filename.endsWith(".jpg")
}

data class UserExerciseDraft(
    val name: String = "",
    val instructions: String = "",
    val level: String = "Beginner",
    val force: String = "Unspecified",
    val mechanic: String = "Unspecified",
    val category: String = "Strength",
    val equipment: String = "Unspecified",
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList(),
    val photoBytes: ByteArray? = null,
    val removePhoto: Boolean = false
) {
    val trimmedName: String get() = name.trim()

    val instructionLines: List<String>
        get() = instructions.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun toExerciseItem(id: String, imagePaths: List<String>): ExerciseItem = ExerciseItem(
        id = id,
        name = trimmedName,
        level = level,
        imagePaths = imagePaths,
        force = force,
        mechanic = mechanic,
        category = category,
        equipment = equipment,
        primaryMuscles = primaryMuscles,
        secondaryMuscles = secondaryMuscles,
        instructions = instructionLines
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UserExerciseDraft) return false
        return name == other.name &&
            instructions == other.instructions &&
            level == other.level &&
            force == other.force &&
            mechanic == other.mechanic &&
            category == other.category &&
            equipment == other.equipment &&
            primaryMuscles == other.primaryMuscles &&
            secondaryMuscles == other.secondaryMuscles &&
            removePhoto == other.removePhoto &&
            (photoBytes contentEquals other.photoBytes)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + instructions.hashCode()
        result = 31 * result + level.hashCode()
        result = 31 * result + force.hashCode()
        result = 31 * result + mechanic.hashCode()
        result = 31 * result + category.hashCode()
        result = 31 * result + equipment.hashCode()
        result = 31 * result + primaryMuscles.hashCode()
        result = 31 * result + secondaryMuscles.hashCode()
        result = 31 * result + removePhoto.hashCode()
        result = 31 * result + (photoBytes?.contentHashCode() ?: 0)
        return result
    }
}
