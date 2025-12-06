// app/src/main/java/com/jjangdol/biorhythm/ui/main/NotificationFragment.kt
package com.jjangdol.biorhythm.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentNotificationBinding
import com.jjangdol.biorhythm.data.model.Notification
import com.jjangdol.biorhythm.data.model.NotificationPriority
import com.jjangdol.biorhythm.vm.UserNotificationViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@AndroidEntryPoint
class NotificationFragment : Fragment(R.layout.fragment_notification) {

    private var _binding: FragmentNotificationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UserNotificationViewModel by viewModels()
    private lateinit var notificationAdapter: UserNotificationAdapter

    private var filterPriority: NotificationPriority? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNotificationBinding.bind(view)

        setupRecyclerView()
        setupClickListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        notificationAdapter = UserNotificationAdapter(
            onItemClick = { notification ->
                viewModel.markAsRead(notification.id)
                navigateToDetail(notification)
            },
            onMoreClick = { notification, isExpanded ->
                // 확장/축소 처리는 어댑터에서 자동 처리
            },
            onMarkReadClick = { notification ->
                viewModel.markAsRead(notification.id)
                // 즉시 어댑터 갱신
                notificationAdapter.notifyDataSetChanged()
            },
            onShareClick = { notification ->
                shareNotification(notification)
            },
            isNotificationRead = { notificationId ->
                viewModel.isNotificationRead(notificationId)
            }
        )

