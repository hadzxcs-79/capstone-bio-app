package com.jjangdol.biorhythm.ui.admin

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jjangdol.biorhythm.databinding.ItemUserStatisticsBinding
import com.jjangdol.biorhythm.model.UserStatistics

class AdminResultsAdapter(
    private val onItemClick: (UserStatistics) -> Unit
) : ListAdapter<UserStatistics, AdminResultsAdapter.UserStatisticsViewHolder>(StatisticsDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserStatisticsViewHolder {
        val binding = ItemUserStatisticsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UserStatisticsViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UserStatisticsViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class UserStatisticsViewHolder(
        private val binding: ItemUserStatisticsBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UserStatistics) {
            binding.apply {
                tvUserName.text = item.userName
                tvDangerCount.text = item.dangerCount.toString()
                tvCautionCount.text = item.cautionCount.toString()
                tvSafeCount.text = item.safeCount.toString()

                root.setOnClickListener { onItemClick(item) }
            }
        }
    }

    class StatisticsDiffCallback : DiffUtil.ItemCallback<UserStatistics>() {
        override fun areItemsTheSame(oldItem: UserStatistics, newItem: UserStatistics): Boolean {
            return oldItem.userId == newItem.userId
        }

        override fun areContentsTheSame(oldItem: UserStatistics, newItem: UserStatistics): Boolean {
            return oldItem == newItem
        }
    }
}