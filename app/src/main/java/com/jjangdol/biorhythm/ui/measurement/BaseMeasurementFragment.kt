package com.jjangdol.biorhythm.ui.measurement

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jjangdol.biorhythm.model.MeasurementResult
import com.jjangdol.biorhythm.model.MeasurementState
import com.jjangdol.biorhythm.model.MeasurementType
import com.jjangdol.biorhythm.vm.SafetyCheckViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.jjangdol.biorhythm.R


/**
 * 모든 측정 화면의 기본 클래스
 * 공통 기능: 권한 처리, 측정 상태 관리, 결과 저장, 네비게이션
 */
abstract class BaseMeasurementFragment : Fragment() {
    // 중복 클릭 방지 변수
    private var isNavigating = false
    private var lastClickTime = 0L
    private val CLICK_DELAY = 1000L // 1초

    protected val safetyCheckViewModel: SafetyCheckViewModel by activityViewModels()

    abstract val measurementType: MeasurementType
    abstract val requiredPermissions: Array<String>
    abstract val nextNavigationAction: Int?

    protected var measurementJob: Job? = null
    protected var currentState: MeasurementState = MeasurementState.Idle

    // 권한 요청 런처
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            onPermissionsGranted()
        } else {
            onPermissionsDenied()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkAndRequestPermissions()
    }

    /**
     * 권한 확인 및 요청
     */
    private fun checkAndRequestPermissions() {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) !=
                    PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    /**
     * 권한이 승인되었을 때 호출 자동 시작 X
     */
    protected open fun onPermissionsGranted() {
//        startMeasurement()
    }

    /**
     * 권한이 거부되었을 때 호출
     */
    protected open fun onPermissionsDenied() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("권한 필요")
            .setMessage("${measurementType.displayName}을 위해 권한이 필요합니다.")
            .setPositiveButton("설정으로 이동") { _, _ ->
                // 앱 설정으로 이동
                openAppSettings()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * 측정 시작 - 하위 클래스에서 구현
     */
    protected abstract fun startMeasurement()

    /**
     * 마지막 측정이 완료되었을 때 하위 클래스가 구현해야 할 추상 함수.
     * BaseFragment는 이 함수를 호출할 뿐, 실제 화면 전환 로직은 모른다.
     */
    abstract fun onLastMeasurementComplete(sessionId: String, rawData: String?)

    /**
     * 측정 완료 처리
     */
    protected fun onMeasurementComplete(score: Float, rawData: String? = null) {
        if (isNavigating) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastClickTime < CLICK_DELAY) return
        lastClickTime = currentTime
        isNavigating = true

        Log.d("BaseMeasurement", "========== onMeasurementComplete 시작 ==========")
        Log.d("BaseMeasurement", "측정 타입: $measurementType")
        Log.d("BaseMeasurement", "점수: $score")
        Log.d("BaseMeasurement", "rawData: ${rawData?.take(100)}") // 처음 100자만

        val result = MeasurementResult(
            type = measurementType,
            score = score,
            rawData = rawData,
            metadata = getMeasurementMetadata()
        )

        Log.d("BaseMeasurement", "MeasurementResult 생성: $result")
        safetyCheckViewModel.addMeasurementResult(result)
        Log.d("BaseMeasurement", "ViewModel에 결과 추가 완료")

        if (nextNavigationAction != null) {
            Log.d("BaseMeasurement", "다음 단계 존재: $nextNavigationAction")
            // 다음 단계가 있으면, 다음 측정 화면으로 이동
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (findNavController().currentDestination?.id != nextNavigationAction) {
                        Log.d("BaseMeasurement", "다음 측정 화면으로 이동")
                        kotlinx.coroutines.delay(200)
                        navigateToNext()
                    } else {
                        Log.d("BaseMeasurement", "이미 다음 화면에 있음")
                    }
                } catch (e: Exception) {
                    Log.e("BaseMeasurement", "네비게이션 실패", e)
                } finally {
                    isNavigating = false
                }
            }
        } else {
            Log.d("BaseMeasurement", "마지막 단계 - 세션 완료 처리")
            // 마지막 단계이면(nextNavigationAction이 null), 세션을 완료하고 하위 클래스의 구현을 호출
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val currentDestId = findNavController().currentDestination?.id
                    Log.d("BaseMeasurement", "현재 destination: $currentDestId")

                    if (currentDestId != R.id.resultFragment) {
                        Log.d("BaseMeasurement", "completeSession 호출 시작")

                        safetyCheckViewModel.completeSession { resultObject ->
                            Log.d("BaseMeasurement", "completeSession 콜백 실행")
                            if (isAdded && !isDetached &&
                                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {

                                val sessionIdString = resultObject.toString()
                                onLastMeasurementComplete(sessionIdString, rawData)
                                Log.d("BaseMeasurement", "onLastMeasurementComplete 호출 완료")
                            } else {
                                Log.w("BaseMeasurement", "Fragment 상태가 비정상적이어서 네비게이션 건너뜀")
                            }
                        }
                    } else {
                        Log.d("BaseMeasurement", "이미 결과 화면에 있어서 completeSession 건너뜀")
                    }
                } catch (e: Exception) {
                    Log.e("BaseMeasurement", "세션 완료 실패", e)
                } finally {
                    isNavigating = false
                }
            }
        }
        Log.d("BaseMeasurement", "========== onMeasurementComplete 종료 ==========")
    }

    /**
     * 다음 화면으로 이동
     */
    private fun navigateToNext() {
        nextNavigationAction?.let { actionId ->
            // 이미 결과 화면에 있으면 네비게이션 하지 않음
            if (findNavController().currentDestination?.id == R.id.resultFragment) return

            if (findNavController().currentDestination?.id != actionId) {
                val nextArgs = Bundle(arguments)
                findNavController().navigate(actionId, nextArgs)
            }
        }
    }


    /**
     * 측정 메타데이터 수집 - 하위 클래스에서 오버라이드 가능
     */
    protected open fun getMeasurementMetadata(): Map<String, String> {
        return mapOf(
            "device" to android.os.Build.MODEL,
            "os_version" to android.os.Build.VERSION.SDK_INT.toString()
        )
    }

    /**
     * 상태 업데이트
     */
    protected fun updateState(state: MeasurementState) {
        currentState = state
        onStateChanged(state)
    }

    /**
     * 상태 변경 시 UI 업데이트 - 하위 클래스에서 구현
     */
    protected abstract fun onStateChanged(state: MeasurementState)

    /**
     * 앱 설정 열기
     */
    private fun openAppSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", requireContext().packageName, null)
        )
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isNavigating = false
        measurementJob?.cancel()
    }
}