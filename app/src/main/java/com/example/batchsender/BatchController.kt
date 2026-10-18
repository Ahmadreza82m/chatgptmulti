package com.example.batchsender

/**
 * Compatibility facade for the original project. Runtime state now lives in AppStore
 * and execution is handled by BatchService so work can continue outside the Activity.
 */
object BatchController {
    enum class JobStatus { PENDING, UPLOADING, PROCESSING, COMPLETED, FAILED, RETRYING, CANCELLED }
}
