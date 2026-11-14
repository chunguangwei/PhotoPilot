# AI 模型下载源与发布材料实证

## 最新轻量包更新

用户反馈原382MB候选过大后，当前轻量项改为201,547,675字节的多语CLIP组合，固定资产和许可见 [轻量候选实证](ai-compact-model-candidates.md)。四个文件已完整下载并核验SHA256。标准模型与新轻量包共五文件、两种源、两个offset合计20次匿名Range请求全部206，长度准确且十组跨源样本SHA一致。权重仍可能跳转海外CDN，国内运营商无代理网络尚待实测。`/private/tmp/photopilot-lite-source-probe.json`保留开发机原始响应记录。下方为此前SigLIP2候选的历史核验。

核验于 2026-11-14 13:21–13:24 UTC。请求通过当前开发机的网络出口发出，使用 Python urllib、匿名 HTTPS 和 Range，每个权重请求仅读取 64 字节。未注册账号、未发布资源、未重复下载完整权重。国内运营商无代理真机网络尚待验证。此文件只记录本次实证及后续材料更新输入。

## 固定资产

| 模型资产 | 固定 revision | 文件字节数 | SHA-256 |
|---|---|---|---|
| `litert-community/embeddinggemma-2-text-vision-440m-litert-lm` 的 `embeddinggemma-2-text-vision-440m.litertlm` | `538e6574db281b4fe17993a3212f10c73bd678c6` | 387710976 | `92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0` |
| `onnx-community/siglip2-base-patch16-224-ONNX` 的 `onnx/model_quantized.onnx` | `ba1f3b0843f24bc5417d38e19c37b287d719b2f4` | 378000135 | `87fb1dbb1c091ea083e71a6c6a92fb2a39dd360ef1415314d9d645ca2b22057d` |
| 同 SigLIP2 仓库的 `tokenizer.model` | 同上 | 4241003 | `61a7b147390c64585d6c3543dd6fc636906c9af3865a5548f27f31aee1d4c8e2` |

此表 SHA 来自主任务的锁定清单和 HF LFS 元数据。完整文件 SHA 由主任务下载后核验。SigLIP2 两文件合计 382241138 字节，按十进制显示约 382.2 MB。EmbeddingGemma2 显示约 387.7 MB。完整安装空间另含临时文件、索引和运行时缓存。

## 匿名与断点续传

对以下精确 URL 实测 `Range: bytes=0-63`。两种入口的权重均返回 HTTP 206、64 字节响应和匹配的 Content-Range 总长度。两入口各自同模型的首 64 字节一致。

