# AGENTS.md

## 项目定义与目标
OPPO Pad Mini（ColorOS / Android 16）多 LSPosed 模块集成开发区。
核心活跃模块：
- **多指触控屏幕旋转控制 (Multi-Finger Screen Rotation Control)**：
  - 双指双击定向旋转（基于平板机身绝对物理硬件坐标系）
  - 显示底边三指横扫 90° 旋转（底边跟随手势旋转）
  - 屏幕任意位置三指三击切换旋转锁定状态
  - MIUIX 原生风格控制中心 App（支持开关控制与热重启作用域）

## 工具链与环境变量
- **JDK**: OpenJDK 17 (`/opt/homebrew/opt/openjdk@17`)
- **Android SDK**: `~/Library/Android/sdk` (`platforms;android-34`, `build-tools;34.0.0`, `platform-tools`)
- **构建命令**: `./gradlew assembleDebug`
- **部署与联调脚本**: `./deploy.sh`（一键编译、ADB 安装并输出过滤日志）

## 硬件与连接现状
- **平板**: OPPO Pad Mini（型号 `OPD2515`，系统 `Android 16 / ColorOS`，ADB 序列号 `427cc046`）
- **输入节点**:
  - 触摸屏全局事件: `/dev/input/event3` (Touchscreen)
  - 屏幕物理分辨率: 1680 x 2520

## 开发约束与准则
1. **磁盘空间约束**: 本机磁盘空间有限，构建及调试后必须保持整洁，及时执行 `./gradlew clean` 清理临时缓存。
2. **真机环境验证**: 严格使用物理真机调试，严禁使用虚拟机/模拟器。
3. **基于取证开发**: 任何 Hook 逻辑必须基于从真机拉取的 APK/JAR 逆向证据及实际日志，严禁凭空假设类名与方法签名。
4. **系统手势防干涉准则**: 任何多指手势检测必须具备严格的几何正交性与位移熔断机制，绝对不得干涉 ColorOS 竖向系统手势（三指截屏、三指分屏等）。

