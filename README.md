# TVBox-Suite

TVBox-Suite 是一个自用定制版 TVbox 项目，包含专属源管理页面、TVbox 资源整合处理脚本等周边工具，纯个人定制使用。

该项目不仅提供了基于 TVbox 的增强改进（主要是防御性编程的健壮性提升）的定制版本，还构建了一套完整的 TVBox 源管理系统，通过 `script` 和 `web` 目录的工具实现视频源的批量管理、优化处理和自动化运维。

## 项目特点

- 支持多种视频源
- 内置多种播放器（系统播放器、IJK播放器、Exo播放器）
- 支持搜索功能
- 支持历史记录
- 支持 DNS 配置
- 支持多版本构建（normal 和 python 版本）

## 版本差异

### normal 版本
- **功能**：提供基础的视频播放和搜索功能
- **体积**：体积较小，适合对应用大小有要求的场景
- **性能**：启动速度快，运行流畅
- **适用场景**：日常视频播放，不需要 Python 爬虫功能的用户

### python 版本
- **功能**：包含 normal 版本的所有功能，同时支持 Python 爬虫
- **体积**：体积较大，包含 Python 运行时环境
- **性能**：启动速度略慢，但功能更丰富
- **适用场景**：需要使用 Python 爬虫功能的用户，对功能完整性有要求的场景

## 目录结构

```
TVBox-Suite/
├── app/             # 应用主代码
├── build_apk.sh     # 构建脚本
├── gradle/          # Gradle 配置
├── quickjs/         # QuickJS 引擎集成
├── README.md        # 项目说明
├── script/          # 工具脚本目录
└── web/             # Web 资源目录
```

## 核心组件

### 构建脚本 (build_apk.sh)

`build_apk.sh` 是项目的核心构建脚本，提供了完整的编译和打包流程：

- **功能**：
  - 自动检测和配置 Java 环境
  - 清理之前的构建产物（可选）
  - 编译并构建 debug 版本的 APK
  - 自动复制构建产物到 web 目录
  - 提供友好的构建过程输出

- **构建流程**：
  1. 检查当前 Java 环境
  2. 清理之前的构建（如果指定 --clean 参数）
  3. 编译并构建 debug 版本的 APK
  4. 检查构建结果并显示生成的 APK 文件
  5. 复制 APK 文件到 web 目录

- **产物管理**：
  - `tvbox.apk`：Python 版本（arm64）
  - `tvbox-old.apk`：Python 版本（armeabi）
  - `tvbox2.apk`：Normal 版本（arm64）
  - `tvbox2-old.apk`：Normal 版本（armeabi）

- **使用方法**：
  ```bash
  # 清理并构建
  ./build_apk.sh --clean
  
  # 增量构建
  ./build_apk.sh
  ```

### Web 目录

`web` 目录是项目的重要组成部分，用于存放和管理各种资源：

- **资源管理**：
  - **APK 文件**：构建完成后，各版本的 APK 文件会被自动复制到该目录
  - **图片资源**：`pic/` 子目录存放了应用所需的图片资源
  - **配置文件**：包含 `tv.json`、`tv.m3u`、`tv.txt` 等应用配置文件

- **Web 界面**：
  - 提供了多个 HTML 文件，如 `4p.html`、`app.html`、`s.html` 等
  - 可能用于提供应用的 Web 控制界面或配置界面

- **工具和数据**：
  - 包含 `xbspider.jar` 等工具文件
  - 存储了 `input.txt` 相关文件，可能用于测试或配置

- **功能作用**：
  - 作为应用资源的集中存储目录
  - 提供 APK 文件的下载和分发功能
  - 可能用于应用的远程配置和管理
  - 支持应用的 Web 相关功能

### 工具脚本 (script 目录)

`script` 目录包含多个工具脚本，用于辅助项目的开发和维护：

- **merge-sources 子目录**：
  - **功能**：用于合并和处理视频源配置
  - **脚本**：
    - `mergeSources.py` 系列脚本：不同版本的视频源合并工具
    - `filterBadApiUrls.sh`：过滤无效的 API URL
    - `validate_sites.py`：验证站点配置的有效性
    - `update.sh`：更新脚本
  - **输出**：生成 `output_lives.json`、`output_lives.m3u`、`output_lives.txt` 等配置文件

- **random-sites 子目录**：
  - **功能**：随机生成站点配置
  - **脚本**：`randomSites.py`
  - **配置**：包含 `blacklist.txt` 和 `whitelist.txt`，用于控制站点的包含和排除

