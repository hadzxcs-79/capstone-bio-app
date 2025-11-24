package com.jjangdol.biorhythm.ui.measurement

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.navigation.fragment.findNavController
import android.view.*
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentPpgMeasurementBinding
import com.jjangdol.biorhythm.model.MeasurementState
import com.jjangdol.biorhythm.model.MeasurementType
import com.jjangdol.biorhythm.ui.measurement.PPGMeasurementFragment.WorkFitnessLevel
import com.jjangdol.biorhythm.ui.view.PPGWaveformView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

@AndroidEntryPoint
class PPGMeasurementFragment : BaseMeasurementFragment() {

    /* ────────────── ViewBinding ────────────── */
    private var _binding: FragmentPpgMeasurementBinding? = null
    private val binding get() = _binding!!

    /* ───────────── Safe-Args ───────────── */
    private val args: PPGMeasurementFragmentArgs by navArgs()

    /* ───── BaseMeasurement 설정 ───── */
    override val measurementType = MeasurementType.PPG
    override val requiredPermissions = arrayOf(Manifest.permission.CAMERA)
    override val nextNavigationAction: Int? = null

    /* ────────── Camera2 변수 ────────── */
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    /* ──────────── 측정 데이터 ─────────── */
    private val rawPPGSignal = mutableListOf<Float>()
    private val timestamps = mutableListOf<Long>()
    private val lumaValues = mutableListOf<Float>()

    // 추가 채널 데이터 (연구용)
    private val redChannel = mutableListOf<Float>()
    private val greenChannel = mutableListOf<Float>()
    private val blueChannel = mutableListOf<Float>()

    private var measurementTimer: CountDownTimer? = null
    private var signalQualityBuffer = mutableListOf<Float>()

    /* ───────────── Constants ───────────── */
    private val MEASUREMENT_TIME = 10_000L // 10초
    private val SAMPLING_RATE = 30

    // 개선된 의학적/산업 기준 (점수 산출 + 안전 기준 병행)
    private val MIN_SIGNAL_QUALITY_THRESHOLD = 40f  // 신호 품질 최소 기준 (측정 실패 방지용)
    private val CRITICAL_HR_MIN = 45f               // 산업안전 기준 (0점 처리)
    private val CRITICAL_HR_MAX = 110f
    private val WARNING_HR_MIN = 50f                // 감점 기준
    private val WARNING_HR_MAX = 100f
    private val CRITICAL_HRV_MIN = 10f              // 10ms 미만은 0점
    private val WARNING_HRV_MIN = 20f               // 20ms 미만은 감점

    /* ───────────── 개선된 데이터 모델 ───────────── */
    data class PPGMeasurementResult(
        val isValid: Boolean,
        val score: Float,                        // 점수는 유지 (0-100)
        val workFitness: WorkFitnessLevel,       // 안전 등급 (참고용)
        val heartRate: Float,
        val hrv: Float,
        val signalQuality: Float,
        val criticalFlags: List<String>,         // 주의/경고 사항
        val additionalMetrics: Map<String, Float>,
        val errorMessage: String? = null
    )

    enum class WorkFitnessLevel(val description: String, val colorRes: Int) {
        EXCELLENT("우수", R.color.safety_safe),
        GOOD("양호", R.color.primary_color),
        FAIR("보통", android.R.color.holo_orange_light),
        POOR("주의", android.R.color.holo_orange_dark),
        CRITICAL("위험", android.R.color.holo_red_dark),
        MEASUREMENT_FAILED("측정 실패", android.R.color.darker_gray)
    }

