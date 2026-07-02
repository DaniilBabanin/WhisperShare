package io.whispershare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast

object ShareUtils {

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("transcription", text))
        // Android 13+ shows its own clipboard toast — don't double up.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(context, context.getString(R.string.copied_toast), Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser_title)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
