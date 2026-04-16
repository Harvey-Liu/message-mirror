# SmsObserver 诊断指南

## 问题：SmsObserver 没有收到短信通知

### 检查步骤

#### 1. 检查权限是否授予

在手机上执行以下操作：
- 打开 **设置** → **应用** → **Message Mirror** → **权限**
- 确认 **短信** 权限已授予

或者在代码中检查日志：
```
SmsObserver not registered (enabled=true, hasPermission=false)  ← 权限未授予
SmsObserver registered successfully  ← 权限已授予
```

#### 2. 检查 sms_enabled 设置

在应用内确认 SMS 功能已启用：
- 打开 Message Mirror 应用
- 确认 "SMS Enabled" 开关是打开的

查看日志确认：
```
SMS setup check: enabled=true, hasPermission=true
```

#### 3. 查看完整日志

在应用内打开 Logs 页面，查找以下关键日志：

**正常流程应该看到：**
```
AlwaysOnService onCreate
AlwaysOnService startForeground
SMS setup check: enabled=true, hasPermission=true
SmsObserver registered successfully
```

**收到短信时应该看到：**
```
SmsObserver onChange uri=content://sms/inbox
SmsObserver received: from='10086' bodyLen=45 date=1234567890
SMS mirror notify sent: from='10086' len=50
```

**如果完全没有日志，说明：**
- SmsObserver 没有注册成功
- 或者 ContentObserver 没有触发

#### 4. 手动测试权限

在 Flutter 代码中添加测试按钮：
```dart
// 测试 SMS 权限
final hasSms = await PermissionService.hasReadSms();
print('Has SMS permission: $hasSms');

// 请求权限（如果未授予）
if (!hasSms) {
  final granted = await PermissionService.requestReadSms();
  print('Permission granted: $granted');
}
```

#### 5. 检查 Android 版本

不同 Android 版本的短信数据库 URI 可能不同：
- Android 4.4+ 使用 `Telephony.Sms.Inbox.CONTENT_URI`
- 某些厂商可能使用不同的 URI

#### 6. 检查短信应用

某些第三方短信应用可能会阻止 ContentObserver：
- Google Messages
- Samsung Messages
- 小米短信
- 华为短信

尝试使用系统默认短信应用测试。

### 常见问题

#### Q1: 权限已授予但仍然没有日志
**解决方案：**
1. 重启应用（完全关闭后重新打开）
2. 重启手机
3. 检查是否有多个 SmsObserver 实例冲突

#### Q2: onChange 被调用但查询失败
**解决方案：**
查看日志是否有：
```
SmsObserver query returned null cursor
```
这可能是因为：
- 短信数据库正在被其他应用锁定
- 权限实际上未授予（运行时权限问题）

#### Q3: 重复收到同一条短信
**解决方案：**
代码已添加 `lastProcessedDate` 去重机制，如果仍有问题，检查日志中的 `date` 值。

### 调试命令

在 Android Studio 或 adb 中查看实时日志：
```bash
adb logcat | grep -i "smsobserver\|smsonserver"
```

或者查看应用的 LogStore：
- 打开 Message Mirror 应用
- 进入 Logs 页面
- 搜索 "SmsObserver"

### 修复后的改进

本次修复包含：
1. ? 添加了 `onChange(selfChange: Boolean)` 兼容旧版本 Android
2. ? 添加了详细的注册日志
3. ? 添加了查询结果检查
4. ? 添加了去重机制（lastProcessedDate）
5. ? 改进了排序方式（按日期倒序）

### 下一步

如果以上步骤都无法解决问题，请提供：
1. 完整的 Logs 页面内容
2. Android 版本号
3. 使用的短信应用名称
4. 是否在锁屏状态下测试
