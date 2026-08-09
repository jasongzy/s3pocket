<p align="center">
  <img src="docs/app-icon.svg" width="144" alt="icon">
</p>

<h1 align="center">S3 Pocket</h1>

[中文文档](README.zh-CN.md)

S3 Pocket is a modern, native Android client for accessing Amazon S3, Cloudflare R2, and other S3-compatible object storage services anywhere.

## ✨ Features

- Complete file-management experience across buckets, folders, and objects, including uploads from the Android share sheet
- First-class object metadata support, including inspection, editing, upload metadata, and reusable templates
- Image, text, code, and video previews
- Public URLs and temporary presigned links for access and sharing
- Multiple Amazon S3 and Cloudflare R2 connections, plus other S3-compatible services and optional R2 usage metrics
- Encrypted local credentials and configuration backup and restore

## 📥 Download

The universal APK is available from the [latest GitHub release](https://github.com/jasongzy/s3pocket/releases/latest). Android 7.0 or newer is required.

## 🔒 Security

All connection credentials, settings, and other sensitive information are stored locally. This app never uploads this data in any form.

Credentials are encrypted with Android Keystore. Exported configuration backups contain credentials and should be handled as sensitive files.

Additionally, anyone with a valid presigned URL can access the corresponding object until the link expires.
