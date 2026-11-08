# 听本 · 仓库规则（给 AI Agent）

> 新会话第一件事：按下面的顺序读，不要先翻历史，不要先读归档。

## 阅读顺序

1. `aoci.txt` → `aoci.meta.txt` → `aoci.code.txt`：仓库认知地图，逐文件记着职责、强关系、对外契约和踩过的坑。**别手翻全工程**，先查：

   ```
   python3 scripts/aoci_check.py --lookup <要动的模块或关键字>
   ```

   查不到命中，说明索引该维护了；维护完再打开源码，不要直接把整个仓库重扫一遍。
2. `docs/00_现行契约.md`：现在成立的硬约束，只写现状、不写历史。
3. `STATUS.md`（现状）→ `ROADMAP.md`（排期）→ `version.txt`（版本唯一真值源）。
4. 要改哪块，再打开对应源码。改完回到第 1 步核一遍认知是否还对齐。

## 噪声边界（不读、不作认知证据）

`dev/`、`reference/`、`samples/`、`contracts/`、`真机截图/`、顶层 `R*_落地报告.md`
与 `本地改码批次*.md`、以及 `scripts/` 下未被现行清单登记的历史验证脚本，都是历史回执。
它们不参与构建、不参与现行自检，也不进 AOCI 受管范围。

**例外**：`scripts/r135_verify.py` 里 `CURRENT_VERIFIERS` / `QUICK` 登记的守门必须跑。

## 硬口径

- `version.txt` 是版本唯一真值源；**每打一次包升一个小版本**，纯改代码或纯文档轮不升。
- 教程、箭头流程、图文步骤、长解释**不进 App 界面**（用户硬口径）。删除确认里写清后果不算教程。
- 真机证据不足，不写「好用」「体验没问题」。静态自检全绿 ≠ 编译过 ≠ 手机上好用。
- 删除独立页之前，先把它的能力搬干净；破坏性操作先预览再执行。
- 宿主 MCP 配置（`.mcp.json`、`opencode.json`、`.codex/`、`.claude/`）含本机绝对路径，**不提交**。
- 密钥、安装包、模型权重、令牌不进仓库，也不进沙盒产物目录。

## AOCI 适配说明（如实）

本仓采用 **AOCI-CODE v0.1.1 公开协议级适配**：`cognition-volumes/v1` + `object-fras-v2`。
官方 CLI 二进制在构建环境里拿不到 Release 资产（CDN 被出网策略拦），且无 Go 1.26 工具链，
所以治理由 `scripts/aoci_check.py` 等价实现，**不要宣称跑过 `aoci verify` / `aoci check`**。
官方源码包、协议文本与中文模板放在沙盒 `tools/aoci-code/`（不进本仓）。

语义一律由模型基于源码证据创作，程序只做结构、覆盖与漂移治理；禁止从路径、文件名、
AST、符号表或模板反推 F/R/A/S。装了官方 `aoci` 的机器上可直接沿用同一套资产：

```
aoci --repo . init --locale zh-CN --agent <宿主>
aoci --repo . scan
aoci --repo . verify
aoci --repo . check
```

`.aoci/config.json` 与 `.aoci/baseline.json` 按官方结构写，但本仓**提交**它们
（官方通常不提交），目的是让 CI 跨机器验证「代码改了、索引没跟着维护」的漂移。
改完受管文件后运行：

```
python3 scripts/aoci_check.py --refresh
python3 scripts/aoci_check.py --selftest
```

## 收口清单

- 改了受管文件 → 刷新基线，并让受影响 Entry 的 `S` / `F` 反映新事实
- 收口前用 `--lookup` 复查本轮主题：查不到就补索引，`--refresh` 后基线才不会假红
- 新增守门脚本 → 登记进 `scripts/r135_verify.py` 的现行清单与 CI
- 升版本 → `version.txt`、Gradle、README / STATUS / ROADMAP、Release 标签一次对齐
- 发版 → 真编译、签名证书指纹、7 个 arm64 `.so`、包内无开发者资料，缺一不发

<!-- aoci:begin -->
## AOCI Repository Cognition

AOCI maintains a stable, versioned, incrementally updatable repository-level cognition layer so models can reuse their understanding of this system across tasks and sessions. `aoci.txt` is the Root Manifest; `aoci.meta.txt` holds the tag dictionary and FRAS discipline; `aoci.code.txt` assigns one independent Entry to every managed object. Together they form the Whole-Index: a high-density view of responsibilities, strong relationships, public contracts, and non-obvious constraints.

At the start of every new Agent Run, read `aoci.txt`, then `aoci.meta.txt`, then `aoci.code.txt` before opening source. Reuse that cognition while it remains reliable; after host context compaction, treat prior model cognition as unreliable and reload the Whole-Index from these assets. Do not substitute historical memory, `dev/` archives, or landing reports for current repository-bound cognition.

Entry semantics must come from the model's understanding of actual evidence. Never derive, prefill, assemble, or rewrite index semantics solely from paths, filenames, extensions, an AST, symbol lists, dependency scans, regular expressions, fixed templates, or rule engines. `R` names strong relationships as `code:<repository-relative-path>`; `S` records only non-inferable, error-preventing constraints. Scale tags are machine facts derived from line counts; importance tags reflect architectural weight, not file size.

When managed objects change, close the task by refreshing the governed baseline and updating only the affected Entries. After alignment, do not repeat maintenance. Purely read-only questions need no maintenance. AOCI is not an AI Agent, RAG replacement, AST replacement, LSP replacement, daemon, or cloud service: agents still inspect real source and tests. Directory section headers are historical coordinates, not runtime roots; readers logically relocate them, and a public repository must keep hostnames, user names, and secrets out of them.

This repository uses protocol-level integration: `scripts/aoci_check.py` performs the governance that the official CLI would, because the binary is unavailable in the build environment. If an official `aoci` binary is installed, its `verify` and `check` remain authoritative and the live Guide and tool schema override this block.
<!-- aoci:end -->
