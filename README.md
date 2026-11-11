# 漫画图集

在手机上粘贴一篇自由文稿，先拆成漫画分镜并在屏幕上检查，确认后再生成白底漫画图。模型只出没有字的画面。内页旁白、封面两行字和第二行红圈由程序叠上去。

版本 0.1.0。包名 `com.manhuatuji.app`。

## 使用流程

1. 新建图集，把豆包原稿或记录直接粘进去。不需要「标题：」「封面字：」「正文：」或标签。
2. 点「生成分镜」。这一步只调用设置里的文本模型，不出图。拆完后，每一镜的旁白会按顺序显示在项目页。
3. 看分镜。可以改某一镜的旁白和画面；也可以写反馈（太少、不连贯，或任意说明）再点「按反馈重新分镜」。
4. 封面两行可以后填，不填也能先看分镜。第二行有字时，出图后会画红圈。
5. 内页旁白可以是改写后的故事，不必和贴进来的原稿逐字相同。第一镜旁白为空时当作封面。看完后点「确认出图」才会生图。文稿在拆完之后又改过，需要重新生成分镜。
6. 预览成品。某一张不满意，点「重做此张」。
7. 导出 `00_封面.png`（如果出了封面）、`01.png`… 和 `文案.txt`。可以保存到相册、分享 zip，或选一个文件夹导出。

旁白是「我」在听的时候，画面里的倾听者按小满来画：同学、亲戚或朋友，不是律师。不要画律所、法庭、直播间或律师装。内页超过 18 张时会提示删减或重拆，但分镜仍然先显示出来。

一张图最多重试 2 次。内容审核拒绝时会先改写提示词再试。超过次数就停止，并告诉你是哪一张。

## 配置 API Key

打开应用里的「设置 → 文本接口」，填写 API Key、接口地址和文本模型。Key 用 Android Keystore 加密后只存在手机本机，不要写进仓库。

文本接口默认是 `https://ai.cangyuansuanli.cn`。预设的 Gemini 3.8、Gemini 4.0、GPT 5.6 只是快捷填法，这个平台上的公开名字可能不同。点「获取模型列表」会按当前地址和 Key 请求 `/v1/models`，点返回的 id 即可填入模型框，也可以自己改。请求走 OpenAI 兼容的 `/v1/chat/completions`。如果账号只通一号通道，把文本接口改成 `https://direct-api.cangyuansuanli.cn`。

「图片接口」和文本接口分开配置：接口地址、接口类型、模型、密钥、尺寸。默认地址是 `https://direct-api.cangyuansuanli.cn/v1`，默认模型是 `gpt-image-2.5`，默认尺寸是 `1024x1365`（3:4 竖图）。这些都可以改。生图 Key 要在图片接口里另填，不会沿用文本 Key。两个页都有「测试连接」：文本发一条很短的对话请求，图片按当前表单的地址、模型和尺寸出一张测试图并立刻删掉。输入框里的 Key 优先；留空则用该页已经保存的 Key，不必先点保存。正式出图用已保存的图片接口。蓬源类主机会先提交异步任务再轮询，超时不会再提交一次。提示词页仍是分镜、画面、审核改写三栏，可以保存或恢复默认。

画面里的字仍由程序后加，模型只出图。

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
