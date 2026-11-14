package com.hosira.oopznative

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.hosira.oopznative.ui.theme.OopzNativeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果在界面里按需再查 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceManager.init(this)
        LogStore.init(this)
        requestPerms.launch(
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            }.toTypedArray()
        )
        setContent { OopzNativeTheme { AppRoot() } }
    }
}

sealed interface Screen {
    data object Login : Screen
    data object Areas : Screen
    data class Voice(val area: OopzApi.Area) : Screen
    data class Diag(val back: Screen) : Screen
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var screen by remember { mutableStateOf<Screen>(Screen.Login) }
    var session by remember { mutableStateOf<OopzApi.Session?>(null) }
    var status by remember { mutableStateOf("未登录") }
    LaunchedEffect(Unit) {
        SessionStore.load(context)?.let {
            session = it
            status = "已恢复登录：${it.name.ifBlank { it.uid.take(8) }}"
            screen = Screen.Areas
        }
    }
    val webLogin = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            val uid = data.getStringExtra("uid").orEmpty()
            val sig = data.getStringExtra("signature").orEmpty()
            if (sig.isNotBlank()) {
                val fresh = OopzApi.Session(uid, sig, "网页登录")
                session = fresh
                SessionStore.save(context, fresh)
                status = "网页登录成功（uid=${uid.take(8)}…）"
                data.getStringExtra("dump")?.let { dump ->
                    SessionStore.rememberDump(dump)
                    OopzApi.debugLog += "网页登录存储：\n${dump.take(800)}"
                }
                screen = Screen.Areas
            } else {
                status = "网页里没找到登录凭证，请确认已登录成功"
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = screen) {
            Screen.Login -> LoginScreen(
                status = status,
                onWebLogin = {
                    webLogin.launch(android.content.Intent(context, WebLoginActivity::class.java))
                },
                onLogin = { phone, code, isSms ->
                    scope.launch {
                        status = "正在登录…"
                        runCatching { OopzApi.login(phone, code, isSms, OopzApi.deviceId()) }
                            .onSuccess {
                                session = it
                                status = "已登录：${it.name.ifBlank { it.uid }}"
                                screen = Screen.Areas
                            }
                            .onFailure {
                                val msg = it.message.orEmpty()
                                status = if (msg.contains("401") || msg.contains("签名"))
                                    "密码登录不可用（Oopz 要签名）→ 请点上面的「网页登录」"
                                else "登录失败：$msg"
                            }
                    }
                }
            )

            Screen.Areas -> AreasScreen(
                context = context,
                session = session,
                status = status,
                onEnter = { area -> screen = Screen.Voice(area) },
                onDiag = { screen = Screen.Diag(Screen.Areas) },
                onLogout = {
                    SessionStore.clear(context)
                    session = null
                    status = "已退出"
                    screen = Screen.Login
                }
            )

            is Screen.Diag -> {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("设备诊断", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { screen = s.back }) { Text("返回") }
                    }
                    val checks = remember(s.back, session) { DeviceDiag.selfCheck(context, session) }
                    Text("自检", style = MaterialTheme.typography.labelLarge)
                    checks.forEach { (name, ok) ->
                        Text("${if (ok) "✓" else "✗"} $name",
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    val text = remember(s.back, session) { DeviceDiag.snapshot(context, session) }
                    LazyColumn(Modifier.weight(1f)) {
                        items(text.lines()) { line -> Text(line, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }

            is Screen.Voice -> VoiceScreen(
                context = context,
                area = s.area,
                session = session,
                onBack = { screen = Screen.Areas }
            )
        }
    }
}

@Composable
fun LoginScreen(
    status: String,
    onWebLogin: () -> Unit,
    onLogin: (String, String, Boolean) -> Unit
) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var sms by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Oopz 语音（原生）", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(phone, { phone = it }, label = { Text("手机号") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            code, { code = it },
            label = { Text(if (sms) "短信验证码" else "密码") },
            singleLine = true,
            visualTransformation = if (sms) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = sms, onCheckedChange = { sms = it })
            Spacer(Modifier.width(8.dp))
            Text(if (sms) "验证码登录（需先在官方客户端获取验证码）" else "密码登录")
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onWebLogin, modifier = Modifier.fillMaxWidth()) {
            Text("网页登录（推荐，一次就好）")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Oopz 的接口要求客户端签名，密码直连会被 401 拦掉；网页登录借官方页面完成签名，之后全程原生。",
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { onLogin(phone.trim(), code, sms) }, modifier = Modifier.fillMaxWidth()) {
            Text("密码/验证码直连（当前会被 401，备用）")
        }
        Spacer(Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodySmall)
        var showDiag by remember { mutableStateOf(false) }
        TextButton(onClick = { showDiag = !showDiag }) { Text(if (showDiag) "收起诊断" else "接口诊断") }
        if (showDiag) {
            LazyColumn(Modifier.height(180.dp)) {
                items(OopzApi.debugLog) { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun AreasScreen(
    context: Context,
    session: OopzApi.Session?,
    status: String,
    onEnter: (OopzApi.Area) -> Unit,
    onLogout: () -> Unit,
    onDiag: () -> Unit = {}
) {
    var areas by remember { mutableStateOf<List<OopzApi.Area>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        loading = true
        runCatching { OopzApi.subscribedAreas(s) }
            .onSuccess { areas = it }
            .onFailure { err = it.message ?: "加载失败" }
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("我的域", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onLogout) { Text("退出登录") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (err.isNotBlank()) Text("错误：$err", color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f)) {
            items(areas) { area ->
                ListItem(
                    headlineContent = { Text(area.name) },
                    supportingContent = { Text(area.id, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { onEnter(area) },
                    modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
                ) { Text("进入语音") }
                HorizontalDivider()
            }
        }
        if (MiuiHelper.isMiui()) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDiag) { Text("设备诊断") }
            TextButton(onClick = { MiuiHelper.openAutoStartSettings(context) }) {
                Text("小米自启动设置")
            }
            TextButton(onClick = { MiuiHelper.requestIgnoreBatteryOptimizations(context) }) {
                Text("申请电池不优化")
            }
        }
        Spacer(Modifier.height(4.dp))
        val scope2 = scope
        TextButton(onClick = { VoiceService.start(context) }) { Text("启动保活服务") }
    }
}

@Composable
fun VoiceScreen(
    context: Context,
    area: OopzApi.Area,
    session: OopzApi.Session?,
    onBack: () -> Unit
) {
    var channels by remember { mutableStateOf<List<OopzApi.Channel>>(emptyList()) }
    var wsState by remember { mutableStateOf("未连接网关") }
    var expired by remember { mutableStateOf(false) }

    LaunchedEffect(session, area) {
        val s = session ?: return@LaunchedEffect
        // REST 先试一次（接口随版本变，失败无所谓）
        runCatching { OopzApi.channels(s, area.id) }
            .onSuccess { if (it.isNotEmpty()) channels = it }
        // 网关实时补全（collect 本身是挂起的，直接在当前 LaunchedEffect 里跑）
        runCatching {
            OopzWs(s, SessionStore.deviceId(context)).connectWithRetry().collect { ev: OopzWs.Event ->
                val found = OopzWs.extractChannels(ev.body)
                if (found.isNotEmpty()) channels = (channels + found).distinctBy { it.id }
                when (ev.id) {
                    OopzWs.READY -> wsState = "网关已就绪"
                    OopzWs.CHECK_RESULT -> {
                        val ok = ev.body?.optBoolean("checkRes") == true
                        wsState = if (ok) "网关校验通过" else "网关拒绝握手（签名失效，请重新网页登录）"
                    }
                }
            }
        }.onFailure {
            val msg = it.message ?: "网关连接失败"
            wsState = msg
            if (msg.contains("签名")) {
                SessionStore.clear(context)
                expired = true
            }
        }
    }

    var log by remember { mutableStateOf(listOf<String>()) }
    var inRoom by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf(VoiceManager.Route.AUTO) }
    var mic by remember { mutableStateOf(VoiceControls.micEnabled) }
    var island by remember { mutableStateOf(VoiceControls.superIslandEnabled) }

    DisposableEffect(Unit) {
        VoiceManager.onEvent = { msg ->
            log = (log + msg).takeLast(12)
            LogStore.append(msg)
        }
        onDispose { VoiceManager.onEvent = null }
    }

    val car = remember { CarLinkHelper.carConnected(context) }
    var carMode by remember { mutableStateOf(VoiceManager.CarMode.AUTO) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(area.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { VoiceManager.leaveRoom(); VoiceService.stop(context); onBack() }) { Text("返回") }
        }
        if (expired) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("登录已过期，需要重新网页登录", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("去重登") }
            }
        }
        if (car != null) {
            Text("🚗 已连接车机：${car.name}", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("未检测到车机蓝牙（可手动选模式）", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Text("车机取音模式", style = MaterialTheme.typography.labelLarge)
        Column {
            VoiceManager.CarMode.values().forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = carMode == m, onClick = {
                        carMode = m
                        VoiceManager.applyCarMode(m)
                    })
                    Text(m.label)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("TRTC 原生语音 · 3A 降噪（AEC/ANS/AGC）", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        Text("输出设备", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VoiceManager.Route.values().forEach { r ->
                FilterChip(selected = route == r, onClick = {
                    route = r
                    if (inRoom) VoiceManager.setRoute(r)
                }, label = { Text(r.label) })
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                if (!inRoom) {
                    VoiceService.start(context)
                    inRoom = true
                    log = log + "已启动保活服务；等待 Oopz 下发 TRTC 凭证（sdkAppId/userSig）"
                } else {
                    VoiceManager.setMicEnabled(mic.not())
                    mic = !mic
                }
            }) { Text(if (inRoom) (if (mic) "静音" else "取消静音") else "加入语音") }

            OutlinedButton(onClick = {
                VoiceManager.leaveRoom(); VoiceService.stop(context); inRoom = false; log = log + "已离开"
            }) { Text("离开") }
        }

        if (inRoom) {
            Spacer(Modifier.height(12.dp))
            Text("麦克风电平：${VoiceManager.localVolume}　对方音量：${VoiceManager.remoteVolume}",
                style = MaterialTheme.typography.labelMedium)
            LinearProgressIndicator(
                progress = { VoiceManager.localVolume / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            val ok = VoiceManager.selfTestMic()
            log = log + if (ok) "麦克风自测：采集已启动，对着手机说话看电平" else "麦克风自测：启动失败（检查权限）"
        }) { Text("麦克风自测") }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {
            val path = LogStore.export(context)
            LogStore.append("导出日志 $path")
            log = log + "日志已导出：$path"
        }) { Text("导出日志") }

        Spacer(Modifier.height(16.dp))
        Text("事件", style = MaterialTheme.typography.labelLarge)
        LazyColumn(Modifier.weight(1f)) {
            items(log) { line -> Text("· $line", style = MaterialTheme.typography.bodySmall) }
        }
        Text(
            "说明：语音凭证由 Oopz 服务端在进频道时下发，本版先把 TRTC 引擎、设备切换与保活打通。",
            style = MaterialTheme.typography.labelSmall
        )
    }
}
