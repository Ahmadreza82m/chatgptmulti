package com.lumo.studio.ops

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** قابلیت ۳۰: اشتراک‌گذاری مستقیم؛ فهرست برنامه‌های گوشی (تلگرام و…) نمایش داده می‌شود. */
object Sharer {
    fun share(ctx: Context, uris: List<Uri>, mime: String): Boolean {
        if (uris.isEmpty()) return false
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = ClipData.newRawUri("files", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        val chooser = Intent.createChooser(intent, "اشتراک‌گذاری").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(chooser) }.isSuccess
    }
}
