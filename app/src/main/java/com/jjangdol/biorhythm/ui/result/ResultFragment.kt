package com.jjangdol.biorhythm.ui.result

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.Navigation
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.navOptions
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.data.UserRepository
import com.jjangdol.biorhythm.databinding.FragmentResultBinding
import com.jjangdol.biorhythm.model.SafetyLevel
import com.jjangdol.biorhythm.util.ScoreCalculator
import com.jjangdol.biorhythm.vm.SafetyCheckViewModel
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class ResultFragment : Fragment(R.layout.fragment_result) {

    private var _binding: FragmentResultBinding? = null
    private val binding get() = _binding!!

    private val args: ResultFragmentArgs by navArgs()
    private val safetyCheckViewModel: SafetyCheckViewModel by activityViewModels()

    @Inject
    lateinit var userRepository: UserRepository

    private val db = Firebase.firestore

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentResultBinding.bind(view)

        val sessionId = args.sessionId
        val recordDate = arguments?.getString("recordDate")
        val documentId = arguments?.getString("documentId")  // 추가

        if (sessionId != null) {
            val session = safetyCheckViewModel.currentSession.value
            if (session != null) {
                displaySessionResults(session)
            }
        } else if (recordDate != null && documentId != null) {
            loadResultsByDateAndId(recordDate, documentId)  // 수정
        }

        setupButtons()
    }

    private fun loadResultsByDateAndId(date: String, documentId: String) {
        db.collection("results")
            .document(date)
            .collection("entries")
            .document(documentId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    displayResults(document)
                } else {
                    Toast.makeText(requireContext(), "해당 결과를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "결과 로드 실패", Toast.LENGTH_SHORT).show()
            }
    }

    private fun displaySessionResults(session: com.jjangdol.biorhythm.model.SafetyCheckSession) {
        val checklistScore = safetyCheckViewModel.checklistScore.value ?: 0
        val tremorScore = session.measurementResults.find { it.type.name == "TREMOR" }?.score ?: 0f
        val pupilScore = session.measurementResults.find { it.type.name == "PUPIL" }?.score ?: 0f
        val ppgScore = session.measurementResults.find { it.type.name == "PPG" }?.score ?: 0f

        val finalScore = com.jjangdol.biorhythm.util.ScoreCalculator.calcFinalSafetyScore(
            checklistScore, tremorScore, pupilScore, ppgScore
        )
        val safetyLevel = com.jjangdol.biorhythm.model.SafetyLevel.fromScore(finalScore)

        binding.tvChecklistScore.text = checklistScore.toString()
        binding.tvTremorScore.text = "${tremorScore.toInt()}점"
        binding.tvPupilScore.text = "${pupilScore.toInt()}점"
        binding.tvPpgScore.text = "${ppgScore.toInt()}점"
        binding.tvFinalScore.text = "${finalScore.toInt()}점"
        binding.tvSafetyLevel.text = safetyLevel.displayName
        binding.tvSafetyLevel.setTextColor(Color.parseColor(safetyLevel.color))

        // 안전 레벨에 따른 아이콘 표시
        binding.ivSafetyStatus.setImageResource(
            when (safetyLevel) {
                SafetyLevel.SAFE -> R.drawable.ic_check_circle
                SafetyLevel.CAUTION -> R.drawable.ic_warning
                SafetyLevel.DANGER -> R.drawable.ic_error
            }
        )

        setupRadarChart(checklistScore, tremorScore, pupilScore, ppgScore)
        setupBarChart(checklistScore, tremorScore, pupilScore, ppgScore, finalScore)

        val riskFactors = ScoreCalculator.identifyRiskFactors(tremorScore, pupilScore, ppgScore)
        if (riskFactors.isNotEmpty()) {
            binding.riskFactorLayout.visibility = View.VISIBLE
            binding.tvRiskFactors.text = riskFactors.joinToString("\n") {
                "• ${it.description} (${it.severity})"
            }
        }
    }

    private fun getUserEmpNum(): String? {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", null)
        val empNum = prefs.getString("emp_num", null)

        return if (!name.isNullOrEmpty() && !empNum.isNullOrEmpty()) {
            empNum
        } else {
            null
        }
    }

    private fun displayResults(document: com.google.firebase.firestore.DocumentSnapshot) {
        val checklistScore = document.getLong("checklistScore")?.toInt() ?: 0
        val tremorScore = document.getDouble("tremorScore")?.toFloat() ?: 0f
        val pupilScore = document.getDouble("pupilScore")?.toFloat() ?: 0f
        val ppgScore = document.getDouble("ppgScore")?.toFloat() ?: 0f
        val finalScore = document.getDouble("finalSafetyScore")?.toFloat() ?: 0f
        val safetyLevel = SafetyLevel.valueOf(
            document.getString("safetyLevel") ?: "CAUTION"
        )
        val recommendations = document.get("recommendations") as? List<String> ?: emptyList()

        // 기본 점수 표시
        binding.tvChecklistScore.text = checklistScore.toString()

        // 측정 점수 표시
        binding.tvTremorScore.text = if (tremorScore > 0) "${tremorScore.toInt()}점" else "미측정"
        binding.tvPupilScore.text = if (pupilScore > 0) "${pupilScore.toInt()}점" else "미측정"
        binding.tvPpgScore.text = if (ppgScore > 0) "${ppgScore.toInt()}점" else "미측정"

        // 최종 안전 점수
        binding.tvFinalScore.text = "${finalScore.toInt()}점"
        binding.tvSafetyLevel.text = safetyLevel.displayName
        binding.tvSafetyLevel.setTextColor(Color.parseColor(safetyLevel.color))

        // 안전 레벨에 따른 아이콘 표시
        binding.ivSafetyStatus.setImageResource(
            when (safetyLevel) {
                SafetyLevel.SAFE -> R.drawable.ic_check_circle
                SafetyLevel.CAUTION -> R.drawable.ic_warning
                SafetyLevel.DANGER -> R.drawable.ic_error
            }
        )

        // 권고사항 표시
        if (recommendations.isNotEmpty()) {
            binding.recommendationLayout.visibility = View.VISIBLE
            binding.tvRecommendations.text = recommendations.joinToString("\n• ", "• ")
        }

        // 차트 설정
        setupRadarChart(checklistScore, tremorScore, pupilScore, ppgScore)
        setupBarChart(checklistScore, tremorScore, pupilScore, ppgScore, finalScore)

        // 위험 요소 분석
        val riskFactors = ScoreCalculator.identifyRiskFactors(tremorScore, pupilScore, ppgScore)
        if (riskFactors.isNotEmpty()) {
            binding.riskFactorLayout.visibility = View.VISIBLE
            binding.tvRiskFactors.text = riskFactors.joinToString("\n") {
                "• ${it.description} (${it.severity})"
            }
        }
    }

    private fun setupRadarChart(
        checklist: Int, tremor: Float, pupil: Float, ppg: Float
    ) {
        val entries = listOf(
            RadarEntry(checklist.toFloat()),
            RadarEntry(tremor),
            RadarEntry(pupil),
            RadarEntry(ppg)
        )

        val dataSet = RadarDataSet(entries, "안전 지표").apply {
            color = Color.parseColor("#2196F3")
            fillColor = Color.parseColor("#2196F3")
            setDrawFilled(true)
            fillAlpha = 100
            lineWidth = 2f
            setDrawHighlightCircleEnabled(true)
            setDrawHighlightIndicators(false)
        }

        binding.radarChart.apply {
            data = RadarData(dataSet)
            description.isEnabled = false
            webLineWidth = 1f
            webColor = Color.LTGRAY
            webLineWidthInner = 1f
            webColorInner = Color.LTGRAY
            webAlpha = 100

            xAxis.apply {
                textSize = 12f
                yOffset = 0f
                xOffset = 0f
                valueFormatter = IndexAxisValueFormatter(
                    listOf("체크리스트", "손떨림", "피로도", "심박")
                )
            }

            yAxis.apply {
                setLabelCount(5, false)
                textSize = 9f
                axisMinimum = 0f
                axisMaximum = 100f
                setDrawLabels(false)
            }

            legend.isEnabled = false
            invalidate()
        }
    }

    private fun setupBarChart(
        checklist: Int, tremor: Float,
        pupil: Float, ppg: Float, final: Float
    ) {
        val entries = listOf(
            BarEntry(0f, checklist.toFloat()),
            BarEntry(1f, tremor),
            BarEntry(2f, pupil),
            BarEntry(3f, ppg),
            BarEntry(4f, final)
        )

        val colors = listOf(
            Color.parseColor("#4CAF50"),
            Color.parseColor("#FF9800"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#F44336"),
            Color.parseColor("#00BCD4")
        )

        val dataSet = BarDataSet(entries, "점수").apply {
            this.colors = colors
            valueTextSize = 12f
        }

        binding.barChart.apply {
            data = BarData(dataSet)
            description.isEnabled = false
            setDrawGridBackground(false)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                valueFormatter = IndexAxisValueFormatter(
                    listOf("체크리스트", "손떨림", "피로도", "심박", "최종")
                )
                setDrawGridLines(false)
            }

            axisLeft.apply {
                axisMinimum = 0f
                axisMaximum = 100f
            }

            axisRight.isEnabled = false
            legend.isEnabled = false
            animateY(1000)
            invalidate()
        }
    }

    private fun setupButtons() {
        // sessionId가 있으면 측정 완료 후 결과, 없으면 바텀네비에서 접근
        val isFromMeasurement = args.sessionId != null

        if (isFromMeasurement) {
            // 측정 완료 후 결과 - 홈과 기록 버튼만 표시 (다시 측정 버튼 제거)
            binding.btnHome.visibility = View.VISIBLE
            binding.btnRetry.visibility = View.GONE  // 다시 측정 버튼 숨기기
            binding.btnHistory.visibility = View.VISIBLE

            // 홈으로 가기 버튼 - 체크리스트 초기화하고 메인 화면으로 이동
            binding.btnHome.setOnClickListener {
                try {
                    // 1. 세션과 체크리스트 상태 초기화
                    safetyCheckViewModel.clearSession()
                    safetyCheckViewModel.resetChecklist() // 체크리스트 초기화 메서드 호출

                    // 2. 메인 화면(홈)으로 이동 (바텀 네비게이션이 보이는 화면)
                    findNavController().navigate(
                        R.id.mainFragment,
                        null,
                        androidx.navigation.navOptions {
                            popUpTo(0) { inclusive = true }  // 모든 백스택 정리
                            launchSingleTop = true
                        }
                    )
                } catch (e: Exception) {
                    try {
                        // 대안: 백스택을 모두 정리하고 메인으로 이동
                        while (findNavController().popBackStack()) {
                            // 계속 뒤로가기를 시도
                        }
                        // 세션 및 체크리스트 초기화
                        safetyCheckViewModel.clearSession()
                        safetyCheckViewModel.resetChecklist()
                        // 메인으로 이동
                        findNavController().navigate(R.id.mainFragment)
                    } catch (e2: Exception) {
                        Toast.makeText(requireContext(), "홈으로 이동 중 오류가 발생했습니다", Toast.LENGTH_SHORT).show()
                    }
                }
            }

        } else {
            // 바텀네비에서 접근 - 기록보기만 표시
            binding.btnHome.visibility = View.GONE
            binding.btnRetry.visibility = View.GONE
            binding.btnHistory.visibility = View.VISIBLE
        }

        // 기록보기 버튼
        binding.btnHistory.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_result_to_history)
            } catch (e: Exception) {
                Log.e("ResultFragment", "Navigation error", e)
                Toast.makeText(requireContext(), "기록 화면으로 이동할 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // 세션 클리어 - 측정 완료 후에만
        if (args.sessionId != null) {
            safetyCheckViewModel.clearSession()
        }
    }
}