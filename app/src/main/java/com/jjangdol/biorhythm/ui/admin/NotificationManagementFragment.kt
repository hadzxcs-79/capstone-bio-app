// app/src/main/java/com/jjangdol/biorhythm/ui/admin/NotificationManagementFragment.kt
package com.jjangdol.biorhythm.ui.admin

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentNotificationManagementBinding
import com.jjangdol.biorhythm.data.model.Notification
import com.jjangdol.biorhythm.data.model.NotificationPriority
import com.jjangdol.biorhythm.vm.NotificationManagementViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.LinearLayout
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import android.widget.ArrayAdapter
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import android.util.Log
import android.content.Context

@AndroidEntryPoint
class NotificationManagementFragment : Fragment(R.layout.fragment_notification_management) {

    private var _binding: FragmentNotificationManagementBinding? = null
    private val binding get() = _binding!!

    private val viewModel: NotificationManagementViewModel by viewModels()
    private lateinit var notificationAdapter: NotificationAdapter

    private var selectedPriority = NotificationPriority.NORMAL
    private var filterPriority: NotificationPriority? = null

    private val selectedAttachmentUris = mutableListOf<Uri>()
    private val editAttachmentUris = mutableListOf<Uri>()
    private companion object { const val DEPT_ALL = "전체" }
    private val selectedDeptPathGlobal: MutableList<String> = mutableListOf()

    private var cameraImageUri: Uri? = null
    private var isForEdit = false

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()

