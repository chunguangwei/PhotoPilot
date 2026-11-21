# AI 第三方许可增量实证

2026-11-14。输出 `docs/superpowers/ai-third-party-notices.txt` 共2878133字节，SHA-256 `edc8e4cf9fc789df613fc002bca7531de93033745a297e80f7359566a146f4b3`。15段原始正文逐字保留，通过 `original_bytes in output_bytes` 检查，外围新增来源标题。可由主任务原样追加到双端许可文本。现有根 LICENSE、平台许可资源和生产代码由本任务保持原状。

## 2026-11-21 更正：iOS ONNX Runtime 1.20.0 → 本地 vendored 1.30.0

上游 `microsoft/onnxruntime` 与 `microsoft/onnxruntime-swift-package-manager` 两仓库已于 2026 年前后从 GitHub 删除（HTTP 404），SPM 依赖 1.20.0 无法再解析，更新期 `make ios-build` 因此失败。本轮将官方 CocoaPods `onnxruntime-c`/`onnxruntime-objc` 1.30.0（本地真实产物）本地化 vendoring 为 `ios/Packages/ONNXRuntime`（UPSTREAM.txt 记录出处；Package.swift 还原被删 SPM 包的产品形态 `onnxruntime`→模块 `OnnxRuntimeBindings`）。该包的 LICENSE 为 MIT、版权 Microsoft Corporation，未随 1.30.0 变更。App 的 `ios/PhotoPilot/Resources/AIThirdPartyNotices.txt` 已追加 vendored 1.30.0 artifact 的 MIT 全文。文档内保留的官方 v1.20.0/v1.23.2 ThirdPartyNotices 汇总仍适用于 1.30.0 二进制的大部分依赖族；1.30.0 专属汇总通知因上游仓库删除暂缺，若后续取得需补入。

## 精确版本与来源

