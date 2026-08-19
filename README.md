# SpaceNoteWaterfall

一款基于 PICO Spatial SDK 的轻量空间待办分拣应用。用户可以输入或粘贴最多 20 条待办，通过垂直便签瀑布将任务拖放到「今天」「以后」「交给别人」和「待决定池」，再在结果页继续编辑、删除、跨分类调整并保存截图。

## 主要功能

- 手动输入与多行文本自动拆分，单批最多 20 条
- 每 2 秒释放一张便签的舒缓分拣节奏
- 手柄射线、点击分类与拖放分类交互
- 四个固定分类的独立任务列表和实时数量统计
- 已分类便签可抓回、重新分拣或跨分类移动
- 手动进入结果页，并可返回分拣页继续调整
- 结果编辑、删除、分类调整与本地结果恢复
- 截图保存到 `DCIM/SpaceNoteWaterfall` 并通过系统图片查看器打开
- Android 自适应图标与 PICO 分层 3D 图标资源

## 技术信息

- Kotlin / SpatialUI Compose
- PICO Spatial SDK BOM 0.13.3
- Planar `DefaultWindowContainer`
- 应用包名：`com.example.spacenote`
- Android API 35

## 构建

```powershell
.\gradlew.bat assembleDebug
```

Debug APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 安装与启动

```powershell
pico-cli app install app/build/outputs/apk/debug/app-debug.apk --replace
pico-cli app launch com.example.spacenote --activity .platform.LaunchActivity
```

## 测试

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedAndroidTest
```

应用为纯本地单次使用工具，不接入任务提醒、日历同步、自动指派或外部服务。
