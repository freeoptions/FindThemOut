package com.findthemout.app.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.findthemout.app.data.AppDatabase
import com.findthemout.app.data.AppExportBundle
import com.findthemout.app.data.AppExportManager
import com.findthemout.app.data.ImageFingerprintCacheDao
import com.findthemout.app.data.IgnoredImageDao
import com.findthemout.app.data.IgnoredImageEntry
import com.findthemout.app.data.PendingDeletionDao
import com.findthemout.app.data.PendingDeletionEntry
import com.findthemout.app.data.PinnedFolder
import com.findthemout.app.data.PinnedFolderDao
import com.findthemout.app.scan.ScanOutput
import com.findthemout.app.scan.ScanProgressSnapshot
import com.findthemout.app.scan.SimilarImageGroup
import com.findthemout.app.scan.SimilarImageItem
import com.findthemout.app.scan.SimilarImageScanner
import com.findthemout.app.ui.theme.FindThemOutTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val chineseConfiguration = Configuration(newBase.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
            setLayoutDirection(Locale.SIMPLIFIED_CHINESE)
        }
        super.attachBaseContext(newBase.createConfigurationContext(chineseConfiguration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FindThemOutTheme {
                FindThemOutApp()
            }
        }
    }
}

@Composable
private fun FindThemOutApp() {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getInstance(context) }
    val pendingDeletionDao = remember(database) { database.pendingDeletionDao() }
    val pendingEntries by pendingDeletionDao.observeAll().collectAsState(initial = emptyList())
    val appScope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var scanOutput by remember { mutableStateOf<ScanOutput?>(null) }
    var scanProgress by remember { mutableStateOf<ScanProgressSnapshot?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    fun startScan(folderPaths: List<String>) {
        if (folderPaths.isEmpty() || isScanning) return
        scanJob?.cancel()
        scanJob = appScope.launch {
            isScanning = true
            try {
                val ignoredPaths = database.ignoredImageDao().getAllPaths().toHashSet()
                val output = SimilarImageScanner.scanSelectedFolders(
                    folderPaths = folderPaths,
                    ignoredPaths = ignoredPaths,
                    cacheDao = database.imageFingerprintCacheDao(),
                    pairCacheDao = database.imagePairCacheDao(),
                    onProgress = { progress -> scanProgress = progress },
                )
                scanOutput = output
                selectedTab = 1
            } catch (exception: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "扫描失败：${exception.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isScanning = false
                scanJob = null
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.Folder, null) }, label = { Text("文件夹") })
                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Image, null) }, label = { Text("结果") })
                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Inventory, null) }, label = { Text("暂存区") })
            }
        },
    ) { padding ->
        when (selectedTab) {
            0 -> FolderManagementScreen(
                padding = padding,
                database = database,
                isScanning = isScanning,
                scanProgress = scanProgress,
                pendingCount = pendingEntries.size,
                onStartScan = { folderPaths ->
                    if (folderPaths.isEmpty()) {
                        Toast.makeText(context, "请先勾选至少一个参与扫描的文件夹。", Toast.LENGTH_SHORT).show()
                    } else {
                        startScan(folderPaths)
                    }
                },
            )

            1 -> ScanResultScreen(
                padding = padding,
                ignoredImageDao = database.ignoredImageDao(),
                pendingDeletionDao = pendingDeletionDao,
                scanOutput = scanOutput,
                isScanning = isScanning,
                scanProgress = scanProgress,
                pendingEntries = pendingEntries,
                onScanOutputChanged = { scanOutput = it },
            )

            else -> PendingDeletionScreen(
                padding = padding,
                pendingDeletionDao = pendingDeletionDao,
                pendingEntries = pendingEntries,
                scanOutput = scanOutput,
                onScanOutputChanged = { scanOutput = it },
            )
        }
    }
}

