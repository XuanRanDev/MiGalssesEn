# 小米眼镜 3.3.0 文件空间保存分析

## 样本

- APK：`MCP/glasses/Xiaomi Glasses_3.3.0.apk`
- 包名：`com.xiaomi.superhexa`
- 版本：`3.3.0`（303000）
- 分析方式：MT MCP 只读 APK 数据查询；未使用 jadx

## 保存调用链

文件空间的主要保存入口为：

```text
FileSpaceHelper.N(MediaBean, focus, Continuation)
  -> FileAndDirUtils.W(...)
  -> FileAndDirUtils.V(...)
  -> FileAndDirUtils.G(Context, File, ...)
  -> FileAndDirUtils.C(File, timestamp)
  -> ContentResolver.insert(MediaStore, ContentValues)
  -> ContentResolver.openOutputStream(uri)
  -> Okio 将 MediaBean.path 指向的本地缓存文件写入目标 Uri
  -> 将 is_pending 更新为 0
```

批量保存对话框存在另一条等价链路：

```text
SaveProgressDialog.provideCallable$lambda$4(...)
  -> FileAndDirUtils.T(...)
  -> FileAndDirUtils.D(...)
  -> FileAndDirUtils.B(File, timestamp)
```

Android 10 以上最终目录取自 `ContentValues` 的 `relative_path`。原 APK 的
`FileAndDirUtils.C()` 会在特定机型写死为 `DCIM/Camera`，其余情况读取内部配置，
所以眼镜媒体会与手机相机内容混在一起。

## Hook 方案

模块只在 `com.xiaomi.superhexa` 主进程加载，Hook：

- `FileAndDirUtils.B(File, long): ContentValues`
- `FileAndDirUtils.C(File, long): ContentValues`

原方法执行完后，仅覆盖返回值中的 `relative_path`。文件名、MIME、大小、日期、
MediaStore collection、写入进度、Content Uri 回调和数据库状态逻辑均保持原样。

默认目录为 `DCIM/MiGlasses`。模块设置页通过 libxposed remote preferences 支持热更新，
允许配置位于 `DCIM`、`Pictures` 或 `Movies` 下的相对目录。

## 版本适配提示

当前 Hook 点和签名已针对 3.3.0 验证。若未来 APK 混淆名变化，模块会记录
DexKit 查询错误；此时需重新通过 MCP 确认特征并升级查询规则。

## DexKit 与缓存

运行时使用 DexKit 2.2.0，通过返回类型、参数类型以及 `_display_name`、
`relative_path`、`is_pending`、`mime_type` 字符串组合定位目标方法，不依赖混淆后的
类名与方法名。查询结果由 `DexKitCacheBridge` 持久化到宿主私有 SharedPreferences。
首次创建 DexKit bridge 前显式调用 `System.loadLibrary("dexkit")` 加载原生库。
缓存以宿主 `versionCode + lastUpdateTime + QUERY_VERSION` 为代际键：同版本再次启动直接
恢复方法描述符；APK 更新或查询规则升级时自动清空并重新扫描。

## 文件空间与眼镜传输协议

### 总体结论

眼镜中的图片和视频并不是通过蓝牙直接传输。应用先通过既有的设备控制通道命令眼镜
开启高速 Wi-Fi 链路，再通过局域网 HTTP 下载文件。APK 3.3.0 同时实现两种链路：

1. 手机创建 Wi-Fi Direct Group，手机作为 Group Owner（GO），眼镜作为 Group Client（GC）。
2. 眼镜开启 Wi-Fi AP，手机连接眼镜热点，并使用 AP 的 gateway 作为眼镜地址。

控制命令类位于 `...bluetooth.command.o95`，说明开启和关闭 Wi-Fi 的命令通过 O95 设备
控制通道发送；媒体文件的数据面则走 Wi-Fi 和 HTTP。

### HTTP 服务

`DeviceMediaDataHandler$fetchServerData$1.invokeSuspend()` 明确构造：

```text
http://<hostIP>:8080/v1/filelists
```

已确认接口：

| 方法 | 地址 | 用途 |
|---|---|---|
| `GET` | `http://<host>:8080/v1/filelists` | 取得眼镜文件列表 |
| `PUT` | `http://<host>:8080/v1/fileTransfer` | 文件传输状态控制，Body 为 `{"action":"..."}` |
| `GET` | `http://<host>:8080/v1/pointLog?size=N` | 读取设备日志/埋点 |
| `DELETE` | 动态 URL，查询参数 `id` | 删除眼镜上的媒体 |
| `GET` | `/device/v1/files/{name}` | 按名称取得文件的兼容接口 |

文件列表请求使用 5 秒连接超时、10 秒读写超时。响应被解析为
`List<O95FileListResponse>`，随后转换为本地 `MediaBean`。应用先下载缩略图，再下载用户
选择的图片/视频源文件，最后写入 App 文件空间；是否继续导出系统相册是另一层逻辑。

