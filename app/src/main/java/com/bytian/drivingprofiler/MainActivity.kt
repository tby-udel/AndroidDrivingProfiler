package com.bytian.drivingprofiler

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
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

    // All motion-related sensors
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null
    private var linearAcceleration: Sensor? = null
    private var gravity: Sensor? = null
    private var rotationVector: Sensor? = null
    private var gameRotationVector: Sensor? = null
    private var significantMotion: Sensor? = null
    private var stepDetector: Sensor? = null

    // Recording State
    private var isRecording = false
    private var dataCount = 0
    private var recordingStartTime = 0L

    // Data Storage
    private var dataFile: File? = null
    private var fileWriter: FileWriter? = null
    private val sensorDataList = mutableListOf<JSONObject>()

    // Sensor Data Display
    private val sensorDisplayData = mutableMapOf<String, String>()

    // Email Configuration
    private val targetEmail = "boyangtian429@gmail.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initSensors()
        requestPermissions()
        displayAvailableSensors()
        clearOldDataFiles() // Clear old data on app start
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

        // Initial State
        btnStop.isEnabled = false
        btnSendData.isEnabled = false
        tvStatus.text = "Ready to start recording"
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Initialize all sensors
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gameRotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        significantMotion = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    }

    private fun displayAvailableSensors() {
        val availableSensors = mutableListOf<String>()

        if (accelerometer != null) availableSensors.add("✓ Accelerometer")
        if (gyroscope != null) availableSensors.add("✓ Gyroscope")
        if (magnetometer != null) availableSensors.add("✓ Magnetometer")
        if (linearAcceleration != null) availableSensors.add("✓ Linear Acceleration")
        if (gravity != null) availableSensors.add("✓ Gravity Sensor")
        if (rotationVector != null) availableSensors.add("✓ Rotation Vector")
        if (gameRotationVector != null) availableSensors.add("✓ Game Rotation Vector")
        if (significantMotion != null) availableSensors.add("✓ Significant Motion")
        if (stepDetector != null) availableSensors.add("✓ Step Detector")

        tvSensorList.text = "Available Sensors:\n${availableSensors.joinToString("\n")}"
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
            Toast.makeText(this, "Previous data files cleared", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to clear old files: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        if (isRecording) return

        // Clear old data files before starting new recording
        clearOldDataFiles()

        isRecording = true
        dataCount = 0
        recordingStartTime = System.currentTimeMillis()
        sensorDataList.clear()
        sensorDisplayData.clear()

        // Create data file
        createDataFile()

        // Register all available sensors
        registerAllSensors()

        // Start location tracking
        startLocationTracking()

        // Update UI
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        btnSendData.isEnabled = false
        tvStatus.text = "Recording driving data..."

        Toast.makeText(this, "Started recording multi-sensor data", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        if (!isRecording) return

        isRecording = false

        // Stop all sensor listeners
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)

        // Close file writing
        closeDataFile()

        // Update UI
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        btnSendData.isEnabled = true
        tvStatus.text = "Recording completed. ${dataCount} data points collected"

        // Show file info
        dataFile?.let { file ->
            val fileSizeKB = file.length() / 1024
            Toast.makeText(this, "Data saved. File size: ${fileSizeKB}KB", Toast.LENGTH_LONG).show()
        }
    }

    private fun registerAllSensors() {
        val sensors = listOf(
            accelerometer, gyroscope, magnetometer, linearAcceleration,
            gravity, rotationVector, gameRotationVector, stepDetector
        )

        sensors.forEach { sensor ->
            sensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }

        // Significant motion sensor needs special handling
        significantMotion?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun startLocationTracking() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, this)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 1f, this)
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createDataFile() {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "driving_data_${timestamp}.json"

            val storageDir = File(getExternalFilesDir(null), "DrivingBehavior")
            if (!storageDir.exists()) {
                storageDir.mkdirs()
            }

            dataFile = File(storageDir, fileName)
            fileWriter = FileWriter(dataFile!!)

            // Write file header
            val header = JSONObject().apply {
                put("recording_start_time", recordingStartTime)
                put("recording_start_datetime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(recordingStartTime)))
                put("device_info", getDeviceInfo())
                put("available_sensors", getAvailableSensorsList())
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
            put("app_version", "1.0")
            put("sdk_version", android.os.Build.VERSION.SDK_INT)
        }
    }

    private fun getAvailableSensorsList(): JSONArray {
        val sensorsArray = JSONArray()
        val sensorMap = mapOf(
            "accelerometer" to accelerometer,
            "gyroscope" to gyroscope,
            "magnetometer" to magnetometer,
            "linear_acceleration" to linearAcceleration,
            "gravity" to gravity,
            "rotation_vector" to rotationVector,
            "game_rotation_vector" to gameRotationVector,
            "significant_motion" to significantMotion,
            "step_detector" to stepDetector
        )

        sensorMap.forEach { (name, sensor) ->
            if (sensor != null) {
                sensorsArray.put(JSONObject().apply {
                    put("sensor_name", name)
                    put("vendor", sensor.vendor)
                    put("version", sensor.version)
                    put("power_mah", sensor.power)
                    put("resolution", sensor.resolution)
                    put("maximum_range", sensor.maximumRange)
                })
            }
        }

        return sensorsArray
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRecording || event == null) return

        try {
            val sensorData = JSONObject().apply {
                put("timestamp_ms", System.currentTimeMillis() - recordingStartTime)
                put("sensor_type", getSensorTypeName(event.sensor.type))
                put("values", JSONArray().apply {
                    event.values.forEach { put(it) }
                })
                put("accuracy", event.accuracy)
                put("absolute_timestamp", System.currentTimeMillis())
            }

            // Save to memory list
            sensorDataList.add(sensorData)

            // Write to file
            fileWriter?.write(sensorData.toString() + "\n")
            fileWriter?.flush()

            dataCount++

            // Update display data
            updateSensorDisplay(event)

        } catch (e: Exception) {
            // Log error but continue running
        }
    }

    private fun getSensorTypeName(sensorType: Int): String {
        return when (sensorType) {
            Sensor.TYPE_ACCELEROMETER -> "accelerometer"
            Sensor.TYPE_GYROSCOPE -> "gyroscope"
            Sensor.TYPE_MAGNETIC_FIELD -> "magnetometer"
            Sensor.TYPE_LINEAR_ACCELERATION -> "linear_acceleration"
            Sensor.TYPE_GRAVITY -> "gravity"
            Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rotation_vector"
            Sensor.TYPE_SIGNIFICANT_MOTION -> "significant_motion"
            Sensor.TYPE_STEP_DETECTOR -> "step_detector"
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

        // Update UI display
        val displayText = sensorDisplayData.entries.joinToString("\n") { (sensor, value) ->
            "$sensor: $value"
        } + "\n\nData Points: $dataCount"

        runOnUiThread {
            tvSensorData.text = displayText
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!isRecording) return

        try {
            val locationData = JSONObject().apply {
                put("timestamp_ms", System.currentTimeMillis() - recordingStartTime)
                put("sensor_type", "gps_location")
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("altitude_m", location.altitude)
                put("speed_ms", location.speed)
                put("bearing_degrees", location.bearing)
                put("accuracy_m", location.accuracy)
                put("absolute_timestamp", System.currentTimeMillis())
            }

            sensorDataList.add(locationData)
            fileWriter?.write(locationData.toString() + "\n")
            fileWriter?.flush()
            dataCount++

        } catch (e: Exception) {
            // Log error but continue running
        }
    }

    private fun closeDataFile() {
        try {
            fileWriter?.let { writer ->
                // Write file footer
                val footer = JSONObject().apply {
                    put("recording_end_time", System.currentTimeMillis())
                    put("recording_end_datetime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    put("total_data_points", dataCount)
                    put("recording_duration_ms", System.currentTimeMillis() - recordingStartTime)
                    put("recording_duration_seconds", (System.currentTimeMillis() - recordingStartTime) / 1000.0)
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
                // Create file URI using FileProvider
                val fileUri: Uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )

                // Get file stats
                val fileSizeKB = file.length() / 1024
                val fileSizeMB = String.format("%.2f", file.length() / (1024.0 * 1024.0))

                // Create email intent
                val emailIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(targetEmail))
                    putExtra(Intent.EXTRA_SUBJECT, "Driving Behavior Data - ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")
                    putExtra(Intent.EXTRA_TEXT,
                        """
                        Hi,
                        
                        Please find attached the driving behavior sensor data.
                        
                        Recording Details:
                        - File Name: ${file.name}
                        - File Size: ${fileSizeMB} MB (${fileSizeKB} KB)
                        - Data Points: $dataCount
                        - Recording Duration: ${String.format("%.1f", (System.currentTimeMillis() - recordingStartTime) / 1000.0)} seconds
                        
                        Data includes:
                        ${getDataSummary()}
                        
                        Best regards,
                        Driving Profiler App
                        """.trimIndent())
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                // Start email activity
                startActivity(Intent.createChooser(emailIntent, "Send driving data via email"))

                Toast.makeText(this, "Opening email app to send data (${fileSizeMB}MB)", Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                Toast.makeText(this, "Failed to send email: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } ?: run {
            Toast.makeText(this, "No data file to send", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getDataSummary(): String {
        val availableSensors = mutableListOf<String>()
        if (accelerometer != null) availableSensors.add("- Accelerometer")
        if (gyroscope != null) availableSensors.add("- Gyroscope")
        if (magnetometer != null) availableSensors.add("- Magnetometer")
        if (linearAcceleration != null) availableSensors.add("- Linear Acceleration")
        if (gravity != null) availableSensors.add("- Gravity Sensor")
        if (rotationVector != null) availableSensors.add("- Rotation Vector")
        if (gameRotationVector != null) availableSensors.add("- Game Rotation Vector")
        availableSensors.add("- GPS Location")

        return availableSensors.joinToString("\n")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Can log sensor accuracy changes
    }

    // LocationListener required methods
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopRecording()
        }
    }
}