package com.jjangdol.biorhythm.ui.history

import android.app.DatePickerDialog
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.data.UserRepository
import com.jjangdol.biorhythm.databinding.FragmentHistoryBinding
import com.jjangdol.biorhythm.model.HistoryItem
import com.jjangdol.biorhythm.model.SafetyLevel
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class HistoryFragment : Fragment(R.layout.fragment_history) {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var userRepository: UserRepository

    private lateinit var historyAdapter: HistoryAdapter
    private val db = Firebase.firestore
    private val dateFormatter = DateTimeFormatter.ISO_DATE

    private var allHistoryItems = mutableListOf<HistoryItem>()
    private var filteredItems = mutableListOf<HistoryItem>()

    private var selectedSafetyLevel: SafetyLevel? = null
    private var selectedStartDate: LocalDate? = null
    private var selectedEndDate: LocalDate? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHistoryBinding.bind(view)

        setupUI()
        setupRecyclerView()
        setupFilters()
        loadHistoryData()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            try {
                findNavController().navigateUp()
            } catch (e: Exception) {
                Log.e("HistoryFragment", "Navigation error", e)
            }
        }
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter { historyItem, documentId ->
            try {
                val bundle = Bundle().apply {
                    putString("recordDate", historyItem.date)
                    putString("documentId", documentId)  // 문서 ID
                }
                findNavController().navigate(
                    R.id.action_history_to_result,
                    bundle
                )
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "결과 화면으로 이동할 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
        binding.recyclerView.apply {
            adapter = historyAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupFilters() {
        // 안전도 필터
        binding.chipGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedSafetyLevel = when (checkedId) {
                R.id.chipSafe -> SafetyLevel.SAFE
                R.id.chipCaution -> SafetyLevel.CAUTION
                R.id.chipDanger -> SafetyLevel.DANGER
                else -> null
            }
            applyFilters()
        }

        // 날짜 필터
        binding.btnDateFilter.setOnClickListener {
            showDateRangePicker()
        }
    }

    private fun showDateRangePicker() {
        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentDay = calendar.get(Calendar.DAY_OF_MONTH)

        // 시작 날짜 선택
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                selectedStartDate = LocalDate.of(year, month + 1, dayOfMonth)

                // 종료 날짜 선택
                DatePickerDialog(
                    requireContext(),
                    { _, endYear, endMonth, endDayOfMonth ->
                        selectedEndDate = LocalDate.of(endYear, endMonth + 1, endDayOfMonth)

                        if (selectedStartDate!!.isAfter(selectedEndDate)) {
                            Toast.makeText(requireContext(), "종료 날짜가 시작 날짜보다 빠릅니다", Toast.LENGTH_SHORT).show()
                            selectedStartDate = null
                            selectedEndDate = null
                        } else {
                            updateDateFilterButton()
                            applyFilters()
                        }
                    },
                    currentYear, currentMonth, currentDay
                ).show()
            },
            currentYear, currentMonth, currentDay
        ).show()
    }

    private fun updateDateFilterButton() {
        binding.btnDateFilter.text = if (selectedStartDate != null && selectedEndDate != null) {
            "${selectedStartDate!!.format(DateTimeFormatter.ofPattern("MM/dd"))} ~ ${selectedEndDate!!.format(DateTimeFormatter.ofPattern("MM/dd"))}"
        } else {
            "날짜"
        }
    }

    private fun getUserEmpNum(): String? {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", null)
        val empNum = prefs.getString("emp_num", null)

        return if (!empNum.isNullOrEmpty() && !name.isNullOrEmpty()) {
            empNum
        } else {
            null
        }
    }

    private fun loadHistoryData() {
        val empNum = getUserEmpNum()
        if (empNum == null) {
            Toast.makeText(requireContext(), "사용자 정보를 찾을 수 없습니다", Toast.LENGTH_SHORT).show()
            binding.progressBar.visibility = View.GONE
            binding.emptyLayout.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.emptyLayout.visibility = View.GONE

        // Collection Group Query로 해당 사용자의 모든 측정 결과 조회
        db.collectionGroup("entries")
            .whereEqualTo("empNum", empNum)
            .get()
            .addOnSuccessListener { documents ->
                allHistoryItems.clear()

                val allItems = documents.mapNotNull { document ->
                    try {
                        val date = document.getString("date") ?: return@mapNotNull null
                        val time = document.getString("time") ?: ""
                        val timestamp = document.getLong("timestamp") ?: 0L
                        val documentId = document.id

                        HistoryItem(
                            date = date,
                            time = time,
                            documentId = documentId,
                            checklistScore = (document.get("checklistScore") as? Number)?.toInt() ?: 0,
                            tremorScore = (document.get("tremorScore") as? Number)?.toFloat() ?: 0f,
                            pupilScore = (document.get("pupilScore") as? Number)?.toFloat() ?: 0f,
                            ppgScore = (document.get("ppgScore") as? Number)?.toFloat() ?: 0f,
                            finalSafetyScore = (document.get("finalSafetyScore") as? Number)?.toFloat() ?: 0f,
                            safetyLevel = document.getString("safetyLevel") ?: "CAUTION",
                            recommendations = document.get("recommendations") as? List<String> ?: emptyList(),
                            timestamp = timestamp
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

                allHistoryItems.addAll(allItems)

                // 시간순으로 정렬 (최신순)
                allHistoryItems.sortByDescending { it.timestamp }

                applyFilters()
                binding.progressBar.visibility = View.GONE

                if (filteredItems.isEmpty()) {
                    binding.emptyLayout.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyLayout.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                }
            }
            .addOnFailureListener { exception ->
                binding.progressBar.visibility = View.GONE
                binding.emptyLayout.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
                Toast.makeText(requireContext(), "기록을 불러오는데 실패했습니다: ${exception.message}", Toast.LENGTH_LONG).show()
            }
    }    private fun applyFilters() {
        filteredItems.clear()

        for (item in allHistoryItems) {
            var includeItem = true

            // 안전도 필터 적용
            if (selectedSafetyLevel != null && item.safetyLevelEnum != selectedSafetyLevel) {
                includeItem = false
            }

            // 날짜 필터 적용
            if (selectedStartDate != null && selectedEndDate != null) {
                try {
                    val itemDate = LocalDate.parse(item.date, dateFormatter)
                    if (itemDate.isBefore(selectedStartDate) || itemDate.isAfter(selectedEndDate)) {
                        includeItem = false
                    }
                } catch (e: Exception) {
                    includeItem = false
                }
            }

            if (includeItem) {
                filteredItems.add(item)
            }
        }

        historyAdapter.submitList(filteredItems.toList())

        // 빈 상태 UI 업데이트
        if (filteredItems.isEmpty() && allHistoryItems.isNotEmpty()) {
            binding.emptyLayout.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else if (filteredItems.isNotEmpty()) {
            binding.emptyLayout.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}