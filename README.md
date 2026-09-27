<div align="center">

<img src="docs/images/app-icon.png" width="160" alt="BioPay 应用图标" />

<h1>BioPay</h1>

<p>验证指纹或面容，轻松完成微信支付</p>
<p>Pay in WeChat with your fingerprint or face</p>

[![Release](https://img.shields.io/github/v/release/kiriashi/BioPay?style=flat)](https://github.com/kiriashi/BioPay/releases)
[![Stars](https://img.shields.io/github/stars/kiriashi/BioPay?style=flat)](https://github.com/kiriashi/BioPay/stargazers)
[![Downloads](https://img.shields.io/github/downloads/kiriashi/BioPay/total?style=flat)](https://github.com/kiriashi/BioPay/releases)
[![License](https://img.shields.io/github/license/kiriashi/BioPay?style=flat)](LICENSE)
[![Android](https://img.shields.io/badge/Android-9.0%2B-green.svg?style=flat)](https://developer.android.com)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-purple.svg?style=flat)](https://github.com/LSPosed/LSPosed)
[![Telegram](https://img.shields.io/badge/Telegram-交流群-blue.svg?style=flat)](https://t.me/biopaychat)

[简体中文](README.md) | [English](README_EN.md)

</div>


## 项目简介

**BioPay** 让你在微信付款时，通过指纹或面容验证自动输入支付密码。即使微信没有为你的设备提供生物支付选项，也可以使用这套方式完成付款。

支持微信内付款和其他应用调起的微信支付，并兼容 ColorOS 等系统将面容传感器安全等级标记为 Class 1（便利级，值4095）的设备。

## 界面预览

<p align="center">
  <img src="docs/images/settings.png" width="300" alt="BioPay 设置页：选择验证方式并保存支付密码" />
</p>

## 可以做什么

| 功能 | 使用体验 |
| --- | --- |
| 指纹、面容与双选模式 | 在设置页选择适合自己的验证方式 |
| 自动输入支付密码 | 微信支付键盘出现时发起验证，通过后自动输入 |
| Class 1 面容兼容 | 部分原本无法用于支付的面容设备也能使用，无需额外兼容模块 |
| 手动输入回退 | 取消验证或遇到错误时，恢复支付键盘 |
| 音量键切换 | 验证时按音量键返回键盘；在键盘状态下按音量键重新验证 |
| 本机加密保存 | 支付密码加密保存在设备上，模块不联网 |

## 支付流程

```mermaid
flowchart TD
    A[微信支付键盘出现] --> B[系统指纹或面容验证]
    B --> C{验证结果}
    C -->|通过| D[自动输入支付密码]
    C -->|取消或错误| E[恢复支付键盘]
    E --> F[手动输入密码]
    E -->|按音量键| B
    D --> G[由微信继续处理付款]
    F --> G
```

识别未通过时，可以继续尝试。BioPay 只协助输入密码，最终支付结果以微信提示为准。

## 安装与设置

**需要：** Android 9.0 或更新版本、支持 LibXposed API 102 的 LSPosed，以及设备上已录入的指纹或面容。当前仅适配微信。

1. 从 [Releases](https://github.com/kiriashi/BioPay/releases) 下载正式版 APK 并安装。
2. 在 LSPosed 中启用 BioPay，勾选微信作用域。
3. 强制停止微信，再重新打开。
4. 进入微信“我 → 设置”，长按页面标题“设置”，打开 BioPay 设置页。
5. 输入六位微信支付密码，选择指纹、面容或双选模式，完成验证后保存。
6. 下次付款时，按系统提示验证即可。

### 仅支持 Class 1 面容的设备

在 LSPosed 中额外勾选 BioPay 的“系统框架（system）”作用域，然后**重启手机**。在 BioPay 设置页选择面容或双选模式。

启用或更新这项兼容功能后，需要重启手机；只重启微信不足以生效。普通指纹和已支持支付的面容设备，可仅勾选微信作用域使用。

这项兼容会改变系统对生物识别强度的判断，也可能影响其他应用。它不会提高面容传感器本身的防伪能力，请根据设备情况决定是否启用。

## 日常使用

- **切换验证方式：** 打开 BioPay 设置页，选择模式并验证后保存。系统最终选择的传感器也取决于设备支持情况。
- **临时手动输入：** 取消系统验证，或在验证时按音量键返回支付键盘。
- **重新验证：** 在支付键盘显示时按一次音量键。
- **关闭功能：** 在设置页关闭指纹和面容开关后保存，微信恢复普通密码支付。
- **清除密码：** 长按设置页的清除按钮，按提示完成验证后清除。

## 隐私与安全

- 支付密码加密保存在本机，模块不请求联网权限，也不会上传密码或生物信息。
- 指纹和面容由系统负责识别；BioPay 在收到验证成功后才解密并输入密码。
- 兼容面容设备的验证不等同于硬件绑定的密码解密保护。
- 源代码按 [AGPL-3.0](LICENSE) 公开，可自行审阅。

## 遇到问题

先确认 LSPosed 中模块及作用域已启用、设备已录入生物信息，并在更新后重启手机。如果微信更新后功能失效，请在 [Issues](https://github.com/kiriashi/BioPay/issues) 提供手机型号、Android、微信及 LSPosed 版本，以及具体操作和错误提示；不要提供支付密码。

也可以加入 [Telegram 交流群](https://t.me/biopaychat)。

## 开源协议与免责声明

Copyright (C) 2026 kiriashi。项目以 [GNU Affero General Public License v3.0 或更新版本](LICENSE) 开源，修改和再分发须遵守该协议。

请仅在自己有权使用和修改的设备、账号上使用，并遵守相关法律及平台规则。本项目不提供任何担保；使用产生的账号、数据或支付风险由使用者自行承担。
