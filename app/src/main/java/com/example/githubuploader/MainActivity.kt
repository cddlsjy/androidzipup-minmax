package com.example.githubuploader

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

// ==================== GitHub API 接口定义 ====================
interface GithubApi {
    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Query("ref") branch: String,
        @Header("Authorization") token: String
    ): GithubContentResponse

    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun createOrUpdateFile(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path") path: String,
        @Header("Authorization") token: String,
        @Body body: CreateFileRequest
    ): GithubFileResponse

    data class CreateFileRequest(
        val message: String,
        val content: String,
        val sha: String? = null,
        val branch: String
    )

    data class GithubContentResponse(
        val sha: String,
        val content: String? = null
    )

    data class GithubFileResponse(
        val content: ContentInfo,
        val commit: CommitInfo? = null
    )

    data class ContentInfo(val sha: String)
    data class CommitInfo(val sha: String, val message: String)
}

// ==================== 内置 YML 文件内容 ====================
val DEFAULT_UNPACK_YML = """
name: Unpack ZIP and Flatten to Root

on:
  push:
    paths: ['**.zip']
  workflow_dispatch:
    inputs:
      zip_file:
        description: '要解压的 ZIP 文件名（例如 "archive.zip"）'
        required: false
        default: ''
      subdir:
        description: '要移动到根目录的子目录名（可选）'
        required: false
        default: ''

jobs:
  unpack-and-move:
    runs-on: ubuntu-latest
    permissions:
      contents: write

    steps:
      - uses: actions/checkout@v4

      # ========== 自动模式：push 且只有一个 ZIP 时 ==========
      - name: 自动解压（仅当只有一个 ZIP 文件）
        if: github.event_name == 'push'
        run: |
          shopt -s dotglob
          zip_files=(*.zip)
          count=${'$'}{#zip_files[@]}

          if [ $count -eq 0 ]; then
            echo "没有找到 ZIP 文件"
            exit 0
          elif [ $count -eq 1 ]; then
            zipfile="${'$'}{zip_files[0]}"
            echo "自动解压单个 ZIP: $zipfile"
            temp_dir="${'$'}{zipfile%.zip}_temp_$$"
            mkdir -p "$temp_dir"
            unzip -o "$zipfile" -d "$temp_dir"
            items=("$temp_dir"/*)
            if [ ${'$'}{#items[@]} -eq 1 ] && [ -d "${'$'}{items[0]}" ]; then
              subdir="${'$'}{items[0]}"
              mv "$subdir"/* . 2>/dev/null || true
              mv "$subdir"/.[!.]* . 2>/dev/null || true
              rm -rf "$subdir"
            else
              mv "$temp_dir"/* . 2>/dev/null || true
              mv "$temp_dir"/.[!.]* . 2>/dev/null || true
            fi
            rm -rf "$temp_dir"
            rm -f "$zipfile"
          else
            echo "检测到多个 ZIP 文件 ($count 个)，为避免冲突，不自动解压。"
            echo "请手动运行 workflow_dispatch，并指定要解压的 ZIP 文件名。"
            echo "存在的 ZIP 文件："
            printf '  - %s\n' "${'$'}{zip_files[@]}"
            exit 0
          fi

      # ========== 手动模式：解压指定的 ZIP ==========
      - name: 手动解压指定的 ZIP 文件
        if: github.event_name == 'workflow_dispatch' && inputs.zip_file != ''
        run: |
          shopt -s dotglob
          zipfile="${{ inputs.zip_file }}"
          if [ ! -f "$zipfile" ]; then
            echo "错误：文件 '$zipfile' 不存在"
            echo "当前目录下的 ZIP 文件："
            ls -1 *.zip 2>/dev/null || echo "（无）"
            exit 1
          fi
          echo "手动解压: $zipfile"
          temp_dir="${'$'}{zipfile%.zip}_temp_$$"
          mkdir -p "$temp_dir"
          unzip -o "$zipfile" -d "$temp_dir"
          items=("$temp_dir"/*)
          if [ ${'$'}{#items[@]} -eq 1 ] && [ -d "${'$'}{items[0]}" ]; then
            subdir="${'$'}{items[0]}"
            mv "$subdir"/* . 2>/dev/null || true
            mv "$subdir"/.[!.]* . 2>/dev/null || true
            rm -rf "$subdir"
          else
            mv "$temp_dir"/* . 2>/dev/null || true
            mv "$temp_dir"/.[!.]* . 2>/dev/null || true
          fi
          rm -rf "$temp_dir"
          rm -f "$zipfile"

      # ========== 手动移动子目录（可选） ==========
      - name: 移动手动指定的子目录到根目录
        if: github.event_name == 'workflow_dispatch' && inputs.subdir != ''
        run: |
          shopt -s dotglob
          SUBDIR="${{ inputs.subdir }}"
          if [ -d "$SUBDIR" ]; then
            echo "移动子目录 '$SUBDIR' 的内容到根目录"
            mv "$SUBDIR"/* . 2>/dev/null || true
            mv "$SUBDIR"/.[!.]* . 2>/dev/null || true
            rm -rf "$SUBDIR"
          else
            echo "错误：子目录 '$SUBDIR' 不存在"
            exit 1
          fi

      # ========== 提交所有更改 ==========
      - name: 提交更改
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add .
          git diff --staged --quiet || git commit -m "自动解压 ZIP 并整理目录结构"
          git push
""".trimIndent()