`O95FileTransferRequest` 只有一个字符串字段 `action`。3.3.0 APK 内没有找到该对象的实际
构造引用，因此当前尚不能静态确认 action 的合法取值；该接口可能是预留接口，不能凭名称
臆造 `start`/`stop`。需要在真机传输时抓 HTTP 请求或动态记录 Retrofit 参数确认。

### Wi-Fi Direct / P2P 开启流程

`MiWearWiFiP2PConfigHandler` 的流程如下：

1. 手机生成 `DIRECT-<随机值>` 格式的 SSID、随机密码和工作频率。
2. 使用 `WifiP2pConfig.Builder` 设置 network name、passphrase 和 operating frequency。
3. 手机调用 Android `WifiP2pManager` 建立 Group Owner。
4. 通过设备控制通道发送 `EnableWiFiP2PGC`，要求眼镜加入该 Group。
5. 命令字段为 `ssid`、`password`、`frequency` 和可空的 `taskId`。
6. 眼镜加入后，P2P 对端 IP 写入 MMKV 键 `wifi_p2p`。
7. `MediaTransManagerViewModel` 读取该 IP，调用 `/v1/filelists`。
8. 应用将当前进程绑定到对应 Wi-Fi `Network`，避免 HTTP 被移动数据或其他网络路由走。

`EnableWiFiP2PGC.getPackedId()` 返回 `0x63`。载荷被封装为
`SystemProtos.System.wifiP2P`，因此电脑端若要自主开启 P2P，除创建 Group Owner 外，还必须
能够向眼镜发送同样的 Wear protobuf 控制包。

### Wi-Fi Direct / P2P 关闭流程

传输结束时：

1. 清除 MMKV 中的 `wifi_p2p` 地址。
2. 调用 `WifiP2pManager.removeGroup()` 移除手机创建的 Group Owner。
3. 解除进程对临时 Wi-Fi Network 的绑定。

当前代码没有看到额外的“关闭眼镜 P2P”命令；眼镜 GC 会随 Group 被移除而断开。应用中
`removeGoOrWifiAp` 和日志 `remove GO,removeGroupSuccess` 与此流程一致。

### 眼镜 Wi-Fi AP 开启流程

兼容 AP 模式由 `MiWearWiFiConfigHandler.enableWiFiAP()` 完成：

1. 通过 `IDeviceOperator.u(...)` 发送单例命令 `EnableWiFiAP`。
2. `EnableWiFiAP.getPackedId()` 返回 `0x58`。
3. 请求的 `SystemProtos.System` 没有额外字段。
4. 眼镜响应 `SystemProtos.WiFiAP.Result`。
5. AP 配置以 `SystemProtos.WiFiAP` 保存到 MMKV 键 `wifi_ap`，内容包括：
   `ssid`、`password/passphrase`、`gateway`。
6. 手机连接该 SSID，确认当前 Wi-Fi SSID 匹配后，把 `gateway` 作为 `<hostIP>` 请求
   `http://<gateway>:8080/v1/filelists`。

`FileTransMiWearDialog` 中存在 `apSsid`、`apPass` 字段，并从已绑定设备的 `WifiAPData`
读取 SSID 和 passphrase，进一步印证 AP 模式由眼镜提供热点。

### 眼镜 Wi-Fi AP 关闭流程

`MiWearWiFiConfigHandler.disableWiFiAP()`：

1. 从 `wifi_ap` 读取并反序列化 `SystemProtos.WiFiAP`。
2. 构造 `DisableWiFiAP(ssid, password, gateway)`。
3. 通过 `IDeviceOperator.u(...)` 发送给眼镜并等待回调。
4. `DisableWiFiAP.getPackedId()` 返回 `0x59`。
5. protobuf 载荷为 `SystemProtos.System.wifiAp`，包含相同的 SSID、密码和 gateway。
6. 手机侧再尝试断开该 AP，并清理临时连接状态。

### 电脑同步软件可行性

只要电脑已经和眼镜处于同一临时网络中，下载器本身较容易实现：

```text
GET http://<眼镜IP>:8080/v1/filelists
  -> 解析文件名、标识符、类型、时间、缩略图 URL 和源文件 URL
  -> 下载到临时文件
  -> 校验大小，必要时支持断点续传
  -> 原子重命名到用户选择的目录
  -> 用 identifier/修改时间建立本地同步索引，避免重复下载
```

难点是“不借助手机 App 主动开启传输模式”。PC 版本需要补齐 O95 控制通道，包括设备发现、
配对/鉴权、帧封装、protobuf 编码、命令回调和超时。当前已经知道 AP/P2P 的业务载荷和
packedId，但尚未还原 `IDeviceOperator` 下层的蓝牙服务、分包及加密/校验格式。

