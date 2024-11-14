package com.test.videorecorder

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class VideoContentProvider : ContentProvider() {

    private val tempDir: File by lazy {
        context?.cacheDir ?: File(context?.filesDir, "tempVideos")
    }

    override fun onCreate(): Boolean {
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return true
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val videoFile = File(tempDir, "video_${System.currentTimeMillis()}.mp4")
        try {
            FileOutputStream(videoFile)
            ParcelFileDescriptor.open(videoFile, ParcelFileDescriptor.MODE_READ_WRITE)
            return Uri.fromFile(videoFile)
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        return null
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        return 0
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val file = File(tempDir, uri.lastPathSegment ?: "")
        return if (file.exists() && file.delete()) 1 else 0
    }

    override fun getType(uri: Uri): String {
        return "video/mp4"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = File(tempDir, uri.lastPathSegment ?: "")
        return if (file.exists()) ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) else null
    }
}
