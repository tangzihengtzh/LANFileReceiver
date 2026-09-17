package com.lanfile.transfer.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * 通用文件选择（SAF ACTION_OPEN_DOCUMENT），支持一次选择多个任意类型文件。
 *
 * 相比 ActivityResultContracts.OpenMultipleDocuments，这里显式请求可持久化的读权限，
 * 以便服务器在 Activity 重建后仍能读取这些文件。
 */
class OpenDocumentsContract : ActivityResultContract<Array<String>, List<Uri>>() {

    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clipData = intent.clipData
        if (clipData != null) {
            return (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
        }
        return listOfNotNull(intent.data)
    }
}
