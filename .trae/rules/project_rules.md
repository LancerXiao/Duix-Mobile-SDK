# Duix-Mobile 项目规则

## 版本号管理

- 每次CI构建自动递增 `versionCode`（在 `.github/workflows/build-and-deploy.yml` 中的 Bump version 步骤）
- `versionName` 格式为 `主版本.次版本.versionCode`（如 `4.1.14`），其中第三位与 versionCode 相同
- 用户可通过 version.json 检查更新，versionCode 递增确保用户能识别新版本
- **不要手动修改 test/build.gradle 中的 versionCode**，CI 会自动递增

## 错误显示

- 界面上必须显示具体的错误原因，不要只显示笼统的"失败"消息
- 所有 TTS/ASR/LLM/初始化错误都应在状态栏显示具体原因
- 方便用户和开发者定位问题

## TTS 引擎

- 优先使用 Edge TTS（音质好）
- Edge TTS 失败时自动切换到 Android 原生 TTS
- 连续失败 2 次以上永久切换到 Android TTS

## 模型缓存

- 模型存储在 `getExternalFilesDir("duix")/model/` 下
- APP 覆盖安装时不会删除模型文件，无需重新下载
- 模型下载源为阿里云 ECS: `http://114.215.183.45/downloads/duix/models/`

## 部署

- APK 部署到阿里云 ECS: `http://114.215.183.45/downloads/duix/`
- 下载页面: `https://www.enlyai.com/downloads/duix/`
- version.json 供应用内更新检查使用
