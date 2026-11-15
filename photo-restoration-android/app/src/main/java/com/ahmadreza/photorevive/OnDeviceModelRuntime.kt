package com.ahmadreza.photorevive

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * Owns the lifecycle of an optional on-device ONNX model.
 *
 * The app deliberately does not bundle a model until its licence, tensor contract,
 * memory use and output quality have been checked for the target Galaxy A24.
 */
object OnDeviceModelRuntime {
    private const val MODEL_DIRECTORY = "models"

    @Volatile
    private var environment: OrtEnvironment? = null

    fun modelFile(context: Context, fileName: String): File {
        require(fileName.endsWith(".onnx", ignoreCase = true)) {
            "Only ONNX model files are supported."
        }
        return File(File(context.filesDir, MODEL_DIRECTORY), fileName)
    }

    fun isModelInstalled(context: Context, fileName: String): Boolean {
        val file = modelFile(context, fileName)
        return file.isFile && file.length() > 0L
    }

    /**
     * Opens a model stored in app-private storage.
     * The caller owns the returned session and must close it after inference.
     */
    fun openSession(context: Context, fileName: String): OrtSession {
        val file = modelFile(context, fileName)
        require(file.isFile && file.length() > 0L) {
            "AI_MODEL_NOT_INSTALLED: $fileName"
        }

        val ortEnvironment = environment ?: synchronized(this) {
            environment ?: OrtEnvironment.getEnvironment().also { environment = it }
        }

        val options = OrtSession.SessionOptions()
        return try {
            ortEnvironment.createSession(file.absolutePath, options)
        } finally {
            options.close()
        }
    }
}
