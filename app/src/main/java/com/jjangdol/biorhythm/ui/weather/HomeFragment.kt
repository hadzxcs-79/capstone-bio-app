package com.jjangdol.biorhythm.ui.weather

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.FusedLocationProviderClient
import com.jjangdol.biorhythm.R
import com.jjangdol.biorhythm.databinding.FragmentWeatherBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.widget.EditText
import androidx.navigation.findNavController
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.*
import com.google.firebase.firestore.Source
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.widget.FrameLayout
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import com.google.firebase.firestore.SetOptions
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.viewModels
import com.jjangdol.biorhythm.vm.UserNotificationViewModel
import kotlinx.coroutines.flow.collectLatest
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.jjangdol.biorhythm.data.model.NotificationPriority
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HomeFragment : Fragment(R.layout.fragment_weather) {

    private var _binding: FragmentWeatherBinding? = null
    private val binding get() = _binding!!
    private lateinit var fused: FusedLocationProviderClient
    private var currentLocCts: CancellationTokenSource? = null
    private val db = FirebaseFirestore.getInstance()
    private lateinit var functions: FirebaseFunctions

    // 알림 확인을 위한 vm
    private val notificationViewModel: UserNotificationViewModel by viewModels()

    // stn 리스트
    private var weatherStations: List<WeatherStation> = emptyList()

    // 지역별 stn 데이터 클래스
    data class WeatherStation(
        val stnId: Int, // api 호출 시 보내줘야 할 지역정보 포맷
        val longitude: Double,
        val latitude: Double
    )

    /** 사용자의 위치 권한 요청 → 응답에 따른 처리 */
    private val requestLocationPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())
        { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
                    || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            //FINE:정밀위치,COARSE:지리상 대략적 위치

            if (granted) {
                showLoading(true)
                fetchLastLocationAndUpdateUI()
                showLoading(false)
            } else {
                Toast.makeText(requireContext(), "위치 권한이 없어 기본 위치를 표시합니다.", Toast.LENGTH_SHORT)
                    .show()
            }
        }

    /** 사용자의 위치 권한이 확인되면 → 허용 시 위치 조회 */
    @SuppressLint("MissingPermission")
    private fun updateLocationName() {
        val fine = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarse = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            requestLocationPerms.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        fused.lastLocation
            .addOnSuccessListener { loc ->
                if (_binding == null) {
                    Log.w("HomeFragment", "View already destroyed, ignoring location update")
                    return@addOnSuccessListener
                }

                if (loc == null) {
                    fetchCurrentLocationFallback()
                    return@addOnSuccessListener
                }

                lifecycleScope.launch(Dispatchers.Main) {
                    if (_binding == null) {
                        return@launch
                    }

                    showLoading(true)

                    try {
                        // 주소 변환
                        val name = withContext(Dispatchers.IO) {
                            reverseGeocodeToShortName(loc.latitude, loc.longitude)
                        }

                        _binding?.let { binding ->
                            binding.tvLocation.text = name ?: "위치 정보 없음"
                        }

                        // 격자 변환 및 날씨 정보 가져와서 UI 업데이트
                        val (nx, ny) = convertToGrid(loc.latitude, loc.longitude)
                        getWeatherAndUpdateUI(nx, ny)

                        // 가장 가까운 관측소 찾아서 기상 특보 조회
                        val nearestStation = findNearestStation(loc.latitude, loc.longitude)
                        if (nearestStation != null) {
                            Log.d("WeatherDebug",
                                "사용자 위치: (${loc.latitude}, ${loc.longitude})")
                            Log.d("WeatherDebug",
                                "선택된 관측소: STN_ID=${nearestStation.stnId}")

                            getWeatherNews(nearestStation.stnId)
                        } else {
                            Log.w("WeatherDebug", "가까운 관측소를 찾을 수 없습니다")
                        }

                    } catch (e: Exception) {
                        Log.e("WeatherDebug", "updateLocationName 내 코루틴 오류", e)
                        if (_binding != null) {
                            Toast.makeText(
                                requireContext(),
                                "날씨 정보를 불러올 수 없습니다",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } finally {
                        if (_binding != null) {
                            delay(50)
                            showLoading(false)
                        }
                    }
                }
            }
            .addOnFailureListener {
                if (_binding != null) {
                    Toast.makeText(requireContext(), "위치 조회에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    showLoading(false)
                }
            }
    }

    /** 위도 경도 -> nx ny 변환 함수를 위한 값 */
    data class LamcParameter(
        val Re: Double = 6371.00877, // 지구 반경 (km)
        val grid: Double = 5.0,       // 격자 간격 (km)
        val slat1: Double = 30.0,     // 표준 위도 1
        val slat2: Double = 60.0,     // 표준 위도 2
        val olon: Double = 126.0,     // 기준점 경도
        val olat: Double = 38.0,      // 기준점 위도
        val xo: Double = 43.0,        // 기준점 X좌표
        val yo: Double = 136.0        // 기준점 Y좌표
    )

    /** 위도 경도 -> nx ny 변환 함수 */
    fun convertToGrid(lat: Double, lon: Double): Pair<Int, Int> {
        val map = LamcParameter()

        val PI = Math.asin(1.0) * 2.0
        val DEGRAD = PI / 180.0

        val re = map.Re / map.grid
        val slat1 = map.slat1 * DEGRAD
        val slat2 = map.slat2 * DEGRAD
        val olon = map.olon * DEGRAD
        val olat = map.olat * DEGRAD

        var sn = Math.tan(PI * 0.25 + slat2 * 0.5) / Math.tan(PI * 0.25 + slat1 * 0.5)
        sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn)
        var sf = Math.tan(PI * 0.25 + slat1 * 0.5)
        sf = Math.pow(sf, sn) * Math.cos(slat1) / sn
        var ro = Math.tan(PI * 0.25 + olat * 0.5)
        ro = re * sf / Math.pow(ro, sn)

        var ra = Math.tan(PI * 0.25 + lat * DEGRAD * 0.5)
        ra = re * sf / Math.pow(ra, sn)
        var theta = lon * DEGRAD - olon
        if (theta > PI) theta -= 2.0 * PI
        if (theta < -PI) theta += 2.0 * PI
        theta *= sn

        val x = (ra * Math.sin(theta) + map.xo + 0.5).toInt()
        val y = (ro - ra * Math.cos(theta) + map.yo + 0.5).toInt()

        return Pair(x, y)
    }


    /** Cloud Function을 호출하고 날씨 정보로 UI를 업데이트하는 함수 */
    private suspend fun getWeatherAndUpdateUI(nx: Int, ny: Int) {
        try {
            val result = functions
                .getHttpsCallable("getWeatherData")
                .call(mapOf("nx" to nx, "ny" to ny))
                .await()

            val outer = result.data as? Map<*, *>
            val weatherData = outer?.get("data") as? Map<String, String>

            if (weatherData != null) {
                binding.tvNowTemp.text = "${weatherData["T1H"] ?: "--"}°"
                binding.tvHumidity.text = "습도 ${weatherData["REH"] ?: "--"}%"
                binding.tvRain.text = "강수 ${weatherData["RN1"] ?: "--"}"

                val skyValue = weatherData["SKY"]
                val ptyValue = weatherData["PTY"]

                val (condition, iconResId) = mapWeatherCondition(skyValue, ptyValue)
                binding.ivNowIcon.setImageResource(iconResId)

                val temp = weatherData["T1H"]?.toDoubleOrNull()
                val humidity = weatherData["REH"]?.toDoubleOrNull()

                // 체감온도 (기상청 기상자료 공개 포털 참고)
                if (temp != null && humidity != null) {
                    val currentMonth = LocalDate.now().monthValue
                    if (currentMonth in 5..9) {
                        // 여름(5~9월)만 체감온도 표시
                        val sensible = calculateSensibleTemperature(temp, humidity)
                        binding.tvNowDesc.text = "$condition · 체감온도 ${"%.1f".format(sensible)}°"
                    } else {
                        // 10~5월은 체감온도 미표시
                        binding.tvNowDesc.text = condition
                    }
                } else {
                    binding.tvNowDesc.text = "$condition · 체감온도 --°"
                }
            } else {
                Toast.makeText(requireContext(), "날씨 정보를 받아오지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("WeatherError", "getWeatherData 호출 실패", e)
            Toast.makeText(requireContext(), "날씨 정보를 가져오는 중 오류: ${e.message}", Toast.LENGTH_LONG)
                .show()
        }
    }

    /** 온도 기반으로 적절한 레벨 결정 */
    private fun determineGuidelineLevel(temp: Double?): String {
        return when {
            temp == null -> "level_0"
            temp >= 38 -> "level_38"
            temp >= 35 -> "level_35"
            temp >= 33 -> "level_33"
            temp >= 31 -> "level_31"
            temp <= -15 -> "level_minus15"
            temp <= -12 -> "level_minus12"
            temp <= -6 -> "level_minus6"
            else -> "level_0"
        }
    }

    /** 날씨 코드(SKY, PTY)를 한글 날씨 상태로 변환하는 도우미 함수 */
    private fun mapWeatherCondition(sky: String?, pty: String?): Pair<String, Int> {
        return when (pty) {
            "0" -> when (sky) {
                "1" -> "맑음" to R.drawable.ic_weather_sunny
                "3" -> "구름많음" to R.drawable.ic_weather_cloudy
                "4" -> "흐림" to R.drawable.ic_weather_cloudy
                else -> "알 수 없음" to R.drawable.ic_weather_sunny
            }

            "1" -> "비" to R.drawable.ic_weather_rainy
            "2" -> "비/눈" to R.drawable.ic_weather_rain_and_snow
            "3" -> "눈" to R.drawable.ic_weather_snowy
            "4" -> "소나기" to R.drawable.ic_weather_rainy
            "5" -> "빗방울" to R.drawable.ic_weather_rainy
            "6" -> "빗방울/눈날림" to R.drawable.ic_weather_rain_and_snow
            "7" -> "눈날림" to R.drawable.ic_weather_snowy
            else -> "알 수 없음" to R.drawable.ic_weather_sunny
        }
    }

    /** 기기의 최근 위치가 확인이 안 된다면, 대체(백업) 경로 가져오기 */
    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocationFallback() {
        currentLocCts?.cancel() //이전 위치 요청을 관리하는 취소 토큰
        val cts = CancellationTokenSource() //이번에 할 요청을 관리할 토큰

        //현재 위치 요청
        fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    updateAddressFrom(loc.latitude, loc.longitude)
                } else {
                    Toast.makeText(requireContext(), "현재 위치를 가져올 수 없습니다.", Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(
                    requireContext(),
                    "현재 위치 요청 실패",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    /** 마지막으로 저장된 기기의 위치로 주소명 갱신 */
    @SuppressLint("MissingPermission")
    private fun fetchLastLocationAndUpdateUI() {
        fused.lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    updateAddressFrom(loc.latitude, loc.longitude)
                } else {
                    Toast.makeText(requireContext(), "현재 위치를 가져올 수 없습니다.", Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "위치 조회 실패", Toast.LENGTH_SHORT).show()
            }
    }

    /** 위도,경도 값을 주소명으로 변환 */
    private fun updateAddressFrom(lat: Double, lon: Double) {
        viewLifecycleOwner.lifecycleScope.launch {
            val name = withContext(Dispatchers.IO)
            {
                reverseGeocodeToShortName(lat, lon)
            }
            val display = name ?: "(${lat.f(4)}, ${lon.f(4)})"
            binding.tvLocation.text = display
        }
    }

    /** 위경도를 "서울특별시 종로구 00동" 식으로 변환 (없으면 null) */
    private fun reverseGeocodeToShortName(lat: Double, lon: Double): String? {
        return try {
            if (!Geocoder.isPresent()) return null

            val g = Geocoder(requireContext(), Locale.KOREA)
            val list: List<Address> = g.getFromLocation(lat, lon, 1) ?: emptyList()

            if (list.isEmpty()) return null
            val a = list[0]

            val wiedarea = a.locality ?: a.adminArea       // 시/도
            val narrowarea = a.subLocality ?: a.subAdminArea // 구/군

            // 동/읍/면 정보 추출
            val detailarea = when {
                // thoroughfare에 동/읍/면이 포함되어 있으면 사용
                a.thoroughfare?.let { it.contains("동") || it.contains("읍") || it.contains("면") } == true
                    -> a.thoroughfare
                // subThoroughfare에 동/읍/면이 포함되어 있으면 사용
                a.subThoroughfare?.let { it.contains("동") || it.contains("읍") || it.contains("면") } == true
                    -> a.subThoroughfare
                else -> null
            }

            when {
                !wiedarea.isNullOrBlank() && !narrowarea.isNullOrBlank() && !detailarea.isNullOrBlank() ->
                    "$wiedarea $narrowarea $detailarea"

                !wiedarea.isNullOrBlank() && !narrowarea.isNullOrBlank() ->
                    "$wiedarea $narrowarea"

                !wiedarea.isNullOrBlank() && !detailarea.isNullOrBlank() ->
                    "$wiedarea $detailarea"

                !wiedarea.isNullOrBlank() -> wiedarea
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        )

        binding.swipeRefreshLayout.setOnRefreshListener {
            // 새로고침 시 실행할 작업
            updateLocationName()
            updateCurrentDate()
            loadWorkTimeFromFirestore()

            // 새로고침 완료 후 애니메이션 종료
            binding.swipeRefreshLayout.postDelayed({
                binding.swipeRefreshLayout.isRefreshing = false
            }, 1000)
        }
    }

    /** 새로고침 로딩 */
    private fun showLoading(show: Boolean) {
        _binding?.let { binding ->
            if (show) {
                if (!binding.swipeRefreshLayout.isRefreshing) {
                    binding.progress.visibility = View.VISIBLE
                }
                setNowSkeleton(true)
            } else {
                binding.progress.visibility = View.GONE
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    private fun setNowSkeleton(show: Boolean) {
        if (show) {
            binding.ivNowIcon.imageAlpha = 80
            binding.tvNowTemp.text = "--°"
            binding.tvNowDesc.text = "불러오는 중…"
            binding.tvHumidity.text = "습도 --%"
            binding.tvRain.text = "강수 --"
        } else {
            binding.ivNowIcon.imageAlpha = 255
            binding.tvNowTemp.text = "--°"
            binding.tvNowDesc.text = "불러오는 중…"
            binding.tvHumidity.text = "습도 --%"
            binding.tvRain.text = "강수 --"
        }
    }

    /** Stull 근사식을 이용한 습구온도 계산 */
    fun calculateWetBulbTemperature(tempC: Double, humidity: Double): Double {
        return tempC * atan(0.151977 * sqrt(humidity + 8.313659)) +
                atan(tempC + humidity) -
                atan(humidity - 1.676331) +
                0.00391838 * humidity.pow(1.5) * atan(0.023101 * humidity) -
                4.686035
    }

    /** 기상청 체감온도 공식 */
    fun calculateSensibleTemperature(tempC: Double, humidity: Double): Double {
        val Tw = calculateWetBulbTemperature(tempC, humidity)
        return -0.2442 + (0.55399 * Tw) + (0.45535 * tempC) -
                (0.0022 * Tw * Tw) + (0.00278 * Tw * tempC) + 3.0
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** 환영문구 */
    private fun greetUser() {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val name = prefs.getString("user_name", null)

        if (!name.isNullOrBlank()) {
            binding.tvUserName.text = name
            binding.tvWelcome.text = "님 환영합니다"
        } else {
            binding.tvUserName.text = ""
            binding.tvWelcome.text = "환영합니다"
        }
    }

    /** 관리자 버튼 표시 여부 결정 */
    private fun checkAdminVisibility() {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val empNum = prefs.getString("emp_num", null)

        if (empNum.isNullOrEmpty()) {
            binding.btnAdminLink.visibility = View.GONE
            return
        }

        db.collection("employees").document(empNum)
            .get()
            .addOnSuccessListener { doc ->
                if (!isAdded || view == null || _binding == null) return@addOnSuccessListener

                lifecycleScope.launch {
                    if (_binding == null) return@launch
                    binding.btnAdminLink.visibility =
                        if (doc.exists() && doc.contains("Password")) View.VISIBLE else View.GONE
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded || _binding == null) return@addOnFailureListener
                binding.btnAdminLink.visibility = View.GONE
            }
    }

    /** 소수점 포맷 */
    private fun Double.f(d: Int) = String.format(Locale.US, "%.${d}f", this)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentWeatherBinding.bind(view)

        functions = Firebase.functions("asia-northeast3")
        fused = LocationServices.getFusedLocationProviderClient(requireContext())

        loadWeatherStations()

        setupSwipeRefresh()
        //유저환영
        greetUser()
        // 초기 로딩 UI 설정
        setNowSkeleton(true)

        // 알림 확인 카드
        setupNotificationCard()

        // 현재 위치명 시도
        updateLocationName()

        // 관리자 여부 확인 후 버튼 표시/숨김
        checkAdminVisibility()

        // 관리자 버튼
        binding.btnAdminLink.setOnClickListener {
            showAdminLoginDialog()
        }

        // 현재 날짜 출력
        updateCurrentDate()

        // 작업 시작, 종료 시간을 TextView로 출력
        loadWorkTimeFromFirestore()

        // 종료 버튼
        binding.Endbutton.setOnClickListener {
            saveEndTimeToFirestore()
        }
    }

    override fun onResume() {
        super.onResume()

        // 다른 프래그먼트에서 돌아올 때마다 갱신
        updateCurrentDate()
        loadWorkTimeFromFirestore()
    }

    // 다이얼로그를 표시하는 함수로 이름 변경 및 리스너 설정 로직 제거
    private fun showAdminLoginDialog() {
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val empNum = prefs.getString("emp_num", null) ?: return

        val input = EditText(requireContext()).apply {
            hint = "비밀번호"
            inputType = InputType.TYPE_CLASS_NUMBER
            keyListener = DigitsKeyListener.getInstance("0123456789")
        }

        val container = FrameLayout(requireContext()).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("관리자 비밀번호 입력")
            .setView(container)
            .setPositiveButton("확인", null)
            .setNegativeButton("취소", null)
            .create()

        dialog.setOnShowListener{
            val okBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okBtn.setOnClickListener {
                val password = input.text.toString().trim()
                if (password.isEmpty()) {
                    Toast.makeText(requireContext(), "비밀번호를 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                verifyAdminLogin(empNum, password, dialog)
            }
        }
        dialog.show()
    }



    // 관리자 로그인
    private fun verifyAdminLogin(empNum: String, password: String, dialog: Dialog) {
        val db = FirebaseFirestore.getInstance()
        db.collection("employees")
            .document(empNum)
            .get(Source.SERVER)
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    Toast.makeText(requireContext(), "관리자 계정이 존재하지 않습니다", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val savedPwStr = doc.getString("Password")

                if (savedPwStr == password) {
                    Toast.makeText(requireContext(), "관리자 로그인 성공", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    try {
                        val mainNavController = requireActivity().findNavController(R.id.navHostFragment)
                        mainNavController.navigate(R.id.action_main_to_newAdmin)
                    } catch (e: Exception) {
                        Log.e("HomeFragment", "Navigation error", e)
                        Toast.makeText(requireContext(), "관리자 페이지로 이동할 수 없습니다", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "비밀번호가 올바르지 않습니다", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Firestore 오류: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // 현재 날짜 표시 (yyyy.MM.dd (요일))
    private fun updateCurrentDate() {
        val currentDate = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd (E)", Locale.KOREAN)
        val displayDate = currentDate.format(formatter)

        binding.tvDate.text = displayDate
    }

    // Firebase에서 작업 시간 조회
    private fun loadWorkTimeFromFirestore() {
        val db = FirebaseFirestore.getInstance()
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val empNum = prefs.getString("emp_num", null)

        if (empNum.isNullOrEmpty()) return

        // Firebase 조회용 날짜 (yyyy-MM-dd 형식)
        val currentDate = LocalDate.now().toString() // "2025-11-09"

        db.collection("WorkTime")
            .document(currentDate)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val empData = document.get(empNum) as? Map<*, *>

                    if (empData != null) {
                        val startTime = empData["StartTime"] as? String
                        val endTime = empData["EndTime"] as? String

                        // StartTime 표시
                        val displayStartTime = if (!startTime.isNullOrEmpty()) {
                            formatTime(startTime) // "01:01:47" -> "01:01"
                        } else {
                            "00:00"
                        }

                        // EndTime 표시
                        val displayEndTime = if (!endTime.isNullOrEmpty()) {
                            formatTime(endTime) // "17:58:54" -> "17:58"
                        } else {
                            "00:00"
                        }

                        // UI 업데이트
                        binding.tvStartTime.text = displayStartTime
                        binding.tvEndTime.text = displayEndTime

                        // 버튼 상태 업데이트
                        updateButtonState(startTime, endTime)
                    } else {
                        // 해당 사번 데이터 없음
                        binding.tvStartTime.text = "00:00"
                        binding.tvEndTime.text = "00:00"
                        updateButtonState(null, null)
                    }
                } else {
                    // 해당 날짜 문서 없음
                    binding.tvStartTime.text = "00:00"
                    binding.tvEndTime.text = "00:00"
                    updateButtonState(null, null)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "작업 시간 조회에 실패했습니다.", Toast.LENGTH_SHORT).show()
                binding.tvStartTime.text = "00:00"
                binding.tvEndTime.text = "00:00"
            }
    }

    // 버튼 상태 업데이트
    private fun updateButtonState(startTime: String?, endTime: String?) {
        when {
            // StartTime이 없으면 버튼 비활성화
            startTime.isNullOrEmpty() -> {
                binding.Endbutton.isEnabled = false
                binding.Endbutton.alpha = 0.5f
                binding.Endbutton.text = "작업 시작 전"
            }
            // EndTime이 이미 있으면 버튼 비활성화
            !endTime.isNullOrEmpty() -> {
                binding.Endbutton.isEnabled = false
                binding.Endbutton.alpha = 0.5f
                binding.Endbutton.text = "작업 종료됨"
            }
            // StartTime만 있고 EndTime이 없으면 버튼 활성화
            else -> {
                binding.Endbutton.isEnabled = true
                binding.Endbutton.alpha = 1.0f
                binding.Endbutton.text = "작업 종료"
            }
        }
    }

    // 시간 형식 변환 (HH:mm:ss -> HH:mm)
    private fun formatTime(time: String): String {
        return try {
            if (time.length >= 5) {
                time.substring(0, 5) // "01:01:47" -> "01:01"
            } else {
                "00:00"
            }
        } catch (e: Exception) {
            "00:00"
        }
    }


    // 작업 종료시간을 Firebase에 업로드. WorkTime/날짜의 필드로 사번 : { EndTime : "시간" } 업로드
    // TODO : 작업 시작 처리가 안됐을 경우(생체 측정 검사를 아직 안 받은 경우) 작업 종료 버튼을 비활성화 or 클릭했을때 종료 처리가 안돼도록 할 예정, 그리고 작업 종료 버튼을 이미 눌렀는데 또 누른 경우 EndTime을 업데이트 하지 못하도록 할 예정.
    // 작업 시작 여부에 따른 버튼 클릭 이벤트
    private fun saveEndTimeToFirestore() {
        val db = FirebaseFirestore.getInstance()
        val prefs = requireContext().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val empNum = prefs.getString("emp_num", null)

        if (empNum.isNullOrEmpty()) return

        val currentDate = LocalDate.now().toString()

        // 먼저 StartTime 존재 여부와 EndTime 중복 여부 확인
        db.collection("WorkTime")
            .document(currentDate)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val empData = document.get(empNum) as? Map<*, *>

                    if (empData != null) {
                        val startTime = empData["StartTime"] as? String
                        val endTime = empData["EndTime"] as? String

                        when {
                            // StartTime이 없는 경우
                            startTime.isNullOrEmpty() -> {
                                Toast.makeText(
                                    requireContext(),
                                    "작업 시작 기록이 없습니다. 먼저 작업을 시작해주세요.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            // EndTime이 이미 있는 경우
                            !endTime.isNullOrEmpty() -> {
                                Toast.makeText(
                                    requireContext(),
                                    "이미 작업 종료 처리가 완료되었습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            // 정상: StartTime만 있고 EndTime이 없는 경우
                            else -> {
                                saveEndTime(currentDate, empNum)
                            }
                        }
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "작업 시작 기록이 없습니다.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        requireContext(),
                        "작업 시작 기록이 없습니다.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "작업 시간 확인에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
    }

    // 실제로 EndTime을 Firebase에 저장하는 함수
    private fun saveEndTime(currentDate: String, empNum: String) {
        val db = FirebaseFirestore.getInstance()
        val currentTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        val data = mapOf(
            empNum to mapOf("EndTime" to currentTime)
        )

        db.collection("WorkTime")
            .document(currentDate)
            .set(data, SetOptions.merge())
            .addOnSuccessListener {
                Toast.makeText(requireContext(), "작업이 정상적으로 종료되었습니다.", Toast.LENGTH_SHORT).show()
                // 저장 후 즉시 UI 업데이트
                loadWorkTimeFromFirestore()
            }
            .addOnFailureListener {
                Toast.makeText(requireContext(), "작업 종료 처리에 실패하였습니다.", Toast.LENGTH_SHORT).show()
            }
    }

    /************* 기상정보문 조회 파트 ************/

    /** 관측소 정보 로드 */
    private fun loadWeatherStations() {
        try {
            val inputStream = resources.openRawResource(R.raw.stn_id_info)
            val jsonString = inputStream.bufferedReader().use { it.readText() }

            val jsonArray = org.json.JSONArray(jsonString)
            val stations = mutableListOf<WeatherStation>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                stations.add(
                    WeatherStation(
                        stnId = obj.getInt("stn_id"),
                        longitude = obj.getDouble("lon"),
                        latitude = obj.getDouble("lat")
                    )
                )
            }

            weatherStations = stations
            Log.d("WeatherDebug", "관측소 데이터 로드 완료: ${weatherStations.size}개")
        } catch (e: Exception) {
            Log.e("WeatherDebug", "관측소 데이터 로드 실패", e)

            if (_binding != null && isAdded) {
                binding.weatherAlertContainer.removeAllViews()

                val errorText = TextView(requireContext()).apply {
                    text = "관측소 데이터를 불러올 수 없어 기상 특보를 조회할 수 없습니다"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 16, 0, 16)
                }

                binding.weatherAlertContainer.addView(errorText)
            }
        }
    }

    // Haversine 공식으로 두 좌표 간 거리 계산 (km)
    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val R = 6371.0 // 지구 반경 (km)

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    // 사용자 위치에서 가장 가까운 관측소 찾기
    private fun findNearestStation(userLat: Double, userLon: Double): WeatherStation? {
        if (weatherStations.isEmpty()) {
            Log.w("WeatherDebug", "관측소 데이터가 비어있습니다")
            return null
        }

        val nearest = weatherStations.minByOrNull { station ->
            calculateDistance(userLat, userLon, station.latitude, station.longitude)
        }

        if (nearest != null) {
            val distance = calculateDistance(userLat, userLon, nearest.latitude, nearest.longitude)
            Log.d("WeatherDebug", "가장 가까운 관측소 - ID: ${nearest.stnId}, 거리: ${"%.2f".format(distance)}km")
        }

        return nearest
    }

    // 기상 정보문 functions 호출 함수
    private suspend fun getWeatherNews(stnId: Int) {
        try {
            Log.d("WeatherNews", "getWeatherNews 호출 시작 - STN_ID: $stnId")

            val result = functions
                .getHttpsCallable("getWeatherNews")
                .call(mapOf("STN_ID" to stnId))
                .await()

            val responseData = result.data as? Map<*, *>

            if (responseData != null) {
                val dataMap = responseData["data"] as? Map<*, *>
                val issued = dataMap?.get("issued") as? List<*>

                if (issued != null && issued.isNotEmpty()) {
                    displayWeatherAlerts(issued)
                } else {
                    displayNoWeatherAlerts()
                }
            } else {
                Log.w("WeatherNews", "특보 데이터가 없습니다")
                displayNoWeatherAlerts()
            }

        } catch (e: Exception) {
            Log.e("WeatherNews", "getWeatherNews 호출 실패", e)

            // NO_DATA 에러는 정상 케이스로 처리
            if (e.message?.contains("NO_DATA") == true) {
                Log.d("WeatherNews", "해당 지역에 발효 중인 특보 없음")
                if (_binding != null && isAdded) {
                    displayNoWeatherAlerts()
                }
            } else {
                if (_binding != null && isAdded) {
                    displayWeatherAlertError()
                }
            }
        }
    }

    private fun displayWeatherAlerts(data: List<*>) {
        if (_binding == null) return

        binding.weatherAlertContainer.removeAllViews()

        data.forEach { item ->
            val alertItem = item as? Map<*, *> ?: return@forEach
            val title = alertItem["title"] as? String ?: return@forEach
            val date = alertItem["date"]?.toString() ?: return@forEach

            val formattedDate = formatAlertDate(date)

            val alertView = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 16)
            }

            val dateTextView = TextView(requireContext()).apply {
                this.text = "발표시간: $formattedDate"
                textSize = 13f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val contentTextView = TextView(requireContext()).apply {
                setText(title)
                textSize = 14f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                setPadding(0, 8, 0, 0)
            }

            alertView.addView(dateTextView)
            alertView.addView(contentTextView)

            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * resources.displayMetrics.density).toInt()
                ).apply {
                    setMargins(0, 16, 0, 0)
                }
                setBackgroundColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
            }

            binding.weatherAlertContainer.addView(alertView)
            if (data.indexOf(item) < data.size - 1) {
                binding.weatherAlertContainer.addView(divider)
            }
        }
    }

    private fun displayNoWeatherAlerts() {
        if (_binding == null) return

        binding.weatherAlertContainer.removeAllViews()

        val noAlertText = TextView(requireContext()).apply {
            text = "현재 발효 중인 기상 특보가 없습니다"
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        binding.weatherAlertContainer.addView(noAlertText)
    }

    private fun displayWeatherAlertError() {
        if (_binding == null) return

        binding.weatherAlertContainer.removeAllViews()

        val errorText = TextView(requireContext()).apply {
            text = "기상 특보 정보를 불러올 수 없습니다"
            textSize = 14f
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 16, 0, 16)
        }

        binding.weatherAlertContainer.addView(errorText)
    }

    private fun formatAlertDate(date: String): String {
        return try {
            if (date.length >= 12) {
                val year = date.substring(0, 4)
                val month = date.substring(4, 6)
                val day = date.substring(6, 8)
                val hour = date.substring(8, 10)
                val minute = date.substring(10, 12)
                "$year.$month.$day $hour:$minute"
            } else {
                date
            }
        } catch (e: Exception) {
            date
        }
    }
    /*************** 여기까지 기상 정보문 코드 ***************/

    /**
     * 알림 확인 코드 부분 시작 ===================================================
     * */
    private fun setupNotificationCard() {
        // 읽지 않은 알림 개수 관찰
        viewLifecycleOwner.lifecycleScope.launch {
            notificationViewModel.unreadCount.collectLatest { count ->
                updateNotificationCard(count)
            }
        }

        // 우선순위별 배지 관찰 (추가)
        viewLifecycleOwner.lifecycleScope.launch {
            notificationViewModel.unreadNotificationsByPriority.collectLatest { priorityMap ->
                updateNotificationBadges(priorityMap)
            }
        }

        // 알림 카드 전체 클릭 시 NotificationFragment로 이동
        binding.cardNotification.setOnClickListener {
            try {
                val bottomNav = requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav)
                bottomNav?.selectedItemId = R.id.notificationFragment
            } catch (e: Exception) {
                Log.e("HomeFragment-debug", "Navigation error: ${e.message}", e)
                Toast.makeText(requireContext(), "에러: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateNotificationCard(unreadCount: Int) {
        binding.apply {
            if (unreadCount > 0) {
                // 읽지 않은 알림이 있으면 카드 표시
                cardNotification.visibility = View.VISIBLE
                tvNotificationSummary.text = "읽지 않은 알림 ${unreadCount}개"
            } else {
                // 읽지 않은 알림이 없으면 카드 숨김
                cardNotification.visibility = View.GONE
            }
        }
    }

    // 새로운 함수 추가
    private fun updateNotificationBadges(priorityMap: Map<NotificationPriority, Int>) {
        binding.apply {
            val highCount = priorityMap[NotificationPriority.HIGH] ?: 0
            val normalCount = priorityMap[NotificationPriority.NORMAL] ?: 0
            val lowCount = priorityMap[NotificationPriority.LOW] ?: 0

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
        }
    }
    /**
     * 여기까지 알림 관련 코드
     * */
}