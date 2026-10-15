package com.example.batchsender

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class BatchAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // مرحله بعد: تشخیص Web App مقصد، پیدا کردن فیلد پیام،
        // الصاق تصویر و واردکردن پرامپت، سپس ارسال.
        // این اسکلت عمداً قبل از شناخت دقیق دو Web App شما ارسال را انجام نمی‌دهد.
    }

    override fun onInterrupt() = Unit

    fun findText(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        return root?.findAccessibilityNodeInfosByText(text)?.firstOrNull()
    }
}
