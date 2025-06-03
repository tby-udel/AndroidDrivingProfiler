package com.bytian.drivingprofiler

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), SensorEventListener {

    // UI组件
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvSensorData: TextView

    // 传感器管理
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // 记录状态
    private var isRecording = false
    private var dataCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化UI
        initViews()

        // 初始化传感器
        initSensors()

        // 请求权限
        requestPermissions()
    }

    private fun initViews() {
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        tvStatus = findViewById(R.id.tv_status)
        tvSensorData = findViewById(R.id.tv_sensor_data)

        // 设置按钮点击事件
        btnStart.setOnClickListener {
            startRecording()
        }

        btnStop.setOnClickListener {
            stopRecording()
        }

        // 初始状态
        btnStop.isEnabled = false
        tvStatus.text = "准备开始记录"
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 100)
        }
    }

    private fun startRecording() {
        if (isRecording) return

        isRecording = true
        dataCount = 0

        // 注册传感器监听器
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        // 更新UI
        btnStart.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = "正在记录数据..."

        Toast.makeText(this, "开始记录传感器数据", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        if (!isRecording) return

        isRecording = false

        // 停止传感器监听
        sensorManager.unregisterListener(this)

        // 更新UI
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        tvStatus.text = "记录完成，共记录 $dataCount 个数据点"

        Toast.makeText(this, "停止记录，数据点：$dataCount", Toast.LENGTH_SHORT).show()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRecording || event == null) return

        dataCount++

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val displayText = "加速度计:\nX: %.2f\nY: %.2f\nZ: %.2f\n\n数据点: %d".format(x, y, z, dataCount)
                tvSensorData.text = displayText
            }

            Sensor.TYPE_GYROSCOPE -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val currentText = tvSensorData.text.toString()
                val newText = currentText + "\n陀螺仪:\nX: %.2f\nY: %.2f\nZ: %.2f".format(x, y, z)
                tvSensorData.text = newText
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 暂时不处理精度变化
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopRecording()
        }
    }
}