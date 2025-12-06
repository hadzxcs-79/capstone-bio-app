package com.jjangdol.biorhythm.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class HistoryItem(
    val date: String = "",
    val time: String = "",
    val documentId: String = "",
    val checklistScore: Int = 0,
    val tremorScore: Float = 0f,
    val pupilScore: Float = 0f,
    val ppgScore: Float = 0f,
    val finalSafetyScore: Float = 0f,
    val safetyLevel: String = "CAUTION",
    val recommendations: List<String> = emptyList(),
    val timestamp: Long = 0L
) {
    val formattedDate: String
        get() = try {
            val localDate = LocalDate.parse(date, DateTimeFormatter.ISO_DATE)
            localDate.format(DateTimeFormatter.ofPattern("MM월 dd일 (E)"))
        } catch (e: Exception) {
            date
        }

    val safetyLevelEnum: SafetyLevel
        get() = try {
            SafetyLevel.valueOf(safetyLevel)
        } catch (e: Exception) {
            SafetyLevel.CAUTION
        }

    val hasAllMeasurements: Boolean
        get() = tremorScore > 0 && pupilScore > 0 && ppgScore > 0
}