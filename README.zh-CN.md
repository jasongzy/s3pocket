<p align="center">
  <img src="docs/app-icon.svg" width="144" alt="图标">
</p>

<h1 align="center">S3 Pocket</h1>

[English Doc](README.md)

S3 Pocket 是一款现代化的原生 Android 对象存储客户端，可以随时随地访问 Amazon S3、Cloudflare R2 及其他兼容 S3 的对象存储服务。

## ✨ 功能

- 覆盖存储桶、目录和对象的完整文件管理体验，支持本地文件夹及 Android 系统分享上传
- 完整的对象元数据支持，包括查看、修改、上传时指定及常用模板
- 图片、文本、代码和视频预览
- 通过公开地址或临时签名链接访问和分享对象
- 管理多个 Amazon S3、Cloudflare R2 及其他 S3 兼容连接，并可查询 R2 用量指标
- 本地加密凭据及配置备份还原

## 📥 下载

通用 APK 可从 [GitHub 最新版本](https://github.com/jasongzy/s3pocket/releases/latest)下载。系统要求为 Android 7.0 或更高版本。

## 🔒 安全说明

所有连接凭据、设置及其他敏感信息均只保存在本地，App 不会以任何形式上传这些数据。

凭据通过 Android Keystore 加密。导出的配置备份包含凭据，应按敏感文件妥善保管。

此外，任何获得有效签名链接的人，都可以在链接失效前访问对应对象。