@Composable
private fun FolderManagementScreen(
    padding: PaddingValues,
    database: AppDatabase,
    isScanning: Boolean,
    scanProgress: ScanProgressSnapshot?,
    pendingCount: Int,
    onStartScan: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val dao = remember(database) { database.pinnedFolderDao() }
    val cacheDao = remember(database) { database.imageFingerprintCacheDao() }
    val pairCacheDao = remember(database) { database.imagePairCacheDao() }
    val ignoredImageDao = remember(database) { database.ignoredImageDao() }
    val pendingDeletionDao = remember(database) { database.pendingDeletionDao() }
    val scope = rememberCoroutineScope()
    val folders by dao.observeFolders().collectAsState(initial = emptyList())
    var showBrowser by rememberSaveable { mutableStateOf(false) }
    var showExportFolderBrowser by rememberSaveable { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<PinnedFolder?>(null) }
    var cacheStats by remember { mutableStateOf<ScanCacheStats?>(null) }
    var ignoredImageCount by remember { mutableIntStateOf(0) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var showResetIgnoredConfirm by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf<AppExportBundle?>(null) }
    var exportFolderPath by remember { mutableStateOf(AppExportManager.getExportFolderPath(context)) }

    val enabledFolders = folders.filter { it.enabled }
    val hasManageStorageAccess = rememberManageStorageAccess()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val tempFile = copyUriToTempFile(context, uri)
                    showImportConfirm = AppExportManager.importFromFile(tempFile)
                } catch (exception: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "导入失败：${exception.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    LaunchedEffect(isScanning) {
        if (!isScanning) {
            cacheStats = loadScanCacheStats(context, cacheDao)
            ignoredImageCount = ignoredImageDao.getCount()
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            FolderSummaryCard(
                totalCount = folders.size,
                enabledCount = enabledFolders.size,
                pendingCount = pendingCount,
                hasManageStorageAccess = hasManageStorageAccess,
                scanProgress = scanProgress,
                isScanning = isScanning,
                cacheStats = cacheStats,
                onClearCache = { showClearCacheConfirm = true },
                onOpenGuide = { showGuideDialog = true },
                onOpenSettings = { showSettingsDialog = true },
            )

            Spacer(modifier = Modifier.height(18.dp))
            Text("固定文件夹", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(folders, key = { _, item -> item.path }) { index, folder ->
                    FolderRow(
                        folder = folder,
                        canMoveUp = index > 0,
                        canMoveDown = index < folders.lastIndex,
                        enabled = !isScanning,
                        onToggleEnabled = { enabled ->
                            scope.launch { dao.setFolderEnabled(folder.path, enabled) }
                        },
                        onMoveUp = { scope.launch { reorderFolders(dao, folders, index, index - 1) } },
                        onMoveDown = { scope.launch { reorderFolders(dao, folders, index, index + 1) } },
                        onDelete = { folderToDelete = folder },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { dao.setAllEnabled(enabledFolders.size != folders.size) } },
                    modifier = Modifier.weight(1f),
                    enabled = folders.isNotEmpty() && !isScanning,
                ) {
                    Text(if (enabledFolders.size == folders.size) "全部取消勾选" else "全部勾选")
                }
                Button(onClick = { onStartScan(enabledFolders.map { it.path }) }, modifier = Modifier.weight(1f), enabled = !isScanning) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (isScanning) "正在扫描..." else "开始扫描")
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 96.dp)
                .navigationBarsPadding()
                .size(68.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            shadowElevation = 10.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(enabled = !isScanning) {
                        if (hasManageStorageAccess) showBrowser = true else requestManageStorage(context)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加文件夹")
            }
        }
    }

    if (showBrowser) {
        FolderBrowserDialog(
            onDismiss = { showBrowser = false },
            onSelect = { path ->
                scope.launch {
                    val normalizedPath = normalizePath(path)
                    val existing = dao.getFolderByPath(normalizedPath)
                    if (existing != null) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, "这个文件夹已经固定过了。", Toast.LENGTH_SHORT).show() }
                    } else {
                        dao.upsertFolder(PinnedFolder(path = normalizedPath, name = File(normalizedPath).name.ifBlank { normalizedPath }, enabled = true, position = folders.size))
                        withContext(Dispatchers.Main) { Toast.makeText(context, "固定文件夹已添加。", Toast.LENGTH_SHORT).show() }
                    }
                }
                showBrowser = false
            },
        )
    }

    if (showExportFolderBrowser) {
        FolderBrowserDialog(
            onDismiss = { showExportFolderBrowser = false },
            onSelect = { path ->
                AppExportManager.setExportFolderPath(context, path)
                exportFolderPath = path
                showExportFolderBrowser = false
                Toast.makeText(context, "导出位置已更新。", Toast.LENGTH_SHORT).show()
            },
        )
    }

    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("移除固定文件夹") },
            text = { Text("这里只会把它从 FindThemOut 的固定列表里移除，不会删除手机里的真实文件。") },
            confirmButton = {
                TextButton(onClick = {
                    folderToDelete = null
                    scope.launch {
                        dao.deleteFolder(folder.path)
                        resequenceFolders(dao)
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text("取消") } },
        )
    }

    if (showClearCacheConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCacheConfirm = false },
            title = { Text("清除扫描缓存") },
            text = { Text("将清空当前所有扫描指纹缓存。下次扫描会重新计算，用于测试最新优化。是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showClearCacheConfirm = false
                    scope.launch {
                        cacheDao.deleteAll()
                        cacheStats = loadScanCacheStats(context, cacheDao)
                        withContext(Dispatchers.Main) { Toast.makeText(context, "扫描缓存已清除。", Toast.LENGTH_SHORT).show() }
                    }
                }) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { showClearCacheConfirm = false }) { Text("取消") } },
        )
    }

    if (showImportConfirm != null) {
        val bundle = showImportConfirm!!
        AlertDialog(
            onDismissRequest = { showImportConfirm = null },
            title = { Text("确认导入配置") },
            text = { Text("将导入固定文件夹、暂存区、扫描指纹缓存和配对缓存。导入时会按路径去重，同一路径不会重复添加。是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirm = null
                    scope.launch {
                        try {
                            importExportBundle(database, bundle)
                            if (!bundle.exportFolderPath.isNullOrBlank()) {
                                AppExportManager.setExportFolderPath(context, bundle.exportFolderPath)
                                exportFolderPath = bundle.exportFolderPath
                            }
                            cacheStats = loadScanCacheStats(context, cacheDao)
                            ignoredImageCount = ignoredImageDao.getCount()
                            withContext(Dispatchers.Main) { Toast.makeText(context, "配置导入完成。", Toast.LENGTH_SHORT).show() }
                        } catch (exception: Exception) {
                            withContext(Dispatchers.Main) { Toast.makeText(context, "导入失败：${exception.message}", Toast.LENGTH_LONG).show() }
                        }
                    }
                }) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { showImportConfirm = null }) { Text("取消") } },
        )
    }

    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = { Text("操作说明") },
            text = {
                Text(
                    "1. 点击右下角加号添加固定文件夹。\n" +
                        "2. 勾选需要参与本次扫描的文件夹。\n" +
                        "3. 点击开始扫描，等待分组结果出现。\n" +
                        "4. 在结果页左右滑比较同组图片，上下滑切换分组。\n" +
                        "5. 把不想保留的图片加入暂存区。\n" +
                        "6. 到暂存区统一确认后再执行永久删除。",
                )
            },
            confirmButton = { TextButton(onClick = { showGuideDialog = false }) { Text("知道了") } },
        )
    }

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "导出位置：${exportFolderPath?.let { trimDisplayedPath(it) } ?: "未设置（默认导出到应用目录）"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "已忽略图片：$ignoredImageCount 张",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { showResetIgnoredConfirm = true },
                        enabled = ignoredImageCount > 0 && !isScanning,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("重置忽略图片")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { showExportFolderBrowser = true }, modifier = Modifier.weight(1f)) {
                            Text("选择导出位置")
                        }
                        OutlinedButton(
                            onClick = {
                                AppExportManager.setExportFolderPath(context, null)
                                exportFolderPath = null
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !exportFolderPath.isNullOrBlank(),
                        ) {
                            Text("清除导出位置")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                scope.launch {
                                    try {
                                        val targetDirectory = exportFolderPath ?: File(context.filesDir, "exports").absolutePath
                                        val bundle = AppExportBundle(
                                            app = "FindThemOut",
                                            exportedAt = LocalDateTime.now().toString(),
                                            exportFolderPath = exportFolderPath,
                                            pinnedFolders = dao.getFolders(),
                                            pendingDeletionEntries = pendingDeletionDao.getAll(),
                                            imageFingerprintCaches = cacheDao.getAll(),
                                            imagePairCaches = pairCacheDao.getAll(),
                                            ignoredImageEntries = ignoredImageDao.getAll(),
                                        )
                                        val file = AppExportManager.exportToFile(context, targetDirectory, bundle)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "配置已导出到指定位置", Toast.LENGTH_SHORT).show()
                                            Toast.makeText(context, file.absolutePath, Toast.LENGTH_LONG).show()
                                        }
                                    } catch (exception: Exception) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "导出失败：${exception.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isScanning,
                        ) {
                            Text("导出配置")
                        }
                        OutlinedButton(onClick = { importLauncher.launch("application/json") }, modifier = Modifier.weight(1f), enabled = !isScanning) {
                            Text("导入配置")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSettingsDialog = false }) { Text("关闭") } },
        )
    }

    if (showResetIgnoredConfirm) {
        AlertDialog(
            onDismissRequest = { showResetIgnoredConfirm = false },
            title = { Text("重置忽略图片") },
            text = { Text("清空后，之前忽略过的图片会重新参与下次扫描匹配。") },
            confirmButton = {
                TextButton(onClick = {
                    showResetIgnoredConfirm = false
                    scope.launch {
                        ignoredImageDao.deleteAll()
                        ignoredImageCount = 0
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "已重置忽略图片", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("继续") }
            },
            dismissButton = { TextButton(onClick = { showResetIgnoredConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ScanResultScreen(
    padding: PaddingValues,
    ignoredImageDao: IgnoredImageDao,
    pendingDeletionDao: PendingDeletionDao,
    scanOutput: ScanOutput?,
    isScanning: Boolean,
    scanProgress: ScanProgressSnapshot?,
    pendingEntries: List<PendingDeletionEntry>,
    onScanOutputChanged: (ScanOutput?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pendingPathSet = remember(pendingEntries) { pendingEntries.map { it.path }.toSet() }
    val markedGroupIds = remember(scanOutput) { mutableStateListOf<String>() }
    var previewGroup by remember { mutableStateOf<List<SimilarImageItem>?>(null) }
    var initialPreviewIndex by remember { mutableIntStateOf(0) }
    var previewGroupIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(18.dp),
    ) {
        if (isScanning && scanProgress != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("正在扫描", style = MaterialTheme.typography.titleMedium)
                    Text(scanProgress.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (scanProgress.total > 0) {
                        LinearProgressIndicator(
                            progress = (scanProgress.current.toFloat() / scanProgress.total.toFloat()).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        if (scanOutput == null) {
            EmptyResultCard()
        } else {
            ResultSummaryCard(scanOutput)
            Spacer(modifier = Modifier.height(10.dp))
            if (scanOutput.groups.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val entries = selectExtrasAcrossGroups(scanOutput).mapNotNull { path ->
                                    findItemByPath(scanOutput, path)?.let { item ->
                                        PendingDeletionEntry(path = item.path, name = item.name, size = item.size)
                                    }
                                }
                                if (entries.isNotEmpty()) {
                                    pendingDeletionDao.upsertAll(entries)
                                    scanOutput.groups.forEach { group ->
                                        if (!markedGroupIds.contains(group.id)) markedGroupIds += group.id
                                    }
                                    Toast.makeText(context, "已将候选图加入暂存区。", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("整批加入暂存区")
                    }
                    OutlinedButton(
                        onClick = { scope.launch { if (pendingEntries.isNotEmpty()) pendingDeletionDao.deleteByPaths(pendingEntries.map { it.path }) } },
                        modifier = Modifier.weight(1f),
                        enabled = pendingEntries.isNotEmpty(),
                    ) {
                        Text("清空暂存区")
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (scanOutput.groups.isEmpty()) {
                EmptyResultCard(message = "这次扫描没有发现相似图片组。")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(scanOutput.groups, key = { _, group -> group.id }) { groupIndex, group ->
                        ResultGroupCard(
                            group = group,
                            pendingPathSet = pendingPathSet,
                            onTogglePending = { item ->
                                scope.launch {
                                    if (pendingPathSet.contains(item.path)) {
                                        pendingDeletionDao.deleteByPath(item.path)
                                    } else {
                                        pendingDeletionDao.upsert(PendingDeletionEntry(path = item.path, name = item.name, size = item.size))
                                    }
                                }
                                if (!markedGroupIds.contains(group.id)) markedGroupIds += group.id
                            },
                            onSelectGroupExtras = {
                                scope.launch {
                                    val entries = group.items.drop(1).map { item ->
                                        PendingDeletionEntry(path = item.path, name = item.name, size = item.size)
                                    }
                                    if (entries.isNotEmpty()) {
                                        pendingDeletionDao.upsertAll(entries)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, "已勾选~", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                if (!markedGroupIds.contains(group.id)) markedGroupIds += group.id
                            },
                            onIgnoreGroup = {
                                scope.launch {
                                    ignoredImageDao.upsertAll(
                                        group.items.map { item ->
                                            IgnoredImageEntry(path = item.path, name = item.name)
                                        },
                                    )
                                    pendingDeletionDao.deleteByPaths(group.items.map { it.path })
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "该组图片已忽略，下次扫描会自动过滤", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                onScanOutputChanged(removePathsFromOutput(scanOutput, group.items.map { it.path }.toSet()))
                                if (!markedGroupIds.contains(group.id)) markedGroupIds += group.id
                            },
                            onPreview = { index ->
                                previewGroup = group.items
                                initialPreviewIndex = index
                                previewGroupIndex = groupIndex
                            },
                        )
                    }
                }
            }
        }
    }

    previewGroup?.let { items ->
        ImagePreviewDialog(
            items = items,
            pendingPathSet = pendingPathSet,
            initialIndex = initialPreviewIndex,
            canJumpToNextGroup = scanOutput != null && scanOutput.groups.size > 1,
            onJumpToNextGroup = {
                val currentOutput = scanOutput ?: return@ImagePreviewDialog
                val randomGroupIndex = pickRandomGroupIndex(
                    groups = currentOutput.groups,
                    currentIndex = previewGroupIndex,
                    excludedGroupIds = collectPendingGroupIds(currentOutput.groups, pendingPathSet),
                )
                if (randomGroupIndex < 0) return@ImagePreviewDialog
                previewGroupIndex = randomGroupIndex
                previewGroup = currentOutput.groups[randomGroupIndex].items
                initialPreviewIndex = 0
            },
            onJumpToPreviousGroup = {
                val currentOutput = scanOutput ?: return@ImagePreviewDialog
                val randomGroupIndex = pickRandomGroupIndex(
                    groups = currentOutput.groups,
                    currentIndex = previewGroupIndex,
                    excludedGroupIds = collectPendingGroupIds(currentOutput.groups, pendingPathSet),
                )
                if (randomGroupIndex < 0) return@ImagePreviewDialog
                previewGroupIndex = randomGroupIndex
                previewGroup = currentOutput.groups[randomGroupIndex].items
                initialPreviewIndex = 0
            },
            onTogglePending = { item ->
                scope.launch {
                    if (pendingPathSet.contains(item.path)) {
                        pendingDeletionDao.deleteByPath(item.path)
                    } else {
                        pendingDeletionDao.upsert(PendingDeletionEntry(path = item.path, name = item.name, size = item.size))
                    }
                }
                val ownerGroup = scanOutput?.groups?.firstOrNull { group -> group.items.any { it.path == item.path } }
                if (ownerGroup != null && !markedGroupIds.contains(ownerGroup.id)) {
                    markedGroupIds += ownerGroup.id
                }
            },
            onSelectGroupExtras = {
                val currentOutput = scanOutput ?: return@ImagePreviewDialog
                val currentGroup = currentOutput.groups.getOrNull(previewGroupIndex) ?: return@ImagePreviewDialog
                scope.launch {
                    val entries = currentGroup.items.drop(1).map { item ->
                        PendingDeletionEntry(path = item.path, name = item.name, size = item.size)
                    }
                    if (entries.isNotEmpty()) {
                        pendingDeletionDao.upsertAll(entries)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "已勾选~", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                if (!markedGroupIds.contains(currentGroup.id)) {
                    markedGroupIds += currentGroup.id
                }
            },
            onIgnoreGroup = {
                val currentOutput = scanOutput ?: return@ImagePreviewDialog
                val currentGroup = currentOutput.groups.getOrNull(previewGroupIndex) ?: return@ImagePreviewDialog
                val updatedOutput = removePathsFromOutput(currentOutput, currentGroup.items.map { it.path }.toSet())
                scope.launch {
                    ignoredImageDao.upsertAll(
                        currentGroup.items.map { item ->
                            IgnoredImageEntry(path = item.path, name = item.name)
                        },
                    )
                    pendingDeletionDao.deleteByPaths(currentGroup.items.map { it.path })
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "该组图片已忽略，下次扫描会自动过滤", Toast.LENGTH_SHORT).show()
                    }
                }
                if (!markedGroupIds.contains(currentGroup.id)) {
                    markedGroupIds += currentGroup.id
                }
                onScanOutputChanged(updatedOutput)
                val randomGroupIndex = pickRandomGroupIndex(
                    groups = updatedOutput.groups,
                    currentIndex = -1,
                    excludedGroupIds = collectPendingGroupIds(updatedOutput.groups, pendingPathSet),
                )
                if (randomGroupIndex >= 0) {
                    previewGroupIndex = randomGroupIndex
                    previewGroup = updatedOutput.groups[randomGroupIndex].items
                    initialPreviewIndex = 0
                } else {
                    previewGroup = null
                }
            },
            onDismiss = { previewGroup = null },
        )
    }
}

@Composable
private fun PendingDeletionScreen(
    padding: PaddingValues,
    pendingDeletionDao: PendingDeletionDao,
    pendingEntries: List<PendingDeletionEntry>,
    scanOutput: ScanOutput?,
    onScanOutputChanged: (ScanOutput?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewIndex by remember { mutableIntStateOf(0) }
    var previewItems by remember { mutableStateOf<List<SimilarImageItem>?>(null) }
    var showPermanentDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(18.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("待删除暂存区", style = MaterialTheme.typography.headlineSmall)
                Text("这里是你临时收集的候选图。确认无误后，再统一永久删除。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                SummaryBadge("当前 ${pendingEntries.size} 张")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (pendingEntries.isEmpty()) {
            EmptyResultCard(message = "暂存区还是空的，你可以先从结果页把候选图加入这里。")
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { pendingDeletionDao.deleteByPaths(pendingEntries.map { it.path }) } },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清空暂存区")
                }
                Button(onClick = { showPermanentDeleteConfirm = true }, modifier = Modifier.weight(1f)) {
                    Text("确认永久删除")
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(pendingEntries, key = { _, item -> item.path }) { index, entry ->
                    PendingDeletionRow(
                        entry = entry,
                        onPreview = {
                            previewItems = pendingEntries.map { pending ->
                                SimilarImageItem(
                                    path = pending.path,
                                    name = pending.name,
                                    width = 0,
                                    height = 0,
                                    size = pending.size,
                                    modifiedAt = pending.addedAt,
                                    score = 0,
                                )
                            }
                            previewIndex = index
                        },
                        onRemove = { scope.launch { pendingDeletionDao.deleteByPath(entry.path) } },
                    )
                }
            }
        }
    }

    previewItems?.let { items ->
        ImagePreviewDialog(
            items = items,
            pendingPathSet = pendingEntries.map { it.path }.toSet(),
            initialIndex = previewIndex,
            canJumpToNextGroup = false,
            onJumpToNextGroup = {},
            onJumpToPreviousGroup = {},
            onTogglePending = { item ->
                scope.launch { pendingDeletionDao.deleteByPath(item.path) }
            },
            onSelectGroupExtras = null,
            onIgnoreGroup = null,
            showRecommendedBadge = false,
            onDismiss = { previewItems = null },
        )
    }

    if (showPermanentDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showPermanentDeleteConfirm = false },
            title = { Text("确认永久删除") },
            text = { Text("将永久删除暂存区中的 ${pendingEntries.size} 张图片。此操作不可恢复，确认继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showPermanentDeleteConfirm = false
                    scope.launch {
                        val paths = pendingEntries.map { it.path }
                        val deletedPaths = deleteFilesDirectly(context, paths)
                        if (deletedPaths.isEmpty()) {
                            Toast.makeText(context, "没有文件被成功删除。", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        pendingDeletionDao.deleteByPaths(deletedPaths)
                        val currentOutput = scanOutput
                        if (currentOutput != null) {
                            onScanOutputChanged(removePathsFromOutput(currentOutput, deletedPaths.toSet()))
                        }
                        val skippedCount = paths.size - deletedPaths.size
                        Toast.makeText(
                            context,
                            if (skippedCount > 0) "已永久删除 ${deletedPaths.size} 张，另有 ${skippedCount} 张删除失败。" else "已永久删除 ${deletedPaths.size} 张图片。",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }) { Text("继续删除") }
            },
            dismissButton = { TextButton(onClick = { showPermanentDeleteConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ResultSummaryCard(scanOutput: ScanOutput) {
    val summary = scanOutput.summary
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("扫描结果", style = MaterialTheme.typography.titleLarge)
            Text("本次扫描了 ${summary.scannedFolders} 个已勾选文件夹，发现 ${summary.groupCount} 组相似图片。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SummaryBadge("目录 ${summary.visitedDirectories}", modifier = Modifier.weight(1f))
                SummaryBadge("文件 ${summary.visitedFiles}", modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SummaryBadge("图片 ${summary.candidateImages}", modifier = Modifier.weight(1f))
                SummaryBadge("耗时 ${formatDuration(summary.elapsedMillis)}", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ResultGroupCard(
    group: SimilarImageGroup,
    pendingPathSet: Set<String>,
    onTogglePending: (SimilarImageItem) -> Unit,
    onSelectGroupExtras: () -> Unit,
    onIgnoreGroup: () -> Unit,
    onPreview: (Int) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已按建议保留图排序：优先文件更大，若大小相同则优先文件名更短。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.size(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSelectGroupExtras) {
                        Text("一键勾选")
                    }
                    OutlinedButton(onClick = onIgnoreGroup) {
                        Text("忽略本组")
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.items.forEachIndexed { index, item ->
                    SimilarImageRow(
                        item = item,
                        inPendingDeletion = pendingPathSet.contains(item.path),
                        isRecommendedKeep = index == 0,
                        onTogglePending = { onTogglePending(item) },
                        onPreview = { onPreview(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SimilarImageRow(
    item: SimilarImageItem,
    inPendingDeletion: Boolean,
    isRecommendedKeep: Boolean,
    onTogglePending: () -> Unit,
    onPreview: () -> Unit,
) {
    val titleColor = if (isRecommendedKeep) Color(0xFF1F8A5B) else MaterialTheme.colorScheme.onSurface
    val metaColor = if (isRecommendedKeep) Color(0xFF1F8A5B) else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPreview),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.path,
            contentDescription = item.name,
            modifier = Modifier
                .size(84.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleSmall,
                color = titleColor,
                fontWeight = if (isRecommendedKeep) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatBytes(item.size),
                style = MaterialTheme.typography.bodySmall,
                color = metaColor,
            )
            if (isRecommendedKeep) {
                Text("建议保留", style = MaterialTheme.typography.labelMedium, color = titleColor)
            }
        }
        Checkbox(checked = inPendingDeletion, onCheckedChange = { onTogglePending() })
    }
}

@Composable
private fun PendingDeletionRow(
    entry: PendingDeletionEntry,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPreview)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = entry.path,
                contentDescription = entry.name,
                modifier = Modifier
                    .size(92.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatBytes(entry.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(trimDisplayedPath(entry.path), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRemove) { Text("移出") }
        }
    }
}

@Composable
private fun EmptyResultCard(message: String = "还没有扫描结果，请先到文件夹页发起一次扫描。") {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("当前没有内容", style = MaterialTheme.typography.titleMedium)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImagePreviewDialog(
    items: List<SimilarImageItem>,
    pendingPathSet: Set<String>,
    initialIndex: Int,
    canJumpToNextGroup: Boolean,
    onJumpToNextGroup: () -> Unit,
    onJumpToPreviousGroup: () -> Unit,
    onTogglePending: (SimilarImageItem) -> Unit,
    onSelectGroupExtras: (() -> Unit)?,
    onIgnoreGroup: (() -> Unit)?,
    showRecommendedBadge: Boolean = true,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var currentIndex by remember(items, initialIndex) { mutableIntStateOf(initialIndex.coerceIn(0, items.lastIndex)) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val currentItem = items[currentIndex]
    val isRecommendedKeep = showRecommendedBadge && currentIndex == 0
    val previousIndex = remember(currentIndex, items.size) { (currentIndex - 1 + items.size) % items.size }
    val nextIndex = remember(currentIndex, items.size) { (currentIndex + 1) % items.size }

    fun showPrevious() {
        dragOffsetX = 0f
        dragOffsetY = 0f
        currentIndex = (currentIndex - 1 + items.size) % items.size
    }

    fun showNext() {
        dragOffsetX = 0f
        dragOffsetY = 0f
        currentIndex = (currentIndex + 1) % items.size
    }

    BackHandler(onBack = onDismiss)

    LaunchedEffect(items, currentIndex) {
        val imageLoader = context.imageLoader
        listOf(previousIndex, nextIndex).distinct().forEach { index ->
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(items[index].path)
                    .crossfade(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(items, currentIndex, canJumpToNextGroup) {
                        detectDragGestures(
                            onDrag = { _, dragAmount ->
                                dragOffsetX += dragAmount.x
                                dragOffsetY += dragAmount.y
                            },
                            onDragEnd = {
                                val absX = abs(dragOffsetX)
                                val absY = abs(dragOffsetY)
                                if (absX >= absY) {
                                    when {
                                        dragOffsetX <= -120f -> showNext()
                                        dragOffsetX >= 120f -> showPrevious()
                                    }
                                } else if (canJumpToNextGroup) {
                                    when {
                                        dragOffsetY <= -120f -> onJumpToNextGroup()
                                        dragOffsetY >= 120f -> onJumpToPreviousGroup()
                                    }
                                }
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                            },
                            onDragCancel = {
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                            },
                        )
                    },
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(currentItem.path)
                        .crossfade(false)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = currentItem.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Surface(color = Color(0x66000000), shape = RoundedCornerShape(999.dp)) {
                        Text(
                            text = "${currentIndex + 1}/${items.size}",
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color(0xB2000000))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${currentItem.name} (${currentIndex + 1}/${items.size})",
                        color = if (isRecommendedKeep) Color(0xFF6EF2A5) else Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(text = formatBytes(currentItem.size), color = Color(0xFFE4ECEB))
                    if (isRecommendedKeep) {
                        Text(text = "建议保留", color = Color(0xFF6EF2A5), style = MaterialTheme.typography.labelLarge)
                    }
                    val isInPending = pendingPathSet.contains(currentItem.path)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (onIgnoreGroup != null) {
                            OutlinedButton(
                                onClick = onIgnoreGroup,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("忽略本组")
                            }
                        }
                        Button(
                            onClick = { onTogglePending(currentItem) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isInPending) Color(0xFFD84B4B) else Color.White,
                                contentColor = if (isInPending) Color.White else Color(0xFF202020),
                            ),
                        ) {
                            Text(if (isInPending) "取消删除" else "删除")
                        }
                        if (onSelectGroupExtras != null) {
                            OutlinedButton(
                                onClick = onSelectGroupExtras,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("一键勾选")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderSummaryCard(
    totalCount: Int,
    enabledCount: Int,
    pendingCount: Int,
    hasManageStorageAccess: Boolean,
    scanProgress: ScanProgressSnapshot?,
    isScanning: Boolean,
    cacheStats: ScanCacheStats?,
    onClearCache: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("FindThemOut", style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable(onClick = onOpenGuide),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "操作说明",
                            modifier = Modifier.padding(8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable(onClick = onOpenSettings),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                            modifier = Modifier.padding(8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            SummaryBadge(if (hasManageStorageAccess) "权限已就绪" else "等待授权")

            Text(
                text = buildCacheStatsText(cacheStats),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onClearCache, enabled = !isScanning) {
                Text("清除扫描缓存")
            }

            if (!hasManageStorageAccess) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("目录浏览器需要存储访问权限", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "为了保持一致的应用内选文件夹体验，当前版本在你的小米 13 场景下使用完整目录访问方式。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (isScanning && scanProgress != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("扫描进行中", style = MaterialTheme.typography.titleSmall)
                        Text(scanProgress.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (scanProgress.total > 0) {
                            LinearProgressIndicator(
                                progress = (scanProgress.current.toFloat() / scanProgress.total.toFloat()).coerceIn(0f, 1f),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

private fun requestManageStorage(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            context.startActivity(intent)
        }
    }
}

@Composable
private fun rememberManageStorageAccess(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasAccess by remember { mutableStateOf(checkManageStorageAccess()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = checkManageStorageAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return hasAccess
}

private fun checkManageStorageAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun normalizePath(path: String): String {
    return path.replace('\\', '/').trimEnd('/')
}

private fun isSamePath(left: String, right: String): Boolean {
    return normalizePath(left) == normalizePath(right)
}

private fun trimDisplayedPath(path: String): String {
    val normalized = path.replace('\\', '/')
    val marker = "/emulated/0/"
    val index = normalized.indexOf(marker)
    return if (index >= 0) normalized.substring(index + marker.length) else normalized
}

private suspend fun reorderFolders(
    dao: PinnedFolderDao,
    folders: List<PinnedFolder>,
    fromIndex: Int,
    toIndex: Int,
) {
    val reordered = folders.toMutableList()
    val moved = reordered.removeAt(fromIndex)
    reordered.add(toIndex, moved)
    dao.upsertFolders(reordered.mapIndexed { index, folder -> folder.copy(position = index) })
}

private suspend fun resequenceFolders(dao: PinnedFolderDao) {
    val updated = dao.getFolders().mapIndexed { index, folder -> folder.copy(position = index) }
    dao.upsertFolders(updated)
}

private fun formatBytes(size: Long): String {
    if (size <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = size.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    val pattern = if (unitIndex == 0) "0" else "0.00"
    return "${DecimalFormat(pattern).format(value)} ${units[unitIndex]}"
}

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = (durationMillis / 1000.0).roundToInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes} 分 ${seconds} 秒" else "${seconds} 秒"
}

private data class ScanCacheStats(
    val entryCount: Int,
    val totalBytes: Long,
)

private fun buildCacheStatsText(cacheStats: ScanCacheStats?): String {
    return if (cacheStats == null) {
        "扫描缓存占用：正在统计..."
    } else {
        "扫描缓存占用：${cacheStats.entryCount} 条，约 ${formatBytes(cacheStats.totalBytes)}"
    }
}

private fun pickRandomGroupIndex(
    groups: List<SimilarImageGroup>,
    currentIndex: Int,
    excludedGroupIds: Set<String>,
): Int {
    val candidates = groups.mapIndexedNotNull { index, group ->
        if (index != currentIndex && !excludedGroupIds.contains(group.id)) index else null
    }
    if (candidates.isEmpty()) return -1
    return candidates.random()
}

private fun collectPendingGroupIds(
    groups: List<SimilarImageGroup>,
    pendingPathSet: Set<String>,
): Set<String> {
    return groups.mapNotNullTo(mutableSetOf()) { group ->
        if (group.items.any { pendingPathSet.contains(it.path) }) group.id else null
    }
}

private fun selectExtrasAcrossGroups(scanOutput: ScanOutput): List<String> {
    return buildList {
        scanOutput.groups.forEach { group ->
            group.items.drop(1).forEach { item -> add(item.path) }
        }
    }
}

private fun findItemByPath(scanOutput: ScanOutput, path: String): SimilarImageItem? {
    return scanOutput.groups.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.path == path }
}

private fun removePathsFromOutput(
    scanOutput: ScanOutput,
    removedPaths: Set<String>,
): ScanOutput {
    val updatedGroups = scanOutput.groups.mapNotNull { group ->
        val remaining = group.items.filterNot { removedPaths.contains(it.path) }
        if (remaining.size < 2) null else group.copy(items = remaining)
    }
    return scanOutput.copy(
        groups = updatedGroups,
        summary = scanOutput.summary.copy(
            groupedImages = updatedGroups.sumOf { it.items.size },
            groupCount = updatedGroups.size,
        ),
    )
}

private suspend fun loadScanCacheStats(
    context: Context,
    cacheDao: ImageFingerprintCacheDao,
): ScanCacheStats = withContext(Dispatchers.IO) {
    val databaseFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
    val walFile = File(databaseFile.absolutePath + "-wal")
    val shmFile = File(databaseFile.absolutePath + "-shm")
    ScanCacheStats(
        entryCount = cacheDao.getCount(),
        totalBytes = databaseFile.lengthSafe() + walFile.lengthSafe() + shmFile.lengthSafe(),
    )
}

private suspend fun deleteFilesDirectly(
    context: Context,
    paths: List<String>,
): List<String> = withContext(Dispatchers.IO) {
    val deletedPaths = mutableListOf<String>()
    paths.forEach { path ->
        val file = File(path)
        try {
            if (file.exists() && file.isFile && file.delete()) {
                deletedPaths += path
            }
        } catch (_: Exception) {
        }
    }
    deletedPaths
}

private suspend fun copyUriToTempFile(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val tempFile = File.createTempFile("findthemout_import_", ".json", context.cacheDir)
    context.contentResolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output ->
            input.copyTo(output)
        }
    } ?: error("无法读取导入文件")
    tempFile
}

private suspend fun importExportBundle(
    database: AppDatabase,
    bundle: AppExportBundle,
) = withContext(Dispatchers.IO) {
    val pinnedFolderDao = database.pinnedFolderDao()
    val pendingDeletionDao = database.pendingDeletionDao()
    val fingerprintDao = database.imageFingerprintCacheDao()
    val pairCacheDao = database.imagePairCacheDao()
    val ignoredImageDao = database.ignoredImageDao()

    val dedupedFolders = bundle.pinnedFolders
        .associateBy { it.path }
        .values
        .sortedBy { it.position }
        .mapIndexed { index, folder -> folder.copy(position = index) }

    pinnedFolderDao.deleteAll()
    pendingDeletionDao.deleteAll()
    fingerprintDao.deleteAll()
    pairCacheDao.deleteAll()
    ignoredImageDao.deleteAll()

    if (dedupedFolders.isNotEmpty()) pinnedFolderDao.upsertFolders(dedupedFolders)
    if (bundle.pendingDeletionEntries.isNotEmpty()) pendingDeletionDao.upsertAll(bundle.pendingDeletionEntries.distinctBy { it.path })
    if (bundle.imageFingerprintCaches.isNotEmpty()) fingerprintDao.upsertAll(bundle.imageFingerprintCaches.distinctBy { it.path })
    if (bundle.imagePairCaches.isNotEmpty()) pairCacheDao.upsertAll(bundle.imagePairCaches.distinctBy { "${it.leftPath}|${it.rightPath}" })
    if (bundle.ignoredImageEntries.isNotEmpty()) ignoredImageDao.upsertAll(bundle.ignoredImageEntries.distinctBy { it.path })
}

private fun File.lengthSafe(): Long = if (exists() && isFile) length() else 0L

@Composable
private fun FolderRow(
    folder: PinnedFolder,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = folder.enabled,
                onCheckedChange = { checked -> onToggleEnabled(checked) },
                enabled = enabled,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    trimDisplayedPath(folder.path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = if (folder.enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = if (folder.enabled) "本次参与扫描" else "本次跳过",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (folder.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp && enabled) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown && enabled) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移")
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Default.Delete, contentDescription = "移除", tint = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderBrowserDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val rootPath = remember { Environment.getExternalStorageDirectory().absolutePath }
    var currentPath by rememberSaveable { mutableStateOf(rootPath) }
    val currentDirectory = remember(currentPath) { File(currentPath) }
    val childDirectories = remember(currentPath) {
        currentDirectory
            .listFiles { file -> file.isDirectory && !file.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase(Locale.getDefault()) }
            ?: emptyList()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF183F36))
                        .padding(horizontal = 18.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("选择文件夹", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text("这里使用统一的应用内浏览风格。", color = Color(0xFFD7ECE5))
                    Text(trimDisplayedPath(currentPath), color = Color(0xFFA8D7C7), style = MaterialTheme.typography.bodySmall)
                }

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!isSamePath(currentPath, rootPath)) {
                        item {
                            BrowserRow(
                                title = "../ 返回上级",
                                subtitle = trimDisplayedPath(currentDirectory.parent ?: rootPath),
                                onClick = {
                                    currentDirectory.parent?.let { parent ->
                                        currentPath = parent
                                    }
                                },
                                leadingIcon = Icons.Default.KeyboardArrowLeft,
                            )
                        }
                    }
                    itemsIndexed(childDirectories, key = { _, directory -> directory.absolutePath }) { _, directory ->
                        BrowserRow(
                            title = directory.name,
                            subtitle = trimDisplayedPath(directory.absolutePath),
                            onClick = { currentPath = directory.absolutePath },
                            onLongClick = { onSelect(directory.absolutePath) },
                            leadingIcon = Icons.Default.Folder,
                        )
                    }
                }

                Divider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    Button(onClick = { onSelect(currentPath) }, modifier = Modifier.weight(1f)) {
                        Text("固定当前目录")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(leadingIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SummaryBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}
