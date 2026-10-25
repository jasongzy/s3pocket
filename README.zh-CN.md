<p align="center">
  <img src="docs/app-icon.svg" width="144" alt="图标">
</p>

<h1 align="center">S3 Pocket</h1>

[English](README.md) | 简体中文

S3 Pocket 是一款现代化的原生 Android 对象存储客户端，可以随时随地访问 Amazon S3、Cloudflare R2 及其他兼容 S3 的对象存储服务。

## ✨ 功能

- 覆盖存储桶、目录和对象的完整文件管理体验，支持从本地文件夹、Android 系统分享及“打开方式”上传
- 完整的对象元数据支持，包括查看、修改、多条件筛选、上传时指定及常用模板
- 图片、文本、代码和视频预览
- 按存储桶配置公开地址，或通过临时签名链接访问和分享对象
- 管理多个 Amazon S3、Cloudflare R2 及其他 S3 兼容连接，并可查询 R2 用量指标
- 本地凭据保护及配置备份还原

## 📥 下载

通用 APK 可从 [GitHub 最新版本](https://github.com/jasongzy/s3pocket/releases/latest)下载。系统要求为 Android 7.0 或更高版本。

## 🔒 安全说明

连接凭据和设置保存在本地，凭据仅用于向所配置的存储服务及其官方 API 进行身份验证。存储连接和文件地址均使用 HTTPS。

凭据通过 Android Keystore 加密。导出的配置备份包含凭据，应按敏感文件妥善保管。

此外，任何获得有效签名链接的人，都可以在链接失效前访问对应对象。
