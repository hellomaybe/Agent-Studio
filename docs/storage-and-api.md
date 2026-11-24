# 持久化与 API

同一个数据库代表一名本机操作者的工作空间。新版有三个不同的编号：草稿的 `revision` 用于检测并发编辑，发布的 `version` 标识不可变的图与能力版本，一次执行的 `runId` 用于查询和发信号。验收套件另有独立的 `revision`。

## 草稿、单节点调试与发布

用 `GET /api/apps` 取得应用列表，用 `GET /api/nodes` 取得注册能力。下表中的路径相对于 `/api/apps/{appId}`。预置 `appId` 为 `meeting-notes` 和 `feedback-triage`。

| 接口 | 请求与行为 |
| --- | --- |
| `GET /draft`、`PUT /draft` | 读取草稿；提交 `{revision,graph}` 保存。版本匹配后递增修订号，过期编辑返回 409。草稿可保留尚未接完的分支。 |
| `GET /samples` | 取得本应用可用于启动演示的文本样例。 |
| `POST /debug` | 传 `{revision,nodeId,mode,inputs}` 调试保存的草稿节点；可另传 `graph` 调试正在编辑的版本，或指定 `version` 调试历史版本。单次选择一种来源。接口只执行指定节点，返回输出、端口、等待标记、拟执行操作和耗时。 |
| `GET /cases`、`PUT /cases` | 取得或保存验收套件 `{revision,cases}`。每个案例包含名称、文本、是否批准、是否预期进入等待、预期本地操作数，还可指定字段值断言。 |
| `POST /evaluations` | 提交 `{revision,graph}`，用固定样例比较当前发布版与候选图。相同输入分别运行为样例，记录各案例是否通过和具体差异。评估只统计处理器提议的操作。 |
| `POST /publish` | 提交 `{revision}`。创建新版本前，服务端验证完整图，并核对针对已保存修订、当前发布版本和当前样例套件的通过记录。重复发布同一个已发布修订返回已有版本。 |
| `GET /versions`、`GET /versions/{version}` | 查看历史清单、图定义和绑定的能力版本。读取历史定义与执行历史版本分别校验。 |

图的控制边为 `{from,port,to}`。数据边由节点的 `bindings` 表达，例如 `{"items":"extract.items"}`。发布时引用的输出必须类型相同，并且在所有到达当前节点的路径上均可用。画布坐标位于 `node.position`。校验错误可带 `nodeId`、`field` 或 `port` 供前端定位。保存套件或修改流程后，重新运行对比以取得相应修订的发布凭据。[图校验](../backend/src/main/java/studio/agent/graph/NodeRegistry.java) · [发布检查](../backend/src/main/java/studio/agent/quality/ReleaseChecks.java)

例如，以下请求可调试初始会议发布版中的筛选节点。返回值可用于检查保留列表、排除列表及判断结果。

```http
POST /api/apps/meeting-notes/debug
Content-Type: application/json

{"version":1,"nodeId":"filter","mode":"sample","inputs":{"items":[{"id":"a","title":"整理发布清单","assignee":"Alice"},{"id":"b","title":"发送材料","assignee":""}]}}
```

真实单节点调试要求节点声明 `liveModel`，服务启用模型并提供 `requestId`。内置能力中，`extract-tasks` 声明了此标记。已占用的真实调试请求编号会被拒绝重复调用，因此在响应中断时应保留该编号核查。样例调试不调用供应商。供应商回传的模型和 token 用量可用于核对，缺少用量的响应展示为未知。

## 完整运行与恢复

`POST /api/apps/{appId}/runs` 接受 `{versionId,requestId,mode,input,pauseAfterNode?}`。输入为 `{"text":"…"}`；未指定版本时锁定当前发布版。真实模式另需 `persistInput:true`，表示同意在本机保留本次输入内容 24 小时。服务先记录已受理请求，后台工作线程随后执行。相同应用、相同请求编号和完全相同的参数返回原运行；相同编号用于不同内容时返回 409。恢复时使用原 `runId`。