            // 카메라로 찍은 경우
            if (data == null || (data.data == null && data.clipData == null)) {
                cameraImageUri?.let { uris.add(it) }
            }
            else {
                // 단일 파일 선택
                data.data?.let { uris.add(it) }

                // 다중 파일 선택
                data.clipData?.let { clipData ->
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i).uri?.let { uris.add(it) }
                    }
                }
            }

            if (uris.isNotEmpty()) {
                if (isForEdit) {
                    uris.forEach { uri ->
                        try {
                            requireContext().contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) { Log.w("NotificationManagement", "권한 획득 실패 (카메라 이미지는 정상): ${e.message}") }
                        editAttachmentUris.add(uri)
                    }
                    currentAttachmentStatusCallback?.invoke()
                } else { addAttachments(uris) }
            }

            // 초기화
            cameraImageUri = null
            isForEdit = false
        } else {
            // 취소된 경우 초기화
            cameraImageUri = null
            isForEdit = false
        }
    }

    private val pickFilesForEdit = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()

            data?.data?.let { uris.add(it) }
            data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) { clipData.getItemAt(i).uri?.let { uris.add(it) } }
            }

            if (uris.isNotEmpty()) {
                uris.forEach { uri ->
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    editAttachmentUris.add(uri)
                }
                currentAttachmentStatusCallback?.invoke()
            }
        }
    }

    private var currentAttachmentStatusCallback: (() -> Unit)? = null
    private var currentReceiverStatusCallback: (() -> Unit)? = null
    private var selectAuth: MutableSet<String> = mutableSetOf(DEPT_ALL) //디폴트(default)값을 전체로
    private var selectDept: MutableSet<String> = mutableSetOf(DEPT_ALL) //디폴트(default)값을 전체로

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        FirebaseFirestore.setLoggingEnabled(true)
        _binding = FragmentNotificationManagementBinding.bind(view)

        setupUI()
        setupRecyclerView()
        setupClickListeners()
        observeViewModel()

        val currentUserEmpNum = getUserEmpNum()
        if (currentUserEmpNum != null) {
            viewModel.loadCurrentUserDepartment(currentUserEmpNum)
        }

        val db = FirebaseFirestore.getInstance()
        val collectionRef = db.collection("Department")

        collectionRef.get()
            .addOnSuccessListener { querySnapshot ->
                val documentIds = querySnapshot.documents.map { it.id }
            }
            .addOnFailureListener { e ->
                Log.e("Firestore", "Department 문서 조회 실패", e)
            }
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun getEmployeeIdFromPrefs(): String? {
        val sp = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        return sp.getString("emp_num", null)
    }

    private fun setupRecyclerView() {
        notificationAdapter = NotificationAdapter(
            onItemClick = { notification ->
                showNotificationDetailDialog(notification)
            },
            onEditClick = { notification ->
                showEditNotificationDialog(notification)
            },
            onDeleteClick = { notification ->
                showDeleteConfirmDialog(notification)
            },
            onToggleStatus = { notification ->
                viewModel.toggleNotificationStatus(notification.id)
            }
        )

        binding.recyclerViewNotifications.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = notificationAdapter
        }
    }

    private fun createFilePickerIntent(): Intent {
        // 카메라 인텐트
        cameraImageUri = createImageUri()
        val takePictureIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            cameraImageUri?.let { uri ->
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        // 파일 선택 인텐트
        val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Chooser 인텐트 생성 (카메라와 파일 선택 모두 표시)
        val chooserIntent = Intent.createChooser(getContentIntent, "파일 선택")

        // 카메라 Uri가 생성된 경우에만 카메라 옵션 추가
        if (cameraImageUri != null) { chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(takePictureIntent)) }

        return chooserIntent
    }

    private fun createImageUri(): Uri? {
        return try {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "biorhythm_${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Biorhythm")
            }
            requireContext().contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
        } catch (e: Exception) { null }
    }

    private fun setupClickListeners() {
        // 우선순위 선택
        binding.chipGroupPriority.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedPriority = when (checkedIds.firstOrNull()) {
                R.id.chipHigh -> NotificationPriority.HIGH
                R.id.chipLow -> NotificationPriority.LOW
                else -> NotificationPriority.NORMAL
            }
        }

        // 파일첨부 버튼
        binding.btnAddFile.setOnClickListener {
            isForEdit = false
            pickFiles.launch(createFilePickerIntent())
        }

        // 필터 선택
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            filterPriority = when (checkedIds.firstOrNull()) {
                R.id.chipFilterHigh -> NotificationPriority.HIGH
                R.id.chipFilterNormal -> NotificationPriority.NORMAL
                R.id.chipFilterLow -> NotificationPriority.LOW
                else -> null
            }
            viewModel.setFilter(filterPriority)
        }

        // 알림 등록 버튼
        binding.btnCreateNotification.setOnClickListener {
            createNotification()
        }

        // 수신자 그룹 버튼
        binding.btnReceiver.setOnClickListener {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(binding.root.windowToken, 0) //키보드 강제로 내림
            openReceiverDropdownDialog()
        }

        // 새로고침 버튼
        binding.btnRefreshNotifications.setOnClickListener {
            viewModel.refreshNotifications()
            Toast.makeText(requireContext(), "알림 목록을 새로고침했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.notifications.collectLatest { notifications ->
                notificationAdapter.submitList(notifications)
                binding.tvNotificationCount.text = "총 ${notifications.size}건"

                // 빈 상태 처리
                binding.emptyNotificationLayout.visibility =
                    if (notifications.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is NotificationManagementViewModel.UiState.Loading -> {
                        binding.btnCreateNotification.isEnabled = false
                        binding.btnCreateNotification.text = "등록 중..."
                    }
                    is NotificationManagementViewModel.UiState.Success -> {
                        binding.btnCreateNotification.isEnabled = true
                        binding.btnCreateNotification.text = "알림 등록"
                        clearForm()
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_SHORT).show()
                    }
                    is NotificationManagementViewModel.UiState.Error -> {
                        binding.btnCreateNotification.isEnabled = true
                        binding.btnCreateNotification.text = "알림 등록"
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        binding.btnCreateNotification.isEnabled = true
                        binding.btnCreateNotification.text = "알림 등록"
                    }
                }
            }
        }
    }

    private fun createNotification() {
        val title = binding.etNotificationTitle.text?.toString()?.trim() ?: ""
        val content = binding.etNotificationContent.text?.toString()?.trim() ?: ""

        val auth: Int =
            if (selectAuth.contains(DEPT_ALL)) { 2 } // "전체"일 경우 2 (모든 사용자)
            else { selectAuth.firstOrNull()?.toIntOrNull() ?: 2 }

        when {
            title.isEmpty() -> {
                binding.etNotificationTitle.error = "제목을 입력하세요"
                binding.etNotificationTitle.requestFocus()
            }
            content.isEmpty() -> {
                binding.etNotificationContent.error = "내용을 입력하세요"
                binding.etNotificationContent.requestFocus()
            }
            else -> {
                val deptTargets =
                    if (selectDept.contains(DEPT_ALL)) listOf(DEPT_ALL)
                    else buildDeptTargetsFromPath(selectedDeptPathGlobal)

                viewModel.createNotification(title, content, selectedPriority, attachments = selectedAttachmentUris.toList(), auth, targetDept = deptTargets)
            }
        }
    }

    //dept 묶어서 가져옴
    private fun buildDeptTargetsFromPath(parts: List<String>): List<String> {
        return parts.indices.map { i -> parts.take(i + 1).joinToString("/") }
    }

    //수신자 그룹 선택
    private fun openReceiverDropdownDialog() {
        val ctx = requireContext()
        val db = FirebaseFirestore.getInstance()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        fun makeDropdown(label: String, hintText: String): Pair<TextInputLayout, MaterialAutoCompleteTextView> {
            val til = TextInputLayout(ctx, null, com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox_ExposedDropdownMenu).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                setPadding(0, 16, 0, 0)
                this.hint = label
            }
            val actv = MaterialAutoCompleteTextView(til.context).apply {
                isFocusable = false
                isCursorVisible = false
                keyListener = null
                setText("", false)
                this.hint = hintText
            }
            til.addView(actv)
            root.addView(til)
            return til to actv
        }

        // 드롭다운
        val (_, ddAuth) = makeDropdown("수신자 권한", "")
        val (_, targetDept1) = makeDropdown("수신자 부서", "")
        val (hideDept2, targetDept2) = makeDropdown("2단계 부서", "")
        val (hideDept3, targetDept3) = makeDropdown("3단계 부서", "")
        val (hideDept4, targetDept4) = makeDropdown("4단계 부서", "")
        hideDept2.visibility = View.GONE
        hideDept3.visibility = View.GONE
        hideDept4.visibility = View.GONE

        val selectedDeptPath = mutableListOf<String>()

        // 현재 선택값 UI 반영
        fun refreshTexts() {
            ddAuth.setText(selectAuth.firstOrNull() ?: "", false)
            targetDept1.setText(selectDept.firstOrNull() ?: "", false)
        }
        refreshTexts()

        viewLifecycleOwner.lifecycleScope.launch {
            // 작성자 auth 가져오기
            val senderAuth = try {
                val empNum = getEmployeeIdFromPrefs()
                if (empNum.isNullOrBlank()) {
                    Toast.makeText(ctx, "사번 정보를 찾을 수 없습니다. 다시 로그인해주세요.", Toast.LENGTH_LONG).show()
                    null
                } else {
                    val emp = db.collection("employees").document(empNum).get().await()
                    val raw = emp.get("auth")

                    when (raw) {
                        is String -> raw               // "0" | "1"
                        is Number -> raw.toInt().toString()
                        is Boolean -> if (raw) "1" else "0"
                        else -> null
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "프로필 로딩 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                null
            }

            // 드롭다운 항목
            val authItems = when (senderAuth) {
                "0" -> listOf("0", "1", DEPT_ALL)
                "1" -> listOf("1", DEPT_ALL)
                else -> emptyList()
            }
            ddAuth.setAdapter(ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, authItems))
            ddAuth.setOnClickListener {
                if (authItems.isEmpty())
                {
                    Toast.makeText(ctx, "발송 권한이 없습니다", Toast.LENGTH_SHORT).show(); return@setOnClickListener
                }
                ddAuth.showDropDown()
            }
            ddAuth.setOnItemClickListener { _, _, position, _ ->
                val v = authItems[position]
                if (senderAuth == "1" && v == "0")
                {
                    Toast.makeText(ctx, "관리자(1)는 0에게 보낼 수 없습니다", Toast.LENGTH_SHORT).show()
                    refreshTexts()
                    return@setOnItemClickListener
                }
                selectAuth.clear()
                selectAuth.add(v)
            }

            val topLevelDepts = loadTopLevelDepts(db)
            val dept1Items = listOf(DEPT_ALL) + topLevelDepts
            targetDept1.setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, dept1Items))
            targetDept1.setOnClickListener {
                if (dept1Items.isEmpty()) {
                    Toast.makeText(ctx, "부서 목록을 불러오는 중", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                targetDept1.showDropDown()
            }
            targetDept1.setOnItemClickListener { _, _, position, _ ->
                val selected = dept1Items[position]

                selectedDeptPath.clear()
                targetDept2.setText("", false)
                targetDept3.setText("", false)
                targetDept4.setText("", false)
                hideDept2.visibility = View.GONE
                hideDept3.visibility = View.GONE
                hideDept4.visibility = View.GONE

                if (selected == DEPT_ALL) {
                    selectDept.clear()
                    selectDept.add(DEPT_ALL)
                } else {
                    selectedDeptPath.add(selected)
                    loadNextLevel(db, selectedDeptPath, hideDept2, targetDept2, hideDept3, targetDept3, hideDept4, targetDept4)
                }
            }

            targetDept2.setOnItemClickListener { _, _, position, _ ->
                val selected = (targetDept2.adapter.getItem(position) as? String) ?: return@setOnItemClickListener

                while (selectedDeptPath.size > 1) selectedDeptPath.removeAt(selectedDeptPath.size - 1)
                targetDept3.setText("", false)
                targetDept4.setText("", false)
                hideDept3.visibility = View.GONE
                hideDept4.visibility = View.GONE

                if (selected.startsWith("전체")) {
                    selectDept.clear()
                    selectDept.add(selectedDeptPath.last())
                } else {
                    selectedDeptPath.add(selected)
                    loadNextLevel(db, selectedDeptPath, hideDept3, targetDept3, hideDept4, targetDept4)
                }
            }

            targetDept3.setOnItemClickListener { _, _, position, _ ->
                val selected = (targetDept3.adapter.getItem(position) as? String) ?: return@setOnItemClickListener

                // 4단계 초기화
                while (selectedDeptPath.size > 2) selectedDeptPath.removeAt(selectedDeptPath.size - 1)
                targetDept4.setText("", false)
                hideDept4.visibility = View.GONE

                if (selected.startsWith("전체")) {
                    selectDept.clear()
                    selectDept.add(selectedDeptPath.last())
                } else {
                    selectedDeptPath.add(selected)
                    loadNextLevel(db, selectedDeptPath, hideDept4, targetDept4)
                }
            }

            targetDept4.setOnItemClickListener { _, _, position, _ ->
                val selected = (targetDept4.adapter.getItem(position) as? String) ?: return@setOnItemClickListener

                while (selectedDeptPath.size > 3) selectedDeptPath.removeAt(selectedDeptPath.size - 1)

                if (selected.startsWith("전체")) {
                    selectDept.clear()
                    selectDept.add(selectedDeptPath.last())
                } else {
                    selectedDeptPath.add(selected)
                    selectDept.clear()
                    selectDept.add(selected)
                }
            }

            // 다이얼로그 표시
            AlertDialog.Builder(ctx)
                .setTitle("수신자 그룹 선택")
                .setView(root)
                .setNegativeButton("닫기", null)
                .setPositiveButton("확인") { d, _ ->
                    if (senderAuth == null) {
                        Toast.makeText(ctx, "발송 권한이 없습니다", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (selectAuth.isEmpty()) {
                        Toast.makeText(ctx, "수신자 권한(auth)을 선택하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (selectDept.isEmpty()) {
                        Toast.makeText(ctx, "수신자 부서(dept)를 선택하세요", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (senderAuth == "1" && selectAuth.contains("0")) {
                        Toast.makeText(ctx, "관리자(1)는 0에게 보낼 수 없습니다", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    selectedDeptPathGlobal.clear()
                    if (!selectDept.contains(DEPT_ALL)) {
                        selectedDeptPathGlobal.addAll(selectedDeptPath)
                    }
                    currentReceiverStatusCallback?.invoke()
                    d.dismiss()
                }
                .show()
        }
    }

    //하위부서 표시
    private fun loadNextLevel(
        db: FirebaseFirestore,
        currentPath: List<String>,
        vararg dropdownPairs: Any
    ) {
        val tilNext = dropdownPairs[0] as TextInputLayout
        val ddNext = dropdownPairs[1] as MaterialAutoCompleteTextView

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val parentDeptId = currentPath.last()
                val subDepts = loadSubDepartments(db, currentPath)

                if (subDepts.isEmpty()) {
                    // 하위 부서가 없으면 현재 선택으로 확정
                    tilNext.visibility = View.GONE
                    selectDept.clear()
                    selectDept.add(parentDeptId)
                    Toast.makeText(requireContext(), "$parentDeptId 선택됨 (하위 부서 없음)", Toast.LENGTH_SHORT).show()
                } else {
                    // 하위 부서가 있으면 드롭다운 표시
                    tilNext.visibility = View.VISIBLE
                    val items = listOf("전체 ($parentDeptId 포함)") + subDepts

                    ddNext.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, items))
                    ddNext.setOnClickListener {
                        ddNext.showDropDown()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "하위 부서 로딩 실패", Toast.LENGTH_SHORT).show()
                tilNext.visibility = View.GONE
            }
        }
    }

    //하위부서 가져오기
    private suspend fun loadSubDepartments(db: FirebaseFirestore, parentPath: List<String>): List<String> {
        if (parentPath.isEmpty()) return emptyList()

        val subCollectionNames = listOf("Level1","Level2","Level3")
        var docRef = db.collection("Department").document(parentPath[0])

        for (i in 1 until parentPath.size) {
            val subCollName = subCollectionNames.getOrElse(i - 1) { "Department" }
            docRef = docRef.collection(subCollName).document(parentPath[i])
        }

        val nextSubCollName = subCollectionNames.getOrElse(parentPath.size - 1) { "Department" }
        val snapshot = docRef.collection(nextSubCollName).get().await()

        return snapshot.documents.mapNotNull { it.getString("name") ?: it.getString("korName") ?: it.getString("title") ?: it.id }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    //최상위부서 가져오기
    private suspend fun loadTopLevelDepts(db: FirebaseFirestore): List<String> {
        try {
            val snap = db.collection("Department").get().await()

            if (!snap.isEmpty) {
                val deptList = snap.documents.map { doc ->
                    val name = doc.getString("name")
                        ?: doc.getString("korName")
                        ?: doc.getString("title")
                        ?: doc.id

                    name
                }.filter { it.isNotBlank() }
                    .distinct()
                    .sorted()

                return deptList
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "부서 목록을 불러오지 못했습니다.", Toast.LENGTH_LONG).show()
        }

        return emptyList()
    }

    private fun addAttachments(newUris: List<Uri>) {
        // 권한
        newUris.forEach { uri ->
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { Log.w("NotificationManagement", "권한 획득 실패 (카메라 이미지는 정상): ${e.message}") }
        }

        // 중복 제거 후 추가
        val current = selectedAttachmentUris.map { it.toString() }.toMutableSet()
        newUris.forEach { uri ->
            if (current.add(uri.toString())) { selectedAttachmentUris.add(uri) }
        }

        // UI 갱신 (버튼 텍스트/칩/리사이클러 등)
        binding.btnAddFile.text =
            if (selectedAttachmentUris.isEmpty()) "첨부파일" else "첨부파일 (${selectedAttachmentUris.size})"
        binding.tvAttachmentStatus.text =
            if (selectedAttachmentUris.isEmpty()) "첨부된 파일이 없습니다"
            else "${selectedAttachmentUris.size}개의 파일이 첨부되었습니다."
    }

    private fun clearForm() {
        binding.etNotificationTitle.text?.clear()
        binding.etNotificationContent.text?.clear()
        binding.chipGroupPriority.check(R.id.chipNormal)
        selectedPriority = NotificationPriority.NORMAL
        selectedAttachmentUris.clear()

        // 수신자 정보도 디폴트(default)값으로 리셋
        selectAuth.clear()
        selectAuth.add(DEPT_ALL)
        selectDept.clear()
        selectDept.add(DEPT_ALL)
        selectedDeptPathGlobal.clear()
    }

    private fun showNotificationDetailDialog(notification: Notification) {
        val bundle = Bundle().apply {
            putParcelable("notification", notification)
            putBoolean("isAdminMode", true) // 관리자 모드로 설정
        }

        findNavController().navigate(
            R.id.action_notificationManagement_to_notificationDetail,
            bundle
        )
    }
    private fun getUserEmpNum(): String? {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val empNum = prefs.getString("emp_num", null)
        return if (!empNum.isNullOrEmpty()) empNum else null
    }

    private fun showEditNotificationDialog(notification: Notification) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        // 제목 수정
        val titleEdit = android.widget.EditText(requireContext()).apply {
            setText(notification.title)
            hint = "제목"
        }
        layout.addView(titleEdit)

        // 내용 수정
        val contentEdit = android.widget.EditText(requireContext()).apply {
            setText(notification.content)
            hint = "내용"
        }
        layout.addView(contentEdit)

        // 수신자 정보 초기화 (기존 알림 정보 로드)
        selectAuth.clear()
        selectDept.clear()
        selectedDeptPathGlobal.clear()

        notification.auth?.let { selectAuth.add(it.toString()) }
        notification.targetDept?.let {
            selectDept.addAll(it)
            if (!it.contains(DEPT_ALL)) {
                // targetDept에서 경로 복원
                it.lastOrNull()?.split("/")?.let { path ->
                    selectedDeptPathGlobal.addAll(path)
                }
            }
        }

        // 수신자 그룹 상태 업데이트
        fun updateReceiverStatus(textView: android.widget.TextView) {
            val authText = if (selectAuth.isNotEmpty()) {
                "권한: ${selectAuth.first()}"
            } else { notification.auth?.let { "권한: $it" } ?: "권한: 미설정" }

            val deptText = if (selectDept.isNotEmpty()) {
                "부서: ${selectDept.joinToString(", ")}"
            } else { notification.targetDept?.joinToString(", ")?.let { "부서: $it" } ?: "부서: 미설정" }

            textView.text = "수신자 그룹 - $authText, $deptText"
        }

        // 수신자 그룹 상태 표시
        val receiverStatus = android.widget.TextView(requireContext()).apply {
            setPadding(0, 20, 0, 10)
        }
        updateReceiverStatus(receiverStatus)
        layout.addView(receiverStatus)
        currentReceiverStatusCallback = { updateReceiverStatus(receiverStatus) }

        // 수신자 그룹 선택
        val receiverButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "수신자 그룹 변경"
            setOnClickListener { openReceiverDropdownDialog() }
        }
        layout.addView(receiverButton)

        // 첨부파일 관리를 위한 임시 리스트
        val editAttachments = (notification.attachmentUrl ?: emptyList()).toMutableList()
        editAttachmentUris.clear() // 수정용 첨부파일 리스트 초기화

        // 첨부파일 상태 업데이트
        fun updateAttachmentStatus(textView: android.widget.TextView) {
            val totalCount = editAttachments.size + editAttachmentUris.size
            textView.text = if (totalCount == 0) "첨부파일 없음"
            else "첨부파일 ${totalCount}개"
        }

        // 첨부파일 상태 표시
        val attachmentStatus = android.widget.TextView(requireContext()).apply {
            setPadding(0, 20, 0, 10)
        }
        updateAttachmentStatus(attachmentStatus)
        layout.addView(attachmentStatus)
        currentAttachmentStatusCallback = { updateAttachmentStatus(attachmentStatus) }

        // 첨부파일 추가
        val addFileButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = "첨부파일 추가"
            setOnClickListener {
                isForEdit = true
                pickFiles.launch(createFilePickerIntent())
            }
        }
        layout.addView(addFileButton)

        // 기존 첨부파일 삭제
        if (editAttachments.isNotEmpty()) {
            val clearOldFilesButton = com.google.android.material.button.MaterialButton(requireContext()).apply {
                text = "기존 첨부파일 모두 삭제"
                setOnClickListener {
                    editAttachments.clear()
                    updateAttachmentStatus(attachmentStatus)
                }
            }
            layout.addView(clearOldFilesButton)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("알림 수정")
            .setView(layout)
            .setPositiveButton("수정") { _, _ ->
                val newTitle = titleEdit.text.toString().trim()
                val newContent = contentEdit.text.toString().trim()

                if (newTitle.isNotEmpty() && newContent.isNotEmpty()) {
                    // 기존 첨부파일 + 새로 추가된 첨부파일 병합
                    val finalAttachments = editAttachments + editAttachmentUris.map { it.toString() }

                    val targetDeptList = if (selectDept.contains(DEPT_ALL)) {
                        listOf(DEPT_ALL)
                    } else if (selectedDeptPathGlobal.isNotEmpty()) {
                        buildDeptTargetsFromPath(selectedDeptPathGlobal)
                    } else {
                        notification.targetDept
                    }

                    viewModel.updateNotification(
                        notificationId = notification.id,
                        title = newTitle,
                        content = newContent,
                        priority = notification.priority,
                        auth = selectAuth.firstOrNull()?.toIntOrNull() ?: notification.auth,
                        targetDept = targetDeptList,
                        attachmentUrl = editAttachments,
                        newAttachmentUris = editAttachmentUris.toList()
                    )
                    currentAttachmentStatusCallback = null
                    currentReceiverStatusCallback = null
                    editAttachmentUris.clear()
                } else {
                    Toast.makeText(requireContext(), "제목과 내용을 모두 입력하세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소") { _, _ ->
                currentAttachmentStatusCallback = null
                currentReceiverStatusCallback = null
                editAttachmentUris.clear()
            }
            .show()
    }

    private fun showDeleteConfirmDialog(notification: Notification) {
        AlertDialog.Builder(requireContext())
            .setTitle("알림 삭제")
            .setMessage("'${notification.title}' 알림을 삭제하시겠습니까?\n삭제된 알림은 복구할 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                viewModel.deleteNotification(notification.id)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}