建议分两阶段实现：

1. **HTTP 原型**：先由官方 App 开启 AP/P2P，电脑手动加入网络；独立程序只完成发现地址、
   列表读取和增量下载。这样可以快速验证文件列表 JSON、文件 URL、认证要求和断点续传。
2. **完全独立版**：抓取一次 App 与眼镜的控制通道通信，再还原 `WearPacket` 封装及
   `0x58/0x59/0x63` 命令，使电脑能够自行开启和关闭传输网络。

### 待验证事项

- `/v1/filelists` 的完整 JSON 样例及每个 URL 是否为绝对地址。
- 文件 GET 是否需要 Header、Cookie、token 或仅依赖局域网可达性。
- 是否支持 HTTP `Range`，以及大视频中断后的恢复行为。
- `O95FileTransferRequest.action` 的实际取值和调用时机。
- O95 控制链路最终使用 BLE GATT、经典蓝牙还是其他已绑定通道，以及其帧头、校验和鉴权。
- P2P 模式下眼镜 IP 的发现方式；目前 App 从 `wifi_p2p` 状态读取，仍需追踪其写入回调。
- AP 模式的 SSID/密码是否每次随机、是否与设备绑定信息长期复用。
- 删除接口必须在确认用户意图后使用；同步器第一版应保持纯只读。

分析依据：MT MCP 对 APK 的 ARSC、DEX/Smali 和调用引用进行只读查询，未使用 jadx。

## hostIP 动态捕获 Hook

`DeviceMediaDataHandler$fetchServerData$1` 的构造函数签名为
`(String hostIP, DeviceMediaDataHandler, Continuation)`。第一个参数被保存到合成字段
`$hostIP`，`invokeSuspend()` 再将其格式化为
`http://%s:8080/v1/filelists`。因此该值就是当前 HTTP 文件服务的眼镜地址，并非在协程内
通过扫描计算出来。

模块现同时 Hook 两层参数边界：

- `DeviceMediaDataHandler$fetchServerData$1` 构造函数的第一个参数；
- `DeviceMediaDataHandler` 中首参为 `String` 的单参数/四参数传输方法（3.3.0 对应
  `l(String)` 与 `k(String, Long, Function2, Continuation)`）。

libxposed 的 remote preferences 在被 Hook 的宿主进程中是只读实现，因此捕获后通过指向
模块 `HostIpReceiver` 的显式广播跨进程传递，由模块进程写入自己的本地 SharedPreferences；
同时记录完整的 `filelists` URL 和来源方法到 Xposed 日志。模块主页点击“刷新眼镜 IP”即可查看。实际值只有在官方 App 进入“文件空间”并触发
列表请求后才会出现；AP 模式通常是眼镜热点 gateway，P2P 模式则来自 `wifi_p2p` 状态。

## 局域网发现脚本

项目提供 `scripts/find-glasses.ps1`。脚本自动枚举活动 IPv4 网卡，按 `/24` 网段并发探测
TCP 8080，并以只读方式请求 `/v1/filelists`；返回成功 JSON 数组的主机会标记为眼镜候选。

```powershell
# 自动扫描所有活动的 /24 网段
.\scripts\find-glasses.ps1

# 只扫描指定网段（填写前三段）
.\scripts\find-glasses.ps1 -Subnet 192.168.43

# 多个网段及自定义超时
.\scripts\find-glasses.ps1 -Subnet 192.168.43,192.168.49 -TimeoutMs 800
```

2026-09-22 的一次实扫覆盖 `192.168.28.0/24` 和 WSL 的 `172.23.240.0/24`，未发现
眼镜文件服务；`192.168.28.1:8080` 返回 502，`192.168.28.216:8080` 返回 404，均不匹配
`/v1/filelists`。扫描前需要先让官方 App 进入文件空间/导入流程以启动眼镜的传输网络，
并让电脑连接眼镜 AP；仅让眼镜正常开机通常不会持续开放该 HTTP 服务。

## P2P 生命周期与 HTTP 安全审计（2026-09-23）

本节通过 MT MCP 0.2.0 对 3.3.0 工作区 `ovbs5de2` 做只读 DEX/Smali 查询，未使用
jadx。真机日志中的 `192.168.49.92`、`createWifiP2P` 与静态调用链相互印证。

### P2P 何时打开

文件空间通过 `MiWearConnectP2POrAPHandler.b()` 选择链路。其判断调用
`com.superhexa.lib.channel.model.a.f()`；3.3.0 中该方法固定返回 `true`，所以这个版本的
文件空间入口总是选择 P2P，传统眼镜 AP 分支当前不可达。

