# 加密出口问卷 / Export compliance worksheet — 1.1.58

2026-10-21本地准备；未登录ASC、未上传文件、未认领出口豁免或批准号。版本改动/最终Distribution签名后须复核。开源、免费、不做广告、使用HTTPS，均不能单独推出“没有加密”或“无需任何文件”。

## 1. 二进制与源码事实

本次实际读取`.build/appstore-audit/PhotoPilot-v1.1.58-final-unsigned.xcarchive/Products/Applications/PhotoPilot.app`：`dev.photopilot.scaffold`，1.1.58(58)；`ITSAppUsesNonExemptEncryption`和`ITSEncryptionExportComplianceCode`均缺席。它是未签名Release归档，不是最终提交IPA。

| 文件（相对上述App） | SHA-256 |
|---|---|
| `PhotoPilot` | `5df803e0a29594dabfa258e97ebd8ed0a0764ed6bd86813dc812f09abf9ca9fc` |
| `Frameworks/AMSMB2.framework/AMSMB2` | `9b3b58966bc7ef382ab9e4f82cbba6b412ae7ca1353009a3854e06d0a43a0f2f` |
| `Info.plist` | `fcf87ade80ac5932052df3817e23103c1b90fdd445f4b0ce49f6d83cac0f98d1` |
| `PrivacyInfo.xcprivacy` | `f902828ec87bfac8258edba796fd94bb50d37236b4e5394ef3a49a02817ef0c0` |

确切库源码归档`.build/appstore-audit/AMSMB2-4.0.3-source.tar.gz` SHA-256=`0b4bd6046f2f06d79cd82a36f967f11b85b8e39aade627e1d6f5504ddd2e5077`；AMSMB2 `1726aaaf7adf63d7d1d2a0c5d1b0e635028215c0`、libsmb2 `aff9fa6ba9f41cfd3c15d184554601ec3f6d8d03`。公开源和218文件manifest见[SMBDistribution](SMBDistribution.md)。以下库路径均相对tar内`AMSMB2-4.0.3/Dependencies/libsmb2/`，不是任取最新上游。

| 功能 | 事实证据与边界 |
|---|---|
| HTTPS/WebDAV TLS、Keychain、CryptoKit | 系统提供的密码学；不能代表整个包仅有系统加密 |
| SMB传输保密 | `ios/PhotoPilot/Domain/SourceAdapter/SmbSourceAdapter.swift:169–175`优先`encrypted:true`；`lib/smb3-seal.c:71–116`处理SMB3 AES128-CCM |
| AES primitive | `lib/aes.c:21–32`在Apple平台调用`AES128_ECB_encrypt_apple`，其他平台调用reference；`lib/aes_apple.c:30–64`使用CommonCrypto。不能因reference符号存在就断言iOS实际执行reference AES |
| 库内模式/签名实现 | `lib/aes128ccm.c:75–132`组装CCM；`lib/smb2-signing.c:123–163,199–205`实现CMAC/HMAC路径；并非把全部SMB密码学交给Apple系统 |
| NTLM认证/密钥派生 | `lib/ntlmssp.c:486–495,551,687–718,1300–1308`调用库内MD4和HMAC-MD5；不等于只有密码保护、没有文件数据加密 |
| 实际嵌入框架nm | `_AES128_ECB_encrypt_apple`、`_AES128_ECB_encrypt_reference`、`_CCCryptorCreate/Update/Release`、`_MD4*`、`_MD5*`、`_SHA256*`、`_SHA384*`、`_SHA512*`、`_hmac*`、`_smb3_aes_cmac_128`、`_smb3_encrypt_pdu`均存在。符号证明包含实现/引用，不证明每次连接执行所有算法 |

## 2. 逐项回答与后台分支

ASC问法随入口/构建状态可能变化；以下按问题语义对照，不谎称已见账号内完整屏幕。

| 中文字段 / English field | 应答稿 / prepared answer |
|---|---|
| 是否使用、包含、访问或实现加密？ / Does your app use, contain, access or implement encryption? | 是 / Yes；包括系统加密和SMB库 |
| 专有或非标准、未被国际标准机构接受的算法 / Proprietary or non-standard algorithms | 当前拟选否 / No；没有发现自创密码算法。Microsoft已公开NTLM/SMB规范；Apple定义也考虑协议是否已公开，不能只因由Microsoft设计就自动判为非标准。仍以真实后台问题定义核对，不把MIT/LGPL或公开源码本身当标准认证 |
| 标准算法，替代或补充Apple操作系统加密 / Standard encryption algorithms instead of, or in addition to, Apple operating-system encryption | 勾选 / Yes；库内CCM/CMAC/哈希/NTLM密钥派生与实际嵌入代码证据见§1 |
| 两者都有 / Both | 当前不选；只有实际新增或Apple个案确认存在non-standard功能时，才转此分支并补CCATS流程 |
| 以上均无 / None of the algorithms mentioned above | 不选；不能按仅HTTPS填此项 |
| 加密是否仅限Apple操作系统内置？ / Limited to encryption within Apple OS? | 否 / No |
| 是否只用于认证、数字签名或密码保护？ / Limited to authentication, digital signatures or password protection? | 否 / No；SMB传输保密保护照片文件数据 |
| 是否适用其他出口豁免？ / Does an exemption apply? | 不能仅凭代码定案；如出现选项，按真实出口主体、具体条款及产品用途判断。不要在未核定前声明豁免或填`ITSAppUsesNonExemptEncryption=false` |
| 是否在法国分发？ / Available in France? | 计划包含法国时填是 / Yes；只在实际地区方案明确移除法国时填否 / No，不能仅为免文件而与可用地区矛盾 |
| CCATS / US classification document | 当前标准算法分支按Apple表不自动要求CCATS；不填写虚构编号。若转专有/非标准分支或真实出口分类需要，则由主体向BIS办理/取得适用文件 |
| 法国加密声明 / French encryption declaration | 包含法国且维持额外标准算法分支时，按Apple官方表需要上传；现无声明/批准号，不填“已完成”。有确切例外结论时保留依据并向Apple核实如何提交 |
| 合规代码 / App Encryption Export Compliance Code | 留待Apple审核文档后返回；不是自定字符串 |

