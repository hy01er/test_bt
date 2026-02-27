# BT压测工具 - 蓝牙配对压测 Android App

## 功能说明

用于自动化测试手机与蓝牙耳机的**首次配对流程**。

**测试循环：**
扫描设备 → 发现目标 → 配对(Bond) → 确认A2DP连接 → 断开 → 取消配对

**统计信息：**
- 成功次数 / 失败次数 / 总次数 / 成功率
- 失败原因分类：Page Timeout、扫描超时、配对失败、连接超时等

---

## 如何获取APK（无需安装Android Studio）

### 方法：使用 GitHub Actions 自动构建（推荐）

**步骤：**

1. **注册GitHub账号**（如已有请跳过）
   - 访问 https://github.com 注册

2. **创建仓库并上传代码**
   ```
   在 GitHub 上新建一个仓库（如 bt-stress-test），然后：
   
   a. 下载安装 Git for Windows: https://git-scm.com/download/win
   b. 在 PowerShell 中执行：
   
   cd C:\Users\HiOpsmen\Desktop\c53d\BtStressTest
   git init
   git add .
   git commit -m "Initial commit"
   git remote add origin https://github.com/你的用户名/bt-stress-test.git
   git push -u origin main
   ```

3. **等待自动构建**
   - 推送后，GitHub 会自动运行构建（约3~5分钟）
   - 访问仓库页面 → 点击 **Actions** 标签
   - 找到最新的 "Build APK" 工作流，点击进入
   - 构建成功后，在底部 **Artifacts** 区域下载 `BtStressTest-debug-apk`

4. **安装APK**
   - 解压下载的压缩包，得到 `app-debug.apk`
   - 将 APK 文件传到手机（微信传文件/数据线）
   - 手机开启 **允许安装未知来源应用**（设置→安全→未知来源）
   - 点击 APK 文件安装

---

## 使用方法

1. 打开 **BT压测工具** App
2. 授予所有蓝牙权限（弹框出现时全部允许）
3. 填写目标设备信息：
   - **设备名称**：填入耳机的蓝牙名称（支持模糊匹配，如 `JL_TWS`）  
   - **MAC地址**：可选，精确匹配（如 `AA:BB:CC:DD:EE:FF`）
   - > 名称和地址至少填一个，两者都填时只要匹配其一即触发测试
4. 填写 **测试次数**（0或留空=无限循环）
5. **将耳机开机并进入可发现状态**（通常是长按开机键）
6. 点击 **开始测试**
7. 观察日志和统计数据

---

## 注意事项

- **首次配对测试**：每轮测试会自动取消配对（从系统已配对列表移除），确保每次都是全新配对
- **耳机状态**：每轮测试后耳机可能需要重新进入可配对模式（部分耳机需手动操作）
- **Android版本**：支持 Android 6.0 及以上
- **Page Timeout**：取消配对后耳机未能及时进入可发现状态，再次扫描时可能出现此错误，属正常现象

---

## 失败原因说明

| 原因         | 说明                                                 |
| ------------ | ---------------------------------------------------- |
| Page Timeout | 配对请求发出后，设备无响应（RF链路问题或设备未就绪） |
| 扫描超时     | 20秒内未扫描到目标设备                               |
| 配对失败     | Bond流程失败（PIN错误、认证取消等）                  |
| 连接超时     | 配对成功但A2DP Profile未能在20秒内连接               |
| 断开失败     | A2DP断开命令无响应                                   |
| 取消配对失败 | removeBond调用失败或设备仍在已配对列表               |
