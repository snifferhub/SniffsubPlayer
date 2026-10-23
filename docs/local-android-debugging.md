# 本机 Android 模拟器调试

本流程用于维护者要求的本机 UI 调试：在 Mac 的 ARM64 模拟器里构建、安装、运行 Debug 包。正式 APK 的签名与发布继续使用 GitHub Actions。

## 已配置的环境

- Android CLI：`android`，配置时已更新至 `1.0.16625370`，默认复用 `/opt/homebrew/share/android-commandlinetools` SDK。
- SDK：Android 36、Build Tools 36.0.0、Platform Tools、Android Emulator。
- 模拟器：`SniffSub_API_36`，Android 16 / Google APIs / ARM64，3 GB 内存、4 个虚拟 CPU。
- Java：快捷脚本优先使用本机 JDK 22，缺少时尝试 JDK 17；不修改其他项目的 Java 环境。可用 `ANDROID_DEV_JAVA_HOME` 指定其他兼容 JDK。
- Shell：`~/.config/android/dev-env.zsh` 由 `~/.zshrc` 加载，提供 SDK 路径和 `android-dev` 函数。
- CLI 默认参数：`~/.androidrc`，固定 SDK 并关闭 CLI 使用情况收集。
- 原有 Shell/CLI 配置的备份：`~/.android/config-backups/`。模拟器数据持久保存在 `~/.android/avd/`。

新开终端即可使用；当前终端执行：

```sh
source ~/.config/android/dev-env.zsh
android-dev doctor
```

`android-dev` 可在任意目录运行，实际工程固定为 `~/coding/userscripts/.worktrees/native-android`。如果移动工程，需要更新该环境文件里的路径。也可以在工程目录直接运行 `./scripts/android-dev.sh`。

## 日常使用

```sh
# 启动窗口、编译当前代码、安装 Debug APK、启动 SniffSubPlayer
android-dev run

# 改完代码后，再执行一次即可覆盖安装，保留应用数据
android-dev run

# 只打开模拟器；也可加 --headless 在后台无窗口运行
android-dev start

# 保存当前屏幕和 UI 布局树
android-dev screenshot
android-dev screenshot --annotate
android-dev layout

# 跟随当前应用进程日志，Ctrl-C 退出
android-dev logs

# 一次性输出日志、查看崩溃
android-dev logs -d
android-dev crash

# 关闭模拟器，保留应用数据
android-dev stop
```

首次构建/启动较慢，后续使用 Gradle 缓存与模拟器快照。脚本使用 `--no-daemon`，构建完成后 Java 构建进程自动退出，避免持续占用数 GB 内存；每次构建启动会稍慢。脚本只操作名称匹配的模拟器，即使接着手机也不会自动选择手机。若以 `start --headless` 启动，需 `stop` 后再 `start` 才能切换为有窗口模式。

截图与布局输出到 `artifacts/local-debug/`，Debug APK 为 `app/build/outputs/apk/debug/app-debug.apk`，均已被 Git 忽略。`layout` 提供的结构取决于应用暴露的信息，WebView/自绘界面应结合截图检查。日志跟随的是启动命令时的进程；应用重启后需要重新运行 `logs`。

## 安装现有 APK 或单独构建

```sh
android-dev build
android-dev install
android-dev launch

# 安装下载好的 APK，不必复制到手机
android-dev install ~/Downloads/SniffSubPlayer-Android.apk
android-dev launch
```

正式包与本地 Debug 包可能签名不同，且正式包版本号更高，不能直接互相覆盖。建议始终在此模拟器使用 Debug 包。若已安装正式包，先明确接受丢失模拟器内应用数据，再执行 `android-dev adb uninstall com.sniffsub.sniff_sub_player`，之后 `android-dev run`。脚本不会自动卸载或清空数据，也不会自动允许版本降级。

## 点击、滑动、返回与连接本机服务

可以直接使用模拟器窗口的鼠标与键盘，也可以用命令：

```sh
android-dev adb shell input tap 500 1000
android-dev adb shell input swipe 800 1500 200 1500 400
android-dev adb shell input keyevent KEYCODE_BACK

# 模拟器通过此地址访问 Mac 上监听端口 8000 的服务
# http://10.0.2.2:8000

# 或将模拟器 localhost:8000 转发至 Mac 的 8000 端口
android-dev adb reverse tcp:8000 tcp:8000
```

`adb shell input text` 适合简单英文，中文输入可使用模拟器键盘。内置浏览器的网页调试可以在 Mac Chrome 打开 `chrome://inspect/#devices`，前提是 Debug 应用开启了 WebView 调试。模拟器界面右侧的扩展控制可以调节旋转、网络等条件。

## 原生 Android CLI

快捷脚本封装了项目路径、JDK 和模拟器选择。也可以直接使用：

```sh
android info
android emulator list
android emulator start SniffSub_API_36
adb devices -l

# 将 emulator-5554 替换成 adb devices 显示的实际序列号
android screen capture --device=emulator-5554 --output=/tmp/android-screen.png
android layout --device=emulator-5554 --pretty
android emulator stop SniffSub_API_36
```

不需要登录 Google 账号就能使用本地 SDK、模拟器、截图和安装功能。`android input` 是否可用取决于 CLI 版本；这里的交互命令使用已验证的 `adb shell input`。

## 常见问题

- 找不到 `android-dev`：新开终端或 `source ~/.config/android/dev-env.zsh`。
- 模拟器黑屏/快照异常：关闭后执行 `android-dev start --cold`。这不主动清空应用数据。
- APK 安装失败：检查包签名/版本号冲突；不要对手机执行卸载命令。
- UI 或日志变化没生效：确认在 Android 工程目录修改代码，然后重新 `android-dev run`。
- 想看 Android 9–11 的兼容样式：另装相应 ARM64 系统镜像并创建 AVD，再通过 `ANDROID_DEV_AVD=名称 android-dev run` 选择。当前只预装 Android 16。

模拟器适合布局、交互、截图、日志与一般功能回归；设备厂商差异、真实音量/亮度手感、性能和特定媒体解码仍需单独验证。

## 配置验收（2026-10-17）

已在此 Mac 验证硬件加速、Debug APK 构建、安装和启动、按钮点击、普通/标注截图、Compose UI 布局树及应用日志读取；模拟器关闭后可从快照重启，已安装的应用保留。更新 CLI 后增量覆盖安装成功，本轮没有新增崩溃日志。没有执行完整应用功能回归；现有崩溃缓冲区仍保留更新前旧 CLI 安装辅助进程的记录。

官方参考：[Android CLI](https://developer.android.com/tools/agents/android-cli)、[模拟器命令行](https://developer.android.com/studio/run/emulator-commandline)、[模拟器网络地址](https://developer.android.com/studio/run/emulator-networking-address)。
