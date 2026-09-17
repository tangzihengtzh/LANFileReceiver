package com.lanfile.transfer.ui

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanfile.transfer.media.SharedFileAccess
import com.lanfile.transfer.model.SharedFile
import com.lanfile.transfer.storage.FileNameUtils
import com.lanfile.transfer.ui.icons.AppIcons
import com.lanfile.transfer.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 手机 → 电脑：选择照片或任意文件并共享给电脑浏览器下载。
 */
@Composable
internal fun ShareSection(
    files: List<SharedFile>,
    serverRunning: Boolean,
    onPickPhotos: () -> Unit,
    onPickFiles: () -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.Send,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("发送文件到电脑", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (files.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text("清空", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = if (files.isEmpty()) {
                "选择手机里的照片或任意文件，电脑打开网页即可下载。"
            } else {
                val total = files.sumOf { if (it.size > 0) it.size else 0L }
                val sent = files.count { it.downloaded }
                "已选 ${files.size} 个 · ${FileNameUtils.formatSize(total)} · 已发送 $sent 个"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (files.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(end = 4.dp)
            ) {
                items(items = files, key = { it.id }) { file ->
                    SharedFileCard(file = file, onRemove = { onRemove(file.id) })
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onPickPhotos,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(AppIcons.Photo, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("选择照片", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onPickFiles,
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(AppIcons.File, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("选择文件", fontSize = 13.sp)
            }
        }

        if (files.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (serverRunning) AppIcons.Info else AppIcons.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (serverRunning) {
                        "在电脑网页的「手机上的文件」区域点击下载"
                    } else {
                        "请先启动文件接收，电脑才能看到这些文件"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SharedFileCard(file: SharedFile, onRemove: () -> Unit) {
    Column(modifier = Modifier.width(96.dp)) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (file.isImage) {
                ImageThumbnail(file = file, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = AppIcons.File,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = extensionLabel(file.displayName),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (file.downloaded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(StatusColors.Running),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = AppIcons.CheckCircle,
                        contentDescription = "已发送",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRemove
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Close,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = file.displayName,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = FileNameUtils.formatSize(file.size),
            fontSize = 10.sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ImageThumbnail(file: SharedFile, modifier: Modifier) {
    val context = LocalContext.current
    var bitmap by remember(file.id) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(file.id) {
        val loaded = withContext(Dispatchers.IO) {
            try {
                SharedFileAccess.loadThumbnailBitmap(context, Uri.parse(file.uri), 256)
                    ?.asImageBitmap()
            } catch (t: Throwable) {
                null
            }
        }
        bitmap = loaded
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = file.displayName,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = AppIcons.Photo,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

private fun extensionLabel(name: String): String {
    val index = name.lastIndexOf('.')
    if (index <= 0 || index >= name.length - 1) return "文件"
    val extension = name.substring(index + 1).uppercase(Locale.ROOT)
    return if (extension.length > 5) extension.take(5) else extension
}
