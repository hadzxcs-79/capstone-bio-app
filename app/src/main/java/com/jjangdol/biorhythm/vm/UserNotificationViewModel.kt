// app/src/main/java/com/jjangdol/biorhythm/vm/UserNotificationViewModel.kt
package com.jjangdol.biorhythm.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjangdol.biorhythm.data.model.Notification
import com.jjangdol.biorhythm.data.model.NotificationPriority
import com.jjangdol.biorhythm.data.repository.UserNotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.combine
import android.content.Context

private const val DEPT_ALL = "전체"

@HiltViewModel
class UserNotificationViewModel @Inject constructor(
    private val userNotificationRepository: UserNotificationRepository,
    private val db: FirebaseFirestore,
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val appContext: Context
) : ViewModel() {

    sealed class AttachmentEvent
    {
        data class Open(val url: String) : AttachmentEvent()
        data class Download(val url: String, val fileName: String) : AttachmentEvent()
        data class Message(val text: String) : AttachmentEvent()
    }

    private val _attachmentEvents = MutableSharedFlow<AttachmentEvent>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val attachmentEvents = _attachmentEvents.asSharedFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _filterPriority = MutableStateFlow<NotificationPriority?>(null)

    //해당되는 알림 가져오기
    private suspend fun loadUserAcl(): Pair<Int?, List<String>>? = try {
        val empNum = getEmployeeIdFromPrefs() ?: return null
        val doc = db.collection("employees").document(empNum).get().await()

        val userAuth: Int? = when (val raw = doc.get("auth")) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            is Boolean -> if (raw) 1 else 0
            else -> null
        }

        val deptString = doc.getString("dept") ?: ""
        val cumulative = mutableListOf<String>()

        if (deptString.isNotBlank()) {
            val parts = deptString.split("/")
            var acc = ""

            for (p in parts) {
                acc = if (acc.isEmpty()) p else "$acc/$p"
                cumulative += acc
            }
        }

        userAuth to cumulative
    } catch (e: Exception) {
        null
    }

    private fun getEmployeeIdFromPrefs(): String? {
        val sp = appContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        return sp.getString("emp_num", null)
    }

    // 'auth=all'로 저장한 알림
    private fun passAuth(n: Notification, userAuth: Int?): Boolean {
        if (userAuth == null) return (n.auth == 2)

        return n.auth >= userAuth
    }

    private val visibleNotifications: StateFlow<List<Notification>> =
        flow {
            val acl = loadUserAcl() ?: run { emit(emptyList()); return@flow }
            val (userAuth, userDeptCumulative) = acl
            val keys = (userDeptCumulative + DEPT_ALL).distinct()
            val q = db.collection("notifications").whereArrayContainsAny("targetDept", keys)

            fun Query.asFlow(tag: String) = callbackFlow<List<Notification>> {
                val reg = addSnapshotListener { snap, e ->
                    if (e != null) { close(e); return@addSnapshotListener }
                    val docs = snap?.documents ?: emptyList()

                    // 클라이언트 필터링: active & auth 조건
                    val list = docs.mapNotNull { d ->
                        try { d.toObject(Notification::class.java)?.copy(id = d.id) } catch (_: Exception) { null }
                    }.filter { n ->
                        val ok = (n.active == true) && passAuth(n, userAuth)
                        ok
                    }.sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }

                    trySend(list)
                }
                awaitClose { reg.remove(); }
            }

            try { q.asFlow("qDeptOnly").collect { result -> emit(result) }
            } catch (e: Exception) { emit(emptyList()) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 읽은 알림 ID 목록
    private val readNotificationIds = userNotificationRepository
        .getReadNotificationIds()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )

    // 필터링된 알림 목록 (모든 알림 표시, 읽음 상태는 별도 처리)
    val notifications = combine(
        visibleNotifications,
        _filterPriority
    ) { list, filter ->
        if (filter == null) list else list.filter { it.priority == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 읽지 않은 알림 개수
    val unreadCount = combine(
        visibleNotifications,
        readNotificationIds
    ) { list, readIds ->
        list.count { it.id !in readIds }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // 읽음 상태 확인 함수
    fun isNotificationRead(notificationId: String): Boolean {
        return readNotificationIds.value.contains(notificationId)
    }

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        data class Success(val message: String = "") : UiState()
        data class Error(val message: String) : UiState()
    }

    fun setFilter(priority: NotificationPriority?) {
        _filterPriority.value = priority
    }

    fun markAsRead(notificationId: String) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            userNotificationRepository.markAsRead(notificationId)
                .onSuccess {
                    _uiState.value = UiState.Success("읽음 처리되었습니다")
                    _uiState.value = UiState.Idle
                }
                .onFailure { e ->
                    _uiState.value = UiState.Error("읽음 처리에 실패했습니다: ${e.message}")
                    _uiState.value = UiState.Idle
                }
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            userNotificationRepository.markAllAsRead()
                .onSuccess {
                    _uiState.value = UiState.Success("모든 알림을 읽음으로 처리했습니다")
                }
                .onFailure { e ->
                    _uiState.value = UiState.Error("읽음 처리에 실패했습니다: ${e.message}")
                }
        }
    }

    fun markMultipleAsRead(notificationIds: List<String>) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            userNotificationRepository.markMultipleAsRead(notificationIds)
                .onSuccess {
                    _uiState.value = UiState.Success("선택한 알림을 읽음으로 처리했습니다")
                }
                .onFailure { e ->
                    _uiState.value = UiState.Error("읽음 처리에 실패했습니다: ${e.message}")
                }
        }
    }

    fun refreshNotifications() {
        _uiState.value = UiState.Success("알림을 새로고침했습니다")
    }

    val unreadNotificationsByPriority: StateFlow<Map<NotificationPriority, Int>> =
        combine(
            visibleNotifications,
            readNotificationIds
        ) { list, readIds ->
            val unread = list.filter { it.id !in readIds }
            mapOf(
                NotificationPriority.HIGH to unread.count { it.priority == NotificationPriority.HIGH },
                NotificationPriority.NORMAL to unread.count { it.priority == NotificationPriority.NORMAL },
                NotificationPriority.LOW to unread.count { it.priority == NotificationPriority.LOW }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
}