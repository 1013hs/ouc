package com.example.rockattitude

import android.content.Context
import java.io.File

object DocStorage {
    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "offline_docs")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(ctx: Context): List<File> {
        return dir(ctx).listFiles()?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }

    fun save(ctx: Context, name: String, bytes: ByteArray): File {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val f = File(dir(ctx), safeName)
        f.writeBytes(bytes)
        return f
    }

    fun delete(file: File) {
        if (file.exists()) file.delete()
    }
}
