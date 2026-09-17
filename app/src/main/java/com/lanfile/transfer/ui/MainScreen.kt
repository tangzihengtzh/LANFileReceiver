package com.lanfile.transfer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanfile.transfer.media.SharedFileAccess
import com.lanfile.transfer.model.LanAddress
import com.lanfile.transfer.model.ServerState
import com.lanfile.transfer.model.ServerStatus
import com.lanfile.transfer.model.TransferItem
import com.lanfile.transfer.model.TransferStatus
import com.lanfile.transfer.network.NetworkUtils
import com.lanfile.transfer.repository.ShareRepository
import com.lanfile.transfer.repository.TransferRepository
import com.lanfile.transfer.server.ServerService
import com.lanfile.transfer.storage.FileNameUtils
import com.lanfile.transfer.storage.FileStorageManager
import com.lanfile.transfer.storage.ServerConfig
import com.lanfile.transfer.ui.icons.AppIcons
import com.lanfile.transfer.ui.theme.StatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    repository: TransferRepository,
    shareRepository: ShareRepository,
    config: ServerConfig
) {
    val context = LocalContext.current
    val serverState by repository.serverState.collectAsState()
    val transfers by repository.transfers.collectAsState()
    val connectedDevices by repository.connectedDevices.collectAsState()
    val sharedFiles by shareRepository.files.collectAsState()

    var showPortDialog by remember { mutableStateOf(false) }
    var storageReady by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // 系统照片选择器：无需任何存储权限
    // 部分机型 getPickImagesMaxLimit() 会返回 1 导致构造异常，这里做一次夹取
    val maxPhotoPick = remember {
        try {
            val limit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                MediaStore.getPickImagesMaxLimit()
            } else {
                MAX_PHOTO_PICK
            }
            limit.coerceIn(2, MAX_PHOTO_PICK)
        } catch (t: Throwable) {
            MAX_PHOTO_PICK
        }
    }
    fun addPickedUris(uris: List<Uri>, emptyHint: String) {
        if (uris.isEmpty()) return
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.forEach { SharedFileAccess.persistReadPermission(context, it) }
                SharedFileAccess.buildSharedFiles(context, uris)
            }
            val added = shareRepository.addAll(items)
            val message = when {
                added > 0 -> "已添加 $added 个文件"
                items.isEmpty() -> emptyHint
                else -> "这些文件已在列表中"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxPhotoPick)
    ) { uris -> addPickedUris(uris, "未识别到照片") }

    val pickFiles = rememberLauncherForActivityResult(
        OpenDocumentsContract()
    ) { uris -> addPickedUris(uris, "未识别到文件") }

    LaunchedEffect(serverState.status) {
        storageReady = withContext(Dispatchers.IO) { FileStorageManager(context).canWrite() }
    }

    // 热点可能在服务器启动后才开启，定期重新探测局域网地址
    LaunchedEffect(serverState.status) {
        if (serverState.status != ServerStatus.RUNNING) return@LaunchedEffect
        while (true) {
            delay(6000)
            val addresses = withContext(Dispatchers.IO) { NetworkUtils.detectLanAddresses(context) }
            repository.setAddresses(addresses)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("局域网文件接收", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            "手机作为接收端 · 电脑浏览器上传",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showPortDialog = true }) {
                        Icon(
                            imageVector = AppIcons.Settings,
                            contentDescription = "设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                state = serverState,
                connectedDevices = connectedDevices,
                storageReady = storageReady,
                onStart = {
                    storageReady = FileStorageManager(context).canWrite()
                    if (storageReady) {
                        ServerService.start(context, config.port)
                    } else {
                        Toast.makeText(context, "缺少存储权限，无法保存文件", Toast.LENGTH_LONG).show()
                    }
                },
                onStop = { ServerService.stop(context) }
            )

            if (serverState.status == ServerStatus.RUNNING) {
                AccessCard(state = serverState, onCopy = { copyAddress(context, it) })
            } else {
                GuideCard()
            }

            ShareSection(
                files = sharedFiles,
                serverRunning = serverState.status == ServerStatus.RUNNING,
                onPickPhotos = {
                    pickPhotos.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                onRemove = { shareRepository.remove(it) },
                onClear = { shareRepository.clear() }
            )

            StorageCard(port = config.port, onEditPort = { showPortDialog = true })

            TransfersCard(
                transfers = transfers,
                onClear = { repository.clearHistory() }
            )

            PrivacyNote()

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showPortDialog) {
        PortDialog(
            current = config.port,
            onDismiss = { showPortDialog = false },
            onConfirm = { port ->
                config.port = port
                showPortDialog = false
                Toast.makeText(context, "端口已保存，重启服务器后生效", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

// ---------------------------------------------------------------- 状态卡片

@Composable
private fun StatusCard(
    state: ServerState,
    connectedDevices: Int,
    storageReady: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val running = state.status == ServerStatus.RUNNING
    val starting = state.status == ServerStatus.STARTING
    val color = when (state.status) {
        ServerStatus.RUNNING -> StatusColors.Running
        ServerStatus.STARTING -> StatusColors.Starting
        ServerStatus.ERROR -> StatusColors.Error
        ServerStatus.STOPPED -> StatusColors.Stopped
    }
    val label = when (state.status) {
        ServerStatus.RUNNING -> "服务器运行中"
        ServerStatus.STARTING -> "正在启动…"
        ServerStatus.ERROR -> "服务器异常"
        ServerStatus.STOPPED -> "服务器未运行"
    }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = color, pulsing = running || starting)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (running) {
                    Text(
                        if (connectedDevices > 0) "已连接设备：$connectedDevices" else "等待电脑连接",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        state.errorMessage?.let { message ->
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = AppIcons.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = message,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        if (!storageReady) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = AppIcons.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "未获得存储写入权限，接收到的文件无法保存",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (running || starting) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("停止文件接收", fontSize = 15.sp)
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(AppIcons.Power, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("启动文件接收", fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val transition = rememberInfiniteTransition(label = "status")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.35f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (pulsing) alpha else 1f))
    )
}

// ---------------------------------------------------------------- 访问地址

@Composable
private fun AccessCard(state: ServerState, onCopy: (String) -> Unit) {
    val primary = state.recommendedAddress
    val others = state.addresses.drop(1)

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("电脑访问地址", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(10.dp))

        if (primary == null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "未检测到可用局域网地址\n请确认 Wi-Fi 或手机热点已开启",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            val url = state.urlFor(primary)
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = primary.label,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = url,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = AppIcons.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "访问 Token",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            text = state.token,
                            fontSize = 26.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "浏览器打开上方地址后，在输入框中填入这 4 位数字",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { onCopy(url) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(AppIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制地址", fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { onCopy(state.token) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(AppIcons.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("复制 Token", fontSize = 13.sp)
                }
            }
        }

        if (others.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "其他可用地址",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            others.forEach { address ->
                OtherAddressRow(state = state, address = address, onCopy = onCopy)
            }
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))

        StepRow(index = 1, text = "保持手机热点开启")
        StepRow(index = 2, text = "电脑连接该热点")
        StepRow(index = 3, text = "浏览器打开上方地址")
        StepRow(index = 4, text = "在网页输入框中填入 Token")
    }
}

@Composable
private fun OtherAddressRow(state: ServerState, address: LanAddress, onCopy: (String) -> Unit) {
    val url = state.urlFor(address)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = url,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = address.label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        IconButton(onClick = { onCopy(url) }) {
            Icon(
                imageVector = AppIcons.Copy,
                contentDescription = "复制",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GuideCard() {
    SectionCard {
        Text("使用步骤", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        StepRow(index = 1, text = "在系统设置中开启手机热点")
        StepRow(index = 2, text = "让电脑连接这个热点")
        StepRow(index = 3, text = "点击上方「启动文件接收」")
        StepRow(index = 4, text = "电脑浏览器打开手机上显示的地址")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "整个传输过程只在局域网内进行，不经过互联网。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepRow(index: Int, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index.toString(),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ---------------------------------------------------------------- 保存位置

@Composable
private fun StorageCard(port: Int, onEditPort: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("保存位置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = FileStorageManager.RELATIVE_DIR,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.Router,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("监听端口", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                text = port.toString(),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onEditPort, shape = RoundedCornerShape(8.dp)) {
                Text("修改", fontSize = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- 传输记录

@Composable
private fun TransfersCard(transfers: List<TransferItem>, onClear: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.FileDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("最近传输", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (transfers.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Text("清空", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        if (transfers.isEmpty()) {
            Text(
                text = "暂无传输记录",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            transfers.forEach { item ->
                TransferRow(item)
            }
        }
    }
}

@Composable
private fun TransferRow(item: TransferItem) {
    val (icon, tint) = when (item.status) {
        TransferStatus.UPLOADING -> AppIcons.Uploading to MaterialTheme.colorScheme.primary
        TransferStatus.COMPLETED -> AppIcons.CheckCircle to StatusColors.Running
        TransferStatus.FAILED -> AppIcons.ErrorOutline to MaterialTheme.colorScheme.error
        TransferStatus.CANCELLED -> AppIcons.Cancel to MaterialTheme.colorScheme.onSurfaceVariant
        TransferStatus.WAITING -> AppIcons.Hourglass to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = item.fileName,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailingText(item),
                fontSize = 12.sp,
                color = tint
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sizeText(item),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.status == TransferStatus.UPLOADING && item.fileSize > 0) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = FileNameUtils.formatSize(item.receivedBytes),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (item.status == TransferStatus.UPLOADING) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        item.errorMessage?.takeIf { item.status == TransferStatus.FAILED }?.let { message ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun trailingText(item: TransferItem): String = when (item.status) {
    TransferStatus.COMPLETED -> "✓ 完成"
    TransferStatus.FAILED -> "失败"
    TransferStatus.CANCELLED -> "已取消"
    TransferStatus.WAITING -> "等待"
    TransferStatus.UPLOADING ->
        if (item.fileSize > 0) "${(item.progress * 100).toInt()}%" else "接收中"
}

private fun sizeText(item: TransferItem): String = when {
    item.status == TransferStatus.UPLOADING && item.fileSize > 0 ->
        "${FileNameUtils.formatSize(item.receivedBytes)} / ${FileNameUtils.formatSize(item.fileSize)}"
    item.status == TransferStatus.COMPLETED -> FileNameUtils.formatSize(item.receivedBytes)
    item.receivedBytes > 0 -> FileNameUtils.formatSize(item.receivedBytes)
    item.fileSize > 0 -> FileNameUtils.formatSize(item.fileSize)
    else -> "未知大小"
}

// ---------------------------------------------------------------- 其他

@Composable
private fun PrivacyNote() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = AppIcons.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp).alpha(0.8f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "所有文件仅通过局域网传输并保存在本机，应用不会向任何互联网服务器发送数据。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PortDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(current.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("监听端口") },
        text = {
            Column {
                Text(
                    "默认 8080。修改后需要重启服务器才能生效。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it.filter { character -> character.isDigit() }.take(5)
                        error = null
                    },
                    singleLine = true,
                    label = { Text("端口号") },
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val port = text.toIntOrNull()
                when {
                    port == null -> error = "请输入数字"
                    !ServerConfig.isValidPort(port) ->
                        error = "端口范围 ${ServerConfig.MIN_PORT}-${ServerConfig.MAX_PORT}"
                    else -> onConfirm(port)
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun copyAddress(context: Context, url: String) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("局域网文件接收地址", url))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "已复制访问地址", Toast.LENGTH_SHORT).show()
        }
    } catch (t: Throwable) {
        Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
    }
}

private const val MAX_PHOTO_PICK = 100