val DEFAULT_BUILD_YML = """
name: Build Android APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

env:
  FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        id: setup-java
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'gradle'

      - name: Set JAVA_HOME globally
        run: echo "JAVA_HOME=${{ steps.setup-java.outputs.path }}" >> $GITHUB_ENV

      - name: Verify Java
        run: |
          echo "JAVA_HOME: $JAVA_HOME"
          java -version

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v3
        with:
          gradle-version: '8.5'

      - name: Remove hardcoded Java home in gradle.properties
        run: |
          if [ -f gradle.properties ]; then
            sed -i '/org.gradle.java.home/d' gradle.properties
            echo "✅ Removed org.gradle.java.home from gradle.properties"
          else
            echo "ℹ️ No gradle.properties file found, skipping."
          fi

      - name: Build APK
        run: gradle assembleDebug --stacktrace --no-daemon

      - name: Find and upload APK
        id: find_apk
        run: |
          APK_PATH=$(find . -name "*.apk" -type f | head -n 1)
          if [ -z "$APK_PATH" ]; then
            echo "❌ No APK found"
            find . -path "*/build/outputs/*" -type f | head -20
            exit 1
          fi
          echo "✅ Found APK: $APK_PATH"
          echo "apk_path=$APK_PATH" >> $GITHUB_OUTPUT

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: ${'$'}{{ steps.find_apk.outputs.apk_path }}
          retention-days: 7
""".trimIndent()

// ==================== 主题颜色 ====================
private val Purple80 = Color(0xFFD0BCFF)
private val PurpleGrey80 = Color(0xFFCCC2DC)
private val Pink80 = Color(0xFFEFB8C8)
private val Purple40 = Color(0xFF6650a4)
private val PurpleGrey40 = Color(0xFF625b71)
private val Pink40 = Color(0xFF7D5260)
private val SuccessGreen = Color(0xFF4CAF50)
private val ErrorRed = Color(0xFFE53935)
private val WarningOrange = Color(0xFFFF9800)
private val InfoBlue = Color(0xFF2196F3)

// ==================== 主活动 ====================
class MainActivity : ComponentActivity() {
    private lateinit var api: GithubApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 Retrofit
        val client = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()

        val gson = GsonBuilder().create()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

        api = retrofit.create(GithubApi::class.java)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Purple80,
                    secondary = PurpleGrey80,
                    tertiary = Pink80
                ),
                lightColorScheme = lightColorScheme(
                    primary = Purple40,
                    secondary = PurpleGrey40,
                    tertiary = Pink40
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UploadScreen(api = api, context = this)
                }
            }
        }
    }
}

