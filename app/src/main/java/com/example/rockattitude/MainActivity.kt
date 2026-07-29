package com.example.rockattitude

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var pressureSensor: Sensor? = null
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)

    private lateinit var tvStrike: TextView
    private lateinit var tvDip: TextView
    private lateinit var tvDipDir: TextView
    private lateinit var tvCurrentCoord: TextView
    private lateinit var tvLatestRecord: TextView
    private lateinit var btnSave: Button
    private lateinit var btnCamera: Button
    private lateinit var btnBorehole: Button
    private lateinit var btnDeclination: Button
    private lateinit var btnProjection: Button

    private val records = mutableListOf<Record>()
    private var currentAttitude: Attitude? = null
    private var magneticDeclination = 0f

    private var photoUri: Uri? = null
    private var photoFile: File? = null
    private var watermarkOptions = WatermarkOptions()

    data class WatermarkOptions(
        var latLng: Boolean = true,
        var altitude: Boolean = true,
        var address: Boolean = true,
        var time: Boolean = true,
        var attitude: Boolean = true,
        var note: Boolean = false,
        var noteText: String = ""
    )

    private lateinit var trackView: TrackView
    private lateinit var btnTrackToggle: Button
    private lateinit var btnNavigate: Button
    private lateinit var tvNavInfo: TextView

    private val trackPoints = mutableListOf<TrackPoint>()
    private var isRecordingTrack = false
    private var currentLocation: Location? = null
    private var currentAddress = "获取中..."
    private var navTarget: TrackPoint? = null
    private var isNavigating = false
    private var lastAlertTime = 0L

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    private lateinit var btnAverage: Button
    private lateinit var btnSatellite: Button
    private val averageSamples = mutableListOf<Attitude>()
    private var isAveraging = false
    private val averageHandler = Handler(Looper.getMainLooper())

    private var satelliteCount = 0
    private var satellitesUsed = 0
    private var gnssCallback: android.location.GnssStatus.Callback? = null
    private val satList = mutableListOf<SatInfo>()

    private lateinit var btnExportKml: Button
    private lateinit var btnExportCsv: Button
    private lateinit var btnHistoryMain: Button
    private lateinit var btnCoord: Button
    private lateinit var btnLithology: Button

    private val historyTracks = mutableMapOf<String, MutableList<TrackPoint>>()
    private var currentLithology = ""
    private var lastGoodLocation: Location? = null

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && photoFile != null && photoFile!!.exists()) processAndSaveWatermarkedPhoto(photoFile!!)
        else Toast.makeText(this, "拍照取消或失败", Toast.LENGTH_SHORT).show()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) showWatermarkOptionsDialog()
        else Toast.makeText(this, "需要相机和位置权限", Toast.LENGTH_LONG).show()
    }

    private val locationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) {
            startLocationUpdates()
            registerGnssStatus()
            forceRefreshLocation()
        } else Toast.makeText(this, "需要位置权限", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStrike = findViewById(R.id.tvStrike)
        tvDip = findViewById(R.id.tvDip)
        tvDipDir = findViewById(R.id.tvDipDir)
        tvCurrentCoord = findViewById(R.id.tvCurrentCoord)
        tvLatestRecord = findViewById(R.id.tvLatestRecord)
        btnSave = findViewById(R.id.btnSave)
        btnCamera = findViewById(R.id.btnCamera)
        btnBorehole = findViewById(R.id.btnBorehole)
        btnDeclination = findViewById(R.id.btnDeclination)
        btnProjection = findViewById(R.id.btnProjection)
        trackView = findViewById(R.id.trackView)
        btnTrackToggle = findViewById(R.id.btnTrackToggle)
        btnNavigate = findViewById(R.id.btnNavigate)
        tvNavInfo = findViewById(R.id.tvNavInfo)
        btnAverage = findViewById(R.id.btnAverage)
        btnSatellite = findViewById(R.id.btnSatellite)
        btnExportKml = findViewById(R.id.btnExportKml)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        btnHistoryMain = findViewById(R.id.btnHistoryMain)
        btnCoord = findViewById(R.id.btnCoord)
        btnLithology = findViewById(R.id.btnLithology)

        records.addAll(RecordStorage.load(this))
        updateLatestRecordView()
        loadHistoryTracks()

        magneticDeclination = getSharedPreferences("settings", MODE_PRIVATE).getFloat("declination", 0f)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        btnSave.setOnClickListener { saveCurrent() }
        btnCamera.setOnClickListener { checkPermissionsAndOpenCamera() }
        btnTrackToggle.setOnClickListener { toggleTrackRecording() }
        btnNavigate.setOnClickListener { showNavigateDialog() }
        btnAverage.setOnClickListener { startAverageSampling() }
        btnSatellite.setOnClickListener { showSatelliteInfo() }
        btnExportKml.setOnClickListener { exportKml() }
        btnExportCsv.setOnClickListener { exportCsv() }
        btnHistoryMain.setOnClickListener { showHistoryMainDialog() }
        btnCoord.setOnClickListener { showCoordDialog() }
        btnLithology.setOnClickListener { showLithologyDialog() }
        btnBorehole.setOnClickListener { showBoreholeDialog() }
        btnDeclination.setOnClickListener { showDeclinationDialog() }
        btnProjection.setOnClickListener { showProjectionDialog() }

        tvCurrentCoord.setOnClickListener { showAllTrackPointsDialog() }
        tvLatestRecord.setOnClickListener { showAllRecordsDialog() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
            registerGnssStatus()
            forceRefreshLocation()
        } else {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        pressureSensor?.also { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (isRecordingTrack || isNavigating) startLocationUpdates()
        registerGnssStatus()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, gravity, 0, 3)
            Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, geomagnetic, 0, 3)
        }
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            val att = AttitudeCalculator.fromRotationMatrix(rotationMatrix)
            currentAttitude = att

            val correctedStrike = (att.strike + magneticDeclination + 360) % 360
            val correctedDipDir = (att.dipDirection + magneticDeclination + 360) % 360

            tvStrike.text = "走向: " + "%.1f".format(correctedStrike) + "°"
            tvDip.text = "倾角: " + "%.1f".format(att.dip) + "°"
            tvDipDir.text = "倾向: " + "%.1f".format(correctedDipDir) + "°"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun forceRefreshLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val client = LocationServices.getFusedLocationProviderClient(this)
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                currentLocation = loc
                updateCoordDisplay()
                Thread {
                    val addr = getAddressFromLocation(loc.latitude, loc.longitude)
                    runOnUiThread {
                        currentAddress = if (addr.isNotBlank()) addr else "地址解析中/失败"
                        updateCoordDisplay()
                    }
                }.start()
            }
        }
        val token = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    currentLocation = loc
                    lastGoodLocation = loc
                    updateCoordDisplay()
                    Thread {
                        val addr = getAddressFromLocation(loc.latitude, loc.longitude)
                        runOnUiThread {
                            currentAddress = if (addr.isNotBlank()) addr else "地址解析中/失败"
                            updateCoordDisplay()
                        }
                    }.start()
                }
            }
    }

    private fun updateCoordDisplay() {
        val loc = currentLocation
        if (loc == null) {
            tvCurrentCoord.text = "坐标获取中...\n点击可查看本次所有轨迹点"
            return
        }
        val lat = "%.6f".format(loc.latitude)
        val lng = "%.6f".format(loc.longitude)
        val alt = if (loc.hasAltitude()) "%.1f".format(loc.altitude) + " m" else "无"
        tvCurrentCoord.text = "纬度: $lat\n经度: $lng\n海拔: $alt\n地点: $currentAddress\n（点击查看本次所有轨迹点）"
    }

    private fun updateLatestRecordView() {
        if (records.isEmpty()) {
            tvLatestRecord.text = "暂无记录"
            return
        }
        val r = records[0]
        val sb = StringBuilder()
        sb.append(r.time).append("\n")
        sb.append("走向: ").append("%.1f".format(r.strike)).append("°  倾角: ").append("%.1f".format(r.dip)).append("°  倾向: ").append("%.1f".format(r.dipDirection)).append("°\n")
        if (r.latitude != 0.0) sb.append("坐标: ").append("%.6f".format(r.latitude)).append(", ").append("%.6f".format(r.longitude)).append("\n")
        if (r.lithology.isNotBlank()) sb.append("岩性: ").append(r.lithology).append("\n")
        if (r.note.isNotBlank()) sb.append("备注: ").append(r.note)
        tvLatestRecord.text = sb.toString()
    }

    private fun showAllTrackPointsDialog() {
        if (trackPoints.isEmpty()) {
            Toast.makeText(this, "本次软件打开后暂无轨迹点", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.append("本次共 ").append(trackPoints.size).append(" 个轨迹点\n\n")
        trackPoints.forEachIndexed { i, p ->
            sb.append(i + 1).append(". ")
            sb.append("%.6f".format(p.latitude)).append(", ").append("%.6f".format(p.longitude))
            if (p.altitude != 0.0) sb.append("  海拔").append("%.1f".format(p.altitude)).append("m")
            sb.append("\n")
        }
        AlertDialog.Builder(this).setTitle("本次轨迹点坐标").setMessage(sb.toString()).setPositiveButton("确定", null).show()
    }

    private fun showAllRecordsDialog() {
        if (records.isEmpty()) {
            Toast.makeText(this, "暂无保存记录", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        records.forEachIndexed { i, r ->
            sb.append("【").append(i + 1).append("】 ").append(r.time).append("\n")
            sb.append("走向: ").append("%.1f".format(r.strike)).append("°  倾角: ").append("%.1f".format(r.dip)).append("°  倾向: ").append("%.1f".format(r.dipDirection)).append("°\n")
            if (r.latitude != 0.0) {
                sb.append("坐标: ").append("%.6f".format(r.latitude)).append(", ").append("%.6f".format(r.longitude))
                if (r.altitude != 0.0) sb.append("  海拔").append("%.1f".format(r.altitude)).append("m")
                sb.append("\n")
            }
            if (r.lithology.isNotBlank()) sb.append("岩性: ").append(r.lithology).append("\n")
            if (r.note.isNotBlank()) sb.append("备注: ").append(r.note).append("\n")
            sb.append("\n")
        }
        AlertDialog.Builder(this).setTitle("全部保存记录（共" + records.size + "条）").setMessage(sb.toString()).setPositiveButton("确定", null).show()
    }

    private fun saveCurrent() {
        val att = currentAttitude ?: run {
            Toast.makeText(this, "请先把手机背面贴在岩面上", Toast.LENGTH_SHORT).show()
            return
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val loc = currentLocation
        val correctedStrike = (att.strike + magneticDeclination + 360) % 360
        val correctedDipDir = (att.dipDirection + magneticDeclination + 360) % 360
        val record = Record(
            strike = correctedStrike,
            dip = att.dip,
            dipDirection = correctedDipDir,
            time = time,
            note = "",
            latitude = loc?.latitude ?: 0.0,
            longitude = loc?.longitude ?: 0.0,
            altitude = loc?.altitude ?: 0.0,
            lithology = currentLithology
        )
        records.add(0, record)
        RecordStorage.save(this, records)
        updateLatestRecordView()
        Toast.makeText(this, "已保存（含坐标与岩性）", Toast.LENGTH_SHORT).show()
    }

    // ==================== 磁偏角校正 ====================
    private fun showDeclinationDialog() {
        val et = EditText(this)
        et.setText(magneticDeclination.toString())
        et.hint = "东偏为正，西偏为负（例如 -5.5）"
        et.inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED

        AlertDialog.Builder(this)
            .setTitle("磁偏角校正（单位：度）")
            .setMessage("当前磁偏角：$magneticDeclination°\n输入后实时生效，保存产状时也会使用校正后的值。")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val value = et.text.toString().toFloatOrNull() ?: 0f
                magneticDeclination = value
                getSharedPreferences("settings", MODE_PRIVATE).edit().putFloat("declination", value).apply()
                Toast.makeText(this, "磁偏角已设为 $value°", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("清零") { _, _ ->
                magneticDeclination = 0f
                getSharedPreferences("settings", MODE_PRIVATE).edit().putFloat("declination", 0f).apply()
                Toast.makeText(this, "已清零", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ==================== 赤平投影 + 玫瑰花图 ====================
    private fun showProjectionDialog() {
        if (records.isEmpty()) {
            Toast.makeText(this, "暂无产状数据，请先保存几条记录", Toast.LENGTH_SHORT).show()
            return
        }
        val view = RoseStereonetView(this)
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            900
        )
        view.setData(records)
        AlertDialog.Builder(this)
            .setTitle("赤平投影 + 走向玫瑰花图")
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    // ==================== 钻孔计算 ====================
    private fun showBoreholeDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val etLat = EditText(this).apply {
            hint = "孔口纬度（例如 30.123456）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val etLng = EditText(this).apply {
            hint = "孔口经度（例如 114.123456）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val etAzimuth = EditText(this).apply {
            hint = "钻孔方位角（0-360°）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etInclination = EditText(this).apply {
            hint = "钻孔倾角（从水平面起算，0=水平 90=竖直）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etDepth = EditText(this).apply {
            hint = "孔深（米）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        currentLocation?.let {
            etLat.setText("%.6f".format(it.latitude))
            etLng.setText("%.6f".format(it.longitude))
        }
        layout.addView(etLat)
        layout.addView(etLng)
        layout.addView(etAzimuth)
        layout.addView(etInclination)
        layout.addView(etDepth)

        AlertDialog.Builder(this)
            .setTitle("钻孔计算")
            .setView(layout)
            .setPositiveButton("计算") { _, _ ->
                try {
                    val lat0 = etLat.text.toString().toDouble()
                    val lng0 = etLng.text.toString().toDouble()
                    val azimuth = etAzimuth.text.toString().toDouble()
                    val inclination = etInclination.text.toString().toDouble()
                    val depth = etDepth.text.toString().toDouble()
                    if (depth <= 0 || inclination < 0 || inclination > 90) {
                        Toast.makeText(this, "请输入有效的孔深和倾角（0-90）", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val incRad = Math.toRadians(inclination)
                    val horizontalDist = depth * cos(incRad)
                    val verticalDepth = depth * sin(incRad)
                    val azRad = Math.toRadians(azimuth)
                    val deltaE = horizontalDist * sin(azRad)
                    val deltaN = horizontalDist * cos(azRad)
                    val deltaLat = deltaN / 111320.0
                    val deltaLng = deltaE / (111320.0 * cos(Math.toRadians(lat0)))
                    val targetLat = lat0 + deltaLat
                    val targetLng = lng0 + deltaLng

                    val result = StringBuilder()
                    result.append("【计算结果】\n\n")
                    result.append("孔口坐标：\n  纬度 ").append("%.6f".format(lat0)).append("\n  经度 ").append("%.6f".format(lng0)).append("\n\n")
                    result.append("目标点（孔深 ").append("%.1f".format(depth)).append(" m）：\n")
                    result.append("  垂直深度：").append("%.2f".format(verticalDepth)).append(" m\n")
                    result.append("  水平距离：").append("%.2f".format(horizontalDist)).append(" m\n\n")
                    result.append("目标点投影到地表的坐标：\n  纬度 ").append("%.6f".format(targetLat)).append("\n  经度 ").append("%.6f".format(targetLng)).append("\n\n")
                    result.append("投影点与孔口的水平距离：\n  ").append("%.2f".format(horizontalDist)).append(" 米")

                    AlertDialog.Builder(this)
                        .setTitle("钻孔计算结果")
                        .setMessage(result.toString())
                        .setPositiveButton("确定", null)
                        .show()
                } catch (e: Exception) {
                    Toast.makeText(this, "输入格式错误，请检查数字", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showHistoryMainDialog() {
        val options = arrayOf("历史产状测量数据（按天）", "历史轨迹（按天）")
        AlertDialog.Builder(this)
            .setTitle("历史记录")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showHistoryDialog()
                    1 -> showHistoryTrackDialog()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showCoordDialog() {
        val isLatLng = CoordHelper.isLatLng(this)
        val zone = CoordHelper.getZone(this)
        val options = arrayOf(
            "当前: " + if (isLatLng) "经纬度" else "公里网（带号 $zone）",
            "切换为经纬度",
            "切换为公里网并修改带号"
        )
        AlertDialog.Builder(this)
            .setTitle("坐标显示方式")
            .setItems(options) { _, which ->
                when (which) {
                    1 -> {
                        CoordHelper.setMode(this, true)
                        Toast.makeText(this, "已切换为经纬度", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val et = EditText(this)
                        et.setText(zone.toString())
                        et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        AlertDialog.Builder(this)
                            .setTitle("输入带号")
                            .setView(et)
                            .setPositiveButton("确定") { _, _ ->
                                val z = et.text.toString().toIntOrNull() ?: 50
                                CoordHelper.setZone(this, z)
                                CoordHelper.setMode(this, false)
                                Toast.makeText(this, "已切换为公里网，带号 $z", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showLithologyDialog() {
        val et = EditText(this)
        et.setText(currentLithology)
        et.hint = "输入当前岩性描述"
        AlertDialog.Builder(this)
            .setTitle("岩性描述")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                currentLithology = et.text.toString().trim()
                Toast.makeText(this, "岩性已设置: $currentLithology", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("岩性库") { _, _ -> showLithologyLibrary() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLithologyLibrary() {
        val list = LithologyStorage.load(this)
        val items = list.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("岩性库")
            .setItems(items) { _, which ->
                currentLithology = list[which]
                Toast.makeText(this, "已选择: $currentLithology", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("批量添加") { _, _ ->
                val et = EditText(this)
                et.hint = "每行一个岩性"
                et.minLines = 5
                AlertDialog.Builder(this)
                    .setTitle("批量添加岩性（每行一个）")
                    .setView(et)
                    .setPositiveButton("添加") { _, _ ->
                        val lines = et.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }
                        list.addAll(lines)
                        LithologyStorage.save(this, list.distinct())
                        Toast.makeText(this, "已添加 ${lines.size} 个", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }// ===== 水印相机 =====
    private fun checkPermissionsAndOpenCamera() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val needRequest = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needRequest.isEmpty()) showWatermarkOptionsDialog()
        else permissionLauncher.launch(needRequest.toTypedArray())
    }

    private fun showWatermarkOptionsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_watermark_options, null)
        val cbLatLng = view.findViewById<CheckBox>(R.id.cbLatLng)
        val cbAltitude = view.findViewById<CheckBox>(R.id.cbAltitude)
        val cbAddress = view.findViewById<CheckBox>(R.id.cbAddress)
        val cbTime = view.findViewById<CheckBox>(R.id.cbTime)
        val cbAttitude = view.findViewById<CheckBox>(R.id.cbAttitude)
        val cbNote = view.findViewById<CheckBox>(R.id.cbNote)
        val etNote = view.findViewById<EditText>(R.id.etNote)
        cbLatLng.isChecked = watermarkOptions.latLng
        cbAltitude.isChecked = watermarkOptions.altitude
        cbAddress.isChecked = watermarkOptions.address
        cbTime.isChecked = watermarkOptions.time
        cbAttitude.isChecked = watermarkOptions.attitude
        cbNote.isChecked = watermarkOptions.note
        etNote.setText(watermarkOptions.noteText)
        AlertDialog.Builder(this)
            .setTitle("选择水印内容")
            .setView(view)
            .setPositiveButton("开始拍照") { _, _ ->
                watermarkOptions = WatermarkOptions(cbLatLng.isChecked, cbAltitude.isChecked, cbAddress.isChecked, cbTime.isChecked, cbAttitude.isChecked, cbNote.isChecked, etNote.text.toString())
                launchCamera()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchCamera() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            photoFile = File.createTempFile("ROCK_" + timeStamp + "_", ".jpg", storageDir)
            photoUri = FileProvider.getUriForFile(this, packageName + ".fileprovider", photoFile!!)
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开相机: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun processAndSaveWatermarkedPhoto(file: File) {
        Toast.makeText(this, "正在获取位置并添加水印...", Toast.LENGTH_SHORT).show()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            addWatermarkAndSave(file, null)
            return
        }
        val client = LocationServices.getFusedLocationProviderClient(this)
        client.lastLocation.addOnSuccessListener { last ->
            if (last != null) addWatermarkAndSave(file, last) else requestFreshLocation(client, file)
        }.addOnFailureListener { requestFreshLocation(client, file) }
    }

    private fun requestFreshLocation(client: FusedLocationProviderClient, file: File) {
        val token = CancellationTokenSource()
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable { token.cancel(); addWatermarkAndSave(file, null) }
        handler.postDelayed(timeout, 10000)
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { loc -> handler.removeCallbacks(timeout); addWatermarkAndSave(file, loc) }
            .addOnFailureListener {
                handler.removeCallbacks(timeout)
                client.lastLocation.addOnSuccessListener { addWatermarkAndSave(file, it) }.addOnFailureListener { addWatermarkAndSave(file, null) }
            }
    }

    private fun addWatermarkAndSave(file: File, location: Location?) {
        try {
            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val result = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = maxOf(result.width * 0.032f, 28f)
                setShadowLayer(5f, 2f, 2f, Color.BLACK)
            }
            val lines = mutableListOf<String>()
            if (watermarkOptions.time) lines.add("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            if (location != null) {
                if (watermarkOptions.latLng) {
                    lines.add("经度: " + "%.6f".format(location.longitude))
                    lines.add("纬度: " + "%.6f".format(location.latitude))
                }
                if (watermarkOptions.altitude) lines.add(if (location.hasAltitude()) "海拔: " + "%.1f".format(location.altitude) + " m" else "海拔: 无数据")
                if (watermarkOptions.address) {
                    val addr = getAddressFromLocation(location.latitude, location.longitude)
                    lines.add(if (addr.isNotBlank()) "地点: $addr" else "地点: 地址解析失败")
                }
            } else if (watermarkOptions.latLng || watermarkOptions.altitude || watermarkOptions.address) {
                lines.add("位置: 获取失败，请开启GPS后重试")
            }
            if (watermarkOptions.attitude && currentAttitude != null) {
                val a = currentAttitude!!
                val cs = (a.strike + magneticDeclination + 360) % 360
                val cd = (a.dipDirection + magneticDeclination + 360) % 360
                lines.add("走向: " + "%.1f".format(cs) + "°  倾角: " + "%.1f".format(a.dip) + "°  倾向: " + "%.1f".format(cd) + "°")
            }
            if (watermarkOptions.note && watermarkOptions.noteText.isNotBlank()) lines.add("备注: " + watermarkOptions.noteText)
            var y = result.height - 40f
            for (i in lines.indices.reversed()) {
                canvas.drawText(lines[i], 40f, y, paint)
                y -= paint.textSize * 1.45f
            }
            val uri = saveBitmapToGallery(result)
            result.recycle()
            original.recycle()
            Toast.makeText(this, if (uri != null) "水印照片已保存" else "保存失败", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "处理失败: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun getAddressFromLocation(lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(this, Locale.CHINA)
            @Suppress("DEPRECATION")
            val list = geocoder.getFromLocation(lat, lng, 1)
            if (!list.isNullOrEmpty()) {
                val a = list[0]
                buildString {
                    a.adminArea?.let { append(it) }
                    a.locality?.let { append(it) }
                    a.subLocality?.let { append(it) }
                    a.thoroughfare?.let { append(it) }
                    a.featureName?.let { if (it != a.thoroughfare) append(it) }
                }.ifBlank { a.getAddressLine(0) ?: "" }
            } else ""
        } catch (e: Exception) { "" }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        val name = "RockAttitude_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RockAttitude")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        }
        return uri
    }

    // ===== 轨迹与高精度定位 =====
    private fun toggleTrackRecording() {
        if (isRecordingTrack) {
            isRecordingTrack = false
            btnTrackToggle.text = "开始记录轨迹"
            saveCurrentTrackToHistory()
            stopLocationUpdates()
            Toast.makeText(this, "已停止记录轨迹", Toast.LENGTH_SHORT).show()
        } else checkLocationPermissionAndStart()
    }

    private fun checkLocationPermissionAndStart() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            isRecordingTrack = true
            btnTrackToggle.text = "停止记录轨迹"
            startLocationUpdates()
            Toast.makeText(this, "开始记录轨迹", Toast.LENGTH_SHORT).show()
        } else locationPermissionLauncher.launch(perms)
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 800)
            .setMinUpdateIntervalMillis(400)
            .setWaitForAccurateLocation(true)
            .build()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                if (loc.accuracy > 25f) return
                if (lastGoodLocation != null) {
                    val dist = lastGoodLocation!!.distanceTo(loc)
                    val timeDiff = (loc.time - lastGoodLocation!!.time) / 1000.0
                    if (timeDiff > 0 && dist / timeDiff > 30) return
                }
                lastGoodLocation = loc
                currentLocation = loc
                Thread {
                    val addr = getAddressFromLocation(loc.latitude, loc.longitude)
                    runOnUiThread {
                        currentAddress = if (addr.isNotBlank()) addr else "地址解析中/失败"
                        updateCoordDisplay()
                    }
                }.start()
                updateCoordDisplay()
                val point = TrackPoint(loc.latitude, loc.longitude, loc.altitude)
                if (isRecordingTrack) {
                    val last = trackPoints.lastOrNull()
                    if (last == null || distanceBetween(last, point) > 1.5) trackPoints.add(point)
                }
                trackView.updateTrack(trackPoints, currentLocation?.let { TrackPoint(it.latitude, it.longitude, it.altitude) }, navTarget)
                if (isNavigating && navTarget != null) updateNavigationInfo()
            }
        }
        fusedLocationClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }

    private fun showNavigateDialog() {
        if (trackPoints.isEmpty()) {
            Toast.makeText(this, "请先记录轨迹再进行导航", Toast.LENGTH_SHORT).show()
            return
        }
        if (isNavigating) {
            isNavigating = false
            navTarget = null
            tvNavInfo.text = ""
            btnNavigate.text = "导航"
            trackView.updateTrack(trackPoints, currentLocation?.let { TrackPoint(it.latitude, it.longitude) }, null)
            Toast.makeText(this, "已停止导航", Toast.LENGTH_SHORT).show()
            return
        }
        val items = Array(trackPoints.size) { index ->
            val p = trackPoints[index]
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(p.time))
            "点" + (index + 1) + "  " + time + "  lat=" + "%.5f".format(p.latitude) + "  lng=" + "%.5f".format(p.longitude)
        }
        AlertDialog.Builder(this)
            .setTitle("选择导航目标点")
            .setItems(items) { _, which ->
                navTarget = trackPoints[which]
                isNavigating = true
                btnNavigate.text = "停止导航"
                if (!isRecordingTrack) startLocationUpdates()
                updateNavigationInfo()
                Toast.makeText(this, "开始导航到选中点", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateNavigationInfo() {
        val loc = currentLocation ?: return
        val target = navTarget ?: return
        val dist = distanceBetween(TrackPoint(loc.latitude, loc.longitude), target)
        val bearing = bearingBetween(TrackPoint(loc.latitude, loc.longitude), target)
        val xte = calculateCrossTrackError(loc, target)
        tvNavInfo.text = "距离目标: " + "%.1f".format(dist) + " m   方位: " + "%.0f".format(bearing) + "°   偏离: " + "%.1f".format(xte) + " m"
        if (abs(xte) > 10.0) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTime > 30000) {
                lastAlertTime = now
                AlertDialog.Builder(this)
                    .setTitle("航向偏离报警")
                    .setMessage("当前偏离航线 " + "%.1f".format(abs(xte)) + " 米，请注意调整方向！")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
        trackView.updateTrack(trackPoints, TrackPoint(loc.latitude, loc.longitude, loc.altitude), target)
    }

    private fun distanceBetween(p1: TrackPoint, p2: TrackPoint): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLng = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude)) * sin(dLng / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearingBetween(p1: TrackPoint, p2: TrackPoint): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val dLng = Math.toRadians(p2.longitude - p1.longitude)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun calculateCrossTrackError(current: Location, target: TrackPoint): Double {
        if (trackPoints.size < 2) return 0.0
        var minDist = Double.MAX_VALUE
        var bestXte = 0.0
        for (i in 0 until trackPoints.size - 1) {
            val a = trackPoints[i]
            val b = trackPoints[i + 1]
            val xte = crossTrackDistance(TrackPoint(current.latitude, current.longitude), a, b)
            val d = distanceBetween(TrackPoint(current.latitude, current.longitude), a)
            if (d < minDist) {
                minDist = d
                bestXte = xte
            }
        }
        return bestXte
    }

    private fun crossTrackDistance(p: TrackPoint, a: TrackPoint, b: TrackPoint): Double {
        val d13 = distanceBetween(a, p) / 6371000.0
        val brng13 = Math.toRadians(bearingBetween(a, p))
        val brng12 = Math.toRadians(bearingBetween(a, b))
        return asin(sin(d13) * sin(brng13 - brng12)) * 6371000.0
    }

    private fun startAverageSampling() {
        if (isAveraging) {
            Toast.makeText(this, "正在采样中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        if (currentAttitude == null) {
            Toast.makeText(this, "请先把手机背面贴在岩面上", Toast.LENGTH_SHORT).show()
            return
        }
        isAveraging = true
        averageSamples.clear()
        btnAverage.text = "采样中..."
        btnAverage.isEnabled = false
        Toast.makeText(this, "开始平均采样，请保持手机稳定 3 秒", Toast.LENGTH_SHORT).show()
        val sampleRunnable = object : Runnable {
            var count = 0
            override fun run() {
                currentAttitude?.let { averageSamples.add(it) }
                count++
                if (count < 15) averageHandler.postDelayed(this, 200) else finishAverageSampling()
            }
        }
        averageHandler.post(sampleRunnable)
    }

    private fun finishAverageSampling() {
        isAveraging = false
        btnAverage.text = "平均采样"
        btnAverage.isEnabled = true
        if (averageSamples.isEmpty()) {
            Toast.makeText(this, "采样失败，请重试", Toast.LENGTH_SHORT).show()
            return
        }
        val avgStrike = circularMean(averageSamples.map { it.strike.toDouble() })
        val avgDip = averageSamples.map { it.dip }.average().toFloat()
        val avgDipDir = circularMean(averageSamples.map { it.dipDirection.toDouble() })
        val correctedStrike = ((avgStrike + magneticDeclination) % 360 + 360) % 360
        val correctedDipDir = ((avgDipDir + magneticDeclination) % 360 + 360) % 360
        val msg = "平均采样结果 " + averageSamples.size + " 个点：\n\n走向: " + "%.1f".format(correctedStrike) + "°\n倾角: " + "%.1f".format(avgDip) + "°\n倾向: " + "%.1f".format(correctedDipDir) + "°"
        AlertDialog.Builder(this)
            .setTitle("平均采样完成")
            .setMessage(msg)
            .setPositiveButton("保存此结果") { _, _ ->
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val loc = currentLocation
                val record = Record(
                    strike = correctedStrike.toFloat(),
                    dip = avgDip,
                    dipDirection = correctedDipDir.toFloat(),
                    time = time,
                    note = "平均采样" + averageSamples.size + "点",
                    latitude = loc?.latitude ?: 0.0,
                    longitude = loc?.longitude ?: 0.0,
                    altitude = loc?.altitude ?: 0.0,
                    lithology = currentLithology
                )
                records.add(0, record)
                RecordStorage.save(this, records)
                updateLatestRecordView()
                Toast.makeText(this, "已保存平均结果", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun circularMean(angles: List<Double>): Double {
        if (angles.isEmpty()) return 0.0
        var sumSin = 0.0
        var sumCos = 0.0
        for (a in angles) {
            val rad = Math.toRadians(a)
            sumSin += sin(rad)
            sumCos += cos(rad)
        }
        val meanRad = atan2(sumSin / angles.size, sumCos / angles.size)
        return (Math.toDegrees(meanRad) + 360) % 360
    }

    private fun showSatelliteInfo() {
        forceRefreshLocation()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
        registerGnssStatus()
        Handler(Looper.getMainLooper()).postDelayed({
            val view = layoutInflater.inflate(R.layout.dialog_satellite, null)
            val tvSatCount = view.findViewById<TextView>(R.id.tvSatCount)
            val skyplot = view.findViewById<SkyplotView>(R.id.skyplotView)
            tvSatCount.text = "当前参与定位卫星数量: " + satellitesUsed + " 颗"
            skyplot.setSatellites(satList.toList())
            AlertDialog.Builder(this).setView(view).setPositiveButton("确定", null).show()
        }, 800)
    }

    private fun registerGnssStatus() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            gnssCallback?.let { try { locationManager.unregisterGnssStatusCallback(it) } catch (e: Exception) {} }
            gnssCallback = object : android.location.GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                    satelliteCount = status.satelliteCount
                    var used = 0
                    satList.clear()
                    for (i in 0 until status.satelliteCount) {
                        val usedInFix = status.usedInFix(i)
                        if (usedInFix) used++
                        val az = status.getAzimuthDegrees(i)
                        val el = status.getElevationDegrees(i)
                        if (el >= 10) satList.add(SatInfo(az, el, usedInFix))
                    }
                    satellitesUsed = used
                }
            }
            try { locationManager.registerGnssStatusCallback(gnssCallback!!, Handler(Looper.getMainLooper())) } catch (e: Exception) {}
        }
    }

    private fun getExportDir(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "111000")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun exportKml() {
        if (trackPoints.size < 2) {
            Toast.makeText(this, "当前轨迹点数不足", Toast.LENGTH_SHORT).show()
            return
        }
        exportKmlWithTrack(trackPoints, "当前轨迹")
    }

    private fun exportKmlWithTrack(points: List<TrackPoint>, name: String) {
        try {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
            sb.append("  <name>").append(name).append("</name>\n")
            sb.append("  <Placemark>\n    <name>轨迹线</name>\n")
            sb.append("    <Style><LineStyle><color>ff0000ff</color><width>4</width></LineStyle></Style>\n")
            sb.append("    <LineString><tessellate>1</tessellate><coordinates>\n")
            for (p in points) sb.append(p.longitude).append(",").append(p.latitude).append(",").append(p.altitude).append(" ")
            sb.append("\n</coordinates></LineString>\n  </Placemark>\n</Document>\n</kml>")
            val fileName = "Track_" + name.replace(" ", "_") + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".kml"
            val file = File(getExportDir(), fileName)
            file.writeText(sb.toString(), Charsets.UTF_8)
            Toast.makeText(this, "KML已保存到 Documents/111000/" + fileName, Toast.LENGTH_LONG).show()
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/vnd.google-earth.kml+xml"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(share, "导出KML"))
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportCsv() {
        if (records.isEmpty()) {
            Toast.makeText(this, "没有产状记录可导出", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val sb = StringBuilder()
            sb.append("时间,走向,倾角,倾向,纬度,经度,海拔,岩性,备注\n")
            for (r in records) {
                sb.append("\"").append(r.time).append("\",")
                sb.append(r.strike).append(",").append(r.dip).append(",").append(r.dipDirection).append(",")
                sb.append(r.latitude).append(",").append(r.longitude).append(",").append(r.altitude).append(",")
                sb.append("\"").append(r.lithology).append("\",\"").append(r.note).append("\"\n")
            }
            val fileName = "产状_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".csv"
            val file = File(getExportDir(), fileName)
            file.writeText(sb.toString(), Charsets.UTF_8)
            Toast.makeText(this, "CSV已保存到 Documents/111000/" + fileName, Toast.LENGTH_LONG).show()
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(share, "导出CSV"))
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showHistoryDialog() {
        if (records.isEmpty()) {
            Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show()
            return
        }
        val grouped = records.groupBy { it.time.substring(0, 10) }.toSortedMap(reverseOrder())
        val dayList = grouped.keys.toList()
        val dayItems = dayList.map { day -> day + "  " + (grouped[day]?.size ?: 0) + " 条记录" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("历史产状测量数据（按天）")
            .setItems(dayItems) { _, which ->
                val selectedDay = dayList[which]
                val dayRecords = grouped[selectedDay] ?: emptyList()
                showDayDetail(selectedDay, dayRecords)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showDayDetail(day: String, dayRecords: List<Record>) {
        val sb = StringBuilder()
        sb.append(day).append(" 共 ").append(dayRecords.size).append(" 条记录\n\n")
        dayRecords.forEachIndexed { index, r ->
            sb.append(index + 1).append(". ").append(r.time.substring(11)).append("\n")
            sb.append("   走向: ").append("%.1f".format(r.strike)).append("°  倾角: ").append("%.1f".format(r.dip)).append("°  倾向: ").append("%.1f".format(r.dipDirection)).append("°\n")
            if (r.latitude != 0.0) sb.append("   坐标: ").append("%.6f".format(r.latitude)).append(", ").append("%.6f".format(r.longitude)).append("\n")
            if (r.lithology.isNotBlank()) sb.append("   岩性: ").append(r.lithology).append("\n")
            if (r.note.isNotBlank()) sb.append("   备注: ").append(r.note).append("\n")
            sb.append("\n")
        }
        AlertDialog.Builder(this).setTitle(day + " 详细数据").setMessage(sb.toString()).setPositiveButton("确定", null).show()
    }

    private fun saveCurrentTrackToHistory() {
        if (trackPoints.size < 2) return
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val key = day + "_" + SimpleDateFormat("HHmmss", Locale.getDefault()).format(Date())
        historyTracks[key] = trackPoints.toMutableList()
        saveHistoryTracks()
    }

    private fun loadHistoryTracks() {
        val prefs = getSharedPreferences("history_tracks", MODE_PRIVATE)
        historyTracks.clear()
        for ((key, value) in prefs.all) {
            try {
                val json = value as? String ?: continue
                val list = mutableListOf<TrackPoint>()
                for (p in json.split("|")) {
                    if (p.isBlank()) continue
                    val arr = p.split(",")
                    if (arr.size >= 2) list.add(TrackPoint(arr[0].toDouble(), arr[1].toDouble(), arr.getOrNull(2)?.toDouble() ?: 0.0))
                }
                if (list.isNotEmpty()) historyTracks[key] = list
            } catch (e: Exception) {}
        }
    }

    private fun saveHistoryTracks() {
        val prefs = getSharedPreferences("history_tracks", MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()
        for ((key, list) in historyTracks) {
            val sb = StringBuilder()
            for (p in list) sb.append(p.latitude).append(",").append(p.longitude).append(",").append(p.altitude).append("|")
            editor.putString(key, sb.toString())
        }
        editor.apply()
    }

    private fun showHistoryTrackDialog() {
        if (historyTracks.isEmpty()) {
            Toast.makeText(this, "暂无历史轨迹", Toast.LENGTH_SHORT).show()
            return
        }
        val keys = historyTracks.keys.sortedDescending().toTypedArray()
        val items = keys.map { key ->
            val count = historyTracks[key]?.size ?: 0
            key.replace("_", " ") + "  （" + count + " 个点）"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("历史轨迹列表（按天）")
            .setItems(items) { _, which ->
                val selectedKey = keys[which]
                val selectedTrack = historyTracks[selectedKey] ?: return@setItems
                showTrackOptions(selectedKey, selectedTrack)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showTrackOptions(key: String, track: List<TrackPoint>) {
        val options = arrayOf("加载到预览窗口编辑", "导出此轨迹为 KML", "删除此轨迹")
        AlertDialog.Builder(this)
            .setTitle(key.replace("_", " "))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        trackPoints.clear()
                        trackPoints.addAll(track)
                        trackView.updateTrack(trackPoints, null, null)
                        Toast.makeText(this, "已加载到预览窗口", Toast.LENGTH_SHORT).show()
                    }
                    1 -> exportKmlWithTrack(track, key)
                    2 -> {
                        historyTracks.remove(key)
                        saveHistoryTracks()
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
