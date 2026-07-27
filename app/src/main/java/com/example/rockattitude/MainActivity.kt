package com.example.rockattitude

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), android.hardware.SensorEventListener {

    private lateinit var sensorManager: android.hardware.SensorManager
    private var accelerometer: android.hardware.Sensor? = null
    private var magnetometer: android.hardware.Sensor? = null

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

    // 水印相机相关
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
        val allGranted = result.values.all { it }
        if (allGranted) {
            showWatermarkOptionsDialog()
        } else {
            Toast.makeText(this, "需要相机和位置权限才能使用水印相机", Toast.LENGTH_LONG).show()
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

        records.addAll(RecordStorage.load(this))
        adapter = RecordAdapter(records) { record, position -> showEditDialog(record, position) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        sensorManager = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
        accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD)

        btnSave.setOnClickListener { saveCurrent() }
        btnCamera.setOnClickListener { checkPermissionsAndOpenCamera() }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also {
            sensorManager.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.also {
            sensorManager.registerListener(this, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent) {
        when (event.sensor.type) {
            android.hardware.Sensor.TYPE_ACCELEROMETER ->
                System.arraycopy(event.values, 0, gravity, 0, 3)
            android.hardware.Sensor.TYPE_MAGNETIC_FIELD ->
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
        }

        if (android.hardware.SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            val att = AttitudeCalculator.fromRotationMatrix(rotationMatrix)
            currentAttitude = att
            tvStrike.text = "走向: ${"%.1f".format(att.strike)}°"
            tvDip.text = "倾角: ${"%.1f".format(att.dip)}°"
            tvDipDir.text = "倾向: ${"%.1f".format(att.dipDirection)}°"
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}

    private fun saveCurrent() {
        val att = currentAttitude ?: run {
            Toast.makeText(this, "请先把手机背面贴在岩面上", Toast.LENGTH_SHORT).show()
            return
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val record = Record(
            strike = att.strike,
            dip = att.dip,
            dipDirection = att.dipDirection,
            time = time
        )
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

    // ==================== 水印相机部分 ====================

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

        if (needRequest.isEmpty()) {
            showWatermarkOptionsDialog()
        } else {
            permissionLauncher.launch(needRequest.toTypedArray())
        }
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

        // 恢复上次选择
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
            photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile!!
            )
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开相机: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun processAndSaveWatermarkedPhoto(file: File) {
        Toast.makeText(this, "正在处理水印...", Toast.LENGTH_SHORT).show()

        // 获取位置
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val cancellationToken = CancellationTokenSource()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationToken.token
            ).addOnSuccessListener { location ->
                addWatermarkAndSave(file, location)
            }.addOnFailureListener {
                addWatermarkAndSave(file, null)
            }
        } else {
            addWatermarkAndSave(file, null)
        }
    }

    private fun addWatermarkAndSave(file: File, location: Location?) {
        try {
            val original = BitmapFactory.decodeFile(file.absolutePath)
                ?: run {
                    Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show()
                    return
                }

            val result = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = result.width * 0.035f   // 根据图片宽度自适应字号
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
            }

            val lines = mutableListOf<String>()

            if (watermarkOptions.time) {
                val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                lines.add("时间: $timeStr")
            }

            if (location != null) {
                if (watermarkOptions.latLng) {
                    lines.add("经度: ${"%.6f".format(location.longitude)}")
                    lines.add("纬度: ${"%.6f".format(location.latitude)}")
                }
                if (watermarkOptions.altitude && location.hasAltitude()) {
                    lines.add("海拔: ${"%.1f".format(location.altitude)} m")
                }
                if (watermarkOptions.address) {
                    val address = getAddressFromLocation(location.latitude, location.longitude)
                    if (address.isNotBlank()) lines.add("地点: $address")
                }
            }

            if (watermarkOptions.attitude && currentAttitude != null) {
                val att = currentAttitude!!
                lines.add("走向: ${"%.1f".format(att.strike)}°  倾角: ${"%.1f".format(att.dip)}°  倾向: ${"%.1f".format(att.dipDirection)}°")
            }

            if (watermarkOptions.note && watermarkOptions.noteText.isNotBlank()) {
                lines.add("备注: ${watermarkOptions.noteText}")
            }

            // 从底部往上画，留一点边距
            var y = result.height - 30f
            for (i in lines.indices.reversed()) {
                canvas.drawText(lines[i], 30f, y, paint)
                y -= paint.textSize * 1.4f
            }

            // 保存到系统相册
            val savedUri = saveBitmapToGallery(result)
            result.recycle()
            original.recycle()

            if (savedUri != null) {
                Toast.makeText(this, "水印照片已保存到相册", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "处理失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getAddressFromLocation(lat: Double, lng: Double): String {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val a = addresses[0]
                listOfNotNull(a.locality, a.subLocality, a.thoroughfare, a.featureName)
                    .distinct()
                    .joinToString("")
                    .ifBlank { a.getAddressLine(0) ?: "" }
            } else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun saveBitmapToGallery(bitmap: Bitmap): Uri? {
        val filename = "RockAttitude_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/RockAttitude")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        contentResolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, contentValues, null, null)
        }
        return uri
    }
}
