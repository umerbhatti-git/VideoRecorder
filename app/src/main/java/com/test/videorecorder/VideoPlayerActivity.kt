@file:Suppress("DEPRECATION")

package com.test.videorecorder

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ContentValues
import android.content.Intent
import android.icu.math.BigDecimal
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.test.videorecorder.databinding.ActivityVideoPlayerBinding
import fr.bmartel.speedtest.SpeedTestReport
import fr.bmartel.speedtest.SpeedTestSocket
import fr.bmartel.speedtest.inter.ISpeedTestListener
import fr.bmartel.speedtest.model.SpeedTestError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class VideoPlayerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoPlayerBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startActivity(Intent(this@VideoPlayerActivity, MainActivity::class.java))
                finish()
            }
        })

        val videoUri: Uri? = intent.getParcelableExtra("videoUri")

        videoUri?.let {
            binding.videoView.apply {
                setVideoURI(it)
                setMediaController(MediaController(this@VideoPlayerActivity))
                requestFocus()
                start()
            }
        }

        binding.ivBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val speedTestSocket = SpeedTestSocket()
        speedTestSocket.addSpeedTestListener(object : ISpeedTestListener {
            override fun onCompletion(report: SpeedTestReport) {
                val transferRateBit = BigDecimal(report.transferRateBit)
                val uploadSpeedMbps = transferRateBit.divide(BigDecimal("1000000"))
                runOnUiThread {
                    println("[COMPLETED] Upload speed: $uploadSpeedMbps Mbps")
                }

                runOnUiThread{
                    val builder = AlertDialog.Builder(this@VideoPlayerActivity)
                    builder.setTitle("Confirmation")
                    builder.setMessage("Internet upload speed is $uploadSpeedMbps Mbps, Do you want to compress the video?")

                    builder.setPositiveButton("Yes") { dialog, _ ->
                        compressVideoWithFFmpeg(videoUri!!)
                        dialog.dismiss()
                    }

                    builder.setNegativeButton("No") { dialog, _ ->
                        dialog.dismiss()
                    }

                    val dialog = builder.create()
                    dialog.show()
                }

                /*if (uploadSpeedMbps.toDouble() < 2) {

                }*/
            }

            override fun onError(speedTestError: SpeedTestError, errorMessage: String) {
                println("[ERROR] $errorMessage")
            }

            override fun onProgress(percent: Float, report: SpeedTestReport) {

            }
        })

        CoroutineScope(Dispatchers.IO).launch {
            speedTestSocket.startUpload(
                "http://speedtest.tele2.net/upload.php",
                1000000,
                10000
            )
        }
    }

    private fun compressVideoWithFFmpeg(uri: Uri) {
        val outputFile =
            File(applicationContext.cacheDir, "compressed_${System.currentTimeMillis()}.mp4")

        val (inputFilePath, videoDuration) = getPathAndDurationFromUri(uri)

        val ffmpegCommand = arrayOf(
            "-i", inputFilePath,
            "-c:v", "libx265",
            "-preset", "medium",
            "-b:v", "5000k",
            "-maxrate", "5000k",
            "-bufsize", "10000k",
            "-c:a", "aac",
            "-b:a", "128k",
            "-vf", "scale=1080:-2,fps=30",
            outputFile.path
        )

        Log.d("FFmpeg", "Running command: ${ffmpegCommand.joinToString(" ")}")

        val progressDialog = ProgressDialog(this).apply {
            setTitle("Compressing Video")
            setMessage("Please wait...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            isIndeterminate = false
            max = 100
            show()
        }

        FFmpegKitConfig.enableStatisticsCallback { statistics ->
            val timeInMillis = statistics.time
            if (videoDuration != null && videoDuration > 0) {
                val progress = (timeInMillis / videoDuration * 100).toInt()
                progressDialog.progress = progress
            }
        }

        FFmpegKit.executeAsync(ffmpegCommand.joinToString(" ")) { session ->
            val returnCode = session.returnCode
            if (returnCode.isValueSuccess) {
                Log.d("FFmpeg", "Compression succeeded")
                runOnUiThread {
                    progressDialog.hide()
                }
                saveCompressedVideoToGallery(outputFile)
            } else {
                Log.e("FFmpeg", "Compression failed: ${session.allLogsAsString}")
            }
        }
    }

    private fun saveCompressedVideoToGallery(file: File) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/")
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                outputStream.write(file.readBytes())
            }
            runOnUiThread {
                Toast.makeText(this@VideoPlayerActivity, "Compressed video saved to gallery", Toast.LENGTH_LONG)
                    .show()
            }
            Log.d("FFmpeg", "Video saved to gallery: $uri")
        } ?: Log.e("FFmpeg", "Failed to save video to gallery")
    }

    private fun getPathAndDurationFromUri(uri: Uri): Pair<String?, Long?> {
        var videoDuration: Long? = null
        var videoPath: String? = null

        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

            if (cursor.moveToFirst() && nameIndex != -1) {
                val displayName = cursor.getString(nameIndex)
                val file = File(applicationContext.cacheDir, displayName)
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    file.outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                videoPath = file.path
            }
        }

        if (videoPath != null) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoPath)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                videoDuration = durationStr?.toLongOrNull()
            } catch (e: Exception) {
                Log.e("FFmpeg", "Error retrieving video duration", e)
            } finally {
                retriever.release()
            }
        }

        if (videoPath == null) {
            Log.e("FFmpeg", "Failed to retrieve path from URI")
        }
        if (videoDuration == null) {
            Log.e("FFmpeg", "Failed to retrieve duration from URI")
        }

        return Pair(videoPath, videoDuration)
    }
}