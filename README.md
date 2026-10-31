# ComfyUI 远程控制

Android 客户端，用手机连接 ComfyUI，选择 API 格式工作流，设置参数并查看任务进度。

## 安装

下载 [v1.1 调试版 APK](downloads/ComfyUI-Remote-Control-v1.1-debug.apk) 并安装。该 APK 使用开发者的调试签名；如果手机上的旧版签名不同，Android 可能拒绝覆盖安装。卸载旧版前请自行备份 App 中的工作流和参数。

## 使用

1. 在 App 左侧菜单添加 ComfyUI 地址，例如 `http://192.168.1.100:8188`，并选择该设备。
2. 在右侧「工作流/参数」导入从 ComfyUI 导出的 **API 格式 JSON**，或使用 [MiniMax H3 演示工作流](examples/h3-text-to-video-api.json)。演示工作流需要服务器已有对应 H3 模型和节点。
3. 在主界面填写提示词、时长、步数和宽高。提示词可以添加多段、调整顺序，提交时按顺序用空行拼接。
4. 点击「保存参数」可命名保存，点击「读取参数」可恢复；旧版保存的单段提示词会按一段载入。
5. 点击「提交任务」，在队列和历史中查看进度及结果。

### AI 改写

在「改写模型设置」中填写 OpenAI Chat Completions 兼容接口的地址、模型名和 API 密钥；无鉴权的本地接口可不填密钥。点击某段下的「改写」，输入要求后，仅替换该段；可以取消或撤销。密钥由 Android Keystore 加密保存，不包含在参数配置中。

## 工作流兼容性

导入的是 API 格式的节点对象，不是普通画布格式。提交时 App 在每个节点的 `inputs` 中匹配字段名：`text` / `prompt`、`steps` / `num_steps`、`width`、`height`、`length` / `frame_count`；时长按照 24 fps 转换为帧数。`seed`、`noise_seed`、`rand_seed`、`seed_number` 和 `manual_seed` 会自动随机化。

当前替换是按名称全局进行的：正负提示词同名时可能被一同替换，连接到这些字段的输入也可能被替换成输入框的值。请先确认工作流适用，并在服务器安装所需模型与自定义节点。

演示 JSON 仅包含工作流，不包含模型权重、LoRA 或个人配置。

## 构建与测试

使用 Android Studio 安装项目要求的 Android SDK，并让 `local.properties` 指向本机 SDK。Windows 下使用 JDK 17 或 Android Studio 自带 JBR：

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Android 设备或模拟器连接后可运行 `:app:connectedDebugAndroidTest`。本版已完成单元测试和模拟器测试；模型改写接口使用本地模拟服务验证。
