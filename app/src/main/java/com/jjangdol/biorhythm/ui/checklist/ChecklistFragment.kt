package com.jjangdol.biorhythm.ui.checklist

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.data.UserRepository
import com.jjangdol.biorhythm.databinding.FragmentChecklistBinding
import com.jjangdol.biorhythm.model.SafetyCheckSession
import com.jjangdol.biorhythm.util.ScoreCalculator
import com.jjangdol.biorhythm.vm.ChecklistViewModel
import com.jjangdol.biorhythm.vm.SafetyCheckViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.tasks.await

@AndroidEntryPoint
class ChecklistFragment : Fragment(R.layout.fragment_checklist) {

    private var _binding: FragmentChecklistBinding? = null
    private val binding get() = _binding!!

    private val checklistViewModel: ChecklistViewModel by viewModels()
    private val safetyCheckViewModel: SafetyCheckViewModel by activityViewModels()

    @Inject
    lateinit var userRepository: UserRepository

    private lateinit var sessionId: String

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentChecklistBinding.bind(view)

        sessionId = UUID.randomUUID().toString()
        initializeSafetyCheckSession()

        setupRecyclerView()
        setupSubmitButton()
        observeViewModel()

        // 금일 기존 측정 여부 확인
        checkTodayMeasurement()

        binding.ivChecklist.apply {
            alpha = 0f
            scaleX = 0.6f
            scaleY = 0.6f
            rotation = -90f

            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .rotation(0f)
                .setDuration(1000L)
                .start()
        }
    }

    private fun initializeSafetyCheckSession() {
        viewLifecycleOwner.lifecycleScope.launch {
            val userId = getUserEmpNum() ?: return@launch
            val session = SafetyCheckSession(
                sessionId = sessionId,
                userId = userId,
                startTime = System.currentTimeMillis()
            )
            safetyCheckViewModel.startNewSession(session)
        }
    }

    // 금일 측정 여부 확인 함수
    private fun checkTodayMeasurement() {
        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val empNum = prefs.getString("emp_num", null) ?: return@launch

            val today = java.time.LocalDate.now().toString() // e.g. "2025-11-08"

            try {
                // Firestore 경로: results/{today}/entries 컬렉션에서 해당 사번으로 시작하는 문서 조회
                val querySnapshot = Firebase.firestore
                    .collection("results")
                    .document(today)
                    .collection("entries")
                    .whereEqualTo("empNum", empNum)
                    .get()
                    .await()

                if (!querySnapshot.isEmpty) {
                    // 오늘 이미 측정한 데이터 존재
                    showRecheckDialog()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "기록 확인 중 오류: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 글래스 모피즘 오버레이 다이얼로그 보여주는 함수
    private fun showRecheckDialog() {
        // 반투명 블러 오버레이 표시
        binding.glassOverlay.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(250L).start()
        }

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle("금일 측정 완료")
            .setMessage("금일 측정을 이미 마쳤습니다.\n재측정 하시겠습니까?")
            .setPositiveButton("예") { d, _ ->
                d.dismiss()
                binding.glassOverlay.visibility = View.GONE
                // 재측정 진행
                Toast.makeText(requireContext(), "새로운 측정을 시작합니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("아니오") { d, _ ->
                d.dismiss()
                //requireActivity().onBackPressedDispatcher.onBackPressed()
                requireActivity()
                    .findNavController(R.id.navHostFragment)
                    .navigate(R.id.mainFragment)
            }
            .create()

        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)

        dialog.show()
    }


    // 사번 정보 가져오기 및 검증
    private suspend fun getUserEmpNum(): String? {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val userName = prefs.getString("user_name", "") ?: ""
        val empNum = prefs.getString("emp_num", "") ?: ""

        if (userName.isEmpty() || empNum.isEmpty()) return null

        return try {
            // 세션정보와 db 정보 검증 절차
            val docRef = Firebase.firestore.collection("employees").document(empNum)
            val doc = docRef.get().await()
            if (!doc.exists()) return null

            val firestoreName = doc.getString("Name")
            return if (firestoreName == userName) empNum else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun setupRecyclerView() {
        val adapter = ChecklistAdapter(emptyList()) { position, selectedOption ->
            checklistViewModel.answerChanged(position, selectedOption)
        }

        binding.rvChecklist.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
            setHasFixedSize(true)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            checklistViewModel.items.collect { items ->
                adapter.updateItems(items)

                val total = items.size
                val completed = items.count { it.selectedOption != null }
                val rate = if (total > 0) (completed * 100 / total) else 0

                binding.tvTotalCount.text = total.toString()
                binding.tvCompletedCount.text = completed.toString()
                binding.tvCompletionRate.text = "$rate%"

                binding.checklistProgressBar.max = total
                binding.checklistProgressBar.progress = completed

                binding.btnSubmit.isEnabled = items.all { it.selectedOption != null }
            }
        }
    }

    private fun setupSubmitButton() = with(binding) {
        btnSubmit.setOnClickListener { submitChecklistAndProceed() }
    }

    private fun observeViewModel() {
        safetyCheckViewModel.sessionState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is SafetyCheckViewModel.SessionState.Loading -> {
                    binding.loadingOverlay.visibility = View.VISIBLE
                    binding.btnSubmit.isEnabled = false
                }
                is SafetyCheckViewModel.SessionState.Success -> {
                    binding.loadingOverlay.visibility = View.GONE
                    navigateToTremorMeasurement()
                }
                is SafetyCheckViewModel.SessionState.Error -> {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.btnSubmit.isEnabled = true
                    Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                }
                else -> binding.loadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun submitChecklistAndProceed() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                binding.loadingOverlay.visibility = View.VISIBLE
                binding.btnSubmit.isEnabled = false

                val items = checklistViewModel.items.value
                val checklistScore = ScoreCalculator.calcChecklistScore(items)

                safetyCheckViewModel.updateChecklistResults(
                    checklistItems = items,
                    checklistScore = checklistScore,
                )

                navigateToTremorMeasurement()
            } catch (e: Exception) {
                showError("오류가 발생했습니다: ${e.message}")
            }
        }
    }

    private fun navigateToTremorMeasurement() {
        try {
            val bundle = bundleOf("sessionId" to sessionId)
            requireActivity()
                .findNavController(R.id.navHostFragment)
                .navigate(R.id.tremorMeasurementFragment, bundle)
        } catch (e: Exception) {
            showError("화면 이동 실패: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun showError(msg: String) {
        binding.loadingOverlay.visibility = View.GONE
        binding.btnSubmit.isEnabled = true
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