// ==================== 主界面 Composable ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(api: GithubApi, context: MainActivity) {
    var repoUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var uploadDefaultUnpack by remember { mutableStateOf(true) }
    var uploadDefaultBuild by remember { mutableStateOf(true) }
    val customYmlFiles = remember { mutableStateListOf<Pair<Uri, String>>() }
    var zipFileUri by remember { mutableStateOf<Uri?>(null) }
    var zipFileName by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var isUploading by remember { mutableStateOf(false) }
    var tokenVisible by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // 保存/加载 token 和 repo 的 SharedPreferences
    val prefs = context.getSharedPreferences("github_uploader", Context.MODE_PRIVATE)
    LaunchedEffect(Unit) {
        token = prefs.getString("token", "") ?: ""
        repoUrl = prefs.getString("repo_url", "") ?: ""
        branch = prefs.getString("branch", "main") ?: "main"
        uploadDefaultUnpack = prefs.getBoolean("upload_default_unpack", true)
        uploadDefaultBuild = prefs.getBoolean("upload_default_build", true)
    }

    fun savePrefs() {
        prefs.edit()
            .putString("token", token)
            .putString("repo_url", repoUrl)
            .putString("branch", branch)
            .putBoolean("upload_default_unpack", uploadDefaultUnpack)
            .putBoolean("upload_default_build", uploadDefaultBuild)
            .apply()
    }

    // 添加带时间戳的日志
    fun addLog(msg: String) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val timestamp = timeFormat.format(Date())
        logs = logs + "[$timestamp] $msg"
    }

    // 从 Uri 获取文件名
    fun getFileNameFromUri(uri: Uri): String {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                cursor.getString(nameIndex)
            } else {
                uri.lastPathSegment ?: "unknown"
            }
        } ?: uri.lastPathSegment ?: "unknown"
    }

    // 读取文件内容为 Base64
    suspend fun readFileAsBase64(uri: Uri): String = withContext(Dispatchers.IO) {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("无法打开文件")
        val bytes = inputStream.use { it.readBytes() }
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // 从仓库地址解析 owner/repo
    fun parseRepo(url: String): Pair<String, String>? {
        val patterns = listOf(
            Regex("github\\.com[:/]([^/]+)/([^/.]+)"),
            Regex("https://github\\.com/([^/]+)/([^/.]+)")
        )
        for (pattern in patterns) {
            val matcher = pattern.find(url)
            if (matcher != null) {
                return matcher.groupValues[1] to matcher.groupValues[2]
            }
        }
        return null
    }

    // 获取文件 sha（用于更新），不存在返回 null
    suspend fun getFileSha(
        api: GithubApi,
        owner: String,
        repo: String,
        path: String,
        token: String,
        branch: String
    ): String? {
        return try {
            val response = api.getFile(owner, repo, path, branch, "token $token")
            if (response.isSuccessful) response.body()?.sha else null
        } catch (e: Exception) {
            null
        }
    }

    // 通用文件上传
    suspend fun uploadFile(
        api: GithubApi,
        owner: String,
        repo: String,
        remotePath: String,
        contentBase64: String,
        token: String,
        branch: String,
        addLog: (String) -> Unit
    ) {
        val sha = getFileSha(api, owner, repo, remotePath, token, branch)
        val body = GithubApi.CreateFileRequest(
            message = "Upload $remotePath",
            content = contentBase64,
            sha = sha,
            branch = branch
        )
        val response = api.createOrUpdateFile(owner, repo, remotePath, "token $token", body)
        if (response.isSuccessful) {
            addLog("✓ $remotePath 上传成功")
        } else {
            val errorBody = response.errorBody()?.string() ?: "Unknown error"
            addLog("✗ $remotePath 失败: ${response.code()} - $errorBody")
        }
    }

    // 上传 YML 内容字符串
    suspend fun uploadYmlContent(
        owner: String,
        repo: String,
        filename: String,
        content: String,
        api: GithubApi,
        token: String,
        branch: String,
        addLog: (String) -> Unit
    ) {
        val contentBase64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        uploadFile(api, owner, repo, ".github/workflows/$filename", contentBase64, token, branch, addLog)
    }

    // 上传逻辑
    fun startUpload() {
        if (isUploading) return
        if (repoUrl.isBlank()) {
            addLog("⚠ 请输入仓库地址")
            return
        }
        if (token.isBlank()) {
            addLog("⚠ 请输入 GitHub Token")
            return
        }

        isUploading = true
        addLog("🚀 开始上传...")

        context.lifecycleScope.launch {
            try {
                val (owner, repo) = parseRepo(repoUrl)
                    ?: throw Exception("仓库地址格式错误，请检查格式")

                addLog("📦 仓库: $owner/$repo")
                addLog("🌿 分支: $branch")

                // 1. 上传默认的 workflow（如果勾选）
                if (uploadDefaultUnpack) {
                    addLog("📤 上传 zip-moveroot.yml ...")
                    uploadYmlContent(owner, repo, "zip-moveroot.yml", DEFAULT_UNPACK_YML, api, token, branch) { addLog(it) }
                } else {
                    addLog("⏭ 跳过 zip-moveroot.yml")
                }

                if (uploadDefaultBuild) {
                    addLog("📤 上传 build.yml ...")
                    uploadYmlContent(owner, repo, "build.yml", DEFAULT_BUILD_YML, api, token, branch) { addLog(it) }
                } else {
                    addLog("⏭ 跳过 build.yml")
                }

                // 2. 上传自定义的 YML 文件
                if (customYmlFiles.isEmpty()) {
                    addLog("📂 无自定义 YML 文件")
                } else {
                    for ((uri, filename) in customYmlFiles) {
                        addLog("📤 上传自定义 YML: $filename ...")
                        try {
                            val contentBase64 = readFileAsBase64(uri)
                            val remotePath = ".github/workflows/$filename"
                            uploadFile(api, owner, repo, remotePath, contentBase64, token, branch) { addLog(it) }
                        } catch (e: Exception) {
                            addLog("✗ $filename 读取失败: ${e.message}")
                        }
                    }
                }

                // 3. 上传 ZIP 文件
                zipFileUri?.let { uri ->
                    addLog("📤 上传 ZIP: $zipFileName ...")
                    try {
                        val contentBase64 = readFileAsBase64(uri)
                        uploadFile(api, owner, repo, zipFileName, contentBase64, token, branch) { addLog(it) }
                    } catch (e: Exception) {
                        addLog("✗ ZIP 读取失败: ${e.message}")
                    }
                } ?: addLog("📂 无 ZIP 文件")

                addLog("✅ 所有操作完成！")

            } catch (e: Exception) {
                addLog("❌ 错误: ${e.message}")
            } finally {
                isUploading = false
            }
        }
    }

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val name = getFileNameFromUri(it)
            when {
                name.endsWith(".yml") || name.endsWith(".yaml") -> {
                    customYmlFiles.add(Pair(uri, name))
                    addLog("➕ 添加自定义 YML: $name")
                }
                name.endsWith(".zip") -> {
                    zipFileUri = uri
                    zipFileName = name
                    addLog("📁 选择 ZIP: $name")
                }
                else -> {
                    addLog("⚠ 不支持的文件类型: $name")
                }
            }
        }
    }

    // 自动滚动到最新日志
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GitHub Workflow 上传工具", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 仓库地址输入
            OutlinedTextField(
                value = repoUrl,
                onValueChange = {
                    repoUrl = it
                    savePrefs()
                },
                label = { Text("仓库地址 (GitHub URL)") },
                placeholder = { Text("例如: https://github.com/user/repo") },
                leadingIcon = {
                    Icon(Icons.Default.Folder, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Token 输入
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                    savePrefs()
                },
                label = { Text("GitHub Token") },
                placeholder = { Text("ghp_xxxxxxxxxxxx") },
                leadingIcon = {
                    Icon(Icons.Default.Description, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Text(if (tokenVisible) "隐藏" else "显示")
                    }
                },
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 分支输入
            OutlinedTextField(
                value = branch,
                onValueChange = {
                    branch = it
                    savePrefs()
                },
                label = { Text("分支名") },
                placeholder = { Text("默认 main") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 分隔线
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 默认文件选择
            Text(
                "默认 Workflow 文件",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uploadDefaultUnpack,
                        onCheckedChange = {
                            uploadDefaultUnpack = it
                            savePrefs()
                        }
                    )
                    Text("zip-moveroot.yml", modifier = Modifier.padding(start = 4.dp))
                }
                Text(
                    "自动解压 ZIP",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = uploadDefaultBuild,
                        onCheckedChange = {
                            uploadDefaultBuild = it
                            savePrefs()
                        }
                    )
                    Text("build.yml", modifier = Modifier.padding(start = 4.dp))
                }
                Text(
                    "构建 Android APK",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 自定义文件选择按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("选择文件")
                }
                Text(
                    "支持 YML / ZIP",
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 自定义 YML 文件列表
            if (customYmlFiles.isNotEmpty()) {
                Text(
                    "自定义 YML 文件 (${customYmlFiles.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    LazyColumn(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(customYmlFiles.size) { index ->
                            val (_, name) = customYmlFiles[index]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        customYmlFiles.removeAt(index)
                                        addLog("🗑 移除: $name")
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        modifier = Modifier.size(16.dp),
                                        tint = ErrorRed
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ZIP 文件显示
            if (zipFileName.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Archive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    "ZIP 文件",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Text(
                                    zipFileName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                addLog("🗑 移除 ZIP: $zipFileName")
                                zipFileUri = null
                                zipFileName = ""
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                tint = ErrorRed
                            )
                        }
                    }
                }
            }

            // 上传按钮
            Button(
                onClick = { startUpload() },
                enabled = !isUploading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("上传中...", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开始上传", style = MaterialTheme.typography.titleMedium)
                }
            }

            // 日志区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "操作日志",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { logs = emptyList() }) {
                    Text("清除日志")
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "日志将在此处显示...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(logs.size) { index ->
                            val log = logs[index]
                            val color = when {
                                log.contains("✓") -> SuccessGreen
                                log.contains("✗") || log.contains("❌") -> ErrorRed
                                log.contains("⚠") -> WarningOrange
                                log.contains("📤") -> InfoBlue
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall,
                                color = color,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