        binding.recyclerViewNotifications.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notificationAdapter
        }
    }

    private fun setupClickListeners() {
        // 필터 칩 그룹
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            filterPriority = when (checkedIds.firstOrNull()) {
                R.id.chipHigh -> NotificationPriority.HIGH
                R.id.chipNormal -> NotificationPriority.NORMAL
                R.id.chipLow -> NotificationPriority.LOW
                else -> null
            }
            viewModel.setFilter(filterPriority)
        }

        // 새로고침 버튼
        binding.btnRefresh.setOnClickListener {
            viewModel.refreshNotifications()
            Toast.makeText(requireContext(), "알림을 새로고침했습니다", Toast.LENGTH_SHORT).show()
        }

        // 선택 읽음 처리 버튼
        binding.btnMarkSelectedRead.setOnClickListener {
            val selectedIds = notificationAdapter.getSelectedUnreadIds()  // 읽지 않은 것만
            if (selectedIds.isNotEmpty()) {
                viewModel.markMultipleAsRead(selectedIds)
                notificationAdapter.clearSelection()
                binding.bottomActionLayout.visibility = View.GONE
            } else {
                Toast.makeText(requireContext(), "읽음 처리할 알림을 선택하세요", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAttachmentUrl(url: String) {
        val uri = Uri.parse(url)
        val mime = guessMimeFromUrl(url)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (mime != null) { setDataAndType(uri, mime) }
            else { data = uri }
        }
        try
        { startActivity(intent) }
        catch (e: ActivityNotFoundException)
        { Toast.makeText(context, "열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show() }
    }

    private fun guessMimeFromUrl(url: String): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(url)
        return if (ext.isNullOrBlank()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
    }

    private fun downloadAttachment(url: String, fileName: String = guessFileName(url)) {
        try
        {
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                .setTitle(fileName)
                .setDescription("첨부파일 다운로드 중")
                .setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,fileName
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val dm = requireContext()
                .getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(request)

            Toast.makeText(requireContext(), "다운로드를 시작했습니다.", Toast.LENGTH_SHORT).show()
        }
        catch (e: Exception)
        {
            Toast.makeText(requireContext(), "다운로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun guessFileName(url: String): String {
        val cleaned = url.substringBefore('?')
        val name = cleaned.substringAfterLast('/')
        return if (name.isBlank()) "attachment" else name
    }

    private fun observeViewModel() {
        // notifications
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.notifications.collectLatest { notifications ->
                notificationAdapter.submitList(notifications)
                binding.emptyLayout.visibility =
                    if (notifications.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        // unreadCount
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.unreadCount.collectLatest { count ->
                binding.tvNotificationSummary.text = if (count > 0) {
                    "읽지 않은 알림 ${count}개"
                } else {
                    "모든 알림을 읽었습니다"
                }
            }
        }

        // 우선순위별 배지
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.unreadNotificationsByPriority.collectLatest { priorityMap ->
                updateNotificationBadges(priorityMap)
            }
        }

        //  읽음 상태 변경 추가
        viewLifecycleOwner.lifecycleScope.launch {
            // UserNotificationRepository의 readNotificationIds Flow 관찰
            // (실제로는 UserNotificationViewModel을 통해 접근해야 함)
            // 임시로 UI 상태 변경을 통해 갱신
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is UserNotificationViewModel.UiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    is UserNotificationViewModel.UiState.Success -> {
                        binding.progressBar.visibility = View.GONE
                        if (state.message.isNotEmpty()) {
                            Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                            // 성공 메시지가 있을 때 어댑터 갱신
                            notificationAdapter.notifyDataSetChanged()
                        }
                    }
                    is UserNotificationViewModel.UiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.attachmentEvents.collectLatest { event ->
                when (event) {
                    is UserNotificationViewModel.AttachmentEvent.Open -> {
                        openAttachmentUrl(event.url)
                    }
                    is UserNotificationViewModel.AttachmentEvent.Download -> {
                        downloadAttachment(event.url, event.fileName)
                    }
                    is UserNotificationViewModel.AttachmentEvent.Message -> {
                        Toast.makeText(requireContext(), event.text, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // 알림 유형별 별 뱃지
    private fun updateNotificationBadges(priorityMap: Map<NotificationPriority, Int>) {
        binding.apply {
            val highCount = priorityMap[NotificationPriority.HIGH] ?: 0
            val normalCount = priorityMap[NotificationPriority.NORMAL] ?: 0
            val lowCount = priorityMap[NotificationPriority.LOW] ?: 0

            val totalUnread = highCount + normalCount + lowCount

            if (totalUnread > 0) {
                // 읽지 않은 알림이 있음 - 배지 표시
                notificationBadgeContainer.visibility = View.VISIBLE
                tvAllReadBadge.visibility = View.GONE

                // 긴급
                chipHighBadge.apply {
                    if (highCount > 0) {
                        visibility = View.VISIBLE
                        text = "긴급 $highCount"
                    } else {
                        visibility = View.GONE
                    }
                }

                // 일반
                chipNormalBadge.apply {
                    if (normalCount > 0) {
                        visibility = View.VISIBLE
                        text = "일반 $normalCount"
                    } else {
                        visibility = View.GONE
                    }
                }

                // 안내
                chipLowBadge.apply {
                    if (lowCount > 0) {
                        visibility = View.VISIBLE
                        text = "안내 $lowCount"
                    } else {
                        visibility = View.GONE
                    }
                }
            } else {
                // 모두 읽음 - 체크 표시
                notificationBadgeContainer.visibility = View.GONE
                tvAllReadBadge.visibility = View.VISIBLE
            }
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun isImageUrl(url: String): Boolean {
        val mime = guessMimeFromUrl(url) ?: return false
        return mime.startsWith("image/")
    }

    private fun isPdfUrl(url: String): Boolean {
        val mime = guessMimeFromUrl(url)
        return (mime == "application/pdf") || url.endsWith(".pdf", ignoreCase = true)
    }

    private suspend fun downloadToCacheHttps(url: String, fileName: String): java.io.File? = withContext(Dispatchers.IO) {
        try {
            val cacheDir = java.io.File(requireContext().cacheDir, "attachments").apply { mkdirs() }
            val local = java.io.File(cacheDir, fileName)
            if (local.exists()) return@withContext local

            val conn = java.net.URL(url).openConnection()
            conn.getInputStream().use { input ->
                java.io.FileOutputStream(local).use { out -> input.copyTo(out) }
            }
            local
        } catch (_: Exception) { null }
    }

    // PDF 1페이지 썸네일 (캐시 파일 필요)
    private suspend fun renderPdf(pdfFile: java.io.File, reqW: Int, reqH: Int): android.graphics.Bitmap? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(pdfFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val scale = minOf(reqW.toFloat() / page.width, reqH.toFloat() / page.height)
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            } catch (_: Exception) { null }
        }


    private fun navigateToDetail(notification: Notification) {
        try {
            val bundle = Bundle().apply {
                putParcelable("notification", notification)
                putBoolean("isAdminMode", false) //관리자 모드는 사용 X
            }

            // MainActivity의 NavController를 찾아서 사용
            val mainNavController = requireActivity()
                .supportFragmentManager
                .findFragmentById(R.id.navHostFragment)
                ?.findNavController()

            mainNavController?.navigate(
                R.id.action_main_to_notificationDetail,
                bundle
            )
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "알림 상세를 열 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareNotification(notification: Notification) {
        val shareText = "${notification.title}\n\n${notification.content}\n\n- ${notification.priority.displayName} 알림"

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, notification.title)
        }

        startActivity(Intent.createChooser(shareIntent, "알림 공유"))
    }

    private fun showMarkAllReadDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("모든 알림 읽음 처리")
            .setMessage("모든 알림을 읽음으로 처리하시겠습니까?")
            .setPositiveButton("읽음 처리") { _, _ ->
                viewModel.markAllAsRead()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}