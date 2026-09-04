# 多指旋转控制 (Multi-Finger Screen Rotation Control)

[![LSPosed Module](https://img.shields.io/badge/LSPosed-Module-blue.svg)](https://github.com/LSPosed/LSPosed)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://android.com)

为 Android 平板与大屏设备量身打造的 LSPosed 多指手势屏幕旋转与系统锁定增强模块。通过直接挂钩系统输入层与窗口管理服务（WMS），实现行云流水、直觉自然的屏幕旋转控制。

---

## 核心特性

### 1. 双指双击定向旋转
- **物理直觉交互**：两指连线即代表您期望的屏幕显示“顶部方向”，任意方向双击两指，屏幕立即精准旋转至两指连线朝向的方向。
- **绝对硬件参考系**：基于平板物理机身传感器硬件坐标系判定，彻底告别多次旋转后的坐标系漂移错乱。

### 2. 显示底边三指横扫 90° 旋转
- **底边跟手旋转**：在当前显示画面底部区域（底部 25%），三指水平向左或向右横扫，底边跟随手指方向旋转 90°（向左横扫即顺时针旋转 90°，向右横扫即逆时针旋转 90°）。
- **零系统干涉熔断保护**：内置严苛的水平/垂直位移比例（$\Delta X \ge 2.5 \Delta Y$）与竖向位移硬截断熔断机制，严密保护系统级竖向手势（如三指下滑截屏、三指上滑分屏），互不干扰。

### 3. 三指三击切换原生旋转锁定
- **原生状态无缝同步**：屏幕任意区域快速轻敲 3 次，无缝切换系统原生旋转锁定（Auto-Rotate / Rotation Lock），状态与控制中心磁贴（Quick Settings Tile）及系统设置完美同步。
- **差异化触觉震动反馈**：解锁与锁定时分别触发轻柔、清脆的触觉震动提示。

### 4. 纯正 MIUIX / HyperOS 风格设置面板
- **极简卡片美学**：遵循超椭圆（Squircle）大圆角、阻尼弹簧胶囊开关与动态微缩触感规范。
- **零延迟无权限热通信**：采用动态广播 IPC 直通 `system_server`，手势开关即调即生效（< 5ms），无需依赖 Root 权限，本地 SharedPreferences 永久持久化。
- **优雅系统框架重启**：内置优雅重启框架（Zygote）功能，避免异常硬杀引发系统崩溃计数。

---

## 作用域与技术栈

- **作用域**：`system_server`（系统框架 / `android`）
- **开发语言**：Kotlin + Jetpack Compose
- **支持架构**：ARM64 / x86_64
- **测试验证机型**：OPPO Pad Mini（Android 16 / ColorOS）

---

## 构建与安装

### 本地编译
```bash
./gradlew assembleRelease
```
编译产物位于 `app/build/outputs/apk/release/app-release-unsigned.apk`（或 debug 版本 `app/build/outputs/apk/debug/app-debug.apk`）。

### 部署与使用
1. 在设备上安装 APK；
2. 打开 LSPosed 管理器，在模块列表中启用 **多指旋转控制**；
3. 勾选作用域 **系统框架 (system_server)**；
4. 重启系统框架生效；
5. 打开应用界面，可根据个人喜好单独开启/关闭任一手势。

---

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 协议开源。
