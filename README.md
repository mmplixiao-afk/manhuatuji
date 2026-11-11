# 漫画图集

在手机上粘贴一篇中文文案，先生成分镜并确认，再生成一套白底漫画图。模型只出没有字的画面，标题、内页旁白和封面第二行的红圈都由程序叠上去。

版本 0.1.0。包名 `com.manhuatuji.app`。

## 使用流程

1. 新建图集，按下面的格式粘贴文案。
2. 点「生成分镜」。这一步只调用文本模型，不出图。
3. 检查人物外貌、旁白和画面，可以改，也可以删掉多余的内页。
4. 点「确认出图」之后才会开始生图。
5. 预览成品。某一张不满意，点「重做此张」。
6. 导出 `00_封面.png`、`01.png`… 和 `文案.txt`。可以保存到相册、分享 zip，或选一个文件夹导出。

```
标题：不超过20个字

封面字（第二行红圈）：
第一行
第二行

正文：
冲突和结局。
一句观点。
换你是她，你会怎么选？

#真实故事 #婚姻
```

标题不超过 20 个字。封面正好两行，红圈只圈第二行。正文最后是一句提问。标签单独一行，标签之间用空格分开，不要写 `#小满说`。

分镜会先引出叙述者小满。她是同学、亲戚或朋友这样的倾听者。人物外貌在分镜里锁定，之后每张图都带同一段描述。第一页会作为后面画面的角色参考；如果接口不接受参考图，会自动去掉参考图再试，外貌描述仍然保留。

一张图最多重试 2 次。内容审核拒绝时会先改写提示词再试。超过次数就停止，并告诉你是哪一张。内页最多 18 张。

## 配置 API Key

打开应用里的「设置」，填写 API Key。Key 用 Android Keystore 加密后只存在手机本机，不要写进仓库。

文本接口默认是 `https://ai.cangyuansuanli.cn`，模型默认 `gemini-3.8-flash-high`，都是 OpenAI 兼容的 `/v1/chat/completions`。如果账号只通一号通道，把文本接口改成 `https://direct-api.cangyuansuanli.cn`。

生图不能改，固定走一号通道：

- 主机：`direct-api.cangyuansuanli.cn`
- 模型：`gpt-image-2.5`
- 尺寸：`1024x1365`（3:4）
- 白底。画面里不写字。

## 构建

需要 JDK 17 或 21、Android SDK 35、Gradle 8.11.1。用 Android Studio 打开本目录，或在命令行执行：

```bash
./gradlew :core:test :app:assembleDebug
```

调试包在 `app/build/outputs/apk/debug/`。`:core:test` 覆盖文案解析、分镜校验、叠字布局和生图请求。

封面和内页叠字使用霞鹜文楷（LXGW WenKai）Medium / Light，许可证是 SIL Open Font License 1.1，字体文件和 `OFL.txt` 在 `app/src/main/assets/fonts/`。不依赖手机里有没有微软雅黑。

## GitHub Actions

推送和 Pull Request 会跑单元测试，并编一个未签名的 debug APK。

签名的正式包只在手动运行 workflow（`workflow_dispatch`）时构建。这个仓库一开始没有签名密钥，不要把 keystore 提交进 git。到仓库 Settings → Secrets and variables → Actions，从 [anzhuotuwen](https://github.com/mmplixiao-afk/anzhuotuwen) 复制同名 Secrets：

- `KEYSTORE_BASE64`：keystore 文件的 base64
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

正式包任务会把 base64 解成 `app/keystore.jks`（已被 gitignore），再执行 `assembleRelease`。

## 还没做

夸克网盘上传和邮件通知。导出目前是相册、系统分享 zip，以及用户选择的文件夹。
