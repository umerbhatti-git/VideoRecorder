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

                runOnUiThread {
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

    /*private fun compressVideoWithFFmpeg(uri: Uri) {
        val outputDir = File(applicationContext.cacheDir, "compressed_segments")
        if (!outputDir.exists()) outputDir.mkdirs()

        val (inputFilePath, videoDuration) = getPathAndDurationFromUri(uri)

        val segmentDuration = 4 * 1000L // 4 seconds in milliseconds
        val numSegments = (videoDuration!! / segmentDuration + if (videoDuration % segmentDuration > 0) 1 else 0).toInt()

        val segmentFiles = mutableListOf<File>()
        val compressedSegmentFiles = mutableListOf<File>()

        val progressDialog = ProgressDialog(this).apply {
            setTitle("Compressing Video")
            setMessage("Please wait...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            isIndeterminate = false
            max = 100
            show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // Step 1: Split the video into segments
            for (i in 0 until numSegments) {
                val startTime = (i * segmentDuration) / 1000.0
                val segmentFile = File(outputDir, "segment_$i.mp4")
                val splitCommand = arrayOf(
                    "-i", inputFilePath,
                    "-ss", startTime.toString(),
                    "-t", (segmentDuration / 1000.0).toString(),
                    "-c", "copy",
                    segmentFile.absolutePath
                )
                val splitSession = FFmpegKit.execute(splitCommand.joinToString(" "))
                if (splitSession.returnCode.isValueSuccess) {
                    segmentFiles.add(segmentFile)
                } else {
                    Log.e("FFmpeg", "Segment creation failed for segment $i")
                    withContext(Dispatchers.Main) { progressDialog.dismiss() }
                    return@launch
                }
            }

            // Step 2: Compress each segment concurrently
            val deferredResults = mutableListOf<Deferred<Unit>>()
            val completedSegments = AtomicInteger(0)

            segmentFiles.forEachIndexed { index, segmentFile ->
                val deferred = async<Unit> {
                    val compressedSegment = File(outputDir, "compressed_$index.mp4")
                    val compressCommand = arrayOf(
                        "-i", segmentFile.absolutePath,
                        "-c:v", "libx265",
                        "-preset", "ultrafast",
                        "-b:v", "5000k",
                        "-maxrate", "5000k",
                        "-bufsize", "10000k",
                        "-c:a", "copy",
                        "-g", "30",
                        "-vf", "scale=1080:-2,fps=30",
                        "-threads", "0",
                        compressedSegment.absolutePath
                    )
                    val compressSession = FFmpegKit.execute(compressCommand.joinToString(" "))
                    if (compressSession.returnCode.isValueSuccess) {
                        compressedSegmentFiles.add(compressedSegment)
                        val progress = (completedSegments.incrementAndGet() * 100) / segmentFiles.size
                        withContext(Dispatchers.Main) { progressDialog.progress = progress }
                    } else {
                        Log.e("FFmpeg", "Compression failed for segment $index")
                    }
                }
                deferredResults.add(deferred)
            }

            deferredResults.awaitAll()

            // Step 3: Concatenate compressed segments
            if (compressedSegmentFiles.size == segmentFiles.size) {
                val concatFile = File(outputDir, "concat_list.txt").apply {
                    writeText(compressedSegmentFiles.reversed().joinToString("\n") { "file '${it.absolutePath}'" })
                }
                val finalOutputFile = File(applicationContext.cacheDir, "compressed_${System.currentTimeMillis()}.mp4")
                val concatCommand = arrayOf(
                    "-f", "concat",
                    "-safe", "0",
                    "-i", concatFile.absolutePath,
                    "-c", "copy",
                    finalOutputFile.absolutePath
                )
                val concatSession = FFmpegKit.execute(concatCommand.joinToString(" "))
                if (concatSession.returnCode.isValueSuccess) {
                    Log.d("FFmpeg", "Final compression succeeded")
                    saveCompressedVideoToGallery(finalOutputFile)
                } else {
                    Log.e("FFmpeg", "Concatenation failed: ${concatSession.allLogsAsString}")
                }

                // Clean up temporary files
                concatFile.delete()
                segmentFiles.forEach { it.delete() }
                compressedSegmentFiles.forEach { it.delete() }
            } else {
                Log.e("FFmpeg", "Not all segments were successfully compressed")
            }

            withContext(Dispatchers.Main) { progressDialog.dismiss() }
        }
    }*/

    private fun compressVideoWithFFmpeg(uri: Uri) {
        val outputFile =
            File(applicationContext.cacheDir, "compressed_${System.currentTimeMillis()}.mp4")

        val (inputFilePath, videoDuration) = getPathAndDurationFromUri(uri)

        val ffmpegCommand = arrayOf(
            "-i", inputFilePath,                  // Input file path (the video to be processed)
            "-c:v", "libx265",                    // Set video codec to H.265 (HEVC) for better compression
            "-preset", "ultrafast",               // Use the 'ultrafast' preset for the fastest encoding (with trade-off in compression efficiency)
            "-b:v", "5000k",                      // Set video bitrate to 5000 kbps (controls the video quality and file size)
            "-maxrate", "5000k",                  // Set maximum video bitrate to 5000 kbps (helps prevent bitrate spikes)
            "-bufsize", "10000k",                 // Set buffer size to 10000 kbps (helps in controlling bitrate fluctuations)
            "-c:a", "copy",                       // Copy audio stream without re-encoding (maintains the original audio quality)
            "-g", "30",                           // Set the GOP (Group of Pictures) size to 30 (keyframe interval)
            "-vf", "scale=1080:-2,fps=30",        // Apply video filter to scale the video to 1080p resolution and set the frame rate to 30 FPS
            "-threads", "0",                      // Use all available CPU threads for faster encoding
            outputFile.path                       // Output file path where the compressed video will be saved
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

        CoroutineScope(Dispatchers.IO).launch {
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
                Toast.makeText(
                    this@VideoPlayerActivity,
                    "Compressed video saved to gallery",
                    Toast.LENGTH_LONG
                )
                    .show()
            }
            Log.d("FFmpeg", "Video saved to gallery: $uri")
        } ?: Log.e("FFmpeg", "Failed to save video to gallery")
    }

    private fun getPathAndDurationFromUri(uri: Uri): Pair<String?, Long?> {
        var videoDuration: Long? = null
        var videoPath: String? = null

        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
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
                val durationStr =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
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