| 目的 | 相对于 `/api/apps/{appId}/runs` 的接口 |
| --- | --- |
| 查询或回放 | `GET /` 返回最近 50 条运行摘要；`GET /{runId}` 返回冻结的图、当前节点、路径、等待项和本地操作结果。 |
| 分页查看事件 | `GET /{runId}/events?after=0&limit=100` 按序号读取，返回 `nextCursor`、`hasMore`。每页范围 1–200。事件携带可用的节点、尝试编号、耗时、端口、下一节点及用量，没有历史证据的值保持未知。 |
| 将历史输入带回调试 | `POST /{runId}/debug-input` 传 `{nodeId,includeContent:true}`。需要实际经过节点、有已完成的尝试、能从留存数据还原输入。内容过期返回 410。接口本身不重跑节点。 |
| 回应人工等待 | 收到 `waiting` 后，`POST /{runId}/resume` 传 `{signalId,waitId,values:{selectedIds:[…]}}`。可提交空列表；只能选等待项提供的 ID。服务重新检查等待编号，旧等待项不能推进新步骤。 |
| 操作者暂停与继续 | `POST /{runId}/pause` 或 `/continue`，传 `{signalId}`。排队状态可直接暂停，运行中的暂停在安全边界生效；这保留检查点，不会取消已发出的模型请求。也可在启动时指定 `pauseAfterNode`。 |
| 重试失败的节点 | `POST /{runId}/retry`，传 `{signalId}`。以服务端返回的 `canRetry` 为准。仅允许内容仍在、属于可重试错误、且启动时冻结的处理器策略允许重放的节点。 |
| 核对外部结果 | 遇到 `unknown`，`POST /{runId}/reconcile` 传 `{signalId,decision:"verified" 或 "terminate",receiptRef?}`。核对只记录复核状态或终止旧运行；不会再次向供应商发送旧请求。可选的回执编号按指纹保存。 |
| 重新输入 | 符合 `canReinput` 的真实运行可调用 `POST /{runId}/reinput`，传 `{requestId,input:{text},persistInput:true}`。这会用原发布版本建立另一条运行，记录 `parentRunId`，并可能发生新的模型费用。 |

`signalId` 在一条运行内用于去重。重发同一个信号及相同参数返回已有状态，用同一编号改选或执行其他动作会得到 409。详情中的 `waiting` 表示需要业务输入，`paused` 表示操作者主动暂停，两者的恢复接口不同。

真实模型的远端结果可能在进程中断或服务超时后无法确认。运行时记录节点尝试、检查点和租约；恢复时安全步骤可重新排队，外部结果不确定的步骤进入 `unknown`。人工选择 `verified` 只表示已核对，选择 `terminate` 才会终止旧运行。旧能力版本不可用时，服务保留历史定义并阻止用不同版本的实现冒充原处理器。

## 写入与保留范围

新版完整运行将处理器提出的任务或工单写入本库 `workflow_local_effect`，与节点完成和运行推进使用同一事务，并以运行、节点激活序号和本次操作序号唯一标识。详情返回 `operationKey`、`effectId`，可核对一次操作的来源。工作台自带的处理器没有向外部工单平台、邮件或通知渠道投递的连接器。[实际提交逻辑](../backend/src/main/java/studio/agent/workflow/WorkflowRunService.java)

新版真实运行的内容保留期从启动时起算，为 24 小时。过期处理清除运行输入、中间和最终输出、等待内容与该运行的本地操作正文。运行身份、版本、事件和尝试元数据仍可查询。到期时尚未完成的普通等待会进入 `expired`；已完成、失败、待核对或终止的历史状态可保留。样例运行没有上述真实输入 TTL。此清理不覆盖数据库备份、旧模板的原有记录或供应商侧数据。

| 表 | 用途 |
| --- | --- |
| `studio_graph_app`、`studio_graph_version` | 当前草稿、最新发布指针、按版本冻结的图与能力版本映射。 |
| `studio_evaluation_suite` | 样例套件、修订号及与候选语义、能力版本和发布基线关联的通过记录。 |
| `workflow_run`、`workflow_event`、`workflow_step_attempt` | 接受请求、检查点、可分页事件、节点激活和每次尝试的结果。 |
| `workflow_signal`、`workflow_local_effect` | 信号去重、本地任务或工单的操作记录。 |
| `studio_live_budget_lock`、`studio_live_reservation` | 在新旧模型运行和真实单节点调试之间协调供应商调用额度。 |
| `studio_draft`、`studio_run` | 旧版固定会议模板的草稿与历史。既有等待继续由旧接口完成。 |

## 旧版会议接口

旧页面继续使用 `/api/studio/meeting-notes`。以下路径相对于该前缀。

| 接口 | 契约 |
| --- | --- |
| `GET /draft`、`PUT /draft` | 读配置；写入 `{revision,config}`，过期编辑返回 409。 |
| `POST /preview` | `{config,sampleKey}` 返回固定样例和预计路径。 |
| `POST /runs` | 固定样例使用 `{revision,requestId,mode:"sample",sampleKey}`，真实运行使用 `mode:"live",title,transcript`。 |
| `GET /runs`、`GET /runs/{id}` | 返回历史运行的原配置、经过的节点、事项与可用的模型用量。 |
| `POST /runs/{id}/confirm` | 提交 `{"selectedIds":["task-1"]}`。相同选择可重复确认；已完成后改选返回 409。 |

旧版确认结果是任务预览。查看或复制 `legacy-meeting-run/v1` 快照时，前端按该次运行保存的数据还原路径，缺失字段标为未知。此展示不会发布新图、迁移运行或改变等待状态。[旧版转换器](../frontend/src/legacy-snapshot.ts)

所有成功响应均可从顶层 `data` 读取。错误按 HTTP 状态处理，图接口可返回 Problem Detail 的 `detail`，运行接口使用 `message` 或 `msg`。模型断线或结果未知时，先用原请求编号、运行记录和供应商回执核对，再决定下一步。
