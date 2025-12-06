package com.jjangdol.biorhythm.ui.measurement

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentTremorMeasurementBinding
import com.jjangdol.biorhythm.model.MeasurementResult
import com.jjangdol.biorhythm.model.MeasurementState
import com.jjangdol.biorhythm.model.MeasurementType
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import org.jtransforms.fft.DoubleFFT_1D

// 떨림 임계값 데이터 클래스
data class TremorThresholds(
    val normalAvgTremor: Float,
    val normalMaxTremor: Float,
    val normalStdDev: Float
)

@AndroidEntryPoint
class TremorMeasurementFragment : BaseMeasurementFragment(),
    SensorEventListener {

    private var _binding: FragmentTremorMeasurementBinding? = null
    private val binding get() = _binding!!

    private lateinit var sessionId: String

    override val measurementType = MeasurementType.TREMOR
    override val requiredPermissions = arrayOf<String>() // 센서는 권한 불필요
    override val nextNavigationAction = R.id.action_tremor_to_pupil

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // 측정 데이터
    private val accelerationData = mutableListOf<Triple<Float, Float, Float>>()
    private var baselineAcceleration: Triple<Float, Float, Float>? = null
    private var isCalibrating = true
    private var measurementTimer: CountDownTimer? = null

    // 측정 설정
    private val CALIBRATION_TIME = 3000L // 3초 캘리브레이션
    private val MEASUREMENT_TIME = 10000L // 10초 측정
    private val SAMPLE_RATE = 50 // Hz
    private val MIN_MEASUREMENT_TIME = 9000L // 최소 측정 시간

    // 측정 결과 저장 변수
    private var latestScore: Float = 0f
    private var latestRawData: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTremorMeasurementBinding.inflate(inflater, container, false)
        return binding.root
    }

    // 마지막 측정(심박)이 아니므로 빈 함수로 구현
    override fun onLastMeasurementComplete(sessionId: String, rawData: String?) {}

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionId = arguments?.getString("sessionId") ?: ""

        setupSensor()
        setupUI()
    }

    private fun setupSensor() {
        sensorManager = requireContext().getSystemService(AppCompatActivity.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (accelerometer == null) {
            binding.tvStatus.text = "가속도 센서를 사용할 수 없습니다"
            binding.btnStart.isEnabled = false
        }
    }

    private fun setupUI() {
        binding.btnStart.setOnClickListener { startMeasurement() }
        binding.btnRetry.setOnClickListener {
            resetMeasurement()
            startMeasurement()
        }

        binding.btnNext.setOnClickListener {
            onMeasurementComplete(latestScore, latestRawData)
        }
    }

    override fun startMeasurement() {
        updateState(MeasurementState.Preparing)
        accelerationData.clear()
        isCalibrating = true

        // UI 상태 변경
        binding.initialButtons.visibility = View.GONE
        binding.measurementGuide.visibility = View.VISIBLE
        binding.tvTimer.visibility = View.VISIBLE

        // 센서 리스너 등록
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // 캘리브레이션 시작
        binding.tvStatus.text = "캘리브레이션 중..."
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true

        // 3초 후 실제 측정 시작
        measurementTimer = object : CountDownTimer(CALIBRATION_TIME, 100) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000) + 1
                binding.tvTimer.text = "준비: ${seconds}초"
            }

            override fun onFinish() {
                startActualMeasurement()
            }
        }.start()
    }

    private fun startActualMeasurement() {
        isCalibrating = false
        calculateBaseline()
        updateState(MeasurementState.InProgress(0f))

        binding.tvStatus.text = "측정 중..."
        binding.progressBar.isIndeterminate = false
        binding.tvProgress.visibility = View.VISIBLE

        measurementTimer = object : CountDownTimer(MEASUREMENT_TIME, 100) {
            override fun onTick(millisUntilFinished: Long) {
                val progress = ((MEASUREMENT_TIME - millisUntilFinished).toFloat() / MEASUREMENT_TIME) * 100
                updateState(MeasurementState.InProgress(progress))

                val seconds = (millisUntilFinished / 1000) + 1
                binding.tvTimer.text = "${seconds}초"
                binding.tvProgress.text = "진행률: ${progress.toInt()}%"
            }

            override fun onFinish() {
                Log.d("TremorTimer", "onFinish called")
                sensorManager.unregisterListener(this@TremorMeasurementFragment)
                analyzeTremor()
            }
        }.start()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = it.values[0]
                val y = it.values[1]
                val z = it.values[2]

                accelerationData.add(Triple(x, y, z))

                // 실시간 떨림 시각화 (옵션)
                if (!isCalibrating) {
                    updateRealtimeVisual(x, y, z)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 센서 정확도 로깅
        Log.d("TremorSensor", "Accuracy changed: $accuracy")
    }

    private fun calculateBaseline() {
        if (accelerationData.isNotEmpty()) {
            val avgX = accelerationData.map { it.first }.average().toFloat()
            val avgY = accelerationData.map { it.second }.average().toFloat()
            val avgZ = accelerationData.map { it.third }.average().toFloat()
            baselineAcceleration = Triple(avgX, avgY, avgZ)

            // 측정 시작을 위해 데이터 초기화
            accelerationData.clear()
        }
    }

    private fun analyzeTremor() {
        val baseline = baselineAcceleration ?: return

        if (accelerationData.isEmpty()) {
            updateState(MeasurementState.Error("측정 데이터가 없습니다"))
            return
        }

        // 측정 품질 검증
        if (!validateMeasurementQuality(accelerationData)) {
            updateState(MeasurementState.Error("측정 품질이 불충분합니다. 다시 시도해주세요."))
            return
        }

        // 떨림 분석
        val tremorMagnitudes = accelerationData.map { (x, y, z) ->
            val dx = x - baseline.first
            val dy = y - baseline.second
            val dz = z - baseline.third
            sqrt(dx.pow(2) + dy.pow(2) + dz.pow(2))
        }

        // 통계 계산
        val avgTremor = tremorMagnitudes.average()
        val maxTremor = tremorMagnitudes.maxOrNull() ?: 0f
        val normalizedStdDev = calculateNormalizedStdDev(tremorMagnitudes, baseline)

        // 주파수 분석 FFT 사용
        val peakFrequency = estimateFrequencyWithFFT(accelerationData, SAMPLE_RATE)

        // 적응형 점수 계산 (0-100)
        val score = calculateAdaptiveTremorScore(avgTremor, maxTremor, normalizedStdDev, peakFrequency)

        // 결과 JSON
        val rawData = """
            {
                "avgTremor": $avgTremor,
                "maxTremor": $maxTremor,
                "normalizedStdDev": $normalizedStdDev,
                "peakFrequency": $peakFrequency,
                "sampleCount": ${accelerationData.size},
                "measurementDuration": ${MEASUREMENT_TIME / 1000}
            }
        """.trimIndent()

        latestScore = score
        latestRawData = rawData

        updateState(MeasurementState.Completed(MeasurementResult(measurementType, score, rawData)))

        // UI 업데이트
        showResults(score)
    }

    private fun validateMeasurementQuality(data: List<Triple<Float, Float, Float>>): Boolean {
        // 최소 측정 시간 확인
        val expectedMinSamples = (MIN_MEASUREMENT_TIME / 1000) * SAMPLE_RATE
        if (data.size < expectedMinSamples) {
            Log.w("TremorValidation", "Insufficient data: ${data.size} < $expectedMinSamples")
            return false
        }

        // 센서 오류나 극단적 움직임 감지
        val magnitudes = data.map { (x, y, z) -> sqrt(x.pow(2) + y.pow(2) + z.pow(2)) }
        val mean = magnitudes.average()
        val stdDev = calculateStandardDeviation(magnitudes)

        // 이상치 비율 확인 (3 시그마 규칙)
        val outliers = magnitudes.count { abs(it - mean) > 3 * stdDev }
        val outlierRatio = outliers.toDouble() / data.size

        if (outlierRatio > 0.15) { // 15% 이상의 이상치는 부적절
            Log.w("TremorValidation", "Too many outliers: ${outlierRatio * 100}%")
            return false
        }

        // 센서 포화 상태 확인 (극단적 값)
        val maxMagnitude = magnitudes.maxOrNull() ?: 0f
        if (maxMagnitude > 50f) { // 중력가속도 대비 극단적으로 큰 값
            Log.w("TremorValidation", "Sensor saturation detected: $maxMagnitude")
            return false
        }

        return true
    }

    private fun calculateNormalizedStdDev(values: List<Float>, baseline: Triple<Float, Float, Float>): Float {
        val baselineMagnitude = sqrt(baseline.first.pow(2) + baseline.second.pow(2) + baseline.third.pow(2))
        val stdDev = calculateStandardDeviation(values)

        // 베이스라인 대비 상대적 변동성 계산 (0으로 나누기 방지)
        return if (baselineMagnitude > 0.1f) {
            stdDev / baselineMagnitude
        } else {
            stdDev
        }
    }


    private fun calculateAdaptiveTremorScore(
        avgTremor: Double,
        maxTremor: Float,
        normalizedStdDev: Float,
        frequency: Float
    ): Float {
        /** 생년월일 정보 삭제 및 AgeGroup 삭제로 인해 기본 값만을 사용 */
        val thresholds = TremorThresholds(0.5f, 2.0f, 0.25f) // 기본값

        // 평균 떨림 점수 (개선된 곡선)
        val avgScore = when {
            avgTremor <= thresholds.normalAvgTremor -> 100.0
            avgTremor <= thresholds.normalAvgTremor * 1.5 -> {
                100 - ((avgTremor - thresholds.normalAvgTremor) / thresholds.normalAvgTremor * 30)
            }
            avgTremor <= thresholds.normalAvgTremor * 3 -> {
                70 - ((avgTremor - thresholds.normalAvgTremor * 1.5) / (thresholds.normalAvgTremor * 1.5) * 40)
            }
            else -> {
                30 - ((avgTremor - thresholds.normalAvgTremor * 3) / (thresholds.normalAvgTremor * 2) * 20)
                    .coerceIn(0.0, 30.0)
            }
        }.coerceIn(0.0, 100.0)

        // 최대 떨림 점수 (작업 안전성을 위해 더 엄격하게)
        val maxScore = when {
            maxTremor <= thresholds.normalMaxTremor -> 100f
            maxTremor <= thresholds.normalMaxTremor * 1.5f -> {
                100 - ((maxTremor - thresholds.normalMaxTremor) / thresholds.normalMaxTremor * 40)
            }
            maxTremor <= thresholds.normalMaxTremor * 3f -> {
                60 - ((maxTremor - thresholds.normalMaxTremor * 1.5f) / (thresholds.normalMaxTremor * 1.5f) * 40)
            }
            else -> {
                20 - ((maxTremor - thresholds.normalMaxTremor * 3f) / (thresholds.normalMaxTremor * 2f) * 15)
                    .coerceIn(0f, 20f)
            }
        }.coerceIn(0f, 100f)

        // 정규화된 표준편차 점수
        val stdScore = when {
            normalizedStdDev <= thresholds.normalStdDev -> 100f
            normalizedStdDev <= thresholds.normalStdDev * 2 -> {
                100 - ((normalizedStdDev - thresholds.normalStdDev) / thresholds.normalStdDev * 50)
            }
            else -> {
                50 - ((normalizedStdDev - thresholds.normalStdDev * 2) / thresholds.normalStdDev * 35)
                    .coerceIn(0f, 50f)
            }
        }.coerceIn(0f, 100f)

        // 세분화된 주파수 점수 (임상 기준 강화)
        val freqScore = when {
            frequency == 0f -> 95f                    // 떨림 없음 (매우 좋음)
            frequency in 4f..6f -> 100f               // 생리적 떨림 (정상)
            frequency in 3f..4f || frequency in 6f..8f -> 90f  // 경계 정상
            frequency in 8f..12f -> 80f               // 본태성 떨림 (경미한 우려)
            frequency in 2f..3f || frequency in 12f..15f -> 70f // 주의 필요
            frequency in 1f..2f || frequency in 15f..20f -> 50f // 상당한 우려
            frequency > 20f -> 30f                    // 심각한 떨림
            else -> 40f                               // 기타 비정상
        }

        // 작업 안전성을 위한 가중치 (보수적 접근)
        // 최대 떨림과 평균 떨림에 더 큰 비중을 둠
        val safetyWeight = if (maxTremor > thresholds.normalMaxTremor * 2) 0.1f else 0f

        val finalScore = (
                avgScore * 0.35 +
                        maxScore * 0.35 +
                        stdScore * 0.15 +
                        freqScore * 0.15
                ).toFloat() - safetyWeight * 100f

        return finalScore.coerceIn(0f, 100f)
    }

    private fun showResults(score: Float) {
        // 프로그래스바와 측정 가이드 숨기기
        binding.progressBar.visibility = View.GONE
        binding.tvProgress.visibility = View.GONE
        binding.measurementGuide.visibility = View.GONE
        binding.tvTimer.visibility = View.GONE

        // 상태 업데이트
        binding.tvStatus.text = "측정 완료"

        // 결과 카드 표시
        binding.resultCard.visibility = View.VISIBLE

        // 결과 아이콘 설정 (더 엄격한 기준)
        binding.ivResultIcon.setImageResource(
            when {
                score >= 85 -> R.drawable.ic_check_circle
                score >= 70 -> R.drawable.ic_warning
                else -> R.drawable.ic_error
            }
        )

        // 결과 아이콘 색상 설정
        binding.ivResultIcon.setColorFilter(
            ContextCompat.getColor(requireContext(), when {
                score >= 85 -> R.color.safety_safe
                score >= 70 -> R.color.safety_caution
                else -> R.color.safety_danger
            })
        )

        // 결과 텍스트 설정
        binding.tvResult.text = "손떨림 점수: ${score.toInt()}점"

        binding.tvResultDetail.text = when {
            score >= 85 -> "작업에 적합한 안정적인 상태입니다"
            score >= 70 -> "주의하여 작업하시기 바랍니다"
            score >= 50 -> "작업 전 충분한 휴식이 필요합니다"
            else -> "작업을 중단하고 휴식을 취하세요"
        }

        // 버튼 그룹 변경
        binding.initialButtons.visibility = View.GONE
        binding.resultButtons.visibility = View.VISIBLE
    }

    private fun calculateStandardDeviation(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance).toFloat()
    }

    private fun estimateFrequencyWithFFT(data: List<Triple<Float, Float, Float>>, sampleRate: Int = 50): Float {
        if (data.size < 64) return 0f // FFT를 위한 최소 샘플 수

        // 진폭 배열 생성 (XYZ 벡터 크기)
        val magnitudes = data.map { (x, y, z) ->
            sqrt(x * x + y * y + z * z).toDouble()
        }.toDoubleArray()

        // FFT 입력 크기를 2의 거듭제곱으로 조정
        val n = Integer.highestOneBit(magnitudes.size)
        val fftInput = magnitudes.copyOf(n)

        val fft = DoubleFFT_1D(n.toLong())
        val fftData = DoubleArray(n * 2)

        // 실수 데이터를 복소수 배열로 복사
        for (i in 0 until n) {
            fftData[2 * i] = fftInput[i]
            fftData[2 * i + 1] = 0.0
        }

        fft.complexForward(fftData)

        // 진폭 스펙트럼 계산
        val amplitudes = DoubleArray(n / 2)
        for (i in 0 until n / 2) {
            val real = fftData[2 * i]
            val imag = fftData[2 * i + 1]
            amplitudes[i] = sqrt(real * real + imag * imag)
        }

        // DC 성분 제거 및 최대 진폭 주파수 찾기
        amplitudes[0] = 0.0 // DC 성분 제거

        val maxIndex = amplitudes.withIndex()
            .drop(1) // DC 제거
            .filter { it.index < amplitudes.size / 4 } // 너무 높은 주파수 제외 (25Hz 이하)
            .maxByOrNull { it.value }?.index ?: return 0f

        val frequencyResolution = sampleRate.toDouble() / n
        return (maxIndex * frequencyResolution).toFloat()
    }

    private fun updateRealtimeVisual(x: Float, y: Float, z: Float) {
        // 실시간 시각화 (옵션)
        binding.tremoimeterView?.updateValue(x, y, z)
    }

    override fun onStateChanged(state: MeasurementState) {
        when (state) {
            is MeasurementState.Preparing -> {
                binding.btnStart.isEnabled = false
            }
            is MeasurementState.InProgress -> {
                binding.progressBar.progress = state.progress.toInt()
            }
            is MeasurementState.Completed -> {
                // showResults()에서 처리됨
            }
            is MeasurementState.Error -> {
                binding.tvStatus.text = state.message
                binding.btnStart.isEnabled = true
                binding.initialButtons.visibility = View.VISIBLE
                binding.resultButtons.visibility = View.GONE
                binding.progressBar.visibility = View.GONE
                binding.measurementGuide.visibility = View.GONE
                binding.resultCard.visibility = View.GONE
            }
            else -> {}
        }
    }

    private fun resetMeasurement() {
        measurementTimer?.cancel()
        sensorManager.unregisterListener(this)
        accelerationData.clear()
        baselineAcceleration = null

        // UI 초기화
        binding.btnStart.isEnabled = true
        binding.initialButtons.visibility = View.VISIBLE
        binding.resultButtons.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.tvProgress.visibility = View.GONE
        binding.resultCard.visibility = View.GONE
        binding.measurementGuide.visibility = View.GONE
        binding.tvTimer.visibility = View.GONE
        binding.tvStatus.text = "측정 준비"

        updateState(MeasurementState.Idle)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        measurementTimer?.cancel()
        sensorManager.unregisterListener(this)
        _binding = null
    }
}