```text
进入文件空间/文件传输 UI
  -> MiWearConnectP2POrAPHandler.b()
  -> DeviceUtils.W(...)
  -> ConnectMiWearP2PFragment.onViewCreated()
  -> ConnectMiWearP2PViewModel.P0(createWifiP2P)
```

创建前必须满足 `IDeviceOperator.i() == true`，即眼镜控制通道处于连接状态。随后清除
MMKV `wifi_p2p`，检查并移除旧 Group，手机创建带随机 `DIRECT-...` SSID、随机密码和选定
频率的 Wi-Fi Direct Group Owner，再通过设备控制通道发送
`EnableWiFiP2PGC(ssid,password,frequency,taskId)`。只有眼镜返回 `code == 0` 且非空
`ipAddress` 时才保留 P2P、绑定对应 Android `Network` 并请求 `/v1/filelists`。

| Result code | 含义 | 行为 |
|---:|---|---|
| 0 + 非空 IP | 成功 | 保留 P2P，进入文件传输 |
| 1 | 设备连接失败 | 上报失败并 removeGroup |
| 2 | 眼镜低电量 | 提示低电量并 removeGroup |
| 3 | 眼镜高温 | 提示高温并 removeGroup |
| 4 | 眼镜正在录像 | 提示录像中并 removeGroup |
| 其他、null 或空 IP | 通用失败 | 上报失败并 removeGroup |

### P2P 何时关闭

统一关闭入口是 `MediaTransManager.e()` / `IMiWearModuleApi.removeGoOrWifiAp()`：先解除
进程默认 Network，清除 `wifi_p2p`，通过 `requestGroupInfo()` 检查 Group，然后调用
`removeGroup()`；如果移除 P2P 失败，再尝试 `DisableWiFiAP`。

已确认关闭触发事件：文件列表为空、下载服务处理完下载结果、录制打断、新绑定流程打断、
用户切换设备。P2P 创建期间的低电量、高温、录像中、连接失败和通用失败也会立即清除
Group。一个明确例外是 `IProfileModuleApi.isLoadingDeviceLog() == true`，此时文件空间不会
移除临时 Wi-Fi，以免中断设备日志上传。

### 8080 HTTP API 静态接口面

| 方法 | 路径 | 3.3.0 调用情况 |
|---|---|---|
| GET | 动态 URL，实际为 `/v1/filelists` | 文件空间实际使用 |
| DELETE | `http://<host>:8080/v1/files/?id=<id>` | 下载完成后实际使用 |
| GET | `http://<host>:8080/v1/pointLog?size=<n>` | 设备日志流程 |
| PUT | `http://<host>:8080/v1/fileTransfer`，JSON body | 未找到调用点，疑似预留 |
| GET | `/device/v1/files/{name}` | 未找到调用点，兼容接口 |

接口定义中没有 Authorization、token、签名、Cookie 或每请求 nonce 参数，传输使用明文
HTTP。客户端使用全局 `RetrofitFactory`，所以不能只凭接口注解断言服务端绝对无认证；但
业务设计很可能把“已进入带随机密码的 P2P 网络”本身当作访问控制边界。

### 潜在安全问题与置信度

1. **同一 P2P 网络内未授权媒体读取（高风险候选，中等置信）**：若无全局 Header 或服务端
   鉴权，任意已加入 Group 的客户端都可列举、下载全部未导入媒体。
2. **任意文件删除/IDOR（高风险候选，中等置信）**：DELETE 只携带列表返回的 `id`，未见
   对象级授权材料；可能通过枚举 ID 删除眼镜媒体。本次未执行 DELETE。
3. **设备日志泄露（中风险候选，中等置信）**：`/v1/pointLog` 仅接受 `size`，若无认证可能
   泄露设备状态、标识或调试信息。
4. **传输状态干扰/DoS（中风险候选，低到中等置信）**：预留 `/v1/fileTransfer` 接受单一
   `action` 字段，若服务端启用且无鉴权可能被同网客户端滥用；APK 中未找到实际 action 值。
5. **明文 HTTP（中风险，静态确认）**：P2P 的 WPA 只防网络外部监听，不能防已加入 Group
   的对端、被入侵手机或恶意同组客户端读取/篡改流量。
6. **服务端路径穿越（未证实）**：客户端 APK 不包含眼镜端 8080 服务实现，不能仅凭
   `/device/v1/files/{name}` 判定漏洞，需要眼镜固件或受控边界输入测试。

当前电脑绕过系统代理直连 `192.168.49.92:8080` 超时，因为电脑没有眼镜 P2P 网络路由；
因此本轮没有完成动态无认证验证，也没有对 DELETE/PUT 发出任何破坏性请求。最小下一步是
在手机 App 的 OkHttp 层记录真实请求 Header，或让电脑加入同一 Wi-Fi Direct Group 后仅做
只读的 `/v1/filelists` 与 `/v1/pointLog?size=1` 验证。