- Android最终依赖声明为 `com.google.ai.edge.litertlm:litertlm-android:0.17.1`。实际缓存AAR为 `/Users/weichunguang/.gradle/caches/modules-2/files-2.1/com.google.ai.edge.litertlm/litertlm-android/0.17.1/9c8ab948b4a010ccd783dfbf82affc541246d3e6/litertlm-android-0.17.1.aar`。从中直接提取 LICENSE 和 THIRD_PARTY_NOTICE.txt。LICENSE与官方下载的v0.17.1原文逐字相同。官方tag树SHA为 `5e58e9a0aef7abf7091207a8b1d1063a1c800f08`。AAR的2146747字节第三方NOTICE完整收入增量，含Abseil、Gson、Kotlin、protobuf、sentencepiece等原始许可/版权。[官方准确版本](https://github.com/google-ai-edge/LiteRT-LM/tree/v0.17.1) · [官方准确LICENSE](https://raw.githubusercontent.com/google-ai-edge/LiteRT-LM/v0.17.1/LICENSE)
- LiteRT-LM准确POM声明Gson2.14.0、kotlin-reflect2.4.0、kotlinx-coroutines-android1.11.0。POM就在同版本Gradle缓存下。完整AAR NOTICE包含Gson和Kotlin段落。最终发布解析树若有版本替换，要以其实际JAR通知进一步核对。
- iOS本地 `ios/Packages/MediaPipeTasks/Package.swift`固定Google1.2.3 XCFramework URL及SHA。完整复制该包LICENSE，包括末尾Lucent2002版权与许可段。实际MPPUniversalEmbedder.h第一行原文为 `// Copyright 2026 The MediaPipe Authors. All Rights Reserved.`，其完整版权/许可头也已收入增量。
- iOS SentencePieceKit锁定 `c0332fa8adda78fc141eed51128a03fd5326b8a0`。完整复制其LICENSE，含 `Copyright 2024 SentencePieceKit Contributors` 以及包实际提供的Apache条款文本。XCFramework里processor/trainer原始头分别为Google2016/2018版权，两个完整许可头保留。[锁定包装器来源](https://github.com/spencer0124/SentencePieceKit/tree/c0332fa8adda78fc141eed51128a03fd5326b8a0)
- iOS ONNX Runtime SPM 1.20.0锁定 `12ce7374c86944e1f68f3a866d10105d8357f074`，Package.swift下载onnxruntime-c-1.20.0。实际artifact LICENSE为MIT、版权Microsoft Corporation。Android实际AAR1.23.2无单独LICENSE/NOTICE条目。增量保留iOS artifact原始MIT全文，另分别完整收入官方v1.20.0和v1.23.2的ThirdPartyNotices。两个文件内包含的原始平台/许可条件均保留。[1.20.0 notices](https://raw.githubusercontent.com/microsoft/onnxruntime/v1.20.0/ThirdPartyNotices.txt) · [1.23.2 notices](https://raw.githubusercontent.com/microsoft/onnxruntime/v1.23.2/ThirdPartyNotices.txt) · [1.23.2 MIT](https://raw.githubusercontent.com/microsoft/onnxruntime/v1.23.2/LICENSE)
- EmbeddingGemma2原始和固定转换模型卡均声明 `license: apache-2.0`。SigLIP2原始卡声明Apache2，固定ONNX转换卡指向原始Google模型。增量保留模型卡许可声明、转换卡出处和Apache完整文本。四个本次抓取模型卡均未包含独立Copyright行，增量采用其真实声明及来源署名。[EmbeddingGemma2](https://huggingface.co/google/embeddinggemma-2) · [固定转换](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/tree/538e6574db281b4fe17993a3212f10c73bd678c6) · [SigLIP2](https://huggingface.co/google/siglip2-base-patch16-224) · [固定ONNX转换](https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/tree/ba1f3b0843f24bc5417d38e19c37b287d719b2f4)

## 实际SDK可证实的待补项

1. SentencePieceKit实际 `sentencepiece.xcframework/ios-arm64/libsentencepiece.a` 经 `nm -gU`可见 `google::protobuf` 和 `absl::logging`定义。SDK目录只附包装器LICENSE和头文件，缺少上游SentencePiece构建revision、Protobuf/Abseil等静态传递库的准确版本与NOTICE汇总。其build脚本读取相邻 `../sentencepiece` 未锁定revision。增量里的LiteRT-LM完整NOTICE包含这些库族许可，但该事实尚无法证明iOS这个二进制与对应版本的完整通知匹配。应补供应商该预编译SDK的准确构建清单/第三方通知，或使用来源锁定的重新构建成果。
2. MediaPipeTasksCommon1.2.3实际framework经 `nm -gU`可见 `_MPP__ZN4absl...`定义。已解析的MediaPipe artifact目录未找到独立NOTICE/LICENSE/acknowledgements，只有本地包装包的LICENSE（含Lucent）。该本地LICENSE已完整附上，框架静态传递依赖的准确1.2.3第三方通知仍需供应商对应版本汇总。LiteRT-LM AAR第三方清单和MediaPipe二进制依赖闭包的等价关系尚未取得实证。
3. ONNX Runtime准确版本的官方ThirdPartyNotices已完整加入，补上了当前iOS artifact和Android AAR中缺少汇总通知的交付缺口。SPM还解析了onnxruntime_extensions0.13.0的artifact，但当前App产品依赖选择OnnxRuntimeBindings。后续若实际链接扩展产品，应补其准确版本独立第三方通知。

以上记录说明供应商静态构建清单的可核验粒度。当前交付保留已取得的完整版权与许可正文。上游若提供更精确的构建清单，再按实际内容补充对应通知。

## 正文原始字节校验

| 原始正文 | 字节数 | SHA-256 |
|---|---|---|
| LiteRT-LM 0.17.1 LICENSE | 11357 | `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4` |
| LiteRT-LM 0.17.1 THIRD_PARTY_NOTICE.txt | 2146747 | `67d807a83a6e4f9457365ca61ff3fa1db95b135a17feeefadeff8e9f02123243` |
| MediaPipeTasks iOS LICENSE | 12331 | `8707eef0533987efc5b155d64761eeb6e20793f50b9bd1a68dad1cf4719d0ed8` |
| SentencePieceKit LICENSE | 10274 | `9a440d4080fbf6ceb53c1fb7120dcb1cdcf52cd9c4ba9e798cbbaee83c35463e` |
| ONNX Runtime artifact LICENSE | 1073 | `2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c` |
| ONNX Runtime v1.20.0 ThirdPartyNotices.txt | 338538 | `cf7342f7ba482ef715ae58f5f497a8d3564fa255164175aea324cd293c5701a0` |
| ONNX Runtime v1.23.2 ThirdPartyNotices.txt | 326866 | `e9e90971a8e75a9a8ac0c6412e29c1202d079998389915aa485f46c816c3b4cc` |
| Google EmbeddingGemma2 upstream model card | 22234 | `2c058c304c68221f1de80979594036eff3972b2bce686bb7b0f43621a6b0b011` |
| EmbeddingGemma2 fixed conversion card | 12659 | `ff1b749caff4561c5ab1dc2ff037d78c24597db4f29e519ce4c94f0ce75899ae` |
| Google SigLIP2 upstream model card | 3375 | `39ac3705d62af9ffa1a14675b8ccb220a75f2d81acd530e564a3b1e3dfe418d8` |
| SigLIP2 fixed conversion card | 555 | `399820e83f2cb2fbca17a6bc76949aacf9ee1caf99e55e6f7f744b24cd23764a` |

## iOS selective category linkage

The local MediaPipe wrapper retains the official1.2.3 binary URLs and checksums. Its package definition now selects the retrieval path. Three original Objective-C conversion-category objects are extracted from the pinned Common archives into a small linkage artifact with a local no-op anchor. Blanket `-all_load` and `-ObjC` flags are removed. `ios/Packages/MediaPipeTasks/UPSTREAM.txt`, `rebuild_categories.py` and `categories-provenance.json` record input archives, original member hashes, output hashes and deterministic rebuilding. The original Google SDK archives remain unchanged. Their Apache license and copyright are included with the local package and app notices.

Real iOS simulator inference on both models passed again after selective linkage. The inspected running-code image contained none of the three checked recording identifiers, `MPPAudioRecord`, `installTapOnBus` and `requestRecordPermission`. Final Release verification is recorded separately in the execution ledger.

## 新轻量多语CLIP许可增量

2026-11-14追加两份完整原文，旧增量的2864874字节保持为新文件精确前缀。Sentence Transformers官方LICENSE固定commit `688fd663e7b242f41f3f62296d08c69937efeb00`，11338字节，SHA-256 `624b030fd62b8b989dd502d5bb6322cbc0dbb524ab9c9708f32efe33d7275735`，完整Apache2原文含真实 `Copyright 2019 Nils Reimers`。轻量文本模型固定模型卡明确Apache2，官方Hub该revision的单独LICENSE路径实测404，因此提供作者项目完整许可作为正文，模型声明继续以锁定模型卡为据。[固定官方LICENSE](https://github.com/UKPLab/sentence-transformers/blob/688fd663e7b242f41f3f62296d08c69937efeb00/LICENSE) · [固定文本模型卡](https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/blob/58edf8cada9e398793dca955574a48cbb7f18be2/README.md)

OpenAI CLIP官方MIT全文固定commit `d05afc436d78f1c48dc0dbf8e5980a9d471f35f6`，1064字节，SHA-256 `987e63b32f6c89ff5160e429458a872ff048e6860b590a3912e938f9da8f14db`，真实版权为 `Copyright (c) 2021 OpenAI`。图像转换Hub `Xenova/clip-vit-base-patch32` 固定revision `6b7c523703ae26e39be4b03774966f8571c7f523`元数据MIT，其单独LICENSE路径实测404。增量中保留OpenAI原始许可和转换出处。[固定OpenAI MIT](https://github.com/openai/CLIP/blob/d05afc436d78f1c48dc0dbf8e5980a9d471f35f6/LICENSE) · [固定图像转换仓库](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/6b7c523703ae26e39be4b03774966f8571c7f523)

Python ONNX Runtime1.27.0已真实加载此次int8文本、q4图像、safetensors投影及词表，结果512维。主任务的 `/private/tmp/photopilot-ai-models/multilingual-clip/real-inference.json`包含全部四资产SHA与中英文猫/海滩/汽车相似度，三组对应提示均把猫排第一。`tokenizer-reference.json`保存12类HF tokenizer参考输入输出。当前记录支持Python功能链路，移动端ORT1.20的MatMulNBits加载与真图推理仍待单独实测。详见 `ai-compact-model-candidates.md`。
