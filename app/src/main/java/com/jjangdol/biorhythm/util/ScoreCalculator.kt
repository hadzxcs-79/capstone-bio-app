package com.jjangdol.biorhythm.util

import com.jjangdol.biorhythm.model.ChecklistItem
import com.jjangdol.biorhythm.model.SafetyLevel
import kotlin.math.absoluteValue

/**
 * 체크리스트 점수, 바이오리듬 인덱스, 측정 점수를 통합하여 최종 안전 점수를 계산
 */
object ScoreCalculator {

    /**
     * 체크리스트에서 답변 별 weight, 문항별 weight 고려 점수 계산
     */
    fun calcChecklistScore(items: List<ChecklistItem>): Int {
        if (items.isEmpty()) return 0

        val totalQuestionWeight = items.sumOf { it.weight }
        if (totalQuestionWeight == 0) return 0

        return items.sumOf { item ->
            val questionWeight = item.weight
            val selected = item.selectedOption ?: return@sumOf 0
            val optionWeights = item.optionWeights ?: listOf(0, 0, 0, 0, 0)

            // 선택지 번호는 1부터 시작하므로 index 보정
            val selectedWeight = optionWeights.getOrNull(selected - 1) ?: 0

            (questionWeight * (selectedWeight / totalQuestionWeight.toDouble())).toInt()
        }
    }


    /**
     * 모든 측정 결과를 포함한 최종 안전 점수 계산
     *
     * @param checklistScore 체크리스트 점수 (0-100)
     * @param tremorScore 손떨림 측정 점수 (0-100)
     * @param pupilScore 피로도 측정 점수 (0-100)
     * @param ppgScore 심박 측정 점수 (0-100)
     * @return 최종 안전 점수 (0-100)
     */
    fun calcFinalSafetyScore(
        checklistScore: Int,
        tremorScore: Float,
        pupilScore: Float,
        ppgScore: Float
    ): Float {
        // 각 항목별 가중치
        val checklistWeight = 0.40f
        val tremorWeight = 0.20f
        val pupilWeight = 0.20f
        val ppgWeight = 0.20f

        // 측정값이 0인 경우 (건너뛴 경우) 가중치 재분배
        var totalWeight = 0f
        var weightedSum = 0f

        if (checklistScore > 0) {
            weightedSum += checklistScore * checklistWeight
            totalWeight += checklistWeight
        }


        if (tremorScore > 0) {
            weightedSum += tremorScore * tremorWeight
            totalWeight += tremorWeight
        }

        if (pupilScore > 0) {
            weightedSum += pupilScore * pupilWeight
            totalWeight += pupilWeight
        }

        if (ppgScore > 0) {
            weightedSum += ppgScore * ppgWeight
            totalWeight += ppgWeight
        }

        // 총 가중치로 나누어 정규화
        return if (totalWeight > 0) {
            (weightedSum / totalWeight).coerceIn(0f, 100f)
        } else {
            0f
        }
    }

    /**
     * 개별 측정 점수를 기반으로 위험 요소 판별
     */
    fun identifyRiskFactors(
        tremorScore: Float,
        pupilScore: Float,
        ppgScore: Float
    ): List<RiskFactor> {
        val riskFactors = mutableListOf<RiskFactor>()

        if (tremorScore < 60 && tremorScore > 0) {
            riskFactors.add(
                RiskFactor(
                    type = "TREMOR",
                    severity = when {
                        tremorScore < 40 -> RiskSeverity.HIGH
                        else -> RiskSeverity.MEDIUM
                    },
                    description = "손떨림이 감지되었습니다"
                )
            )
        }

        if (pupilScore < 60 && pupilScore > 0) {
            riskFactors.add(
                RiskFactor(
                    type = "FATIGUE",
                    severity = when {
                        pupilScore < 40 -> RiskSeverity.HIGH
                        else -> RiskSeverity.MEDIUM
                    },
                    description = "피로도가 높은 상태입니다"
                )
            )
        }

        if (ppgScore < 60 && ppgScore > 0) {
            riskFactors.add(
                RiskFactor(
                    type = "STRESS",
                    severity = when {
                        ppgScore < 40 -> RiskSeverity.HIGH
                        else -> RiskSeverity.MEDIUM
                    },
                    description = "심장 박동이 불안정합니다"
                )
            )
        }

        return riskFactors
    }

    /**
     * 작업 유형별 안전 기준 적용
     */
    fun getWorkTypeSafetyThreshold(workType: WorkType): SafetyThreshold {
        return when (workType) {
            WorkType.PRECISION_WORK -> SafetyThreshold(
                minScore = 80f,
                criticalFactors = listOf("TREMOR"),
                description = "정밀 작업"
            )
            WorkType.HEAVY_MACHINERY -> SafetyThreshold(
                minScore = 85f,
                criticalFactors = listOf("TREMOR", "FATIGUE"),
                description = "중장비 작업"
            )
            WorkType.HIGH_ALTITUDE -> SafetyThreshold(
                minScore = 90f,
                criticalFactors = listOf("TREMOR", "FATIGUE", "STRESS"),
                description = "고소 작업"
            )
            WorkType.DRIVING -> SafetyThreshold(
                minScore = 75f,
                criticalFactors = listOf("FATIGUE"),
                description = "운전 작업"
            )
            WorkType.GENERAL -> SafetyThreshold(
                minScore = 60f,
                criticalFactors = emptyList(),
                description = "일반 작업"
            )
        }
    }

    /**
     * 시간대별 가중치 조정 (교대 근무 고려)
     */
    fun getTimeBasedWeightAdjustment(hour: Int): Float {
        return when (hour) {
            in 0..5 -> 0.8f    // 새벽 근무 시 기준 완화
            in 6..8 -> 0.9f    // 이른 아침
            in 9..17 -> 1.0f   // 주간 근무
            in 18..21 -> 0.95f // 저녁
            in 22..23 -> 0.85f // 야간
            else -> 1.0f
        }
    }

    /**
     * 연속 근무일수에 따른 피로도 보정
     */
    fun getContinuousWorkDayAdjustment(continuousDays: Int): Float {
        return when {
            continuousDays <= 5 -> 1.0f
            continuousDays <= 7 -> 0.95f
            continuousDays <= 10 -> 0.90f
            else -> 0.85f
        }
    }
}

/**
 * 위험 요소 정보
 */
data class RiskFactor(
    val type: String,
    val severity: RiskSeverity,
    val description: String
)

/**
 * 위험 심각도
 */
enum class RiskSeverity {
    LOW, MEDIUM, HIGH
}

/**
 * 작업 유형
 */
enum class WorkType {
    PRECISION_WORK,    // 정밀 작업
    HEAVY_MACHINERY,   // 중장비 조작
    HIGH_ALTITUDE,     // 고소 작업
    DRIVING,          // 운전
    GENERAL           // 일반 작업
}

/**
 * 안전 기준값
 */
data class SafetyThreshold(
    val minScore: Float,
    val criticalFactors: List<String>,
    val description: String
)