package com.jjangdol.biorhythm.ui.history

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.ItemHistoryBinding
import com.jjangdol.biorhythm.model.HistoryItem
import com.jjangdol.biorhythm.model.SafetyLevel

class HistoryAdapter(
    private val onItemClick: (HistoryItem, String) -> Unit
) : ListAdapter<HistoryItem, HistoryAdapter.HistoryViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class HistoryViewHolder(
        private val binding: ItemHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HistoryItem) {
            binding.apply {
                // 날짜 설정
                tvDate.text = item.formattedDate
                tvTime.text = item.time

                // 안전도 설정
                val safetyLevel = item.safetyLevelEnum
                tvSafetyLevel.text = safetyLevel.displayName
                tvSafetyLevel.setTextColor(Color.parseColor(safetyLevel.color))

                ivSafetyIcon.setImageResource(
                    when (safetyLevel) {
                        SafetyLevel.SAFE -> R.drawable.ic_check_circle
                        SafetyLevel.CAUTION -> R.drawable.ic_warning
                        SafetyLevel.DANGER -> R.drawable.ic_error
                    }
                )

                // 최종 점수
                tvFinalScore.text = "${item.finalSafetyScore.toInt()}점"

                // 측정 상태
                tvMeasurementStatus.text = if (item.hasAllMeasurements) {
                    "완전 측정"
                } else {
                    "부분 측정"
                }

                // 개별 점수들
                tvChecklistScore.text = item.checklistScore.toString()
                tvTremorScore.text = if (item.tremorScore > 0) {
                    item.tremorScore.toInt().toString()
                } else {
                    "-"
                }
                tvPupilScore.text = if (item.pupilScore > 0) {
                    item.pupilScore.toInt().toString()
                } else {
                    "-"
                }
                tvPpgScore.text = if (item.ppgScore > 0) {
                    item.ppgScore.toInt().toString()
                } else {
                    "-"
                }

                // 클릭 이벤트 - documentId 함께 전달
                root.setOnClickListener {
                    onItemClick(item, item.documentId)
                }
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<HistoryItem>() {
        override fun areItemsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
            return oldItem.documentId == newItem.documentId  // documentId로 비교
        }

        override fun areContentsTheSame(oldItem: HistoryItem, newItem: HistoryItem): Boolean {
            return oldItem == newItem
        }
    }
}