    /* ───────────── Lifecycle ──────────── */
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPpgMeasurementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraManager = requireContext()
            .getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
        setupUI()
    }

    /* ─────────── UI & 버튼 ─────────── */
    private fun setupUI() = with(binding) {
        btnStart.setOnClickListener { startMeasurement() }
        btnRetry.setOnClickListener {
            resetMeasurement()
            startMeasurement()
        }
        btnNext.setOnClickListener {
            // 결과는 handleNextAction에서 처리됨
        }
    }

    /* ─────────── 측정 시작 ─────────── */
    override fun startMeasurement() {
        updateState(MeasurementState.Preparing)
        startBgThread()

        Handler(Looper.getMainLooper()).postDelayed({
            openCamera()
        }, 500)
    }

    /* ---------- Background Thread ---------- */
    private fun startBgThread() {
        bgThread = HandlerThread("CameraBg", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        bgHandler = Handler(bgThread.looper)
    }

    private fun stopBgThread() {
        bgThread.quitSafely()
        try {
            bgThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    /* ─────────── Camera 열기 ─────────── */
    private fun openCamera() {
        try {
            val camId = findBestCamera() ?: throw Exception("적합한 카메라를 찾을 수 없습니다")

            val characteristics = cameraManager.getCameraCharacteristics(camId)
            if (!characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)!!) {
                updateState(MeasurementState.Error("플래시가 없는 기기입니다"))
                return
            }

            val hardware = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            if (hardware == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                showWarning("카메라 성능이 낮아 측정 정확도가 떨어질 수 있습니다")
            }

            cameraManager.openCamera(camId, stateCallback, bgHandler)

        } catch (se: SecurityException) {
            updateState(MeasurementState.Error("카메라 권한이 필요합니다"))
        } catch (e: Exception) {
            updateState(MeasurementState.Error("카메라 오류: ${e.message}"))
        }
    }

    private fun findBestCamera(): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cam: CameraDevice) {
            cameraDevice = cam
            createSession()
        }

        override fun onDisconnected(cam: CameraDevice) {
            cam.close()
            cameraDevice = null
        }

        override fun onError(cam: CameraDevice, err: Int) {
            cam.close()
            cameraDevice = null
            updateState(MeasurementState.Error("카메라 오류 코드: $err"))
        }
    }

    /* ────────── CaptureSession ────────── */
    private fun createSession() {
        try {
            imageReader = ImageReader.newInstance(
                640, 480, ImageFormat.YUV_420_888, 3
            ).apply {
                setOnImageAvailableListener(enhancedImageListener, bgHandler)
            }

            val yuvSurface = imageReader!!.surface

            val reqBuilder = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(yuvSurface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(SAMPLING_RATE, SAMPLING_RATE))
            }

            cameraDevice!!.createCaptureSession(
                listOf(yuvSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session

                        Handler(Looper.getMainLooper()).postDelayed({
                            startActualMeasurement()

                            val torchReq = reqBuilder.apply {
                                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                            }.build()

                            session.setRepeatingRequest(torchReq, captureCallback, bgHandler)
                        }, 500)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        updateState(MeasurementState.Error("카메라 설정 실패"))
                    }
                },
                bgHandler
            )

        } catch (e: Exception) {
            updateState(MeasurementState.Error("카메라 설정 오류: ${e.message}"))
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            // 프레임 타이밍 정보는 필요시 여기에 추가
        }
    }

    /* ---------- Enhanced Image Processing ---------- */
    private val enhancedImageListener = ImageReader.OnImageAvailableListener { reader ->
        reader.acquireLatestImage()?.let { image ->
            try {
                processImage(image)
            } finally {
                image.close()
            }
        }
    }

    private fun processImage(image: android.media.Image) {
        val currentTime = System.currentTimeMillis()

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val ySize = yBuffer.remaining()
        val yData = ByteArray(ySize)
        yBuffer.get(yData)

        val width = image.width
        val height = image.height
        val centerX = width / 2
        val centerY = height / 2
        val roiSize = min(width, height) / 3

        var lumaSum = 0.0
        var pixelCount = 0

        for (y in (centerY - roiSize / 2)..(centerY + roiSize / 2)) {
            for (x in (centerX - roiSize / 2)..(centerX + roiSize / 2)) {
                val idx = y * width + x
                if (idx < ySize) {
                    lumaSum += (yData[idx].toInt() and 0xFF)
                    pixelCount++
                }
            }
        }

        val lumaMean = (lumaSum / pixelCount).toFloat()

        synchronized(this) {
            rawPPGSignal.add(lumaMean)
            timestamps.add(currentTime)
            lumaValues.add(lumaMean)

            require(rawPPGSignal.size == timestamps.size) {
                "Signal and timestamp arrays size mismatch"
            }

            if (rawPPGSignal.size >= SAMPLING_RATE) {
                val recentSignal = rawPPGSignal.takeLast(SAMPLING_RATE)
                val quality = evaluateSignalQuality(recentSignal)
                signalQualityBuffer.add(quality)

                requireActivity().runOnUiThread {
                    updateSignalQualityDisplay(quality)
                    updatePPGVisualization(lumaMean, quality)
                }
            }
        }
    }

    /* ---------- 신호 품질 평가 ---------- */
    private fun evaluateSignalQuality(signal: List<Float>): Float {
        if (signal.size < 10) return 0f

        val normalizedSignal = normalizeSignal(signal)

        // 1. 패턴 규칙성 (40%)
        val patternScore = evaluatePattern(normalizedSignal)

        // 2. 주파수 일관성 (30%)
        val frequencyScore = evaluateFrequencyContent(normalizedSignal)

        // 3. 신호 안정성 (30%)
        val stabilityScore = calculateNormalizedStability(normalizedSignal)

        val totalScore = (patternScore * 0.4f + frequencyScore * 0.3f + stabilityScore * 0.3f) * 100

        Log.d("PPG_Quality", "Pattern: $patternScore, Freq: $frequencyScore, Stability: $stabilityScore, Total: $totalScore")
        return totalScore
    }

    private fun evaluatePattern(normalizedSignal: List<Float>): Float {
        val peaks = detectRobustPeaks(normalizedSignal)
        if (peaks.size < 3) return 0.2f

        val intervals = peaks.zipWithNext { a, b -> (b - a).toFloat() }
        val mean = intervals.average().toFloat()
        val std = intervals.standardDeviation()
        val cv = if (mean > 0) std / mean else Float.MAX_VALUE

        return when {
            cv < 0.15f -> 1.0f
            cv < 0.25f -> 0.8f
            cv < 0.40f -> 0.6f
            else -> 0.3f
        }
    }

    private fun evaluateFrequencyContent(signal: List<Float>): Float {
        val mean = signal.average().toFloat()
        val crossings = signal.zipWithNext().count { (a, b) ->
            (a <= mean && b > mean) || (a >= mean && b < mean)
        }

        val freq = crossings.toFloat() / (signal.size / SAMPLING_RATE.toFloat()) / 2
        return when {
            freq in 0.5f..3.0f -> 1.0f
            freq in 0.3f..4.0f -> 0.7f
            else -> 0.3f
        }
    }

    private fun calculateNormalizedStability(normalizedSignal: List<Float>): Float {
        if (normalizedSignal.size < 5) return 0f

        val diffs = normalizedSignal.zipWithNext { a, b -> abs(b - a) }
        val avgDiff = diffs.average().toFloat()
        val diffStd = diffs.standardDeviation()
        val diffCV = if (avgDiff > 0) diffStd / avgDiff else Float.MAX_VALUE

        return when {
            diffCV < 0.5f -> 1.0f
            diffCV < 1.0f -> 0.8f
            diffCV < 2.0f -> 0.6f
            else -> 0.3f
        }
    }

    /* ---------- UI 업데이트 ---------- */
    private fun updateSignalQualityDisplay(quality: Float) {
        val qualityText = when {
            quality >= 50 -> "좋음"
            quality >= 40 -> "보통"
            quality >= 30 -> "낮음"
            else -> "매우 낮음"
        }

        binding.tvSignalQuality.apply {
            text = qualityText
            setTextColor(when {
                quality >= 50 -> requireContext().getColor(android.R.color.holo_blue_dark)
                quality >= 40 -> requireContext().getColor(android.R.color.holo_orange_light)
                else -> requireContext().getColor(android.R.color.holo_red_dark)
            })
        }
    }

    private fun updatePPGVisualization(value: Float, quality: Float) {
        binding.ppgWaveformView?.addDataPoint(value)
        binding.ppgWaveformView?.setSignalQuality(
            when {
                quality >= 50 -> PPGWaveformView.SignalQuality.GOOD
                quality >= 40 -> PPGWaveformView.SignalQuality.POOR
                else -> PPGWaveformView.SignalQuality.NONE
            },
            quality / 100f
        )
    }

    /* ─────────── 실측 & 타이머 ─────────── */
    private fun startActualMeasurement() {
        requireActivity().runOnUiThread {
            updateState(MeasurementState.InProgress(0f))
            clearMeasurementData()

            binding.apply {
                tvInstruction.text = "카메라에 손가락을 올려주세요"
                fingerGuideImage.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                tvProgress.visibility = View.VISIBLE
            }
        }

        Handler(bgHandler.looper).postDelayed({
            measurementTimer = object : CountDownTimer(MEASUREMENT_TIME, 200) {
                override fun onTick(msLeft: Long) {
                    val progress = ((MEASUREMENT_TIME - msLeft) / MEASUREMENT_TIME.toFloat()) * 100

                    requireActivity().runOnUiThread {
                        updateState(MeasurementState.InProgress(progress))
                        binding.progressBar.progress = progress.toInt()
                        binding.tvProgress.text = "측정 중... ${progress.toInt()}%"

                        val remainingSeconds = (msLeft / 1000 + 1).toInt()
                        binding.tvTimer.text = "${remainingSeconds}초"
                    }
                }

                override fun onFinish() {
                    Log.d("PPG", "측정 완료. 총 신호 길이: ${rawPPGSignal.size}")
                    closeCamera()
                    analyzeCompleteMeasurement()
                }
            }.start()
        }, 2000)
    }

    private fun estimateInstantHeartRate(): Float {
        val recentSignal = rawPPGSignal.takeLast(SAMPLING_RATE * 10)
        val filtered = preprocessSignal(recentSignal, true)
        val peaks = detectRobustPeaks(filtered)

        return if (peaks.size >= 2) {
            val intervals = peaks.zipWithNext { a, b -> b - a }
            60f * SAMPLING_RATE / intervals.average().toFloat()
        } else 60f
    }

    /* ────────── 신호 분석 (의학적 기준 적용) ────────── */
    private fun analyzeCompleteMeasurement() {
        lifecycleScope.launch {
            updateState(MeasurementState.InProgress(100f))

            // UI 업데이트 (메인 스레드)
            withContext(Dispatchers.Main) {
                binding.apply {
                    tvInstruction.text = "측정 완료"
                    tvInstruction.setTextColor(requireContext().getColor(R.color.primary_color))

                    // 측정 카드 전체 숨기기
                    measurementCard.visibility = View.GONE

                    progressBar.visibility = View.GONE
                    tvProgress.visibility = View.GONE
                }
            }

            // 신호 분석 (백그라운드)
            val result = withContext(Dispatchers.Default) {
                processCompleteSignalWithMedicalStandards()
            }

            // 결과 표시 (메인 스레드)
            displayImprovedResults(result)
        }
    }

    private fun processCompleteSignalWithMedicalStandards(): PPGMeasurementResult {
        Log.d("PPG", "===== 개선된 신호 분석 시작 =====")
        Log.d("PPG", "원본 신호 길이: ${rawPPGSignal.size}")

        // 1단계: 신호 품질 검증 (최소 기준만 확인)
        val avgQuality = signalQualityBuffer.average().toFloat()
        Log.d("PPG", "평균 신호 품질: ${avgQuality}%")

        if (avgQuality < MIN_SIGNAL_QUALITY_THRESHOLD) {
            return PPGMeasurementResult(
                isValid = false,
                score = 0f,
                workFitness = WorkFitnessLevel.MEASUREMENT_FAILED,
                heartRate = 0f,
                hrv = 0f,
                signalQuality = avgQuality,
                criticalFlags = listOf("신호 품질 부족 (${avgQuality.toInt()}%)"),
                additionalMetrics = emptyMap(),
                errorMessage = "신호 품질이 측정 기준 미달입니다"
            )
        }

        // 2단계: 신호 전처리
        val processed = preprocessSignal(rawPPGSignal)
        Log.d("PPG", "전처리 후 신호 길이: ${processed.size}")

        val minSamples = (SAMPLING_RATE * 10).toInt()
        if (processed.size < minSamples) {
            return PPGMeasurementResult(
                isValid = false,
                score = 0f,
                workFitness = WorkFitnessLevel.MEASUREMENT_FAILED,
                heartRate = 0f,
                hrv = 0f,
                signalQuality = avgQuality,
                criticalFlags = listOf("측정 시간 부족"),
                additionalMetrics = emptyMap(),
                errorMessage = "유효한 신호가 부족합니다"
            )
        }

        // 3단계: 심박 검출
        val beats = detectHeartbeats(processed)
        Log.d("PPG", "검출된 심박 수: ${beats.size}")

        if (beats.size < 5) {
            val aggressiveBeats = detectHeartbeatsAggressive(processed)
            if (aggressiveBeats.size < 5) {
                return PPGMeasurementResult(
                    isValid = false,
                    score = 0f,
                    workFitness = WorkFitnessLevel.MEASUREMENT_FAILED,
                    heartRate = 0f,
                    hrv = 0f,
                    signalQuality = avgQuality,
                    criticalFlags = listOf("심박 검출 실패"),
                    additionalMetrics = emptyMap(),
                    errorMessage = "심박을 충분히 검출할 수 없습니다"
                )
            }
            return processWithMedicalStandards(aggressiveBeats, avgQuality, processed)
        }

        return processWithMedicalStandards(beats, avgQuality, processed)
    }

    // 의학적/산업 기준으로 점수 산출 + 안전 등급 평가
    private fun processWithMedicalStandards(
        beats: List<Int>,
        signalQuality: Float,
        processed: List<Float>
    ): PPGMeasurementResult {
        val hr = calculateHeartRate(beats)
        val hrv = calculateHRV(beats)
        val additionalMetrics = extractAdditionalFeatures(processed, beats)
        val criticalFlags = mutableListOf<String>()

        Log.d("PPG", "측정 결과 - HR: $hr BPM, HRV: $hrv ms")

        // 점수 계산 (의학적 기준 반영)
        val score = calculateImprovedScore(hr, hrv, signalQuality, additionalMetrics, criticalFlags)

        // 안전 등급 결정 (점수 기반)
        val workFitness = determineWorkFitnessFromScore(score, hr, hrv, criticalFlags)

        return PPGMeasurementResult(
            isValid = true,
            score = score,
            workFitness = workFitness,
            heartRate = hr,
            hrv = hrv,
            signalQuality = signalQuality,
            criticalFlags = criticalFlags,
            additionalMetrics = additionalMetrics
        )
    }

    // 점수 계산 (의학적 안전 기준 반영)
    private fun calculateImprovedScore(
        hr: Float,
        hrv: Float,
        signalQuality: Float,
        features: Map<String, Float>,
        criticalFlags: MutableList<String>
    ): Float {
        var score = 0f

        // 1. 심박수 점수 (40%) - 의학적 기준 적용
        val hrScore = when {
            // Critical 범위: 0점
            hr < CRITICAL_HR_MIN -> {
                criticalFlags.add("심박수 위험 수준 (${hr.toInt()} < $CRITICAL_HR_MIN)")
                0f
            }
            hr > CRITICAL_HR_MAX -> {
                criticalFlags.add("심박수 위험 수준 (${hr.toInt()} > $CRITICAL_HR_MAX)")
                0f
            }
            // Warning 범위: 감점
            hr < WARNING_HR_MIN -> {
                criticalFlags.add("심박수 낮음 (${hr.toInt()} < $WARNING_HR_MIN)")
                40f
            }
            hr > WARNING_HR_MAX -> {
                criticalFlags.add("심박수 높음 (${hr.toInt()} > $WARNING_HR_MAX)")
                40f
            }
            // 이상적 범위: 만점
            hr in 60f..80f -> 100f
            hr in 55f..85f -> 90f
            hr in 50f..90f -> 85f
            hr in 45f..100f -> 75f
            else -> 60f
        }
        score += hrScore * 0.4f

        // 2. HRV 점수 (35%) - 의학적 기준 적용
        val hrvScore = when {
            // Critical: 0점
            hrv < CRITICAL_HRV_MIN -> {
                criticalFlags.add("HRV 위험 수준 (${hrv.toInt()}ms < $CRITICAL_HRV_MIN)")
                0f
            }
            // Warning: 감점
            hrv < WARNING_HRV_MIN -> {
                criticalFlags.add("HRV 낮음 (${hrv.toInt()}ms < $WARNING_HRV_MIN)")
                30f
            }
            // 정상 범위
            hrv > 50 -> 100f
            hrv > 35 -> 90f
            hrv > 25 -> 80f
            hrv > 20 -> 70f
            else -> 50f
        }
        score += hrvScore * 0.35f

        // 3. 신호 품질 점수 (15%) - 품질에 따른 신뢰도 반영
        val qualityScore = when {
            signalQuality >= 80 -> 100f
            signalQuality >= 70 -> 90f
            signalQuality >= 60 -> 80f
            signalQuality >= 50 -> 70f
            signalQuality >= 40 -> 60f
            else -> 30f
        }
        score += qualityScore * 0.15f

        // 4. 추가 생체 특징 (10%)
        val featureScore = calculateFeatureScore(features)
        score += featureScore * 0.1f

        val finalScore = score.coerceIn(0f, 100f)
        Log.d("PPG", "점수 계산: HR=$hrScore, HRV=$hrvScore, Quality=$qualityScore, Feature=$featureScore, Final=$finalScore")

        return finalScore
    }

    // 점수 기반 안전 등급 결정
    private fun determineWorkFitnessFromScore(
        score: Float,
        hr: Float,
        hrv: Float,
        criticalFlags: List<String>
    ): WorkFitnessLevel {
        // Critical 조건이 있으면 무조건 CRITICAL
        if (hr < CRITICAL_HR_MIN || hr > CRITICAL_HR_MAX || hrv < CRITICAL_HRV_MIN) {
            return WorkFitnessLevel.CRITICAL
        }

        // 점수 기반 등급
        return when {
            score >= 85f -> WorkFitnessLevel.EXCELLENT
            score >= 75f -> WorkFitnessLevel.GOOD
            score >= 60f -> WorkFitnessLevel.FAIR
            score >= 40f -> WorkFitnessLevel.POOR
            else -> WorkFitnessLevel.CRITICAL
        }
    }

    private fun calculateFeatureScore(features: Map<String, Float>): Float {
        var score = 50f // 기본 점수

        // LF/HF ratio (자율신경계 균형)
        features["lf_hf_ratio"]?.let { ratio ->
            score += when {
                ratio in 0.5f..2.0f -> 25f    // 이상적
                ratio in 0.3f..3.0f -> 15f    // 양호
                else -> 0f                     // 불균형
            }
        }

        // Signal stability
        features["signal_stability"]?.let { stability ->
            score += stability * 25f
        }

        return score.coerceIn(0f, 100f)
    }

    /* ─────────── Signal Processing ─────────── */
    private fun preprocessSignal(signal: List<Float>, simple: Boolean = false): List<Float> {
        if (signal.size < SAMPLING_RATE) return signal

        val detrended = seeingRedDetrend(signal)
        if (simple) return lightSmoothing(detrended)

        val filtered = seeingRedLowPass(detrended)
        return normalizeSignal(filtered)
    }

    private fun seeingRedDetrend(signal: List<Float>): List<Float> {
        if (signal.size < SAMPLING_RATE) return signal

        val windowSize = SAMPLING_RATE
        val result = mutableListOf<Float>()

        for (i in signal.indices) {
            val start = maxOf(0, i - windowSize / 2)
            val end = minOf(signal.size, i + windowSize / 2)
            val rollingAvg = signal.subList(start, end).average().toFloat()
            result.add(signal[i] - rollingAvg)
        }
        return result
    }

    private fun seeingRedLowPass(signal: List<Float>): List<Float> {
        val cutoffWindow = 8
        if (signal.size < cutoffWindow) return signal

        return signal.windowed(cutoffWindow, 1) { window ->
            window.average().toFloat()
        }
    }

    private fun lightSmoothing(signal: List<Float>): List<Float> {
        if (signal.size < 3) return signal
        return signal.windowed(3, 1) { window ->
            window.average().toFloat()
        }
    }

    private fun normalizeSignal(signal: List<Float>): List<Float> {
        val mean = signal.average().toFloat()
        val variance = signal.map { (it - mean).pow(2) }.average().toFloat()
        val std = sqrt(variance)
        return if (std > 0) {
            signal.map { (it - mean) / std }
        } else {
            signal
        }
    }

    private fun detectHeartbeats(signal: List<Float>): List<Int> {
        Log.d("PPG", "심박 검출 시작. 신호 길이: ${signal.size}")
        val peaks = detectRobustPeaks(signal)
        Log.d("PPG", "초기 피크 검출: ${peaks.size}개")
        val filtered = filterPhysiologicallyValidPeaks(peaks)
        Log.d("PPG", "필터링 후 피크: ${filtered.size}개")
        return filtered
    }

    private fun detectHeartbeatsAggressive(signal: List<Float>): List<Int> {
        Log.d("PPG", "공격적 심박 검출 시작")
        val peaks = detectAggressivePeaks(signal)
        Log.d("PPG", "공격적 피크 검출: ${peaks.size}개")
        val filtered = filterPhysiologicallyValidPeaksRelaxed(peaks)
        Log.d("PPG", "관대한 필터링 후: ${filtered.size}개")
        return filtered
    }

    private fun detectRobustPeaks(signal: List<Float>): List<Int> {
        if (signal.size < 10) return emptyList()

        val sorted = signal.sorted()
        val percentile75 = sorted[(sorted.size * 0.75).toInt()]
        val percentile25 = sorted[(sorted.size * 0.25).toInt()]
        val iqr = percentile75 - percentile25
        val threshold = percentile75 - 0.5f * iqr
        val minDistance = (SAMPLING_RATE * 0.3).toInt()

        val peaks = mutableListOf<Int>()
        var lastPeak = -minDistance

        for (i in 1 until signal.size - 1) {
            if (signal[i] > threshold &&
                signal[i] > signal[i - 1] &&
                signal[i] > signal[i + 1] &&
                i - lastPeak >= minDistance) {

                peaks.add(i)
                lastPeak = i
            }
        }

        Log.d("PPG", "Detected ${peaks.size} peaks with threshold: $threshold")
        return peaks
    }

    private fun detectAggressivePeaks(signal: List<Float>): List<Int> {
        if (signal.size < 10) return emptyList()

        val smoothed = signal.windowed(3, 1) { it.average().toFloat() }
        val valleys = findValleysRelaxed(smoothed)
        val peaks = mutableListOf<Int>()

        for (i in 0 until valleys.size - 1) {
            val segmentStart = valleys[i]
            val segmentEnd = minOf(valleys[i + 1], signal.size - 1)

            if (segmentEnd > segmentStart + 3) {
                val segment = signal.subList(segmentStart, segmentEnd)
                val maxIdx = segment.indexOf(segment.maxOrNull() ?: 0f)
                val absoluteIdx = segmentStart + maxIdx

                if (isValidPeakRelaxed(signal, absoluteIdx, peaks.lastOrNull())) {
                    peaks.add(absoluteIdx)
                }
            }
        }

        Log.d("PPG", "Aggressive hybrid: ${valleys.size} valleys → ${peaks.size} peaks")
        return peaks
    }

    private fun findValleysRelaxed(smoothed: List<Float>): List<Int> {
        val valleys = mutableListOf<Int>()

        for (i in 1 until smoothed.size - 1) {
            if (smoothed[i] <= smoothed[i - 1] &&
                smoothed[i] <= smoothed[i + 1]) {
                valleys.add(i)
            }
        }

        val minDistance = (SAMPLING_RATE * 0.25).toInt()
        if (valleys.size < 2) return valleys

        val filtered = mutableListOf(valleys[0])
        for (i in 1 until valleys.size) {
            if (valleys[i] - filtered.last() >= minDistance) {
                filtered.add(valleys[i])
            }
        }
        return filtered
    }

    private fun isValidPeakRelaxed(signal: List<Float>, peakIdx: Int, lastPeakIdx: Int?): Boolean {
        if (peakIdx <= 0 || peakIdx >= signal.size - 1) return false

        if (signal[peakIdx] < signal[peakIdx - 1] ||
            signal[peakIdx] < signal[peakIdx + 1]) return false

        if (lastPeakIdx != null) {
            val interval = peakIdx - lastPeakIdx
            val minInterval = (SAMPLING_RATE * 0.25).toInt()
            val maxInterval = (SAMPLING_RATE * 1.5).toInt()

            if (interval < minInterval || interval > maxInterval) return false
        }

        if (signal[peakIdx] < -1.0f) return false
        return true
    }

    private fun filterPhysiologicallyValidPeaks(peaks: List<Int>): List<Int> {
        if (peaks.size < 3) return peaks

        val intervals = peaks.zipWithNext { a, b -> b - a }
        val medianInterval = intervals.sorted()[intervals.size / 2]

        val filtered = mutableListOf(peaks[0])
        for (i in 1 until peaks.size) {
            val interval = peaks[i] - filtered.last()
            if (interval in (medianInterval * 0.5).toInt()..(medianInterval * 1.5).toInt()) {
                filtered.add(peaks[i])
            }
        }

        Log.d("PPG", "Filtered peaks: ${peaks.size} → ${filtered.size}")
        return filtered
    }

    private fun filterPhysiologicallyValidPeaksRelaxed(peaks: List<Int>): List<Int> {
        if (peaks.size < 2) return peaks

        val intervals = peaks.zipWithNext { a, b -> b - a }
        val avgInterval = intervals.average().toFloat()

        val filtered = mutableListOf(peaks[0])
        for (i in 1 until peaks.size) {
            val interval = peaks[i] - filtered.last()
            if (interval in (avgInterval * 0.3).toInt()..(avgInterval * 1.7).toInt()) {
                filtered.add(peaks[i])
            }
        }

        Log.d("PPG", "Relaxed filter: ${peaks.size} → ${filtered.size}")
        return filtered
    }

    private fun calculateHeartRate(beats: List<Int>): Float {
        if (beats.size < 2) return 0f

        val intervals = beats.zipWithNext { a, b -> (b - a).toFloat() }
        val avgIntervalInSamples = intervals.average()
        val rawBpm = ((SAMPLING_RATE * 60f) / avgIntervalInSamples).toFloat()
        val clampedBpm = rawBpm.coerceIn(40f, 150f)

        Log.d("PPG", "Heart rate: raw=$rawBpm, clamped=$clampedBpm, intervals=${intervals.size}")
        return clampedBpm
    }

    private fun calculateHRV(beats: List<Int>): Float {
        if (beats.size < 3) return 0f

        val intervals = beats.zipWithNext { a, b ->
            (b - a).toFloat() * (1000f / SAMPLING_RATE)
        }

        val successiveDiffs = intervals.zipWithNext { a, b -> (b - a).pow(2) }
        val rmssd = sqrt(successiveDiffs.average().toFloat())

        Log.d("PPG", "HRV calculation: RMSSD = $rmssd ms")
        return rmssd
    }

    private fun extractAdditionalFeatures(signal: List<Float>, beats: List<Int>): Map<String, Float> {
        val features = mutableMapOf<String, Float>()

        // LF/HF ratio 계산 (자율신경계 균형)
        val lfHfRatio = calculateLFHFRatio(signal, beats)
        if (lfHfRatio > 0) {
            features["lf_hf_ratio"] = lfHfRatio
        }

        // 신호 안정성
        features["signal_stability"] = calculateNormalizedStability(signal)

        // 펄스 진폭 변동성
        if (beats.size >= 2) {
            val amplitudes = extractPulseAmplitudes(signal, beats)
            if (amplitudes.isNotEmpty()) {
                features["amplitude_variability"] = amplitudes.standardDeviation()
            }
        }

        return features
    }

    private fun calculateLFHFRatio(signal: List<Float>, beats: List<Int>): Float {
        if (beats.size < 5) return 0f

        // 주파수 도메인 분석
        val intervals = beats.zipWithNext { a, b ->
            (b - a).toFloat() * (1000f / SAMPLING_RATE)
        }

        if (intervals.size < 3) return 0f

        var lfPower = 0f
        var hfPower = 0f

        // 스펙트럼 분석
        for (i in 1 until intervals.size) {
            val freq = abs(intervals[i] - intervals[i-1]) / intervals.average()
            val power = intervals[i].pow(2)

            when {
                freq in 0.04f..0.15f -> lfPower += power
                freq in 0.15f..0.4f -> hfPower += power
            }
        }

        return if (hfPower > 0) lfPower / hfPower else 0f
    }

    private fun extractPulseAmplitudes(signal: List<Float>, beats: List<Int>): List<Float> {
        val amplitudes = mutableListOf<Float>()

        for (i in 0 until beats.size - 1) {
            val start = beats[i]
            val end = minOf(beats[i + 1], signal.size)

            if (end > start) {
                val segment = signal.subList(start, end)
                val max = segment.maxOrNull() ?: 0f
                val min = segment.minOrNull() ?: 0f
                amplitudes.add(max - min)
            }
        }

        return amplitudes
    }

    /* ────────── 결과 표시 ────────── */
    private fun displayImprovedResults(result: PPGMeasurementResult) {
        requireActivity().runOnUiThread {
            binding.apply {
                // UI 상태 변경
                progressBar.visibility = View.GONE
                tvProgress.visibility = View.GONE
                measurementInfo.visibility = View.GONE
                measurementCard.visibility = View.GONE
                resultCard.visibility = View.VISIBLE
                initialButtons.visibility = View.GONE
                resultButtons.visibility = View.VISIBLE

                if (result.isValid) {
                    // 점수 중심 표시 + 안전 등급 보조
                    tvResult.text = "${result.score.toInt()}점"
                    tvResult.setTextColor(requireContext().getColor(result.workFitness.colorRes))

                    // 측정값 표시
                    tvHeartRate.text = "${result.heartRate.toInt()} BPM"
                    tvHRV.text = "${result.hrv.toInt()} ms"
                    tvMeasurementTime.text = "10초"

                    // 안전 등급 표시
                    tvWorkFitness.text = result.workFitness.description
                    tvWorkFitness.setTextColor(requireContext().getColor(result.workFitness.colorRes))
                    tvWorkFitness.visibility = View.VISIBLE

                    // 상세 설명 (Critical flags 포함)
                    tvResultDetail.text = buildDetailedDescription(result)

                    // 아이콘 색상
                    val iconColor = requireContext().getColor(result.workFitness.colorRes)
                    ivResultIcon.setColorFilter(iconColor)

                    // 다음 단계 결정 (점수 기반)
                    when (result.workFitness) {
                        WorkFitnessLevel.EXCELLENT, WorkFitnessLevel.GOOD -> {
                            if (measurementType == MeasurementType.PPG) {
                                btnNext.text = "결과 보기" // 심혈관 체크까지 끝낸 경우
                            } else {
                                btnNext.text = "다음 측정"
                            }
                            btnNext.setBackgroundColor(requireContext().getColor(R.color.primary_color))
                        }
                        WorkFitnessLevel.FAIR -> {
                            btnNext.text = "계속 진행"
                            btnNext.setBackgroundColor(requireContext().getColor(android.R.color.holo_orange_light))
                        }
                        WorkFitnessLevel.POOR -> {
                            btnNext.text = "주의사항 확인 후 계속"
                            btnNext.setBackgroundColor(requireContext().getColor(android.R.color.holo_orange_dark))
                        }
                        WorkFitnessLevel.CRITICAL -> {
                            btnNext.text = "재측정 권장"
                            btnNext.setBackgroundColor(requireContext().getColor(android.R.color.holo_red_dark))

                            // CRITICAL인 경우 주의 안내
                            showCriticalDialog(result)
                        }
                        WorkFitnessLevel.MEASUREMENT_FAILED -> {
                            btnNext.text = "다시 측정"
                            btnNext.setBackgroundColor(requireContext().getColor(android.R.color.darker_gray))
                        }
                    }

                } else {
                    // 측정 실패
                    tvResult.text = "측정 실패"
                    tvResult.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                    tvResultDetail.text = result.errorMessage ?: "측정을 다시 시도해 주세요"
                    tvHeartRate.text = "--"
                    tvHRV.text = "--"
                    tvWorkFitness.visibility = View.GONE
                    btnNext.text = "다시 측정"
                }

                // 버튼 액션 설정
                btnNext.setOnClickListener {
                    handleNextAction(result)
                    saveStartTimeToFirestore()
                }
            }
        }
    }

    private fun buildDetailedDescription(result: PPGMeasurementResult): String {
        val description = StringBuilder()

        // 점수 기반 설명
        when {
            result.score >= 85f -> {
                description.append("매우 우수한 심혈관 상태입니다.")
            }
            result.score >= 75f -> {
                description.append("양호한 심혈관 상태입니다.")
            }
            result.score >= 60f -> {
                description.append("보통 수준의 심혈관 상태입니다.")
            }
            result.score >= 40f -> {
                description.append("주의가 필요한 상태입니다.")
            }
            else -> {
                description.append("위험 수준입니다. 즉시 휴식이 필요합니다.")
            }
        }

        // Critical flags 추가
        if (result.criticalFlags.isNotEmpty()) {
            description.append("\n\n주의사항:")
            result.criticalFlags.forEach { flag ->
                description.append("\n• $flag")
            }
        }

        // 안전 등급별 권고사항
        when (result.workFitness) {
            WorkFitnessLevel.CRITICAL -> {
                description.append("\n\n🚨 충분한 휴식 후 재측정을 권장합니다.")
            }
            WorkFitnessLevel.POOR -> {
                description.append("\n\n⚠️ 작업 강도를 조절하시기 바랍니다.")
            }
            else -> {
                // 정상 범위는 추가 메시지 없음
            }
        }

        return description.toString()
    }

    private fun showCriticalDialog(result: PPGMeasurementResult) {
        // AlertDialog로 위험 상황 안내
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("⚠️ 주의 필요")
            .setMessage("현재 측정 결과가 위험 수준입니다.\n\n${result.criticalFlags.joinToString("\n")}\n\n충분한 휴식 후 재측정하시기 바랍니다.")
            .setPositiveButton("재측정") { _, _ ->
                resetMeasurement()
                startMeasurement()
            }
            .setNegativeButton("그래도 계속", null)
            .setCancelable(false)
            .show()
    }

    private fun handleNextAction(result: PPGMeasurementResult) {
        when (result.workFitness) {
            WorkFitnessLevel.EXCELLENT,
            WorkFitnessLevel.GOOD,
            WorkFitnessLevel.FAIR,
            WorkFitnessLevel.POOR,
            WorkFitnessLevel.CRITICAL -> {

                val rawData = buildRawDataJson(result)
                val sessionId = args.sessionId // 이미 SafeArgs로 받은 세션 ID

                // VM에 결과 반영하기
                onMeasurementComplete(result.score, rawData)

                val bundle = Bundle().apply {
                    putBoolean("isMeasurementComplete", true) // 측정이 완료되었다는 신호
                    putString("sessionId", sessionId)
                    putString("rawData", rawData)
                }
                findNavController().navigate(R.id.action_ppg_to_result, bundle)
            }
            WorkFitnessLevel.MEASUREMENT_FAILED -> {
                resetMeasurement()
                startMeasurement()
            }
        }
    }

    override fun onLastMeasurementComplete(sessionId: String, rawData: String?) {
        Log.d("PPGMeasurement", "onLastMeasurementComplete 호출")

        // viewLifecycleOwner가 활성 상태인지 확인
        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Log.w("PPGMeasurement", "View lifecycle가 활성 상태가 아님")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // MainFragment로 이동 + sessionId 전달
                val bundle = Bundle().apply {
                    putString("sessionId", sessionId)
                    putString("navigateTo", "result")  // MainFragment에서 result로 이동하라는 플래그
                }

                findNavController().navigate(
                    R.id.mainFragment,
                    bundle,
                    androidx.navigation.navOptions {
                        popUpTo(R.id.mainFragment) {
                            inclusive = false
                        }
                        launchSingleTop = true
                    }
                )
            } catch (e: Exception) {
                Log.e("PPGMeasurement", "네비게이션 실패", e)
            }
        }
    }

    private fun buildRawDataJson(result: PPGMeasurementResult): String {
        return """
        {
            "isValid": ${result.isValid},
            "score": ${result.score},
            "workFitness": "${result.workFitness.name}",
            "heartRate": ${result.heartRate},
            "hrv": ${result.hrv},
            "signalQuality": ${result.signalQuality},
            "criticalFlags": [${result.criticalFlags.joinToString(",") { "\"$it\"" }}],
            "signalLength": ${rawPPGSignal.size},
            "duration": $MEASUREMENT_TIME,
            "samplingRate": $SAMPLING_RATE,
            "medicalStandards": {
                "hrCriticalRange": "$CRITICAL_HR_MIN-$CRITICAL_HR_MAX",
                "hrvCriticalMin": $CRITICAL_HRV_MIN,
                "signalQualityMin": $MIN_SIGNAL_QUALITY_THRESHOLD
            },
            "additionalMetrics": {
                ${result.additionalMetrics.entries.joinToString(",\n") {
            "\"${it.key}\": ${it.value}"
        }}
            },
            "timestamp": ${System.currentTimeMillis()}
        }
        """.trimIndent()
    }

    /* ─────────── Helper Functions ─────────── */
    private fun List<Float>.variance(): Float {
        if (isEmpty()) return 0f
        val mean = average().toFloat()
        return map { (it - mean).pow(2) }.average().toFloat()
    }

    private fun List<Float>.standardDeviation(): Float = sqrt(variance())

    private fun showWarning(message: String) {
        requireActivity().runOnUiThread {
            binding.tvInstruction.apply {
                text = "⚠️ $message"
                setTextColor(requireContext().getColor(android.R.color.holo_orange_dark))
            }
        }
    }

    /* ─────────── 상태 UI 업데이트 ─────────── */
    override fun onStateChanged(state: MeasurementState) {
        when (state) {
            is MeasurementState.Preparing -> {
                binding.apply {
                    btnStart.isEnabled = false
                    tvInstruction.text = "카메라 준비 중..."
                    tvSignalQuality.text = "준비 중"
                    tvTimer.text = "10초"
                }
            }

            is MeasurementState.InProgress -> {
                binding.apply {
                    progressBar.progress = state.progress.toInt()
                    tvProgress.text = "측정 중... ${state.progress.toInt()}%"
                }
            }

            is MeasurementState.Completed -> {
                // displayImprovedResults에서 처리됨
            }

            is MeasurementState.Error -> {
                binding.apply {
                    tvInstruction.text = state.message
                    tvInstruction.setTextColor(requireContext().getColor(android.R.color.holo_red_dark))
                    btnStart.isEnabled = true
                    progressBar.visibility = View.GONE
                    tvProgress.visibility = View.GONE
                    initialButtons.visibility = View.VISIBLE
                    resultButtons.visibility = View.GONE
                }
            }

            else -> { /* Idle 등 */ }
        }
    }

    /* ─────────── Reset / Cleanup ─────────── */
    private fun resetMeasurement() {
        measurementTimer?.cancel()
        clearMeasurementData()
        closeCamera()

        binding.apply {
            btnStart.isEnabled = true
            progressBar.visibility = View.GONE
            tvProgress.visibility = View.GONE

            // 측정 카드와 내부 요소들 다시 보이기
            measurementCard.visibility = View.VISIBLE
            fingerGuideCard.visibility = View.VISIBLE
            waveformCard.visibility = View.VISIBLE
            measurementInfoCard.visibility = View.VISIBLE
            measurementInfo.visibility = View.VISIBLE

            resultCard.visibility = View.GONE
            initialButtons.visibility = View.VISIBLE
            resultButtons.visibility = View.GONE

            tvInstruction.text = "카메라에 손가락을 올려주세요"
            tvInstruction.setTextColor(requireContext().getColor(R.color.text_primary))
            tvTimer.text = "10초"
            tvSignalQuality.text = "준비 중"
            fingerGuideImage.visibility = View.VISIBLE
            tvWorkFitness.visibility = View.GONE

            // 파형뷰 초기화 (있다면)
            ppgWaveformView?.clear()
        }
    }

    private fun clearMeasurementData() {
        rawPPGSignal.clear()
        timestamps.clear()
        lumaValues.clear()
        redChannel.clear()
        greenChannel.clear()
        blueChannel.clear()
        signalQualityBuffer.clear()
    }

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        measurementTimer?.cancel()
        closeCamera()
        if (::bgThread.isInitialized) stopBgThread()
        _binding = null
    }

    // 작업 시작 시간을 Firebase에 업로드. WorkTime/날짜의 필드로 사번 : { StartTime : "시간" } 업로드
    private fun saveStartTimeToFirestore() {
        val db = FirebaseFirestore.getInstance()
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val empNum = prefs.getString("emp_num", null)

        if (empNum.isNullOrEmpty()) return

        // 현재 날짜와 시간 계산
        val currentDate = LocalDate.now().toString() // 예: "2025-11-08"
        val currentTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        // Firestore에 저장할 데이터
        val data = mapOf(
            empNum to mapOf("StartTime" to currentTime)
        )

        // Firestore 업로드 (merge 옵션으로 다른 사번 데이터 보존)
        db.collection("WorkTime")
            .document(currentDate)
            .set(data, SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "작업이 정상적으로 시작되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "작업 시작 처리에 실패하였습니다.", Toast.LENGTH_SHORT).show()
            }
    }
}