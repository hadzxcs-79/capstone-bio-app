package com.jjangdol.biorhythm.vm

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.jjangdol.biorhythm.data.UserRepository
import com.jjangdol.biorhythm.model.*
import com.jjangdol.biorhythm.util.ScoreCalculator
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SafetyCheckViewModel @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val userRepository: UserRepository,
    private val application: Application
) : AndroidViewModel(application) {

    private val _currentSession = MutableStateFlow<SafetyCheckSession?>(null)
    val currentSession: StateFlow<SafetyCheckSession?> = _currentSession.asStateFlow()

    private val _sessionState = MutableLiveData<SessionState>(SessionState.Idle)
    val sessionState: LiveData<SessionState> = _sessionState

    // 체크리스트 상태 관리용 LiveData 추가
    private val _checklistAnswers = MutableLiveData<MutableMap<String, Any>>(mutableMapOf())
    val checklistAnswers: LiveData<MutableMap<String, Any>> = _checklistAnswers

    private val _checklistScore = MutableLiveData<Int>(0)
    val checklistScore: LiveData<Int> = _checklistScore

    private val dateFormatter = DateTimeFormatter.ISO_DATE

    // 체크리스트 점수를 메모리에 저장
    private var savedChecklistScore: Int = 0

    sealed class SessionState {
        object Idle : SessionState()
        object Loading : SessionState()
        data class Success(val message: String) : SessionState()
        data class Error(val message: String) : SessionState()
    }

    private fun getUserEmpNum(): String? {
        val prefs = application.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        val empNum = prefs.getString("emp_num", null)

        return empNum
    }

    private fun getUserId(): String? {
        val prefs = application.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", null)
        val empNum = prefs.getString("emp_num", null)

        Log.d("SafetyCheck-userId", "이름: ${name} 사번: ${empNum}")

        return if (!name.isNullOrEmpty() && !empNum.isNullOrEmpty()) {
            userRepository.getUserId(name, empNum)
        } else {
            null
        }
    }

    private fun getUserProfile(): Pair<String, String>? {
        val prefs = application.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val dept = prefs.getString("dept", "") ?: ""
        val name = prefs.getString("user_name", "") ?: ""

        return if (name.isNotEmpty() && dept.isNotEmpty()) {
            Pair(dept, name)
        } else {
            null
        }
    }

    fun startNewSession(session: SafetyCheckSession) {
        _currentSession.value = session
    }

    fun updateChecklistResults(
        checklistItems: List<ChecklistItem>,
        checklistScore: Int
    ) {
        // 점수를 메모리에 저장
        this.savedChecklistScore = checklistScore

        // LiveData 업데이트
        _checklistScore.value = checklistScore

        _currentSession.value?.let { session ->
            _currentSession.value = session.copy(
                checklistResults = checklistItems
            )
        }
    }

    // 체크리스트 답변 업데이트 메서드
    fun updateChecklistAnswer(questionId: String, answer: Any) {
        val currentAnswers = _checklistAnswers.value ?: mutableMapOf()
        currentAnswers[questionId] = answer
        _checklistAnswers.value = currentAnswers
    }

    // 체크리스트 초기화 메서드
    fun resetChecklist() {
        // 체크리스트 관련 모든 상태 초기화
        _checklistAnswers.value = mutableMapOf()
        _checklistScore.value = 0

        // 저장된 점수도 초기화
        savedChecklistScore = 0

        // 현재 세션의 체크리스트 결과도 초기화
        _currentSession.value?.let { session ->
            _currentSession.value = session.copy(
                checklistResults = emptyList()
            )
        }
    }

    // 체크리스트 완료 상태 확인 메서드
    fun isChecklistCompleted(): Boolean {
        val answers = _checklistAnswers.value ?: return false
        // 필수 질문들이 모두 답변되었는지 확인하는 로직
        // 실제 구현은 체크리스트 항목 수에 따라 조정 필요
        return answers.size >= getRequiredQuestionCount()
    }

    private fun getRequiredQuestionCount(): Int {
        // 실제 체크리스트 필수 질문 수를 반환
        // 이 값은 실제 체크리스트 구조에 맞게 조정하세요
        return 10 // 예시값
    }

    fun addMeasurementResult(result: MeasurementResult) {
        Log.d("SafetyCheckVM", "========== addMeasurementResult ==========")
        Log.d("SafetyCheckVM", "측정 타입: ${result.type}")
        Log.d("SafetyCheckVM", "점수: ${result.score}")
        _currentSession.value?.let { session ->
            val updated = session.measurementResults.toMutableList()
            updated.removeAll { it.type == result.type }
            updated.add(result)
            _currentSession.value = session.copy(measurementResults = updated)
        }
        Log.d("SafetyCheckVM", "현재 세션의 측정 결과 개수: ${_currentSession.value?.measurementResults?.size}")
    }

    /**
     * 세션 정보(사용자 정보, 측정 정보) 종합 및 Firestore 저장 함수 호출
    */
    fun completeSession(onComplete: (SafetyCheckResult) -> Unit) {
        viewModelScope.launch {
            _sessionState.value = SessionState.Loading

            try {
                val session = _currentSession.value ?: throw Exception("세션이 없습니다")
                val userId = getUserId() ?: throw Exception("사용자 정보를 찾을 수 없습니다")
                val userEmpNum = getUserEmpNum() ?: throw Exception("사용자 사번정보를 찾을 수 없습니다")
                val userProfile = getUserProfile() ?: throw Exception("사용자 프로필을 찾을 수 없습니다")

                val (dept, name) = userProfile

                val checklistScore = savedChecklistScore

                val tremorScore = session.measurementResults
                    .find { it.type == MeasurementType.TREMOR }?.score ?: 0f
                val pupilScore = session.measurementResults
                    .find { it.type == MeasurementType.PUPIL }?.score ?: 0f
                val ppgScore = session.measurementResults
                    .find { it.type == MeasurementType.PPG }?.score ?: 0f

                val finalScore = ScoreCalculator.calcFinalSafetyScore(
                    checklistScore = checklistScore,
                    tremorScore = tremorScore,
                    pupilScore = pupilScore,
                    ppgScore = ppgScore
                )

                val safetyLevel = SafetyLevel.fromScore(finalScore)

                val recommendations = generateRecommendations(
                    safetyLevel, tremorScore, pupilScore, ppgScore
                )

                val currentDate = LocalDate.now().format(dateFormatter)

                val result = SafetyCheckResult(
                    userId = userId,
                    empNum = userEmpNum,
                    name = name,
                    dept = dept,
                    checklistScore = checklistScore,
                    tremorScore = tremorScore,
                    pupilScore = pupilScore,
                    ppgScore = ppgScore,
                    finalSafetyScore = finalScore,
                    date = currentDate,
                    recommendations = recommendations
                )

                saveResultToFirestore(result)

                _currentSession.value = session.copy(isCompleted = true)
                _sessionState.value = SessionState.Success("안전 체크 완료")

                onComplete(result)

            } catch (e: Exception) {
                _sessionState.value = SessionState.Error("세션 완료 실패: ${e.message}")
            }
        }
    }

    private fun generateRecommendations(
        level: SafetyLevel,
        tremorScore: Float,
        pupilScore: Float,
        ppgScore: Float
    ): List<String> {
        val recommendations = mutableListOf<String>()

        when (level) {
            SafetyLevel.DANGER -> {
                recommendations.add("즉시 휴식을 취하시고 관리자에게 보고하세요")
                recommendations.add("충분한 수분 섭취와 휴식이 필요합니다")
            }
            SafetyLevel.CAUTION -> {
                recommendations.add("가벼운 스트레칭 후 작업을 시작하세요")
                recommendations.add("주기적으로 휴식을 취하며 작업하세요")
            }
            SafetyLevel.SAFE -> {
                recommendations.add("안전한 상태입니다. 작업을 진행하세요")
            }
        }

        // 개별 측정 결과에 따른 권고사항
        if (tremorScore > 0 && tremorScore < 70) {
            recommendations.add("손떨림이 감지됩니다. 정밀 작업 시 주의하세요")
        }
        if (pupilScore > 0 && pupilScore < 70) {
            recommendations.add("피로도가 높습니다. 충분한 휴식을 취하세요")
        }
        if (ppgScore > 0 && ppgScore < 70) {
            recommendations.add("심박이 불안정합니다. 스트레스 관리가 필요합니다")
        }

        return recommendations
    }

    suspend fun saveResultToFirestore(result: SafetyCheckResult) {
        val today = result.date
        val empNum = result.empNum
        val currentTime = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(java.util.Date())

        // 명시적으로 Map으로 변환
        val dataMap = hashMapOf(
            "userId" to result.userId,
            "empNum" to result.empNum,
            "name" to result.name,
            "dept" to result.dept,
            "checklistScore" to result.checklistScore,
            "tremorScore" to result.tremorScore,
            "pupilScore" to result.pupilScore,
            "ppgScore" to result.ppgScore,
            "finalSafetyScore" to result.finalSafetyScore,
            "safetyLevel" to result.safetyLevel.name,
            "date" to result.date,
            "time" to currentTime,
            "timestamp" to result.timestamp,
            "recommendations" to result.recommendations
        )

        try {
            // 오늘 날짜의 entries 컬렉션에서 해당 사번으로 시작하는 문서 조회
            val attemptsSnapshot = firestore.collection("results")
                .document(today)
                .collection("entries")
                .whereEqualTo("empNum", empNum)
                .get()
                .await()

            val attemptCount = attemptsSnapshot.size() + 1
            val documentId = "${empNum}_${attemptCount}"  // 예: "001660_1", "001660_2"

            // results/날짜/entries/사번_횟수 경로에 저장
            firestore.collection("results")
                .document(today)
                .collection("entries")
                .document(documentId)
                .set(dataMap)
                .await()

        } catch (e: Exception) {
            Log.e("SafetyCheck", "Firestore 저장 중 에러", e)
            Log.e("SafetyCheck", "에러 타입: ${e.javaClass.simpleName}")
            Log.e("SafetyCheck", "에러 메시지: ${e.message}")
            throw e
        }
    }



    // 세션 클리어 메서드
    fun clearSession() {
        _currentSession.value = null
        _sessionState.value = SessionState.Idle
        // 체크리스트는 별도 메서드로 초기화하므로 여기서는 세션만 초기화
    }

    // 완전 초기화 메서드 (모든 상태 초기화)
    fun clearAll() {
        clearSession()
        resetChecklist()
    }

    override fun onCleared() {
        super.onCleared()
        clearAll()
    }
}