- **samples 子目录**：
  - **功能**：提供各种配置文件的示例
  - **示例**：`api.py.sample`、`crontab.sample`、`nginx.conf.sample` 等

- **功能作用**：
  - 自动化处理视频源配置，提高配置管理效率
  - 验证和过滤无效的视频源，提升应用稳定性
  - 提供配置文件示例，方便开发者参考和使用
  - 辅助项目的持续集成和自动化构建

## TVBox 源管理系统

`script` 和 `web` 目录共同组成了一个独立的 TVBox 源管理系统，提供了完整的视频源管理解决方案：

### 系统架构

- **前端管理界面**：通过 `web/s.html` 提供用户友好的管理界面
- **后端处理**：
  - `merge-sources` 脚本：处理和合并视频源
  - `random-sites` 脚本：优化站点排序
- **存储**：`web` 目录作为资源存储和服务目录

### 核心功能

1. **批量源管理**：
   - 通过 `s.html` 管理界面，支持批量输入多行视频源
   - 系统会根据无效历史自动去重后添加到输入文件

2. **源处理和优化**：
   - `mergeSources.3.0.py` 脚本将输入文件的源整理为 TVBox 所需的源文件
   - 支持频道聚合、分组管理、频道排序等功能
   - 生成多种格式的输出文件，满足不同需求

3. **站点排序优化**：
   - `randomSites.py` 提供对 TVBox 源的站点排序（白名单+随机+黑名单）
   - 解决源数量大时搜索结果很慢以及搜索结果不理想的问题

4. **自动化运维**：
   - 支持通过定时触发，根据访问情况自动执行
   - 定时任务配置可参考 `samples/crontab.sample`

5. **Web 服务集成**：
   - 通过 web 服务提供视频源访问
   - Web 服务配置可参考 `samples/nginx.conf.sample`
   - 后端 API 实现可参考 `samples/api.py.sample`

### 使用流程

1. **输入源**：通过 `s.html` 界面批量输入视频源
2. **处理源**：运行 `mergeSources.3.0.py` 脚本处理输入文件
3. **优化源**：运行 `randomSites.py` 脚本优化站点排序
4. **部署服务**：配置 web 服务提供源访问
5. **自动化维护**：设置定时任务自动执行源处理和优化

### 技术优势

- **高效管理**：批量处理和自动去重，提高源管理效率
- **智能优化**：通过白名单+随机+黑名单机制，优化搜索结果
- **易于部署**：提供完整的配置示例，方便快速部署
- **稳定可靠**：验证和过滤无效源，提升应用稳定性
- **扩展性强**：模块化设计，易于添加新功能和适配新需求

### 应用场景

- **源数量大**：当视频源数量众多时，通过排序优化提升搜索速度和质量
- **维护成本高**：通过自动化处理和定时任务，减少人工维护成本
- **源质量参差不齐**：通过验证和过滤，提升整体源质量
- **多设备使用**：通过 web 服务，为多台设备提供统一的源访问

## 核心修改

- **搜索功能优化**：通过防御性编程提升搜索功能的健壮性
- **代码结构优化**：调整项目结构，提高代码可维护性
- **构建系统优化**：简化构建配置，提高构建效率

## 配置说明

### 默认设置

应用的默认设置在 `app/src/main/java/com/github/tvbox/osc/base/App.java` 文件中定义：

```java
private void initParams() {
    putDefault(HawkConfig.HOME_REC, 2);       // Home Rec 0=豆瓣, 1=推荐, 2=历史
    putDefault(HawkConfig.PLAY_TYPE, 1);      // Player   0=系统, 1=IJK, 2=Exo
    putDefault(HawkConfig.IJK_CODEC, "硬解码");// IJK Render 软解码, 硬解码
    putDefault(HawkConfig.HOME_SHOW_SOURCE, true);  // true=Show, false=Not show
    putDefault(HawkConfig.HOME_NUM, 2);       // History Number
    putDefault(HawkConfig.DOH_URL, 2);        // DNS
    putDefault(HawkConfig.SEARCH_VIEW, 2);    // Text or Picture
}
```

## 运行环境

- **最低 Android 版本**：Android 5.0 (API 21)
- **推荐 Android 版本**：Android 7.0 及以上
- **CPU 架构**：支持 armeabi-v7a 和 arm64-v8a

## 贡献

欢迎提交 Issue 和 Pull Request 来改进项目。

## 许可证

本项目基于开源协议发布，具体协议请参考项目中的 LICENSE 文件。

## 项目地址

- **GitHub**：[https://github.com/zhiyuan411/TVBox-Suite](https://github.com/zhiyuan411/TVBox-Suite)