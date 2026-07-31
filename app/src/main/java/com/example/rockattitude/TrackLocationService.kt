package com.example.rockattitude

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlin.math.*

class TrackLocationService : Service() {

    companion object {
        const val ACTION_START = "com.example.rockattitude.TRACK_START"
        const val ACTION_STOP = "com.example.rockattitude.TRACK_STOP"
        const val CHANNEL_ID = "track_location_channel"
        const val NOTIF_ID = 10087
        @Volatile var isRunning = false
        @Volatile var currentSession: TrackSession? = null
        val livePoints = mutableListOf<TrackPoint>()
        var lastLoc: Location? = null
        var motionState = "unknown" // stationary / walking / fast
    }

    private lateinit var fused: FusedLocationProviderClient
    private var callback: LocationCallback? = null
    private var lm: LocationManager? = null
    private var nativeListener: LocationListener? = null
    private val handler = Handler(Looper.getMainLooper())
    private var persistRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "轨迹记录", NotificationManager.IMPORTANCE_LOW)
            ch.description = "野外轨迹后台持续记录"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TrackLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("岩层产状 · 轨迹记录中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .build()
    }

    private fun startTracking() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        isRunning = true
        if (currentSession == null) {
            val id = "T" + System.currentTimeMillis()
            currentSession = TrackSession(
                id = id,
                name = "轨迹_" + SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(Date()),
                startTime = System.currentTimeMillis(),
                endTime = System.currentTimeMillis(),
                points = mutableListOf()
            )
            livePoints.clear()
            TrackStorage.setCurrentId(this, id)
        }
        startForeground(NOTIF_ID, buildNotification("定位中…"))
        requestLocations()
        schedulePersist()
    }

    private fun requestLocations() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(800L)
            .setMinUpdateDistanceMeters(1f)
            .setWaitForAccurateLocation(false)
            .build()

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onNewLocation(it) }
            }
        }
        try {
            fused.requestLocationUpdates(req, callback!!, Looper.getMainLooper())
        } catch (_: Exception) {}

        nativeListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) { onNewLocation(loc) }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 800L, 1f, nativeListener!!)
            lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5f, nativeListener!!)
        } catch (_: Exception) {}
    }

    private fun onNewLocation(loc: Location) {
        if (!loc.hasAccuracy() || loc.accuracy > 25f) return
        if (lastLoc != null && loc.time <= lastLoc!!.time) return

        // 速度跳变
        if (lastLoc != null) {
            val dist = lastLoc!!.distanceTo(loc)
            val dt = (loc.time - lastLoc!!.time) / 1000.0
            if (dt > 0 && dist / dt > 12.0) return
        }

        val speed = if (loc.hasSpeed()) loc.speed else 0f
        motionState = when {
            speed < 0.35f -> "stationary"
            speed < 2.0f -> "walking"
            else -> "fast"
        }

        // 静止：平滑显示但不加点
        if (lastLoc != null && motionState == "stationary") {
            val dist = lastLoc!!.distanceTo(loc)
            if (dist < 4.5f) {
                val a = 0.22f
                loc.latitude = a * loc.latitude + (1 - a) * lastLoc!!.latitude
                loc.longitude = a * loc.longitude + (1 - a) * lastLoc!!.longitude
                lastLoc = Location(loc)
                updateNotif()
                return
            }
        }

        // 平滑
        if (lastLoc != null) {
            val a = 0.35f
            loc.latitude = a * loc.latitude + (1 - a) * lastLoc!!.latitude
            loc.longitude = a * loc.longitude + (1 - a) * lastLoc!!.longitude
        }
        lastLoc = Location(loc)

        val minDist = when (motionState) {
            "fast" -> 2.0
            "walking" -> 3.5
            else -> 6.0
        }
        val pt = TrackPoint(loc.latitude, loc.longitude, loc.altitude, loc.time)
        val last = livePoints.lastOrNull()
        if (last == null || haversine(last, pt) >= minDist) {
            livePoints.add(pt)
            currentSession?.points?.add(pt)
            currentSession?.endTime = System.currentTimeMillis()
        }
        updateNotif()
    }

    private fun haversine(a: TrackPoint, b: TrackPoint): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val x = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
                cos(Math.toRadians(b.latitude)) * sin(dLng / 2).pow(2)
        return R * 2 * atan2(sqrt(x), sqrt(1 - x))
    }

    private fun updateNotif() {
        val n = livePoints.size
        val state = when (motionState) {
            "stationary" -> "静止"
            "walking" -> "徒步"
            "fast" -> "快速"
            else -> ""
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification("已记录 $n 点 · $state"))
    }

    private fun schedulePersist() {
        persistRunnable = object : Runnable {
            override fun run() {
                currentSession?.let {
                    it.endTime = System.currentTimeMillis()
                    TrackStorage.upsert(this@TrackLocationService, it)
                }
                handler.postDelayed(this, 15000L)
            }
        }
        handler.postDelayed(persistRunnable!!, 15000L)
    }

    private fun stopTracking() {
        isRunning = false
        try { callback?.let { fused.removeLocationUpdates(it) } } catch (_: Exception) {}
        try { nativeListener?.let { lm?.removeUpdates(it) } } catch (_: Exception) {}
        persistRunnable?.let { handler.removeCallbacks(it) }
        currentSession?.let {
            it.endTime = System.currentTimeMillis()
            TrackStorage.upsert(this, it)
        }
        currentSession = null
        TrackStorage.setCurrentId(this, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }
}

// 简易日期格式（Service 内用）
private fun SimpleDateFormat(pattern: String, locale: Locale) =
    java.text.SimpleDateFormat(pattern, locale)
