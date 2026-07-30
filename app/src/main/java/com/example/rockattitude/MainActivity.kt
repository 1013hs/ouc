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
import android.view.View
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
    private var currentPressure = 1013.25f

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
    private lateinit var btnDocs: Button
    private lateinit var btnAbout: Button

    private val records = mutableListOf<Record>()
    private var currentAttitude: Attitude? = null
    private var magneticDeclination = 0f
    private var pendingRecord: Record? = null

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
    private var locationManager: android.location.LocationManager? = null
    private var nativeLocationListener: android.location.LocationListener? = null

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
        if (success && photoFile != null && photoFile!!.exists()) {
            if (pendingRecord != null) {
                pendingRecord!!.photoPath = photoFile!!.absolutePath
                records.add(0, pendingRecord!!)
                RecordStorage.save(this, records)
                updateLatestRecordView()
                pendingRecord = null
                Toast.makeText(this, "产状+照片已保存", Toast.LENGTH_SHORT).show()
            } else {
                processAndSaveWatermarkedPhoto(photoFile!!)
            }
        } else {
            if (pendingRecord != null) {
                records.add(0, pendingRecord!!)
                RecordStorage.save(this, records)
                updateLatestRecordView()
                pendingRecord = null
                Toast.makeText(this, "已保存（未拍照）", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "拍照取消或失败", Toast.LENGTH_SHORT).show()
            }
        }
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

    private val pickDocLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else "文档_${System.currentTimeMillis()}.docx"
            } ?: "文档_${System.currentTimeMillis()}.docx"
            contentResolver.openInputStream(uri)?.use { input ->
                DocStorage.save(this, name, input.readBytes())
            }
            Toast.makeText(this, "已添加: $name", Toast.LENGTH_SHORT).show()
            showDocLibrary()
        } catch (e: Exception) {
            Toast.makeText(this, "添加失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
        btnDocs = findViewById(R.id.btnDocs)
        btnAbout = findViewById(R.id.btnAbout)
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
        btnDocs.setOnClickListener { showDocLibrary() }
        btnAbout.setOnClickListener { showAboutDialog() }

        tvCurrentCoord.setOnClickListener { showAllTrackPointsDialog() }
        tvLatestRecord.setOnClickListener { showAllRecordsDialog() }

        trackView.setOnClickListener {
            SharedTrackData.points = trackPoints.toList()
            startActivity(android.content.Intent(this, TrackFullActivity::class.java))
        }

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
            Sensor.TYPE_PRESSURE -> {
                currentPressure = event.values[0]
                val now = System.currentTimeMillis()
                SharedTrackData.pressHistory.add(now to currentPressure)
                if (SharedTrackData.pressHistory.size > 120) SharedTrackData.pressHistory.removeAt(0)
            }
        }
        var att: Attitude? = null
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            att = AttitudeCalculator.fromRotationMatrix(rotationMatrix)
        } else {
            att = AttitudeCalculator.fromGravity(gravity)
        }
        if (att != null) {
            currentAttitude = att
            val correctedStrike = (att.strike + magneticDeclination + 360) % 360
            val correctedDipDir = (att.dipDirection + magneticDeclination + 360) % 360
            tvStrike.text = "走向: " + "%.1f".format(correctedStrike) + "°"
            tvDip.text = "倾角: " + "%.1f".format(att.dip) + "°"
            tvDipDir.text = "倾向: " + "%.1f".format(correctedDipDir) + "°"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun formatCoord(lat: Double, lng: Double): String {
        return if (CoordHelper.isLatLng(this)) {
            "纬度: " + "%.6f".format(lat) + "\n经度: " + "%.6f".format(lng)
        } else {
            val zone = CoordHelper.getZone(this)
            val (n, e) = CoordHelper.toGaussKruger(lat, lng, zone)
            "北向: " + "%.2f".format(n) + " m\n东向: " + "%.2f".format(e) + " m\n带号: $zone"
        }
    }

    private fun formatCoordOneLine(lat: Double, lng: Double): String {
        return if (CoordHelper.isLatLng(this)) {
            "%.6f".format(lat) + ", " + "%.6f".format(lng)
        } else {
            val zone = CoordHelper.getZone(this)
            val (n, e) = CoordHelper.toGaussKruger(lat, lng, zone)
            "N" + "%.1f".format(n) + " E" + "%.1f".format(e) + " (带" + zone + ")"
        }
    }

    private fun forceRefreshLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && loc.accuracy <= 50f) processNewLocation(loc)
            }
            val token = CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                .addOnSuccessListener { loc -> if (loc != null) processNewLocation(loc) }
        } catch (e: Exception) {}
        startNativeLocation()
    }

    private fun processNewLocation(loc: Location) {
        if (loc.accuracy > 30f) return
        if (lastGoodLocation != null && loc.time <= lastGoodLocation!!.time) return
        if (lastGoodLocation != null) {
            val dist = lastGoodLocation!!.distanceTo(loc)
            val dt = (loc.time - lastGoodLocation!!.time) / 1000.0
            if (dt > 0 && dist / dt > 35.0) return
        }
        if (lastGoodLocation != null) {
            val speed = if (loc.hasSpeed()) loc.speed else 0f
            val dist = lastGoodLocation!!.distanceTo(loc)
            if (speed < 0.45f && dist < 2.8f) {
                currentLocation = loc
                updateCoordDisplay()
                updateAddressAsync(loc)
                return
            }
        }
        if (lastGoodLocation != null && isRecordingTrack) {
            val alpha = 0.38f
            loc.latitude = alpha * loc.latitude + (1 - alpha) * lastGoodLocation!!.latitude
            loc.longitude = alpha * loc.longitude + (1 - alpha) * lastGoodLocation!!.longitude
        }
        lastGoodLocation = Location(loc)
        currentLocation = loc
        updateCoordDisplay()
        updateAddressAsync(loc)
        recordAlt(loc)
        val point = TrackPoint(loc.latitude, loc.longitude, loc.altitude)
        if (isRecordingTrack) {
            val last = trackPoints.lastOrNull()
            val minDist = if (loc.hasSpeed() && loc.speed > 1.5f) 1.2 else 2.5
            if (last == null || distanceBetween(last, point) > minDist) trackPoints.add(point)
        }
        trackView.updateTrack(trackPoints, TrackPoint(loc.latitude, loc.longitude, loc.altitude), navTarget)
        if (isNavigating && navTarget != null) updateNavigationInfo()
    }

    private fun recordAlt(loc: Location) {
        if (loc.hasAltitude()) {
            val now = System.currentTimeMillis()
            SharedTrackData.altHistory.add(now to loc.altitude.toFloat())
            if (SharedTrackData.altHistory.size > 120) SharedTrackData.altHistory.removeAt(0)
        }
    }

    private fun startNativeLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (locationManager == null) locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        try {
            val lastGps = locationManager?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            val lastNet = locationManager?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            val best = when {
                lastGps != null && lastNet != null -> if (lastGps.time >= lastNet.time) lastGps else lastNet
                lastGps != null -> lastGps
                lastNet != null -> lastNet
                else -> null
            }
            if (best != null) processNewLocation(best)
        } catch (e: Exception) {}
        if (nativeLocationListener == null) {
            nativeLocationListener = object : android.location.LocationListener {
                override fun onLocationChanged(loc: Location) { processNewLocation(loc) }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
        }
        try {
            locationManager?.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 700L, 1f, nativeLocationListener!!)
            locationManager?.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 1200L, 3f, nativeLocationListener!!)
        } catch (e: Exception) {}
    }

    private fun updateAddressAsync(loc: Location) {
        Thread {
            val addr = getAddressFromLocation(loc.latitude, loc.longitude)
            runOnUiThread {
                currentAddress = if (addr.isNotBlank()) addr else "地址解析中/失败"
                updateCoordDisplay()
            }
        }.start()
    }

    private fun startLocationUpdates() { forceRefreshLocation() }

    private fun stopLocationUpdates() {
        try { locationCallback?.let { fusedLocationClient.removeLocationUpdates(it); locationCallback = null } } catch (e: Exception) {}
        try { nativeLocationListener?.let { locationManager?.removeUpdates(it) } } catch (e: Exception) {}
    }

    private fun updateCoordDisplay() {
        val loc = currentLocation
        if (loc == null) {
            tvCurrentCoord.text = "坐标获取中...\n点击可查看本次所有轨迹点"
            return
        }
        val alt = if (loc.hasAltitude()) "%.1f".format(loc.altitude) + " m" else "无"
        tvCurrentCoord.text = formatCoord(loc.latitude, loc.longitude) +
                "\n海拔: $alt\n地点: $currentAddress\n（点击查看本次所有轨迹点）"
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
        if (r.latitude != 0.0) sb.append(formatCoordOneLine(r.latitude, r.longitude)).append("\n")
        if (r.lithology.isNotBlank()) sb.append("岩性: ").append(r.lithology).append("\n")
        if (r.note.isNotBlank()) sb.append("备注: ").append(r.note).append("\n")
        if (r.photoPath.isNotBlank()) sb.append("【已关联照片】")
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
            sb.append(i + 1).append(". ").append(formatCoordOneLine(p.latitude, p.longitude))
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
        val items = records.mapIndexed { i, r ->
            val photoFlag = if (r.photoPath.isNotBlank()) " 📷" else ""
            (i + 1).toString() + ". " + r.time.substring(11) + "  走向" + "%.0f".format(r.strike) + "°" + photoFlag
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("全部保存记录（共" + records.size + "条）")
            .setItems(items) { _, which -> showRecordDetail(records[which]) }
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showRecordDetail(r: Record) {
        val sb = StringBuilder()
        sb.append("时间: ").append(r.time).append("\n")
        sb.append("走向: ").append("%.1f".format(r.strike)).append("°\n")
        sb.append("倾角: ").append("%.1f".format(r.dip)).append("°\n")
        sb.append("倾向: ").append("%.1f".format(r.dipDirection)).append("°\n")
        if (r.latitude != 0.0) sb.append(formatCoord(r.latitude, r.longitude)).append("\n")
        if (r.altitude != 0.0) sb.append("海拔: ").append("%.1f".format(r.altitude)).append(" m\n")
        if (r.lithology.isNotBlank()) sb.append("岩性: ").append(r.lithology).append("\n")
        if (r.note.isNotBlank()) sb.append("备注: ").append(r.note).append("\n")
        val builder = AlertDialog.Builder(this).setTitle("产状详情").setMessage(sb.toString())
        if (r.photoPath.isNotBlank() && File(r.photoPath).exists()) {
            builder.setPositiveButton("查看照片") { _, _ ->
                try {
                    val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", File(r.photoPath))
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/*")
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "无法打开照片", Toast.LENGTH_SHORT).show()
                }
            }
            builder.setNegativeButton("关闭", null)
        } else {
            builder.setPositiveButton("关闭", null)
        }
        builder.show()
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
            strike = correctedStrike, dip = att.dip, dipDirection = correctedDipDir,
            time = time, note = "",
            latitude = loc?.latitude ?: 0.0, longitude = loc?.longitude ?: 0.0,
            altitude = loc?.altitude ?: 0.0, lithology = currentLithology
        )
        AlertDialog.Builder(this)
            .setTitle("保存产状")
            .setMessage("是否为该测点拍照？")
            .setPositiveButton("拍照并保存") { _, _ ->
                pendingRecord = record
                launchCameraForRecord()
            }
            .setNegativeButton("仅保存") { _, _ ->
                records.add(0, record)
                RecordStorage.save(this, records)
                updateLatestRecordView()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun launchCameraForRecord() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            photoFile = File.createTempFile("POINT_" + timeStamp + "_", ".jpg", storageDir)
            photoUri = FileProvider.getUriForFile(this, packageName + ".fileprovider", photoFile!!)
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开相机", Toast.LENGTH_SHORT).show()
            pendingRecord?.let {
                records.add(0, it)
                RecordStorage.save(this, records)
                updateLatestRecordView()
            }
            pendingRecord = null
        }
    }

    private fun showAboutDialog() {
        val msg = """
岩层产状测量软件 (Rock Attitude) - 版权声明与免责声明

软件名称：岩层产状测量 (Rock Attitude)
当前版本：V6.15
制作人：1037YHL
版权所有：© 2026 1037YHL。保留所有权利。

一、 版权声明与权力保护
版权归属与AI辅助：本软件（包含但不限于软件程序、界面设计、图标、代码、文档及相关数据内容）之完整版权、知识产权及所有相关权益，均独家归属于制作人 1037YHL。虽然软件部分模块在研发过程中借助了AI（人工智能）技术进行辅助设计与编写，但经人工深度调试、整合与重构形成的软件整体架构与最终成果之合法权益与权属仍全部归属于制作人 1037YHL。
原创声明与第三方组件：本软件在功能设计与交互体验上积极参考并吸收了 DGS数字填图、奥维互动地图、高德地图、两步路 等业内优秀专业软件的先进理念，但具有完全独立的知识产权，未抄袭、复制或盗用上述任何软件的源代码、底层算法或专有内容。软件在开发过程中若引用了部分开源代码、公开图标或第三方组件，其相关权益归属原作者所有，本软件对其进行了合规集成与合法调用。
权力保护：
未经制作人明确书面授权，任何单位、个人、企业或组织不得以任何形式（包括但不限于：复制、修改、反向工程、反编译、汇编、截取核心算法、盗用界面UI、二次打包分发等）侵犯本软件的知识产权。
本软件仅供地质工作者、科研人员及相关专业爱好者在合法合规的前提下进行学习、研究使用。严禁用于任何形式的非法商业牟利、恶意破解或篡改。
制作人保留依法追究一切侵权、盗版、恶意篡改及不正当竞争行为法律责任的权利。

二、 免责声明
使用本软件即表示您已阅读、理解并完全同意接受本免责声明的所有条款。如果您不同意本声明的任何内容，请立即卸载并停止使用本软件。
1. 误差问题、AI辅助与软件准确度
AI辅助说明：本软件部分功能及代码逻辑由AI技术辅助生成，软件所呈现的计算结果与处理流程仅供参考。
精度局限：受移动终端内置传感器物理局限及AI算法局限性的影响，测量结果无法替代传统地质罗盘、全站仪、高精度RTK等专业仪器。软件计算结果不作为最终法定地质测量依据。
2. 个人能力与使用风险自承担
地质野外工作属于高风险特种作业。因用户自身操作不当或对软件数据产生绝对依赖而导致的一切损失，制作人 1037YHL 不承担任何法律责任。
3. 适用范围与专业参考
本软件主要适用于地质教学实习、普查、大面踏勘及初步调查。严禁将数据直接应用于国家重大基础设施、精密矿产储量计算等对精度有严苛要求的法定工程项目。
4. 传感器调用、设备保护、数据安全与软件服务
用户负有定期备份数据的责任。因野外恶劣环境导致的设备损坏或数据丢失，制作人不承担赔偿责任。
5. 法律法规、涉密问题、测绘合规与不可抗力
用户必须严格遵守《测绘法》《保守国家秘密法》等相关法律法规。严禁在军事禁区、保密核心区进行测量。因不可抗力导致服务中断的，制作人不承担责任。
特别提示：野外地质工作，安全与严谨永远是第一位的。请结合传统地质罗盘及综合地质观察进行交叉验证。
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("关于 / 版权与免责声明")
            .setMessage(msg)
            .setPositiveButton("我已阅读并同意", null)
            .show()
    }

    private fun showDeclinationDialog() {
        val et = EditText(this)
        et.setText(magneticDeclination.toString())
        et.hint = "东偏为正，西偏为负（例如 -5.5）"
        et.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        AlertDialog.Builder(this)
            .setTitle("磁偏角校正（单位：度）")
            .setMessage("当前磁偏角：$magneticDeclination°")
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

    private fun showProjectionDialog() {
        if (records.isEmpty()) {
            Toast.makeText(this, "暂无产状数据，请先保存几条记录", Toast.LENGTH_SHORT).show()
            return
        }
        val view = RoseStereonetView(this)
        view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 900)
        view.setData(records)
        AlertDialog.Builder(this).setTitle("赤平投影 + 走向玫瑰花图").setView(view).setPositiveButton("关闭", null).show()
    }

    private fun showBoreholeDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val etLat = EditText(this).apply {
            hint = "孔口纬度"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val etLng = EditText(this).apply {
            hint = "孔口经度"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val etAzimuth = EditText(this).apply {
            hint = "钻孔方位角（0-360°）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val etInclination = EditText(this).apply {
            hint = "钻孔倾角（从水平面，0=水平 90=竖直）"
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
                    result.append("【计算结果】\n\n孔口：").append("%.6f".format(lat0)).append(", ").append("%.6f".format(lng0))
                    result.append("\n垂直深度：").append("%.2f".format(verticalDepth)).append(" m")
                    result.append("\n水平距离：").append("%.2f".format(horizontalDist)).append(" m")
                    result.append("\n投影坐标：").append("%.6f".format(targetLat)).append(", ").append("%.6f".format(targetLng))
                    AlertDialog.Builder(this).setTitle("钻孔计算结果").setMessage(result.toString()).setPositiveButton("确定", null).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "输入格式错误", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("绘制柱状图") { _, _ -> showBoreholeColumnInputDialog() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBoreholeColumnInputDialog() {
        val etData = EditText(this).apply {
            hint = "每行一层：名称,起始深度,结束深度\n例：\n第四系,0,12\n砂岩,12,45\n泥岩,45,80"
            minLines = 6
            setText("第四系,0,12\n砂岩,12,45\n泥岩,45,80")
        }
        AlertDialog.Builder(this)
            .setTitle("输入钻孔层位数据")
            .setView(etData)
            .setPositiveButton("绘制并预览") { _, _ ->
                val lines = etData.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }
                val colors = listOf(
                    Color.parseColor("#FFECB3"), Color.parseColor("#FFE0B2"),
                    Color.parseColor("#FFCCBC"), Color.parseColor("#C8E6C9"),
                    Color.parseColor("#BBDEFB"), Color.parseColor("#D1C4E9"),
                    Color.parseColor("#F8BBD0"), Color.parseColor("#B2EBF2")
                )
                val layers = mutableListOf<BoreholeColumnView.Layer>()
                lines.forEachIndexed { i, line ->
                    val p = line.split(",", "，")
                    if (p.size >= 3) {
                        val name = p[0].trim()
                        val from = p[1].trim().toFloatOrNull() ?: 0f
                        val to = p[2].trim().toFloatOrNull() ?: (from + 10f)
                        layers.add(BoreholeColumnView.Layer(name, from, to, colors[i % colors.size]))
                    }
                }
                if (layers.isEmpty()) {
                    Toast.makeText(this, "没有解析到有效层位数据", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                showColumnPreviewDialog(layers)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showColumnPreviewDialog(layers: List<BoreholeColumnView.Layer>) {
        val columnView = BoreholeColumnView(this)
        columnView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 900)
        columnView.setLayers(layers)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 10, 20, 10)
            addView(columnView)
        }
        AlertDialog.Builder(this)
            .setTitle("钻孔柱状图预览")
            .setView(container)
            .setPositiveButton("导出图片") { _, _ ->
                val etName = EditText(this)
                etName.setText("钻孔柱状图_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()))
                etName.hint = "输入文件名（不含扩展名）"
                AlertDialog.Builder(this)
                    .setTitle("导出柱状图 - 自定义文件名")
                    .setView(etName)
                    .setPositiveButton("保存") { _, _ ->
                        val name = etName.text.toString().trim().ifBlank { "钻孔柱状图" }
                        saveColumnImage(columnView, name)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun saveColumnImage(view: BoreholeColumnView, fileName: String) {
        try {
            val width = 800
            val height = 1200
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            val dir = getExportDir()
            val file = File(dir, "$fileName.png")
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
            Toast.makeText(this, "柱状图已保存到 Documents/111000/$fileName.png", Toast.LENGTH_LONG).show()
            val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
            val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(share, "分享柱状图"))
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: " + e.message, Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showDocLibrary() {
        val files = DocStorage.list(this)
        val items = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("离线文档库")
            .setItems(items) { _, which ->
                val f = files[which]
                val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { startActivity(intent) } catch (e: Exception) {
                    Toast.makeText(this, "无法打开，请安装WPS或Office", Toast.LENGTH_LONG).show()
                }
            }
            .setPositiveButton("上传文档") { _, _ -> pickDocLauncher.launch("*/*") }
            .setNegativeButton("关闭", null)
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
                        updateCoordDisplay()
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
                                updateCoordDisplay()
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
                    .setTitle("批量添加岩性")
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
    }

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
        if (currentLocation != null) {
            addWatermarkAndSave(file, currentLocation)
            return
        }
        forceRefreshLocation()
        Handler(Looper.getMainLooper()).postDelayed({
            addWatermarkAndSave(file, currentLocation)
        }, 2800)
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
                if (watermarkOptions.latLng) lines.add(formatCoordOneLine(location.latitude, location.longitude))
                if (watermarkOptions.altitude) lines.add(if (location.hasAltitude()) "海拔: " + "%.1f".format(location.altitude) + " m" else "海拔: 无数据")
                if (watermarkOptions.address) {
                    val addr = getAddressFromLocation(location.latitude, location.longitude)
                    lines.add(if (addr.isNotBlank()) "地点: $addr" else "地点: " + currentAddress)
                }
            } else if (watermarkOptions.latLng || watermarkOptions.altitude || watermarkOptions.address) {
                lines.add("位置: 获取失败，请到室外开启GPS后重试")
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
            "点" + (index + 1) + "  " + time + "  " + formatCoordOneLine(p.latitude, p.longitude)
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
                    strike = correctedStrike.toFloat(), dip = avgDip, dipDirection = correctedDipDir.toFloat(),
                    time = time, note = "平均采样" + averageSamples.size + "点",
                    latitude = loc?.latitude ?: 0.0, longitude = loc?.longitude ?: 0.0,
                    altitude = loc?.altitude ?: 0.0, lithology = currentLithology
                )
                AlertDialog.Builder(this)
                    .setTitle("是否拍照？")
                    .setMessage("是否为该平均采样点拍照？")
                    .setPositiveButton("拍照并保存") { _, _ ->
                        pendingRecord = record
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
        val et = EditText(this)
        et.setText("轨迹_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()))
        et.hint = "输入文件名（不含扩展名）"
        AlertDialog.Builder(this)
            .setTitle("导出 KML - 自定义文件名")
            .setView(et)
            .setPositiveButton("导出") { _, _ ->
                val name = et.text.toString().trim().ifBlank { "轨迹" }
                exportKmlWithTrack(trackPoints, name)
            }
            .setNegativeButton("取消", null)
            .show()
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
            val file = File(getExportDir(), "$name.kml")
            file.writeText(sb.toString(), Charsets.UTF_8)
            Toast.makeText(this, "KML已保存: ${file.name}", Toast.LENGTH_LONG).show()
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
        val et = EditText(this)
        et.setText("产状_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()))
        et.hint = "输入文件名（不含扩展名）"
        AlertDialog.Builder(this)
            .setTitle("导出 CSV - 自定义文件名")
            .setView(et)
            .setPositiveButton("导出") { _, _ ->
                val name = et.text.toString().trim().ifBlank { "产状" }
                doExportCsv(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doExportCsv(fileName: String) {
        try {
            val isLatLng = CoordHelper.isLatLng(this)
            val sb = StringBuilder()
            sb.append("\uFEFF")
            if (isLatLng) {
                sb.append("测点日期,纬度,经度,海拔(m),走向,倾角,倾向,岩性,备注\n")
            } else {
                sb.append("测点日期,北向(m),东向(m),海拔(m),走向,倾角,倾向,岩性,备注\n")
            }
            for (r in records) {
                sb.append("\"").append(r.time).append("\",")
                if (isLatLng) {
                    sb.append("%.6f".format(r.latitude)).append(",")
                    sb.append("%.6f".format(r.longitude)).append(",")
                } else {
                    val (n, e) = CoordHelper.toGaussKruger(r.latitude, r.longitude, CoordHelper.getZone(this))
                    sb.append("%.2f".format(n)).append(",")
                    sb.append("%.2f".format(e)).append(",")
                }
                sb.append("%.1f".format(r.altitude)).append(",")
                sb.append("%.1f".format(r.strike)).append(",")
                sb.append("%.1f".format(r.dip)).append(",")
                sb.append("%.1f".format(r.dipDirection)).append(",")
                sb.append("\"").append(r.lithology).append("\",")
                sb.append("\"").append(r.note).append("\"\n")
            }
            val file = File(getExportDir(), "$fileName.csv")
            file.writeText(sb.toString(), Charsets.UTF_8)
            Toast.makeText(this, "CSV已保存（已解决乱码）: ${file.name}", Toast.LENGTH_LONG).show()
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
                showDayDetail(dayList[which], grouped[dayList[which]] ?: emptyList())
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
            if (r.latitude != 0.0) sb.append("   ").append(formatCoordOneLine(r.latitude, r.longitude)).append("\n")
            if (r.lithology.isNotBlank()) sb.append("   岩性: ").append(r.lithology).append("\n")
            if (r.note.isNotBlank()) sb.append("   备注: ").append(r.note).append("\n")
            if (r.photoPath.isNotBlank()) sb.append("   【已关联照片】\n")
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