- [EmbeddingGemma2 国际固定源](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/538e6574db281b4fe17993a3212f10c73bd678c6/embeddinggemma-2-text-vision-440m.litertlm)
- [EmbeddingGemma2 hf-mirror 固定入口](https://hf-mirror.com/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/resolve/538e6574db281b4fe17993a3212f10c73bd678c6/embeddinggemma-2-text-vision-440m.litertlm)
- [SigLIP2 国际固定源](https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx/model_quantized.onnx)
- [SigLIP2 hf-mirror 固定入口](https://hf-mirror.com/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx/model_quantized.onnx)

对上述两个 hf-mirror 权重入口追加 `Range: bytes=64-127`，实测均返回 HTTP 206 和正确的非零 Content-Range。tokenizer 的 HF/hf-mirror 两入口也实测此范围，均为 `bytes 64-127/4241003`，返回的 64 字节相同。

| 资产 | Content-Range 总长度 | 最终响应 ETag | 最终 host |
|---|---|---|---|
| EmbeddingGemma2 | 387710976 | `056306ada65a882a29fc663841472e6c35debf8b7dd904d489f9c06e8399d7f0` | `us.aws.cdn.hf.co` |
| SigLIP2 合并 ONNX | 378000135 | `334802e1c50ea088b8422ec388cbc24bd3443a913c86aadccd0ebbf05a692610` | `us.aws.cdn.hf.co` |
| SigLIP2 tokenizer | 4241003 | `fde8653f2f656fb4ab30c2a5db64ba86a916a86134355b76c3bf26a5b022b323` | `us.aws.cdn.hf.co` |

全部最终响应含 `accept-ranges: bytes`。CDN ETag 与资产 SHA-256 分别保留，安装必须用锁定 SHA 校验。每次从固定 resolve URL 获取新跳转，最终带过期签名的 URL 只用于当前请求。

hf-mirror 在本次请求中把全部大文件继续重定向至 HF 海外 CDN，最终 POP 为 `aws-ap-southeast-1`。当前证据可支持“可选镜像入口”和“本次匿名请求成功”。若产品要保证国内独立分发，需要具有相同完整文件 SHA 的国内托管资产，或先找到并验证确实在国内承载文件的镜像。自托管属于可落实的方案，实施仍需要实际资源托管和国内直连实测。当前国内独立托管源存在缺口。

## ModelScope 实证

以下两个固定转换仓库文件列表接口都返回 HTTP 404。

- [EmbeddingGemma2 转换仓库固定版本文件列表](https://modelscope.cn/api/v1/models/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/repo/files?Revision=538e6574db281b4fe17993a3212f10c73bd678c6&Recursive=true)
- [SigLIP2 ONNX 转换仓库固定版本文件列表](https://modelscope.cn/api/v1/models/onnx-community/siglip2-base-patch16-224-ONNX/repo/files?Revision=ba1f3b0843f24bc5417d38e19c37b287d719b2f4&Recursive=true)

ModelScope 的 `google/embeddinggemma-2` 仓库元数据及 `master` 文件列表返回 HTTP 200，元数据标注 Apache-2.0、多语言。资产只有原始完整模型 `model.safetensors`，1488915288 字节，SHA-256 `197a32965d4b1105faf060417baa899e193fb73cd401f42ec9295234d5553d79`，并含 tokenizer/config 等。此资产格式和体积需要额外转换与独立验证。已选 440M LiteRT-LM 资产在该文件列表中缺失。[公开文件列表](https://modelscope.cn/api/v1/models/google/embeddinggemma-2/repo/files?Revision=master&Recursive=true)

本次未找到与选定转换资产完全一致、可锁定 revision 并匿名续传的 ModelScope 源。搜索接口 `GET /api/v1/models?Name=...` 返回404，记录为接口探测失败。

## 双语与更小候选

EmbeddingGemma2 的原始模型卡说明支持 100 多种语言和跨模态共享向量空间。固定转换卡明确 270M 文本骨干加 170M 图像编码器，并给出图像分类用途。具体中英文照片分类质量仍需双端使用同一数据集验证。[原始模型卡](https://huggingface.co/google/embeddinggemma-2) · [固定转换模型卡](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/blob/538e6574db281b4fe17993a3212f10c73bd678c6/README.md)

SigLIP2 官方模型卡支持任意候选标签的零样本图像分类。论文说明多语言图文编码器，并提供 ViT-B 起的模型规模。这里的 base 完整模型含文本编码器，模型文件约378 MB，另需 tokenizer。它相对选定 EmbeddingGemma2 的下载节省约1.4%，界面应给准确体积，避免让用户误以为存在数量级差距。[官方模型卡](https://huggingface.co/google/siglip2-base-patch16-224) · [原始论文](https://arxiv.org/abs/2502.14786)

同一固定 SigLIP2 转换仓库存在更小的分离组合，HF API 已返回以下精确 LFS 数据。

| 文件 | 字节数 | SHA-256 |
|---|---|---|
| `onnx/text_model_quantized.onnx` | 283438275 | `3a0603d3a00c05a80a6ded4743c16aaac7b1e62cdcc7e362e7ce418659b96400` |
| `onnx/vision_model_q4f16.onnx` | 54630849 | `a0740d409b91ba59c273efa530b238efcf603fd968c7940bf313f071a5de27ed` |

加 tokenizer 共342310127字节，约342.3 MB。该混合量化组合需要确认移动 ONNX Runtime 对 MatMulNBits/float16 的支持、精度和投影匹配，当前尚未下载运行。单独 54.6 MB vision 文件仍需配套文本编码器才能处理任意中英文提示。完整 `model_q4f16.onnx` 为497418715字节，超过现候选。API元数据地址为[固定版本 ONNX 列表](https://huggingface.co/api/models/onnx-community/siglip2-base-patch16-224-ONNX/tree/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/onnx?expand=false)。

本次没有取得同时满足显著更小、原生中英文任意提示、可直接双端运行的另一完整模型实证。当前378MB合并版可作为较轻选项，模型加载与分类仍需运行验证。

## 模型许可材料

EmbeddingGemma2 固定转换卡 YAML 与原始 Google 模型卡都写 Apache-2.0。原始卡链接的 Google 官方许可页也为 Apache2 全文。转换和原始仓库的 `LICENSE` 路径均实测404。SigLIP2 原始模型卡标注 Apache-2.0，转换卡写明 base_model 与 ONNX 转换来源。模型出处、锁定 revision、转换格式及 Apache 全文应随 App 可查看，在自托管下载目录保留同样材料。模型许可独立适用，应用现有非商业声明需保留其第三方许可例外。[Google 官方 Apache2](https://ai.google.dev/gemma/apache_2) · [SigLIP2 转换卡](https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/blob/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/README.md)

具体更新落点为 `ios/PhotoPilot/Resources/ThirdPartyNotices.txt`、Android 对应许可证资源、`store/appstore/review/RightsRegister.md`。iOS 许可证页面在 `ios/PhotoPilot/UI/Settings/SettingsViewController.swift` 925附近从 Bundle 读取 ThirdPartyNotices。实际新增推理运行时及 tokenizer 库的许可也要按最终锁定依赖补充。

## 隐私和新版审核文案输入

`PRIVACY.md` 英中照片条款要补充本地向量、分类标签、用户文字提示的存储和删除范围。模型下载条款要列出HF、镜像、重定向CDN或最终自托管供应商，解释供应商接收连接IP、固定模型请求路径、HTTP技术信息，清楚说明本地照片和分类输入的实际处理位置。镜像/CDN日志留存期尚未获得实证。HF公开政策说明自动记录IP、会话及设备信息，现有仅天气数据流的说明需要扩展。[HF隐私政策](https://huggingface.co/privacy)

`store/appstore/review/AppPrivacyQuestionnaire.md` 及 `ios/PhotoPilot/Resources/PrivacyInfo.xcprivacy` 要按最终网络请求和供应商用途重新核对，现有 Other Diagnostic Data 已申报 App Functionality/linked=yes/tracking=no，但新的下载请求日志也可能涉及 Product Interaction 等用途。需依据实际服务记录判断。纯本地照片和本地向量处理按 Apple 本地处理规则填写。[Apple App Privacy Details](https://developer.apple.com/app-store/app-privacy-details/)

`store/appstore/review/ListingMetadata.md`、review `README.md` 和新版 What’s New 要写清AI相框、两个可选模型及真实下载体积、首次需联网下载、下载完成后的离线处理、结果需用户确认、删除模型入口和兼容设备条件。审核步骤覆盖下载安装/取消重试/切换模型/中英文分类/AI相框保存播放，提供可用的固定匿名下载源。提交前按实包按钮标签和测试结果回填。所有新增功能应在审核备注中可复现。若初次使用功能要下载资源，Apple 4.2.3(ii)要求提示下载和说明大小。[Apple审核规则](https://developer.apple.com/app-store/review/guidelines/)

推理引擎和功能实现随提交二进制提供，模型文件采用已声明的资源下载路径。Apple公开Core ML文档介绍运行时下载模型机制，可用于理解平台支持的资源交付方式。当前LiteRT-LM/ONNX具体实现的审核结果仍待实际提交后确定。[Apple模型下载文档](https://developer.apple.com/documentation/coreml/downloading-and-compiling-a-model-on-the-user-s-device)

## 可重复探测与测试

`python3 tools/ai_models/source_probe.py` 从模型清单读取 URL，仅请求每项资产的 `0–63` 和 `64–127` 范围，输出 UTC 时间、最终 host、ETag、Content-Range、采样 SHA-256 与错误列表。采样哈希用于比对入口，不替代完整文件 SHA。

2026-11-14T13:25:48Z 实跑，三资产乘两地区乘两个范围共12请求，全部 HTTP206、总长度正确、errors 为空，相同资产相同范围跨入口采样哈希一致，最终 host 均为 `us.aws.cdn.hf.co`。经主任务新增授权，已仅把这些实测 hf-mirror 固定 revision URL 写入 `contracts/ai-models.json` 的 `files[].urls.domestic`。原生打包清单同步由主任务执行。

`python3 -m unittest discover -s tests/ai -p test_model_sources.py` 先看到6个工具缺失的失败，再实现并通过6项，覆盖正确206、服务器忽略Range、错误偏移、错误总长度、截断采样、大小写头字段。`python3 -m unittest discover -s tools/tests -p 'test_*.py'` 本次52项通过。
