package com.jjangdol.biorhythm.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.jjangdol.biorhythm.R
import java.util.*
import kotlin.math.*

/**
 * 모던하고 아름다운 PPG 파형 시각화 뷰
 */
class PPGWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 색상 설정
    private val primaryColor = Color.parseColor("#10B981") // 민트
    private val accentColor = Color.parseColor("#F59E0B") // 앰버
    private val dangerColor = Color.parseColor("#EF4444") // 빨강
    private val backgroundColor = Color.parseColor("#F8FAFC")
    private val surfaceColor = Color.parseColor("#FFFFFF")

    // 데이터 저장
    private val dataPoints = LinkedList<Float>()
    private val maxDataPoints = 200 // 더 부드러운 곡선을 위해 증가

    // 신호 품질 상태
    enum class SignalQuality {
        NONE,        // 신호 없음
        POOR,        // 신호 불량
        GOOD,        // 신호 양호
        EXCELLENT    // 신호 우수
    }

    private var signalQuality = SignalQuality.NONE
    private var signalStrength = 0f // 0.0 ~ 1.0

    // 그리기 도구
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = backgroundColor
        style = Paint.Style.FILL
    }

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = surfaceColor
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 4f, Color.parseColor("#1F000000"))
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(5f, 5f), 0f)
    }

    private val waveMainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(6f, 0f, 3f, Color.parseColor("#40000000"))
    }

    private val waveGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val centerLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#475569")
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        textAlign = Paint.Align.LEFT
    }

    // 애니메이션
    private var animationOffset = 0f
    private var pulseAnimation = 0f
    private var scanLinePosition = 0f

    private val scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            scanLinePosition = animation.animatedValue as Float
            invalidate()
        }
    }

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            pulseAnimation = animation.animatedValue as Float
            invalidate()
        }
    }

    // 심박수 계산
    private var lastPeakTime = 0L
    private var currentBPM = 0
    private val bpmHistory = LinkedList<Int>()
    private val maxBPMHistory = 8

    // 경로 객체
    private val wavePath = Path()
    private val fillPath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        startAnimations()
    }

    private fun startAnimations() {
        scanAnimator.start()
        pulseAnimator.start()
    }

    /**
     * 새로운 데이터 포인트 추가
     */
    fun addDataPoint(value: Float) {
        synchronized(dataPoints) {
            dataPoints.add(value)
            if (dataPoints.size > maxDataPoints) {
                dataPoints.removeFirst()
            }
        }

        // 피크 감지 및 BPM 계산
        detectPeakAndCalculateBPM(value)

        invalidate()
    }

    /**
     * 신호 품질 수동 설정
     */
    fun setSignalQuality(quality: SignalQuality, strength: Float = 0.5f) {
        this.signalQuality = quality
        this.signalStrength = strength.coerceIn(0f, 1f)
        invalidate()
    }

    private fun getSignalColor(): Int {
        return when (signalQuality) {
            SignalQuality.NONE -> Color.parseColor("#94A3B8")
            SignalQuality.POOR -> dangerColor
            SignalQuality.GOOD -> accentColor
            SignalQuality.EXCELLENT -> primaryColor
        }
    }

    private fun getSignalColorWithAlpha(alpha: Int): Int {
        val baseColor = getSignalColor()
        return Color.argb(
            alpha,
            Color.red(baseColor),
            Color.green(baseColor),
            Color.blue(baseColor)
        )
    }

    private fun getSignalColorHex(): String {
        val baseColor = getSignalColor()
        return String.format("#%06X", (0xFFFFFF and baseColor))
    }

    /**
     * 데이터 초기화
     */
    fun clear() {
        synchronized(dataPoints) {
            dataPoints.clear()
        }
        bpmHistory.clear()
        currentBPM = 0
        signalQuality = SignalQuality.NONE
        signalStrength = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // 배경과 표면
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)
        canvas.drawRoundRect(
            8f, 8f, width - 8f, height - 8f,
            12f, 12f, surfacePaint
        )

        // 그리드
        drawModernGrid(canvas, width, height)

        // 중앙선
        canvas.drawLine(0f, height / 2, width, height / 2, centerLinePaint)

        // 파형
        drawWaveform(canvas, width, height)

        // 스캔 라인
        drawScanLine(canvas, width, height)

        // 상태 표시
        drawStatus(canvas, width, height)

        // 하트비트 인디케이터
        if (signalQuality != SignalQuality.NONE) {
            drawHeartbeatIndicator(canvas, width, height)
        }
    }

    private fun drawModernGrid(canvas: Canvas, width: Float, height: Float) {
        val padding = 16f

        // 수평 격자 (더 섬세하게)
        for (i in 1..4) {
            val y = padding + (height - padding * 2) * i / 5
            canvas.drawLine(padding, y, width - padding, y, gridPaint)
        }

        // 수직 격자 (시간 간격)
        for (i in 1..8) {
            val x = padding + (width - padding * 2) * i / 9
            canvas.drawLine(x, padding, x, height - padding, gridPaint)
        }
    }

    private fun drawWaveform(canvas: Canvas, width: Float, height: Float) {
        synchronized(dataPoints) {
            if (dataPoints.size < 2) {
                drawNoSignalMessage(canvas, width, height)
                return
            }

            val padding = 16f
            val drawWidth = width - padding * 2
            val drawHeight = height - padding * 2

            // 데이터 정규화
            val minValue = dataPoints.minOrNull() ?: 0f
            val maxValue = dataPoints.maxOrNull() ?: 1f
            val range = if (maxValue - minValue > 0) maxValue - minValue else 1f

            wavePath.reset()
            fillPath.reset()

            var isFirst = true

            dataPoints.forEachIndexed { index, value ->
                val x = padding + (index.toFloat() / (maxDataPoints - 1)) * drawWidth
                val normalizedValue = (value - minValue) / range
                val y = padding + drawHeight - (normalizedValue * drawHeight * 0.8f) - drawHeight * 0.1f

                if (isFirst) {
                    wavePath.moveTo(x, y)
                    fillPath.moveTo(x, height - padding)
                    fillPath.lineTo(x, y)
                    isFirst = false
                } else {
                    wavePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }

            // 채우기 영역 완성
            fillPath.lineTo(width - padding, height - padding)
            fillPath.close()

            // 그라데이션 색상 설정
            val signalColor = getSignalColor()

            // 채우기 그라데이션 (수정됨)
            val fillGradient = LinearGradient(
                0f, padding, 0f, height - padding,
                getSignalColorWithAlpha(51),  // 20% 투명도
                getSignalColorWithAlpha(13),  // 5% 투명도
                Shader.TileMode.CLAMP
            )

            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = fillGradient
                style = Paint.Style.FILL
            }

            canvas.drawPath(fillPath, fillPaint)

            // 글로우 효과
            waveGlowPaint.color = getSignalColorWithAlpha(102) // 40% 투명도
            canvas.drawPath(wavePath, waveGlowPaint)

            // 메인 파형
            waveMainPaint.color = signalColor
            canvas.drawPath(wavePath, waveMainPaint)
        }
    }

    private fun drawScanLine(canvas: Canvas, width: Float, height: Float) {
        if (signalQuality == SignalQuality.NONE) return

        val padding = 16f
        val scanX = padding + scanLinePosition * (width - padding * 2)

        val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = getSignalColorWithAlpha(153) // 60% 투명도
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        canvas.drawLine(scanX, padding, scanX, height - padding, scanPaint)

        // 스캔 라인 효과 (글로우)
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = getSignalColorWithAlpha(51) // 20% 투명도
            strokeWidth = 8f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(scanX, padding, scanX, height - padding, glowPaint)
    }

    private fun drawNoSignalMessage(canvas: Canvas, width: Float, height: Float) {
        val message = "손가락을 카메라에 올려주세요"
        textPaint.color = Color.parseColor("#94A3B8")
        textPaint.textSize = 24f

        canvas.drawText(message, width / 2, height / 2, textPaint)

        // 대기 중 애니메이션 (점)
        val dotRadius = 4f
        val dotSpacing = 20f
        val dotCount = 3
        val totalWidth = (dotCount - 1) * dotSpacing

        repeat(dotCount) { i ->
            val alpha = (sin(pulseAnimation * PI * 2 + i * PI / 2) * 0.5 + 0.5).toFloat()
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#94A3B8")
                this.alpha = (alpha * 255).toInt()
                style = Paint.Style.FILL
            }

            val dotX = width / 2 - totalWidth / 2 + i * dotSpacing
            val dotY = height / 2 + 40f

            canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)
        }
    }

    private fun drawStatus(canvas: Canvas, width: Float, height: Float) {
        statusPaint.color = getSignalColor()
        statusPaint.textAlign = Paint.Align.LEFT

        val statusText = when (signalQuality) {
            SignalQuality.NONE -> "신호 감지 중..."
            SignalQuality.POOR -> "신호 불량"
            SignalQuality.GOOD -> "신호 양호"
            SignalQuality.EXCELLENT -> "신호 우수"
        }

        canvas.drawText(statusText, 20f, 35f, statusPaint)

        // 신호 강도 바
        if (signalQuality != SignalQuality.NONE) {
            drawSignalStrengthBar(canvas, width)
        }
    }

    private fun drawSignalStrengthBar(canvas: Canvas, width: Float) {
        val barWidth = 100f
        val barHeight = 6f
        val barX = width - barWidth - 20f
        val barY = 25f

        // 배경 바
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(barX, barY, barX + barWidth, barY + barHeight, 3f, 3f, bgPaint)

        // 신호 강도 바
        val strengthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = getSignalColor()
            style = Paint.Style.FILL
        }
        val strengthWidth = barWidth * signalStrength
        canvas.drawRoundRect(barX, barY, barX + strengthWidth, barY + barHeight, 3f, 3f, strengthPaint)
    }

    private fun drawHeartbeatIndicator(canvas: Canvas, width: Float, height: Float) {
        if (currentBPM == 0) return

        val heartSize = 20f + sin(pulseAnimation * PI * 2).toFloat() * 5f
        val heartX = width - 40f
        val heartY = height - 30f

        val heartPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = getSignalColor()
            style = Paint.Style.FILL
            alpha = (150 + sin(pulseAnimation * PI * 2) * 50).toInt()
        }

        // 간단한 하트 모양
        canvas.drawCircle(heartX - heartSize/3, heartY - heartSize/3, heartSize/2, heartPaint)
        canvas.drawCircle(heartX + heartSize/3, heartY - heartSize/3, heartSize/2, heartPaint)

        val heartPath = Path().apply {
            moveTo(heartX, heartY + heartSize/2)
            lineTo(heartX - heartSize, heartY - heartSize/4)
            lineTo(heartX + heartSize, heartY - heartSize/4)
            close()
        }
        canvas.drawPath(heartPath, heartPaint)
    }

    private fun detectPeakAndCalculateBPM(value: Float) {
        if (dataPoints.size >= 5) {
            val recent = dataPoints.takeLast(5)
            val current = recent[2] // 중간값
            val prev = recent[1]
            val next = recent[3]

            // 피크 감지: 주변보다 높고 임계값 이상
            val threshold = dataPoints.average() * 1.2
            if (current > prev && current > next && current > threshold) {
                val currentTime = System.currentTimeMillis()

                if (lastPeakTime != 0L) {
                    val interval = currentTime - lastPeakTime
                    val bpm = (60000 / interval).toInt()

                    // 유효한 BPM 범위
                    if (bpm in 40..200) {
                        bpmHistory.add(bpm)
                        if (bpmHistory.size > maxBPMHistory) {
                            bpmHistory.removeFirst()
                        }

                        // 중간값 사용 (노이즈 감소)
                        val sortedBPM = bpmHistory.sorted()
                        currentBPM = sortedBPM[sortedBPM.size / 2]
                    }
                }

                lastPeakTime = currentTime
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scanAnimator.cancel()
        pulseAnimator.cancel()
    }
}