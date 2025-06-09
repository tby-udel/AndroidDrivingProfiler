package com.bytian.drivingprofiler

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    // UI Components
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnSendData: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSensorData: TextView
    private lateinit var tvSensorList: TextView

    // Sensor Management
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager

    // Core Sensors
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var linearAcceleration: Sensor? = null
    private var gravity: Sensor? = null
    private var magnetometer: Sensor? = null

    // Recording State
    private var isRecording = false
    private var dataCount = 0
    private var recordingStartTime = 0L

    // Data Storage
    private var dataFile: File? = null
    private var fileWriter: FileWriter? = null

    // Sensor Data Display and Throttling
    private val sensorDisplayData = mutableMapOf<String, String>()
    private val lastSampleTimes = mutableMapOf<String, Long>()
    private val handler = Handler(Looper.getMainLooper())
    private var uiUpdateTimer: Timer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Data integrity monitoring
    private var lastDataWriteTime = 0L
    private var dataGapDetected = false

    // Battery monitoring
    private var startBatteryLevel = -1
    private var endBatteryLevel = -1
    private var batteryReceiver: BroadcastReceiver? = null
    private var currentBatteryLevel = -1

    // Email Configuration
    private val targetEmail = "boyangtian429@gmail.com"

    // Sampling Intervals (more lenient for stability)
    private val samplingIntervals = mapOf(
        "accelerometer" to 5L,
        "gyroscope" to 5L,
        "linear_acceleration" to 5L,
        "gravity" to 10L,
        "magnetometer" to 50L,
        "gps_location" to 500L
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initSensors()
        initBatteryMonitoring()
        requestPermissions()
        displayAvailableSensors()
        clearOldDataFiles()
    }

    private fun initViews() {
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        btnSendData = findViewById(R.id.btn_send_data)
        tvStatus = findViewById(R.id.tv_status)
        tvSensorData = findViewById(R.id.tv_sensor_data)
        tvSensorList = findViewById(R.id.tv_sensor_list)

        btnStart.setOnClickListener { startRecording() }
        btnStop.setOnClickListener { stopRecording() }
        btnSendData.setOnClickListener { sendDataViaEmail() }

        btnStop.isEnabled = false
        btnSendData.isEnabled = false
        tvStatus.text = "Ready to start optimized recording"
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    private fun initBatteryMonitoring() {
        try {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    try {
                        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                        if (level != -1 && scale != -1) {
                            currentBatteryLevel = (level * 100) / scale
                        }
                    } catch (e: Exception) {
                        // Ignore battery update errors
                    }
                }
            }

            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            registerReceiver(batteryReceiver, filter)

            // Get initial battery level safely
            try {
                val batteryStatus = registerReceiver(null, filter)
                batteryStatus?.let { intent ->
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level != -1 && scale != -1) {
                        currentBatteryLevel = (level * 100) / scale
                    }
                }
            } catch (e: Exception) {
                currentBatteryLevel = 100 // Default fallback
            }
        } catch (e: Exception) {
            // If battery monitoring fails, continue without it
            currentBatteryLevel = 100
        }
    }

    private fun displayAvailableSensors() {
        val availableSensors = mutableListOf<String>()

        if (accelerometer != null) availableSensors.add("✓ Accelerometer")
        if (gyroscope != null) availableSensors.add("✓ Gyroscope")
        if (linearAcceleration != null) {
            availableSensors.add("✓ Linear Acceleration")
        } else {
            availableSensors.add("✗ Linear Acceleration (not available)")
        }
        if (gravity != null) {
            availableSensors.add("✓ Gravity Sensor")
        } else {
            availableSensors.add("✗ Gravity Sensor (not available)")
        }
        if (magnetometer != null) availableSensors.add("✓ Magnetometer")
        availableSensors.add("✓ GPS Location")

        val batteryText = if (currentBatteryLevel != -1) "\nBattery: ${currentBatteryLevel}%" else ""
        tvSensorList.text = "Optimized Sensor Configuration:\n${availableSensors.joinToString("\n")}${batteryText}"
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACTIVITY_RECOGNITION
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 100)
        }
    }

    private fun clearOldDataFiles() {
        try {
            val storageDir = File(getExternalFilesDir(null), "DrivingBehavior")
            if (storageDir.exists()) {
                storageDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".json")) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            // Silent cleanup
        }
    }

    private fun startRecording() {
        if (isRecording) return

        try {
            clearOldDataFiles()
            acquireWakeLock()

            // Record starting battery level
            startBatteryLevel = currentBatteryLevel

            isRecording = true
            dataCount = 0
            recordingStartTime = System.currentTimeMillis()
            lastDataWriteTime = recordingStartTime
            dataGapDetected = false
            sensorDisplayData.clear()
            lastSampleTimes.clear()

            createDataFile()
            registerOptimizedSensors()
            startOptimizedLocationTracking()
            startUIUpdateTimer()
            startDataIntegrityMonitor()

            btnStart.isEnabled = false
            btnStop.isEnabled = true
            btnSendData.isEnabled = false
            tvStatus.text = "Recording sensor data... (Battery: ${startBatteryLevel}%)"

            Toast.makeText(this, "Started recording with power optimization", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // If something fails, reset state
            isRecording = false
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            Toast.makeText(this, "Failed to start recording: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return

        isRecording = false

        // Record ending battery level
        endBatteryLevel = currentBatteryLevel
        val batteryUsed = if (startBatteryLevel != -1 && endBatteryLevel != -1) {
            startBatteryLevel - endBatteryLevel
        } else -1

        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        stopUIUpdateTimer()
        stopDataIntegrityMonitor()
        releaseWakeLock()

        closeDataFile()

        btnStart.isEnabled = true
        btnStop.isEnabled = false
        btnSendData.isEnabled = true

        dataFile?.let { file ->
            val fileSizeMB = String.format("%.2f", file.length() / (1024.0 * 1024.0))
            val fileSizeKB = file.length() / 1024
            val duration = (System.currentTimeMillis() - recordingStartTime) / 1000.0
            val gapWarning = if (dataGapDetected) "\n⚠️ Data gaps detected!" else ""
            val batteryInfo = if (batteryUsed > 0) "\nBattery used: ${batteryUsed}%" else ""

            tvStatus.text = "Recording completed!\nData points: ${dataCount}\nFile size: ${fileSizeMB}MB (${fileSizeKB}KB)\nDuration: ${String.format("%.1f", duration)}s${batteryInfo}${gapWarning}"

            val batteryMessage = if (batteryUsed > 0) ", Battery: -${batteryUsed}%" else ""
            Toast.makeText(this, "Data saved: ${fileSizeMB}MB, ${dataCount} points${batteryMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DrivingProfiler::RecordingWakeLock"
            )
            wakeLock?.acquire(60 * 60 * 1000L)
        } catch (e: Exception) {
            // Continue without wake lock if it fails
            Toast.makeText(this, "Wake lock not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun startUIUpdateTimer() {
        uiUpdateTimer = Timer()
        uiUpdateTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                if (isRecording) {
                    handler.post(updateDisplayRunnable)
                }
            }
        }, 0, 100)
    }

    private fun stopUIUpdateTimer() {
        uiUpdateTimer?.cancel()
        uiUpdateTimer = null
    }

    private val updateDisplayRunnable = Runnable {
        val displayText = sensorDisplayData.entries.joinToString("\n") { (sensor, value) ->
            "$sensor: $value"
        } + "\n\nData Points: $dataCount"

        if (currentBatteryLevel != -1) {
            val batteryDrop = if (startBatteryLevel != -1) {
                startBatteryLevel - currentBatteryLevel
            } else 0
            tvSensorData.text = "$displayText\nBattery: $currentBatteryLevel% (Used: ${batteryDrop}%)"
        } else {
            tvSensorData.text = displayText
        }
    }

    private fun startDataIntegrityMonitor() {
        handler.postDelayed(dataIntegrityChecker, 3000) // Start checking after 3 seconds
    }

    private fun stopDataIntegrityMonitor() {
        handler.removeCallbacks(dataIntegrityChecker)
    }

    private val dataIntegrityChecker = object : Runnable {
        override fun run() {
            if (isRecording) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastData = currentTime - lastDataWriteTime

                // If no data written for 2 seconds (reduced from 3), try to recover
                if (timeSinceLastData > 2000) {
                    logDataGap(timeSinceLastData)
                    dataGapDetected = true

                    // Try to recover by re-registering sensors
                    try {
                        sensorManager.unregisterListener(this@MainActivity)
                        Thread.sleep(100) // Brief pause
                        registerOptimizedSensors()

                        // Log recovery attempt
                        logRecoveryAttempt()
                    } catch (e: Exception) {
                        // Recovery failed, continue monitoring
                    }
                }

                // Schedule next check every 2 seconds (more frequent)
                handler.postDelayed(this, 2000)
            }
        }
    }

    private fun logDataGap(gapDuration: Long) {
        try {
            val gapData = JSONObject().apply {
                put("timestamp_ms", System.currentTimeMillis() - recordingStartTime)
                put("event_type", "data_gap_detected")
                put("gap_duration_ms", gapDuration)
                put("absolute_timestamp", System.currentTimeMillis())
                put("battery_level", currentBatteryLevel)
                put("data_points_so_far", dataCount)
            }
            fileWriter?.write(gapData.toString() + "\n")
            fileWriter?.flush()
        } catch (e: Exception) {
            // Continue recording
        }
    }

    private fun logRecoveryAttempt() {
        try {
            val recoveryData = JSONObject().apply {
                put("timestamp_ms", System.currentTimeMillis() - recordingStartTime)
                put("event_type", "sensor_recovery_attempt")
                put("absolute_timestamp", System.currentTimeMillis())
                put("battery_level", currentBatteryLevel)
            }
            fileWriter?.write(recoveryData.toString() + "\n")
            fileWriter?.flush()
        } catch (e: Exception) {
            // Continue recording
        }
    }

    private fun registerOptimizedSensors() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAcceleration?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gravity?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun startOptimizedLocationTracking() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0.5f, this)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1f, this)
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Continue without GPS if it fails
            Toast.makeText(this, "GPS not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createDataFile() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "driving_data_optimized_${timestamp}.json"

            val storageDir = File(getExternalFilesDir(null), "DrivingBehavior")
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }

            dataFile = File(storageDir, fileName)
            fileWriter = FileWriter(dataFile!!)

            val header = JSONObject().apply {
                put("recording_start_time", recordingStartTime)
                put("recording_start_datetime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(recordingStartTime)))
                put("version", "optimized_v2.0_battery_monitoring")
                put("sampling_strategy", "balanced_stability")
                put("device_info", getDeviceInfo())
                put("sensor_configuration", getSensorConfiguration())
                put("battery_start_level", startBatteryLevel)
            }
            fileWriter?.write(header.toString() + "\n")
            fileWriter?.flush()

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to create data file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getDeviceInfo(): JSONObject {
        return JSONObject().apply {
            put("device_model", android.os.Build.MODEL)
            put("device_brand", android.os.Build.BRAND)
            put("android_version", android.os.Build.VERSION.RELEASE)
            put("app_version", "2.0_battery_optimized")
            put("sdk_version", android.os.Build.VERSION.SDK_INT)
        }
    }

    private fun getSensorConfiguration(): JSONObject {
        return JSONObject().apply {
            put("core_sensors", JSONObject().apply {
                put("accelerometer", "~50Hz (GAME)")
                put("gyroscope", "~50Hz (GAME)")
                put("linear_acceleration", "~50Hz (GAME)")
                put("gravity", "~16Hz (UI)")
            })
            put("auxiliary_sensors", JSONObject().apply {
                put("magnetometer", "~5Hz (NORMAL)")
                put("gps_location", "2Hz")
            })
            put("total_estimated_frequency", "~137Hz")
            put("power_optimization", "balanced_stability")
            put("throttling_strategy", "lenient_burst_prevention")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRecording || event == null) return

        try {
            val sensorType = getSensorTypeName(event.sensor.type)
            val currentTime = System.currentTimeMillis()

            val interval = samplingIntervals[sensorType] ?: 20L
            val lastTime = lastSampleTimes[sensorType] ?: 0L

            if (currentTime - lastTime < interval) {
                return
            }

            lastSampleTimes[sensorType] = currentTime

            val sensorData = JSONObject().apply {
                put("timestamp_ms", currentTime - recordingStartTime)
                put("sensor_type", sensorType)
                put("values", JSONArray().apply {
                    event.values.forEach { put(it.toDouble()) }
                })
                put("accuracy", event.accuracy)
                put("absolute_timestamp", currentTime)
                put("battery_level", currentBatteryLevel)
            }

            fileWriter?.write(sensorData.toString() + "\n")
            fileWriter?.flush()

            dataCount++
            lastDataWriteTime = currentTime
            updateSensorDisplay(event)

        } catch (e: Exception) {
            // Continue recording despite errors
        }
    }

    private fun getSensorTypeName(sensorType: Int): String {
        return when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> "accelerometer"
            Sensor.TYPE_GYROSCOPE -> "gyroscope"
            Sensor.TYPE_MAGNETIC_FIELD -> "magnetometer"
            Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
            Sensor.TYPE_GRAVITY -> "gravity"
            else -> "unknown"
        }
    }

    private fun updateSensorDisplay(event: SensorEvent) {
        val sensorName = getSensorTypeName(event.sensor.type)

        val values = when {
            event.values.size >= 3 -> "X:%.2f Y:%.2f Z:%.2f".format(event.values[0], event.values[1], event.values[2])
            event.values.size == 1 -> "Value:%.2f".format(event.values[0])
            else -> event.values.joinToString(" ") { "%.2f".format(it) }
        }

        sensorDisplayData[sensorName] = values
    }

    override fun onLocationChanged(location: Location) {
        if (!isRecording) return

        val currentTime = System.currentTimeMillis()
        val lastGpsTime = lastSampleTimes["gps_location"] ?: 0L

        if (currentTime - lastGpsTime < 500L) {
            return
        }

        lastSampleTimes["gps_location"] = currentTime

        try {
            val locationData = JSONObject().apply {
                put("timestamp_ms", currentTime - recordingStartTime)
                put("sensor_type", "gps_location")
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("altitude_m", location.altitude)
                put("speed_ms", location.speed)
                put("bearing_degrees", location.bearing)
                put("accuracy_m", location.accuracy)
                put("absolute_timestamp", currentTime)
                put("battery_level", currentBatteryLevel)
            }

            fileWriter?.write(locationData.toString() + "\n")
            fileWriter?.flush()
            dataCount++
            lastDataWriteTime = currentTime

        } catch (e: Exception) {
            // Continue recording
        }
    }

    private fun closeDataFile() {
        try {
            fileWriter?.let { writer ->
                val batteryUsed = if (startBatteryLevel != -1 && endBatteryLevel != -1) {
                    startBatteryLevel - endBatteryLevel
                } else -1

                val footer = JSONObject().apply {
                    put("recording_end_time", System.currentTimeMillis())
                    put("recording_end_datetime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    put("total_data_points", dataCount)
                    put("recording_duration_ms", System.currentTimeMillis() - recordingStartTime)
                    put("recording_duration_seconds", (System.currentTimeMillis() - recordingStartTime) / 1000.0)
                    put("average_sampling_rate_hz", String.format("%.1f", dataCount / ((System.currentTimeMillis() - recordingStartTime) / 1000.0)))
                    put("battery_start_level", startBatteryLevel)
                    put("battery_end_level", endBatteryLevel)
                    put("battery_consumed_percent", batteryUsed)
                    put("optimization_summary", "Power and storage optimized with battery monitoring")
                }
                writer.write(footer.toString() + "\n")
                writer.close()
            }
            fileWriter = null
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to close file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendDataViaEmail() {
        dataFile?.let { file ->
            try {
                val fileUri: Uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )

                val fileSizeMB = String.format("%.2f", file.length() / (1024.0 * 1024.0))
                val duration = (System.currentTimeMillis() - recordingStartTime) / 1000.0
                val batteryUsed = if (startBatteryLevel != -1 && endBatteryLevel != -1) {
                    startBatteryLevel - endBatteryLevel
                } else -1

                val emailIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(targetEmail))
                    putExtra(Intent.EXTRA_SUBJECT, "Optimized Driving Data with Battery Monitoring - ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")
                    putExtra(Intent.EXTRA_TEXT,
                        """
                        Hi,
                        
                        Optimized driving behavior sensor data with battery monitoring attached.
                        
                        Recording Details:
                        - File Size: ${fileSizeMB} MB
                        - Data Points: $dataCount
                        - Duration: ${String.format("%.1f", duration)} seconds
                        - Average Rate: ${String.format("%.1f", dataCount / duration)} Hz
                        
                        Battery Information:
                        - Starting Battery: ${startBatteryLevel}%
                        - Ending Battery: ${endBatteryLevel}%
                        - Battery Consumed: ${if (batteryUsed > 0) "${batteryUsed}%" else "N/A"}
                        
                        Optimization Features:
                        - Core sensors: ~50Hz (Accelerometer, Gyroscope, Linear Acceleration)
                        - Gravity sensor: ~16Hz
                        - Magnetometer: ~5Hz  
                        - GPS: 2Hz
                        - Total rate: ~137Hz (balanced for stability)
                        - Power optimized with wake lock management ✓
                        - Battery monitoring throughout recording ✓
                        - Data gap detection ✓
                        
                        Data includes motion vectors, orientation, precise location tracking,
                        and battery consumption metrics optimized for driving behavior analysis.
                        
                        Driving Profiler App (v2.0 - Battery Optimized)
                        """.trimIndent())
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                startActivity(Intent.createChooser(emailIntent, "Send optimized driving data"))

                val batteryInfo = if (batteryUsed > 0) ", Battery used: ${batteryUsed}%" else ""
                Toast.makeText(this, "Sending optimized data (${fileSizeMB}MB${batteryInfo})", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                Toast.makeText(this, "Failed to send email: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        super.onDestroy()

        try {
            stopUIUpdateTimer()
            stopDataIntegrityMonitor()
            releaseWakeLock()
            handler.removeCallbacks(updateDisplayRunnable)

            batteryReceiver?.let {
                try {
                    unregisterReceiver(it)
                } catch (e: Exception) {
                    // Receiver might not be registered
                }
            }

            if (isRecording) {
                stopRecording()
            }
        } catch (e: Exception) {
            // Ensure app doesn't crash on destroy
        }
    }
}