Apple的现行文件表：仅Apple OS密码学不要求ASC文档；额外业界标准算法在法国分发时要求法国声明；专有算法分支要求美国CCATS，并在法国分发时要求法国声明。**是否需要ASC文件**与美国出口分类、许可例外/报告义务不是同一问题。没有法国地区也不等于不受美国出口规则影响。

实际后台出现本稿没有的问题时，记录原文和选择项再判断，不靠旧问卷截图猜题。最终文件应在App Review或TestFlight App Review前完成；Apple要求先具备准确App描述和可用地区才可判断文档充分性。

## 3. 可供Apple出口支持核对的技术说明（按需使用，没有发送）

**中文：** PhotoPilot 1.1.58(58)是本地照片导入和幻灯播放应用。它使用Apple系统HTTPS/TLS、Keychain和CryptoKit，并动态链接AMSMB2 4.0.3，内含上述固定提交的libsmb2。SMB读取连接优先要求SMB3加密；库在Apple上使用CommonCrypto执行AES-128块原语，在库中实现CCM/CMAC及NTLM认证所需MD4/HMAC-MD5和SHA/HMAC代码。归档还包含reference AES符号，但Apple编译路径调用CommonCrypto。未发现自创算法；NTLM/SMB已有Microsoft公开规范，当前拟选额外标准算法。如贵方需要个案核对，请确认本归类和计划法国分发时所需文档。

**English:** PhotoPilot 1.1.58 (58) imports and displays photos locally. It uses Apple-provided HTTPS/TLS, Keychain and CryptoKit and dynamically links AMSMB2 4.0.3 with libsmb2 at the revisions above. SMB read connections first require encryption. On Apple platforms the library calls CommonCrypto for the AES-128 block primitive and implements CCM/CMAC and NTLM-related MD4/HMAC-MD5 and SHA/HMAC code in the library. Reference AES symbols are also present, but the Apple dispatch path calls CommonCrypto. We found no custom cryptographic algorithm. Microsoft publishes the NTLM and SMB protocol specifications. Our prepared selection is additional standard algorithms. If case-specific clarification is needed, please confirm this classification and the documentation required for intended availability in France.

这封说明只有在得到另行发送授权后才发送，不含审核凭证、用户照片或NAS地址。

## 4. 需由真实主体补齐的最小内容

1. 出口/申报主体法定姓名或名称、所在地、地址、联系方式及有权签署人；不能从开发机用户名推定。
2. 法国是否首发：保留法国则办理/取得适用声明材料；如暂缓，需地区方案明确批准，其他非大陆地区不随之删去。
3. 最终申报主体确认按实际问题采用额外标准算法分支；若后台/Apple个案要求其他分类，则保留答复并补适用文件。如需CCATS，补真实文件/编号，不默认“免费开源”自动豁免。
4. 最终Distribution包和匹配源/库/配置哈希，以及ASC根据准确回答给出的文档要求/批准代码。当前unsigned哈希不能冒充最终签名包哈希。

官方来源（2026-10-21核验）：[Apple overview](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance/)、[Apple documentation matrix](https://developer.apple.com/help/app-store-connect/reference/app-information/export-compliance-documentation-for-encryption/)、[Apple determine/upload workflow](https://developer.apple.com/help/app-store-connect/manage-app-information/determine-and-upload-app-encryption-documentation/)、[BIS encryption controls](https://www.bis.gov/learn-support/encryption-controls)、[BIS data-confidentiality boundary](https://www.bis.gov/learn-support/encryption-controls/cryptography-for-data-confidentiality)、[ANSSI procedures](https://cyber.gouv.fr/reglementation/reglementation-identite-confiance-numerique/controles-reglementaires-cryptographie/controle-moyen-de-cryptologie/controle-rglementaire-cryptographie-demarches/)。没有取得法务签字或保证Apple接受。

协议公开性依据：[Microsoft MS-NLMP](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-nlmp/)与[MS-SMB2](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-smb2/)已联网核验；不能把商用协议名称直接等同自创/未公开密码学。
