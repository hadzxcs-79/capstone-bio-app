package com.jjangdol.biorhythm.model

/**
 * Firestore에 저장된 한 사람의 체크리스트 + 바이오리듬 + 생체신호 최종 점수 결과
 */
data class ChecklistResult(
    val userId: String = "",
    val empNum: String = "",
    val name: String = "",
    val dept: String = "",
    val date: String = "",
    val time: String = "",

    // 점수 관련
    val checklistScore: Int = 0,
    val finalScore: Int = 0,  // 기존 필드 유지

    // 생체신호 점수들 (실제 DB에 있는 필드들)
    val finalSafetyScore: Int = 0,  // 실제 최종 안전 점수
    val ppgScore: Int = 0,          // 맥박 점수
    val pupilScore: Int = 0,        // 동공 점수
    val tremorScore: Int = 0,       // 손떨림 점수

    // 추가 정보
    val safetyLevel: String = "",   // "SAFE", "CAUTION", "DANGER"
    val recommendations: List<String> = emptyList(),
    val timestamp: Long = 0
)