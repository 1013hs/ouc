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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    // ===== 传感器相关 =====
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)

    private lateinit var tvStrike: TextView
    private lateinit var tvDip: TextView
    private lateinit var tvDipDir: TextView
    private lateinit var btnSave: Button
    private lateinit var btnCamera: Button
    private lateinit var recyclerView: RecyclerView

    private val records = mutableListOf<Record>()
    private lateinit var adapter: RecordAdapter
    private var currentAttitude: Attitude? = null

    // ===== 水印相机 =====
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

    // ===== 轨迹 & 导航 =====
    private lateinit var trackView: TrackView
    private lateinit var btnTrackToggle: Button
    private lateinit var btnNavigate: Button
    private lateinit var tvNavInfo: TextView

    private val trackPoints = mutableListOf<TrackPoint>()
    private var isRecordingTrack = false
    private var currentLocation: Location? = null
    private var navTarget: TrackPoint? = null
    private var isNavigating = false
    private var lastAlertTime = 0L

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    // ===== 平均采样 =====
    private lateinit var btnAverage: Button
    private lateinit var btnSatellite: Button
    private val averageSamples = mutableListOf<Attitude>()
    private var isAveraging = false
    private val averageHandler = Handler(Looper.getMainLooper())

    // ===== 卫星信息 =====
    private var satelliteCount = 0
    private var satellitesUsed = 0
    private var gnssCallback: android.location.GnssStatus.Callback? = null

    // ===== 导出 & 历史 =====
    private lateinit var btnExportKml: Button
    private lateinit var btnExportCsv: Button
    private lateinit var btnHistory: Button

    // ===== Activity Result =====
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoFile != null && photoFile!!.exists()) {
            processAndSaveWatermarkedPhoto(photoFile!!)
        } else {
            Toast.makeText(this, "拍照取消或失败", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            showWatermarkOptionsDialog()
        } else {
            Toast.makeText(this, "需要相机和位置权限", Toast.LENGTH_LONG).show()
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "需要位置权限才能记录轨迹", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStrike = findViewById(R.id.tvStrike)
        tvDip = findViewById(R.id.tvDip)
        tvDipDir = findViewById(R.id.tvDipDir)
        btnSave = findViewById(R.id.btnSave)
        btnCamera = findViewById(R.id.btnCamera)
        recyclerView = findViewById(R.id.recyclerView)
        trackView = findViewById(R.id.trackView)
        btnTrackToggle = findViewById(R.id.btnTrackToggle)
        btnNavigate = findViewById(R.id.btnNavigate)
        tvNavInfo = findViewById(R.id.tvNavInfo)
        btnAverage = findViewById(R.id.btnAverage)
        btnSatellite = findViewById(R.id.btnSatellite)
        btnExportKml = findViewById(R.id.btnExportKml)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        btnHistory = findViewById(R.id.btnHistory)

        records.addAll(RecordStorage.load(this))
        adapter = RecordAdapter(records) { record, position -> showEditDialog(record, position) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        btnSave.setOnClickListener { saveCurrent() }
        btnCamera.setOnClickListener { checkPermissionsAndOpenCamera() }
        btnTrackToggle.setOnClickListener { toggleTrackRecording() }
        btnNavigate.setOnClickListener { showNavigateDialog() }
        btnAverage.setOnClickListener { startAverageSampling() }
        btnSatellite.setOnClickListener { showSatelliteInfo() }
        btnExportKml.setOnClickListener { exportKml() }
        btnExportCsv.setOnClickListener { exportCsv() }
        btnHistory.setOnClickListener { showHistoryDialog() }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (isRecordingTrack || isNavigating) {
            startLocationUpdates()
        }
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
            tvStrike.text = "走向: ${"%.1f".format(att.strike)}°"
            tvDip.text = "倾角: ${"%.1f".format(att.dip)}°"
            tvDipDir.text = "倾向: ${"%.1f".format(att.dipDirection)}°"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun saveCurrent() {
        val att = currentAttitude ?: run {
            Toast.makeText(this, "请先把手机背面贴在岩面上", Toast.LENGTH_SHORT).show()
            return
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val record = Record(strike = att.strike, dip = att.dip, dipDirection = att.dipDirection, time = time)
        records.add(0, record)
        RecordStorage.save(this, records)
        adapter.notifyItemInserted(0)
        recyclerView.scrollToPosition(0)
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun showEditDialog(record: Record, position: Int) {
        val view = layoutInflater.inflate(R.layout.dialog_edit, null)
        val etStrike = view.findViewById<EditText>(R.id.etStrike)
        val etDip = view.findViewById<EditText>(R.id.etDip)
        val etDipDir = view.findViewById<EditText>(R.id.etDipDir)
        val etNote = view.findViewById<EditText>(R.id.etNote)

        etStrike.setText(record.strike.toString())
        etDip.setText(record.dip.toString())
        etDipDir.setText(record.dipDirection.toString())
        etNote.setText(record.note)

        AlertDialog.Builder(this)
            .setTitle("编辑记录")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                try {
                    record.strike = etStrike.text.toString().toFloat()
                    record.dip = etDip.text.toString().toFloat()
                    record.dipDirection = etDipDir.text.toString().toFloat()
                    record.note = etNote.text.toString()
                    RecordStorage.save(this, records)
                    adapter.notifyItemChanged(position)
                    Toast.makeText(this, "已更新", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "输入格式错误", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("删除") { _, _ ->
                records.removeAt(position)
                RecordStorage.save(this, records)
                adapter.notifyItemRemoved(position)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ===== 水印相机 =====
    private fun checkPermissionsAndOpenCamera() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val needRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
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
                watermarkOptions = WatermarkOptions(
                    latLng = cbLatLng.isChecked,
                    altitude = cbAltitude.isChecked,
                    address = cbAddress.isChecked,
                    time = cbTime.isChecked,
                    attitude = cbAttitude.isChecked,
                    note = cbNote.isChecked,
                    noteText = etNote.text.toString()
                )
                launchCamera()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchCamera() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            photoFile = File.createTempFile("ROCK_${timeStamp}_", ".jpg", storageDir)
            photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile!!)
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开相机: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun processAndSaveWatermarkedPhoto(file: File) {
        Toast.makeText(this, "正在获取位置并添加水印...", Toast.LENGTH_SHORT).show()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            addWatermarkAndSave(file, null)
            return
        }
        val client = LocationServices.getFusedLocationProviderClient(this)
        client.lastLocation
            .addOnSuccessListener { last ->
                if (last != null) addWatermarkAndSave(file, last)
                else requestFreshLocation(client, file)
            }
            .addOnFailureListener { requestFreshLocation(client, file) }
    }

    private fun requestFreshLocation(client: FusedLocationProviderClient, file: File) {
        val token = CancellationTokenSource()
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable {
            token.cancel()
            addWatermarkAndSave(file, null)
        }
        handler.postDelayed(timeout, 10000)
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { loc ->
                handler.removeCallbacks(timeout)
                addWatermarkAndSave(file, loc)
            }
            .addOnFailureListener {
                handler.removeCallbacks(timeout)
                client.lastLocation
                    .addOnSuccessListener { addWatermarkAndSave(file, it) }
                    .addOnFailureListener { addWatermarkAndSave(file, null) }
            }
    }

    private fun addWatermarkAndSave(file: File, location: Location?) {
        try {
            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val result = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (result.width * 0.032f).coerceAtLeast(28f)
                setShadowLayer(5f, 2f, 2f, Color.BLACK)
            }
            val lines = mutableListOf<String>()
            if (watermarkOptions.time) {
                lines.add("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            }
            if (location != null) {
                if (watermarkOptions.latLng) {
                    lines.add("经度: ${"%.6f".format(location.longitude)}")
                    lines.add("纬度: ${"%.6f".format(location.latitude)}")
                }
                if (watermarkOptions.altitude) {
                    lines.add(if (location.hasAltitude()) "海拔: ${"%.1f".format(location.altitude)} m" else "海拔: 无数据")
                }
                if (watermarkOptions.address) {
                    val addr = getAddressFromLocation(location.latitude, location.longitude)
                    lines.add(if (addr.isNotBlank()) "地点: $addr" else "地点: 地址解析失败")
                }
            } else if (watermarkOptions.latLng || watermarkOptions.altitude || watermarkOptions.address) {
                lines.add("位置: 获取失败（请开启GPS后重试）")
            }
            if (watermarkOptions.attitude && currentAttitude != null) {
                val a = currentAttitude!!
                lines.add("走向: ${"%.1f".format(a.strike)}°  倾角: ${"%.1f".format(a.dip)}°  倾向: ${"%.1f".format(a.dipDirection)}°")
            }
            if (watermarkOptions.note && watermarkOptions.noteText.isNotBlank()) {
                lines.add("备注: ${watermarkOptions.noteText}")
            }
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
            Toast.makeText(this, "处理失败: ${e.message}", Toast.LENGTH_LONG).show()
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
                }.ifBlank { a.getAddressLine(0) ?: "" }
            } else ""
        } catch (e: Exception) { "" }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        val name = "RockAttitude_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
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
    }// ===== 轨迹记录 & 导航 =====
    private fun toggleTrackRecording() {
        if (isRecordingTrack) {
            isRecordingTrack = false
            btnTrackToggle.text = "开始记录轨迹"
            stopLocationUpdates()
            Toast.makeText(this, "已停止记录轨迹", Toast.LENGTH_SHORT).show()
        } else {
            checkLocationPermissionAndStart()
        }
    }

    private fun checkLocationPermissionAndStart() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            isRecordingTrack = true
            btnTrackToggle.text = "停止记录轨迹"
            startLocationUpdates()
            Toast.makeText(this, "开始记录轨迹", Toast.LENGTH_SHORT).show()
        } else {
            locationPermissionLauncher.launch(perms)
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                currentLocation = loc
                val point = TrackPoint(loc.latitude, loc.longitude, loc.altitude)

                if (isRecordingTrack) {
                    val last = trackPoints.lastOrNull()
                    if (last == null || distanceBetween(last, point) > 3.0) {
                        trackPoints.add(point)
                    }
                }

                trackView.updateTrack(
                    trackPoints,
                    currentLocation?.let { TrackPoint(it.latitude, it.longitude, it.altitude) },
                    navTarget
                )

                if (isNavigating && navTarget != null && currentLocation != null) {
                    updateNavigationInfo()
                }
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
            trackView.updateTrack(
                trackPoints,
                currentLocation?.let { TrackPoint(it.latitude, it.longitude) },
                null
            )
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
                if (!isRecordingTrack) {
                    startLocationUpdates()
                }
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

        tvNavInfo.text = "距离目标: ${"%.1f".format(dist)} m   方位: ${"%.0f".format(bearing)}°   偏离: ${"%.1f".format(xte)} m"

        if (abs(xte) > 10.0) {
            val now = System.currentTimeMillis()
            if (now - lastAlertTime > 30000) {
                lastAlertTime = now
                AlertDialog.Builder(this)
                    .setTitle("⚠ 航向偏离报警")
                    .setMessage("当前偏离航线 ${"%.1f".format(abs(xte))} 米，请注意调整方向！")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }

        trackView.updateTrack(
            trackPoints,
            TrackPoint(loc.latitude, loc.longitude, loc.altitude),
            target
        )
    }

    private fun distanceBetween(p1: TrackPoint, p2: TrackPoint): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLng = Math.toRadians(p2.longitude - p1.longitude)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude)) *
                sin(dLng / 2).pow(2)
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
        val toTarget = distanceBetween(TrackPoint(current.latitude, current.longitude), target)
        if (toTarget < minDist) {
            val last = trackPoints.lastOrNull() ?: return 0.0
            bestXte = crossTrackDistance(TrackPoint(current.latitude, current.longitude), last, target)
        }
        return bestXte
    }

    private fun crossTrackDistance(p: TrackPoint, a: TrackPoint, b: TrackPoint): Double {
        val d13 = distanceBetween(a, p) / 6371000.0
        val brng13 = Math.toRadians(bearingBetween(a, p))
        val brng12 = Math.toRadians(bearingBetween(a, b))
        return asin(sin(d13) * sin(brng13 - brng12)) * 6371000.0
    }

    // ==================== 多点平均采样 ====================
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
                if (count < 15) {
                    averageHandler.postDelayed(this, 200)
                } else {
                    finishAverageSampling()
                }
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

        val msg = "平均采样结果（${averageSamples.size} 个点）：\n\n" +
                "走向: ${"%.1f".format(avgStrike)}°\n" +
                "倾角: ${"%.1f".format(avgDip)}°\n" +
                "倾向: ${"%.1f".format(avgDipDir)}°"

        AlertDialog.Builder(this)
            .setTitle("平均采样完成")
            .setMessage(msg)
            .setPositiveButton("保存此结果") { _, _ ->
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val record = Record(
                    strike = avgStrike.toFloat(),
                    dip = avgDip,
                    dipDirection = avgDipDir.toFloat(),
                    time = time,
                    note = "平均采样(${averageSamples.size}点)"
                )
                records.add(0, record)
                RecordStorage.save(this, records)
                adapter.notifyItemInserted(0)
                recyclerView.scrollToPosition(0)
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

    // ==================== 卫星信息 ====================
    private fun showSatelliteInfo() {
        registerGnssStatus()

        val loc = currentLocation
        val sb = StringBuilder()

        sb.append("【卫星信息】\n")
        sb.append("可见卫星数: $satelliteCount\n")
        sb.append("用于定位卫星数: $satellitesUsed\n\n")

        sb.append("【当前位置】\n")
        if (loc != null) {
            sb.append("纬度: ${"%.6f".format(loc.latitude)}\n")
            sb.append("经度: ${"%.6f".format(loc.longitude)}\n")
            if (loc.hasAltitude()) {
                sb.append("海拔: ${"%.1f".format(loc.altitude)} m\n")
            } else {
                sb.append("海拔: 无数据\n")
            }
            if (loc.hasAccuracy()) {
                sb.append("精度: ±${"%.1f".format(loc.accuracy)} m\n")
            }
            sb.append("时间: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(loc.time))}")
        } else {
            sb.append("暂无位置信息\n请开启GPS并等待定位")
        }

        AlertDialog.Builder(this)
            .setTitle("卫星与位置信息")
            .setMessage(sb.toString())
            .setPositiveButton("确定", null)
            .show()
    }

    private fun registerGnssStatus() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (gnssCallback == null) {
                gnssCallback = object : android.location.GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                        satelliteCount = status.satelliteCount
                        var used = 0
                        for (i in 0 until status.satelliteCount) {
                            if (status.usedInFix(i)) used++
                        }
                        satellitesUsed = used
                    }
                }
                try {
                    locationManager.registerGnssStatusCallback(gnssCallback!!, Handler(Looper.getMainLooper()))
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }
    }

    // ==================== 导出 KML ====================
    private fun exportKml() {
        if (trackPoints.isEmpty() && records.isEmpty()) {
            Toast.makeText(this, "没有可导出的数据", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
            sb.append("<Document>\n")
            sb.append("  <name>岩层产状测量轨迹</name>\n")
            sb.append("  <description>由岩层产状测量APP导出</description>\n")

            if (trackPoints.size >= 2) {
                sb.append("  <Placemark>\n")
                sb.append("    <name>测量轨迹</name>\n")
                sb.append("    <LineString>\n")
                sb.append("      <coordinates>\n")
                for (p in trackPoints) {
                    sb.append("        \( {p.longitude}, \){p.latitude},${p.altitude}\n")
                }
                sb.append("      </coordinates>\n")
                sb.append("    </LineString>\n")
                sb.append("  </Placemark>\n")
            }

            trackPoints.forEachIndexed { index, p ->
                sb.append("  <Placemark>\n")
                sb.append("    <name>轨迹点${index + 1}</name>\n")
                sb.append("    <Point>\n")
                sb.append("      <coordinates>\( {p.longitude}, \){p.latitude},${p.altitude}</coordinates>\n")
                sb.append("    </Point>\n")
                sb.append("  </Placemark>\n")
            }

            sb.append("</Document>\n")
            sb.append("</kml>\n")

            val fileName = "RockAttitude_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.kml"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            file.parentFile?.mkdirs()
            file.writeText(sb.toString())

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/vnd.google-earth.kml+xml"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "导出KML文件"))
            Toast.makeText(this, "KML已生成", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 导出 CSV ====================
    private fun exportCsv() {
        if (records.isEmpty()) {
            Toast.makeText(this, "没有产状记录可导出", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val sb = StringBuilder()
            sb.append("时间,走向(°),倾角(°),倾向(°),备注\n")
            for (r in records) {
                sb.append("\"\( {r.time}\", \){r.strike},\( {r.dip}, \){r.dipDirection},\"${r.note}\"\n")
            }

            val fileName = "RockAttitude_产状_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            file.parentFile?.mkdirs()
            file.writeText(sb.toString(), Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(shareIntent, "导出产状CSV"))
            Toast.makeText(this, "CSV已生成", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 历史测量（按天） ====================
    private fun showHistoryDialog() {
        if (records.isEmpty()) {
            Toast.makeText(this, "暂无历史记录", Toast.LENGTH_SHORT).show()
            return
        }

        val grouped = records.groupBy { it.time.substring(0, 10) }
            .toSortedMap(reverseOrder())

        val dayList = grouped.keys.toList()
        val dayItems = dayList.map { day ->
            val count = grouped[day]?.size ?: 0
            "$day  （$count 条记录）"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("历史测量记录（按天）")
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
        sb.append("$day 共 ${dayRecords.size} 条记录\n\n")
        dayRecords.forEachIndexed { index, r ->
            sb.append("${index + 1}. ${r.time.substring(11)}\n")
            sb.append("   走向: ${"%.1f".format(r.strike)}°  倾角: ${"%.1f".format(r.dip)}°  倾向: ${"%.1f".format(r.dipDirection)}°\n")
            if (r.note.isNotBlank()) sb.append("   备注: ${r.note}\n")
            sb.append("\n")
        }

        AlertDialog.Builder(this)
            .setTitle("$day 详细数据")
            .setMessage(sb.toString())
            .setPositiveButton("确定", null)
            .show()
    }
}
