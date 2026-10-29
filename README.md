# PoseAlarm (起床动起来)

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)

**PoseAlarm** 是一款用摄像头识别人体姿势的闹钟：闹钟响起后，必须对着镜头做完设定的动作，铃声才会停。没有贪睡按钮，全程离线运行。

---

## 核心特性

- **端侧离线姿势识别**：基于 ML Kit Pose Detection，模型随 APK 打包，不联网。
- **四种关闹钟任务**
  - 深蹲：膝盖弯曲小于 105° 再站直，算 1 个
  - 开合跳：双手过头、双脚分开再收回，算 1 个
  - 双手举高：保持指定秒数，中断超过 0.5 秒重新计时
  - T 字站姿：双臂水平侧平举，保持指定秒数
- **关不掉的响铃**：前台服务循环播放闹铃并振动，音量一分钟内渐强，系统闹钟音量不低于 70%；响铃界面拦截返回键和音量键，通知不可划掉。
- **锁屏直接亮屏**：全屏通知 + 锁屏上显示，摄像头画面上实时绘制骨架与进度。
- **多闹钟与重复日**：工作日 / 周末 / 每天 / 自选；重启、改时间、改时区后自动重新排定。

---

## 核心架构与模块

```
app/src/main/java/com/posealarm/
├── MainActivity.java      # 主界面：闹钟列表、新建 / 编辑、权限提醒、试响
├── RingActivity.java      # 响铃界面：CameraX 取帧 + ML Kit 姿势识别，完成任务才停止
├── PoseMission.java       # 动作判断与计数 / 计时 (纯 Java，有单元测试)
├── PoseOverlayView.java   # 摄像头画面上的骨架绘制
├── AlarmService.java      # 响铃前台服务：铃声、振动、音量渐强与下限
├── AlarmScheduler.java    # AlarmManager.setAlarmClock 排定闹钟
├── AlarmReceiver.java     # 到点触发响铃并排下一次
├── BootReceiver.java      # 开机 / 改时间后重新排定
├── AlarmStore.java        # 闹钟列表存储 (SharedPreferences JSON)
├── Alarm.java             # 闹钟数据模型与下次响铃时间计算
└── HazardStripeView.java  # 黄黑警示条纹
prototype-web/             # 最早的网页版原型 (MediaPipe)
```

---

## 构建

- **JDK** 17，**Android SDK** API 34，**Min SDK** 26，**Gradle** 8.7 (含 wrapper)

```bash
# 单元测试
./gradlew testDebugUnitTest

# 编译 Debug APK
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```
