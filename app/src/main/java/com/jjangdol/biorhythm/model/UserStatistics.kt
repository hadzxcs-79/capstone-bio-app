package com.jjangdol.biorhythm.model

data class UserStatistics(
    val userId: String,
    val userName: String,
    val safeCount: Int,      // 70-100점
    val cautionCount: Int,   // 50-69점
    val dangerCount: Int     // 0-49점
)