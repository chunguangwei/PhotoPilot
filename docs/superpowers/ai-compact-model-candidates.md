# 真正轻量的中英零样本图像分类候选

2026-11-14公开来源核验。只读Hub API、作者模型卡、配置与范围请求。完整下载仅为1.57MB投影和约3MB tokenizer小资产。大ONNX的真实推理由主任务下载后验证，本文件记录可复核资产和实现前提。

## 多语CLIP组合

首选验证 `sentence-transformers/clip-ViT-B-32-multilingual-v1` 的官方ARM64 int8文本模型，配原始OpenAI CLIP ViT-B/32的量化图像编码器。作者明确该学生文本模型与原始ViT-B/32图像空间对齐，可做自由文本多语零样本图像分类。列出的训练语言包括英语，以及zh-cn、zh-tw。输出空间为512维。模型卡许可Apache2，原始CLIP及Xenova图像转换仓库为MIT。[作者模型卡](https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/blob/58edf8cada9e398793dca955574a48cbb7f18be2/README.md) · [原始CLIP许可](https://github.com/openai/CLIP/blob/main/LICENSE) · [图像转换仓库](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/6b7c523703ae26e39be4b03774966f8571c7f523)

| 文件 | revision | 字节数 | SHA-256 |
|---|---|---|---|
| `sentence-transformers/clip-ViT-B-32-multilingual-v1` 的 `onnx/model_qint8_arm64.onnx` | `58edf8cada9e398793dca955574a48cbb7f18be2` | 135336307 | `3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7` |
| 同仓 `2_Dense/model.safetensors` | 同上 | 1572984 | `d12568dc7300970a4d3dbb49068ad16cd89b99840b74b026f8e48071e9414f74` |
| 同仓 `vocab.txt` | 同上 | 995526 | `fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c` |
| `Xenova/clip-vit-base-patch32` 的 `onnx/vision_model_q4.onnx` | `6b7c523703ae26e39be4b03774966f8571c7f523` | 63642858 | `0769eb1d2f6f68927bbfa6e5330df4c4c3c112f89cd43eae28baafa9e6bd34b4` |
| 同图像仓 `onnx/vision_model_int8.onnx`，备用方案 | 同上 | 88648877 | `0ab0c1b3ace708e539633af1744d5a95247fe4e14d3e08ff197ef82a6cb9bd93` |

使用q4图像四项合计201547675字节，约201.55MB，比387710976字节EmbeddingGemma2减少48.016%。使用int8图像合计226553694字节，约226.55MB，减少41.57%。另需很小的配置文件。若使用完整 `tokenizer.json` 1961847字节替代vocab，总体积分别202513996与227520015字节。tokenizer.json本次完整下载SHA为 `5b4e1a8171c81dfd666ae40265b9530c6e0b3d53923fe8ac493dcc84229adf81`。

Hub API响应包含固定revision、实际size和LFS SHA，地址为[文本模型API](https://huggingface.co/api/models/sentence-transformers/clip-ViT-B-32-multilingual-v1?blobs=true)与[图像模型API](https://huggingface.co/api/models/Xenova/clip-vit-base-patch32?blobs=true)。所有资产都有公开固定resolve下载地址，无需自建权重源。

### 投影与tokenizer实现前提

官方 `modules.json` 定义Transformer → mean Pooling → Dense。ARM64 ONNX对应Transformer，尾部范围读取可见DistilBERT输出层。文本需使用attention_mask做masked mean，再用Dense从768映射512，最后L2归一化。原始配置给出Dense无bias、Identity激活。[模块定义](https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/blob/58edf8cada9e398793dca955574a48cbb7f18be2/modules.json) · [池化配置](https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/blob/58edf8cada9e398793dca955574a48cbb7f18be2/1_Pooling/config.json) · [Dense配置](https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/blob/58edf8cada9e398793dca955574a48cbb7f18be2/2_Dense/config.json)

投影safetensors已完整下载并核对SHA。文件首8字节little-endian UInt64为112，接着112字节JSON header。唯一tensor是 `linear.weight`，dtype `F32`、shape `[512,768]`、data_offsets `[0,1572864]`。Float32数据从文件byte120开始，为row-major512行768列。Swift/Kotlin可以读取定长头、解析JSON并读取little-endian Float32矩阵，实现512个行点积，无需pickle、Python或新增运行时模型转换。保留公开safetensors原文件即可。

作者文本tokenizer是cased multilingual DistilBERT/BERT WordPiece。配置为 `do_lower_case=false`、`tokenize_chinese_chars=true`、`strip_accents=null`、BasicTokenizer启用，最大512tokens，需CLS/SEP与attention_mask。可用公开vocab文件实现，或采用tokenizer.json。当前SigLIP2 SentencePiece实现需要按此模型配套替换。[tokenizer配置](https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/blob/58edf8cada9e398793dca955574a48cbb7f18be2/tokenizer_config.json)

q4图像依赖移动端ONNX Runtime的MatMulNBits CPU支持，现有iOS1.20和Android1.23.2需实测实际Session加载和中英图文相似度。int8图像作为备用。两个编码器都需验证各自输入输出及图像预处理，不能直接套用当前SigLIP224归一化配置。自由文本中英能力由原始作者提供明确证据，量化精度与手机性能由本项目真实运行验证。

### 匿名下载实证

2026-11-14对text int8、Dense投影、vision q4、vision int8的HF与hf-mirror入口各请求 `Range bytes=64-127`，共8次。全部HTTP206，各自Content-Range总长度与上表完全一致，同一资产跨入口的64字节SHA一致。最终host全部为 `us.aws.cdn.hf.co`，镜像入口的境内独立承载仍缺证据。

| 资产 | 64字节采样SHA-256 | 最终ETag |
|---|---|---|
| 文本ARM64 int8 | `c3eb09f4e6158fe20c47e0c9cadaeaf86462a78b9baa7ca955880ccdf3b4af62` | `dd135bbf84312362766d722f027e2f4e7c8576002329c0e91b99f9d6be54e3f2` |
| Dense投影 | `8e1cbbbc3f91ec67fb57bb0813835ed5e02b3314877a16d007cb9e092c8cc2ad` | `af0117141167690c7a5a67c392fdcc28e3f7d6ec9a4941e6dcb9f7c4975144df` |
| 图像q4 | `423dbf137cd5510f3d9d13ffdb57c2f02dd4e754c0d27e06445b1bfedf6a5fc7` | `a7a292fd3842245208967a81b403391b97c2851f011acac3c5df3f652ddd7c46` |
| 图像int8 | `4bcdbec350a26b385e01b92736157bfbb916754a96db20f5757b8b62cdcd97da` | `1516777a46435303ad461836ab60f57e261dc1a1d3a74b0c8fece3d94ca9cb12` |

完整小资产已保存 `/private/tmp/photopilot-multiclip-model.safetensors`、`/private/tmp/photopilot-multiclip-vocab.txt`、`/private/tmp/photopilot-multiclip-tokenizer.json`，供主任务复用。大ONNX未重复下载。

## Chinese-CLIP RN50

作者官方资料给出77M参数，图像ResNet50约38M、文本RBT3约39M。固定Hubrevision为 `717ba215769231e53b9b7c6b9d329b9cc5944418`。公开实际PyTorch checkpoint `clip_cn_rn50.pt` 为308316425字节，SHA `b196ee3ee528b70be1158ab1aafb1d2f1c801ad2d9ffb3bae31b0d305f82fc88`。模型卡Apache2，训练数据约两亿中文图文对，作者用途集中中文自由文本检索和分类。官方提供ONNX转换路径，现查到的官方仓文件尚未提供现成int8手机双端资产。依据参数推算77MB int8只能作转换估算，实际序列化大小和效果要转换后确认。英语任意自由文本匹配尚缺作者明确双语评测，当前无法据此满足本项目中英要求。[官方模型卡](https://huggingface.co/OFA-Sys/chinese-clip-rn50/blob/717ba215769231e53b9b7c6b9d329b9cc5944418/README.md) · [官方规模表](https://github.com/OFA-Sys/Chinese-CLIP#模型规模--下载链接) · [官方部署说明](https://github.com/OFA-Sys/Chinese-CLIP/blob/master/deployment.md)

## MobileCLIP-S0的精确许可和语言边界

Apple原始模型仓固定revision `71aa3e13dda93115871afbd017336535ba29886c`，模型LICENSE实际5820字节，SHA `d8f5d9dfbdb60dc81b8f1720421699e7811070f0b46f6668f90253d5ac8c0b20`。该文件17–28行的License Scope明确限定科研用途，并明确把产品开发排除在该定义之外。相关短原文为“exclusively for Research Purposes”和“product development or use in any commercial product or service.”。这里针对该固定checkpoint实际提供的许可文本作判断。[Apple模型准确许可](https://huggingface.co/apple/MobileCLIP-S0/blob/71aa3e13dda93115871afbd017336535ba29886c/LICENSE)

官方代码仓固定commit `48faa0fea4b08d74188b3841771aca6ff2c92852` 的 `LICENSE_MODELS` 同样有上述许可范围。该文件5816字节，SHA `30c59d255d2b4075ba11b81ce5aa0109c28d28138d66d52f7f5d8cbba814df8b`。代码仓还有单独的软件LICENSE，Xenova转换仓的LICENSE实际是软件许可文本。选模型时需核对对应Apple checkpoint的模型许可。[固定模型许可原文](https://github.com/apple-aiml-research/ml-mobileclip/blob/48faa0fea4b08d74188b3841771aca6ff2c92852/LICENSE_MODELS)

Xenova `mobileclip_s0` 固定revision `757d59c9c6870a76a4b0306f05f5061bca15c39f`，文本int8文件42799230字节、图像int8文件11846808字节，合计54646038字节，另有tokenizer。该低体积属实，当前中文自由文本能力缺少作者证据，且对应Apple模型许可范围存在上述产品用途限制。社区五语言MobileCLIP多语文本项目列出英语、阿拉伯语、法语、俄语、西语，中文也未在其声明范围。[固定转换仓](https://huggingface.co/Xenova/mobileclip_s0/tree/757d59c9c6870a76a4b0306f05f5061bca15c39f) · [五语言社区候选](https://huggingface.co/Youme21/mobileclip-s0-multilingual-text-tflite)

## Python真实功能验证

主任务使用Python ONNX Runtime1.27.0完成q4图像＋官方int8多语文本＋masked mean＋公开Dense投影的真实推理，输出维度512，四个实际文件SHA与上表一致。结果文件为 `/private/tmp/photopilot-ai-models/multilingual-clip/real-inference.json`。

| 猫图片的提示组 | 猫分数 | 海滩分数 | 汽车分数 | 最高匹配 |
|---|---|---|---|---|
| 英语 `a photo of ...` | 0.263549 | 0.176404 | 0.205229 | 猫 |
| 中文 `…的照片` | 0.264495 | 0.184081 | 0.224032 | 猫 |
| 中文短标签 | 0.254858 | 0.179503 | 0.221912 | 猫 |

附加英语主题组宠物/日落/家庭分数为0.245807/0.186141/0.216490，最高为宠物。此单张样例验证了中英自由文本功能和投影空间连接。更多图片精度、阈值选择与移动端速度仍需项目实测。12类HF tokenizer参考文件位于 `/private/tmp/photopilot-ai-models/multilingual-clip/tokenizer-reference.json`，覆盖中英、混合文字、空白、组合重音、特殊token、emoji、控制字符和补充平面汉字。移动端ORT1.20兼容性继续单独验证。

新增文本Apache2与OpenAI图像MIT完整许可已追加至 `ai-third-party-notices.txt`。正文来自固定官方源并保留Nils Reimers2019、OpenAI2021真实版权，来源与SHA记录在 `ai-third-party-notices-evidence.md`。

## ModelScope固定同SHA国内来源补充核验

2026-11-14追加核验已找到四项完全同SHA的现有公开ModelScope资产。两仓元数据分别Apache2与MIT，均为public、可匿名访问。其独立commit与HF commit分别固定，实际文件内容保持原清单SHA。固定版本Files API再次返回Code200，并给出以下精确size与SHA。

| 用途 | ModelScope固定revision | 路径 | 字节数 | SHA-256 |
|---|---|---|---|---|
| 文本int8 | `e8721d65650e509c422d91f8e42057b751e44d8f` | `onnx/model_qint8_arm64.onnx` | 135336307 | `3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7` |
| Dense投影 | 同上 | `2_Dense/model.safetensors` | 1572984 | `d12568dc7300970a4d3dbb49068ad16cd89b99840b74b026f8e48071e9414f74` |
| 词表 | 同上 | `vocab.txt` | 995526 | `fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c` |
| 图像q4 | `cd8e8fac174dd664b6e5b584d05abb025694b49c` | `onnx/vision_model_q4.onnx` | 63642858 | `0769eb1d2f6f68927bbfa6e5330df4c4c3c112f89cd43eae28baafa9e6bd34b4` |

[文本仓固定Files API](https://modelscope.cn/api/v1/models/sentence-transformers/clip-ViT-B-32-multilingual-v1/repo/files?Revision=e8721d65650e509c422d91f8e42057b751e44d8f&Recursive=true) · [图像仓固定Files API](https://modelscope.cn/api/v1/models/Xenova/clip-vit-base-patch32/repo/files?Revision=cd8e8fac174dd664b6e5b584d05abb025694b49c&Recursive=true)

实际固定下载URL如下，已逐条请求验证。

- [ModelScope文本int8固定文件](https://modelscope.cn/models/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/e8721d65650e509c422d91f8e42057b751e44d8f/onnx/model_qint8_arm64.onnx)
- [ModelScope投影固定文件](https://modelscope.cn/models/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/e8721d65650e509c422d91f8e42057b751e44d8f/2_Dense/model.safetensors)
- [ModelScope词表固定文件](https://modelscope.cn/models/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/e8721d65650e509c422d91f8e42057b751e44d8f/vocab.txt)
- [ModelScope图像q4固定文件](https://modelscope.cn/models/Xenova/clip-vit-base-patch32/resolve/cd8e8fac174dd664b6e5b584d05abb025694b49c/onnx/vision_model_q4.onnx)

文本、Dense、图像q4分别请求 `Range bytes=0-63` 和 `bytes=64-127`。六次响应均HTTP206、Content-Length64、Content-Range为精确请求范围及清单总字节数。最终域名均为ModelScope中国区CDN `cdn-lfs-cn-1.modelscope.cn`。非零64字节sampleSHA与此前HF样本一致。响应ETag依次为 `D6234CF4B251DBFAEF48DBA247DAAA5D-53`、`3BEC7990A038B60BB7A6667B445C9978`、`5ED4629F9F16B1127A761F84ABD36613`。ETag继续仅作该下载源续传身份，最终完整内容仍按原SHA校验。大模型本次只做Range与固定索引SHA核验，没有重复完整下载。

词表有服务器特殊行为。两次Range分别返回HTTP200、Content-Length64、`Content-Range bytes 0-63/995526`与`bytes 64-127/995526`，无ETag，最终host为 `modelscope.cn`。据此只记录其整文件下载可用，未认定为支持标准206断点续传。另一次无Range完整GET返回HTTP200、995526字节，实际计算SHA为 `fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c`，完全一致。Dense也完整GET返回1572984字节，实际SHA `d12568dc7300970a4d3dbb49068ad16cd89b99840b74b026f8e48071e9414f74`一致。无ETag时重新获取约1MB词表即可。

本轮请求来自开发机当前网络，国内运营商真机直连速度和连续下载稳定性仍需实际网络验证。现有公开固定ModelScope源已找到，可先作为轻量组合的独立来源。catalog由主任务维护，此只读调研没有修改它。
