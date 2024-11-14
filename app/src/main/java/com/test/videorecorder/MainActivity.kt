package com.test.videorecorder

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.test.videorecorder.databinding.ActivityMainBinding
import java.io.IOException
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var videoUri: Uri? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.getOrDefault(Manifest.permission.CAMERA, false) &&
                permissions.getOrDefault(Manifest.permission.RECORD_AUDIO, false)
            ) {
                openCamera()
            } else {
                openCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: android.graphics.SurfaceTexture,
                width: Int,
                height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean =
                true

            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }

        binding.ivStartStop.setOnClickListener {

            if (binding.ivStartStop.tag == "start") {
                if (!isRecording) {
                    startRecording()
                    binding.ivStartStop.tag = "stop"
                    binding.ivStartStop.setImageDrawable(
                        ContextCompat.getDrawable(
                            this@MainActivity,
                            R.drawable.baseline_stop_circle_24
                        )
                    )
                }
            } else {
                if (isRecording) {
                    stopRecording()
                    binding.ivStartStop.tag = "start"
                    binding.ivStartStop.setImageDrawable(
                        ContextCompat.getDrawable(
                            this@MainActivity,
                            R.drawable.ic_record
                        )
                    )
                }
            }
        }
    }

    private fun startPreview() {
        if (cameraDevice == null || !binding.textureView.isAvailable) return

        try {
            val surfaceTexture = binding.textureView.surfaceTexture ?: return
            surfaceTexture.setDefaultBufferSize(1920, 1080)
            val previewSurface = Surface(surfaceTexture)

            val outputConfig = OutputConfiguration(previewSurface)
            val outputConfigs = listOf(outputConfig)
            val executor = Executors.newSingleThreadExecutor()
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val previewRequestBuilder =
                                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    ?.apply {
                                        addTarget(previewSurface)
                                    }
                            captureSession?.setRepeatingRequest(
                                previewRequestBuilder?.build()!!,
                                null,
                                null
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("VideoRecorder", "Failed to configure capture session.")
                    }
                }
            )

            cameraDevice?.createCaptureSession(sessionConfig)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun getCameraId(): String {
        cameraManager.cameraIdList.forEach { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return cameraManager.cameraIdList[0]
    }

    private fun openCamera() {
        if (!allPermissionsGranted()) {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
            return
        }

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            cameraManager.openCamera(getCameraId(), object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    if (binding.textureView.isAvailable) {
                        startPreview()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun getMediaRecorderOrientationHint(): Int {
        val rotation = windowManager.defaultDisplay.rotation
        val cameraCharacteristics = cameraManager.getCameraCharacteristics(getCameraId())
        val sensorOrientation = cameraCharacteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0

        return when (rotation) {
            Surface.ROTATION_0 -> sensorOrientation
            Surface.ROTATION_90 -> (sensorOrientation + 270) % 360
            Surface.ROTATION_180 -> (sensorOrientation + 180) % 360
            Surface.ROTATION_270 -> (sensorOrientation + 90) % 360
            else -> sensorOrientation
        }
    }

    private fun setUpMediaRecorder() {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "video_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }

        videoUri =
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        mediaRecorder = MediaRecorder()
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            val pfd = videoUri?.let { contentResolver.openFileDescriptor(it, "rw") }
            setOutputFile(pfd?.fileDescriptor)
            if (isHevcSupported()) {
                setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            } else {
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            }
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(30)
            setVideoSize(1920, 1080)
            setOrientationHint(getMediaRecorderOrientationHint())

            try {
                prepare()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }


    private fun startRecording() {
        try {
            val surfaceTexture = binding.textureView.surfaceTexture ?: return
            surfaceTexture.setDefaultBufferSize(1920, 1080)
            val previewSurface = Surface(surfaceTexture)

            setUpMediaRecorder()
            val mediaRecorderSurface = mediaRecorder!!.surface

            val previewOutputConfig = OutputConfiguration(previewSurface)
            val recorderOutputConfig = OutputConfiguration(mediaRecorderSurface)
            val outputConfigs = listOf(previewOutputConfig, recorderOutputConfig)
            val executor = Executors.newSingleThreadExecutor()
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputConfigs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val captureRequestBuilder =
                                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    ?.apply {
                                        addTarget(previewSurface)
                                        addTarget(mediaRecorderSurface)
                                    }
                            captureSession?.setRepeatingRequest(
                                captureRequestBuilder?.build()!!,
                                null,
                                null
                            )
                            mediaRecorder?.start()
                            isRecording = true
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("VideoRecorder", "Failed to configure capture session for recording.")
                    }
                }
            )

            cameraDevice?.createCaptureSession(sessionConfig)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            Toast.makeText(this, "Error starting recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        if (isRecording) {
            try {
                mediaRecorder?.apply {
                    stop()
                    reset()
                    release()
                }
                mediaRecorder = null
                isRecording = false

//                openCamera()
                videoUri?.let {
                    val intent = Intent(this, VideoPlayerActivity::class.java).apply {
                        putExtra("videoUri", it)
                    }
                    startActivity(intent)
                    finish()
                }
                Toast.makeText(this, "Video saved to gallery!", Toast.LENGTH_SHORT)
                    .show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Error stopping recording.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Recording was not started.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isHevcSupported(): Boolean {
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codecInfo ->
            codecInfo.isEncoder && codecInfo.supportedTypes.contains("video/hevc")
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}