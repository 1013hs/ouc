package com.example.rockattitude

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private lateinit var tvStrike: TextView
    private lateinit var tvDip: TextView
    private lateinit var tvDipDir: TextView
    private lateinit var btnSave: Button
    private lateinit var recyclerView: RecyclerView

    private val records = mutableListOf<Record>()
    private lateinit var adapter: RecordAdapter

    private var currentAttitude: Attitude? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStrike = findViewById(R.id.tvStrike)
        tvDip = findViewById(R.id.tvDip)
        tvDipDir = findViewById(R.id.tvDipDir)
        btnSave = findViewById(R.id.btnSave)
        recyclerView = findViewById(R.id.recyclerView)

        records.addAll(RecordStorage.load(this))
        adapter = RecordAdapter(records) { record, position -> showEditDialog(record, position) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        btnSave.setOnClickListener { saveCurrent() }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
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
}
