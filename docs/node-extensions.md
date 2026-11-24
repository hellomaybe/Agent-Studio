# 为编辑器增加业务节点

节点由 Spring Bean 提供。一个处理器同时描述配置表单、字段类型和实际运算。通用编辑器读取 `GET /api/nodes` 后生成能力列表和配置控件。现有节点 `filter-assigned` 是可跟读的完整示例；其 Java 类名为 `FilterAssignedNode`。[实现](../backend/src/main/java/studio/agent/extensions/FilterAssignedNode.java) · [接口](../backend/src/main/java/studio/agent/graph/NodeHandler.java)

## 从筛选节点理解契约

在会议模板中，待办提取步骤产出一个 `TASKS` 列表。`filter-assigned` 缺省保留已分配负责人的事项。它使用两个独立的判断：`onlyAssigned` 决定保留哪些事项，`minCount` 决定保留数量是否达到进入确认的门槛。调整门槛不会删除已经保留的事项。

| 描述字段 | 示例 | 用途 |
| --- | --- | --- |
| `type`、`label`、`version` | `filter-assigned`、筛选已分配待办、`"1"` | 业务图引用稳定的能力类型；界面显示名称，发布记录固定能力版本。 |
| `inputs`、`outputs` | 输入 `items:TASKS`；输出 `items:TASKS`、`hasItems:BOOLEAN`、`excluded:TASKS`、`reason:TEXT` | 输入选择器按字段类型和上游可达性筛选，运行时检查实际值。 |
| `ports` | `["next"]` | 配置可连接的控制出口。筛选结果经 `hasItems` 传给条件节点，由后者选择 `yes` 或 `no`。 |
| `config`、`configFields` | `onlyAssigned=true` 使用布尔控件；`minCount=1` 使用 1–50 的整数控件 | 声明默认值、界面标签、控件和取值限制。 |
| `exampleInputs` | 一个由 Alice 负责的任务和一个负责人为空的任务 | 新节点进入调试器时可以立即以此输入试算。 |
| `liveModel` | 筛选节点默认为 `false`；待办提取节点为 `true` | 调试器据此显示真实模型模式，运行时据此识别需要占用模型额度的步骤。 |

供应商返回或旧版会议使用的任务结构可能不同。通用图中的 `TASKS` 最多包含 50 项，每项都要有唯一非空的 `id`、非空的 `title` 以及字符串 `assignee`。未分配负责人用空字符串。其他内置字段类型为 `TEXT` 和 `BOOLEAN`。配置控件支持 `text`、`textarea`、`number`、`boolean`、`select`；下拉项由 `Field.options` 给出。[字段校验](../backend/src/main/java/studio/agent/graph/NodeRegistry.java) · [前端元数据类型](../frontend/src/workbench/types.ts)

## 注册新能力

例如，若要让运营人员在任意两个文本步骤之间插入“整理空白”，可以在应用扫描的 `studio.agent.extensions` 包内新增以下类。选择唯一的 `type`。重启后，注册表会收集所有 `NodeHandler` Bean。

```java
package studio.agent.extensions;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import studio.agent.graph.Graph;
import studio.agent.graph.NodeHandler;

@Component
public class NormalizeTextNode implements NodeHandler {
  @Override
  public Descriptor descriptor() {
    return new Descriptor(
        "normalize-text", "整理空白", "1",
        Map.of("text", "TEXT"), Map.of("text", "TEXT"), List.of("next"),
        Map.of("trim", true),
        List.of(new Field("trim", "去除首尾空白", "boolean", List.of(), null, null)),
        Map.of("text", "  本周发布计划  "));
  }

  @Override
  public boolean replaySafe(String mode) {
    return true;
  }

  @Override
  public Outcome evaluate(Graph.Node node, Map<String, Object> inputs, Context context) {
    String text = (String) inputs.get("text");
    boolean trim = (Boolean) node.config().get("trim");
    return Outcome.next(Map.of("text", trim ? text.strip() : text));
  }
}
```

注册表在执行前合并默认配置和节点配置，检查配置与输入；执行后检查声明的输出和实际返回端口。`Outcome.next(...)` 适用于声明了 `next` 出口的处理器。具有条件出口的处理器应按实际输入返回声明的端口。对于需要人工确认的节点，请参考内置 `confirm`：首次调用返回等待状态和候选事项。收到用户选择后，运行时通过 `Context.approvedIds()` 提供 ID，处理器从原事项中选出获准的项目，空选择走另一条分支。[内置节点](../backend/src/main/java/studio/agent/graph/BuiltinNodes.java)

在“流程编排”里选择新增能力。可以添加独立节点，也可以把单出口能力插入既有连线。选择该节点的上游字段，检查下游绑定，再保存草稿。字段引用采用 `节点ID.输出字段`。例如，新文本节点 ID 为 `clean` 时，可把它的输入绑定到 `start.text`，再把提取节点的输入改成 `clean.text`。在分支汇合处，只能引用所有到达路径上都执行过的生产者。能力类型和图内节点 ID 各自承担不同职责，同一能力可在图内出现多次。

## 执行、副作用和版本

纯计算节点可像示例一样声明安全重放。`replaySafe(mode)` 默认返回 `false`。只有在相同输入下再次执行也安全时才返回 `true`。运行启动时保存该策略；一次结果不确定的外部调用不适合直接重发。

如果节点需要创建本地业务结果，把拟写入的受支持内容放入 `Outcome.effects()`，例如内置工单处理器返回带有 `kind`、`title` 和 `priority` 的对象。节点调试和样例评估只显示这些提议；工作流执行器在提交节点结果时将它们写入本库，并为每项分配唯一操作标识。进入等待的节点应返回空的 effects 列表。外部发送、扣款或工单同步需要相应的适配器、权限和供应方幂等约定；仅返回一个 `kind` 不会触发此类外部操作。

升级已有能力时，保持兼容实现，或为改变的语义更新能力版本并重新验证业务图。发布保留能力版本映射，运行时检查已注册版本。注册表同时按 `type` 找到一个实现；需要两种行为同时可执行时，可以新类型承载新行为，逐步迁移并发布使用它的图。历史定义和运行记录可保持只读。

为自己的真实模型节点返回 `new Descriptor(...).withLiveModel()`。`NodeRegistry.usesLiveModel(type)` 读取这个标记，调试器在服务端启用模型时提供真实模式，完整运行会在启动时保存每个节点类型的模型标记并在执行前预占额度。处理器仍需实现 `evaluate` 的两条路径：样例模式使用固定输入或确定性样例，真实模式才调用供应商。实现 `replaySafe(mode)` 时分别考虑这两条路径，例如内置模型节点只允许样例模式安全重放。能力声明本身不会配置模型客户端、读取密钥或改变供应方的数据保留策略。[单节点调试入口](../backend/src/main/java/studio/agent/graph/GraphController.java) · [运行器](../backend/src/main/java/studio/agent/workflow/WorkflowRunService.java)

## 验证一次扩展

1. 在应用上下文测试里读取注册表，确认能力类型、版本、端口、默认配置、样例输入都存在。通过注册表执行处理器，覆盖正常结果、错误字段类型、越界配置和分支条件。已有 `GraphStudioTest` 覆盖注册扩展和筛选节点的固定输入。[后端示例测试](../backend/src/test/java/studio/agent/graph/GraphStudioTest.java)
2. 在节点调试器固定一份输入。对 `filter-assigned` 的内置样例，默认配置应返回一项 `items`、一项 `excluded` 和 `hasItems=true`。将门槛改为 2 并保持输入不变，应保留同一项，`hasItems` 改为 `false`。检查输出中的 `reason` 能否解释排除原因。
3. 通过“对比与发布”为整条图增加验收样例。除了等待条件和本地操作数，还可检查具体 `节点.字段` 的值。覆盖从相关分支到结束的路径。先保存有效草稿和样例，取得与该修订匹配的通过结果，再发布。
4. 从项目根目录运行 `mvn -f backend/pom.xml verify`。在 `frontend` 目录运行 `npm run build` 和 `npm run test:e2e`。测试画布时特别关注新增能力能否在现有前端出现、有类型的输入能否连到合法上游，以及记录回放是否使用原图与实际端口。[前端图契约测试](../frontend/e2e/editor-graph.spec.ts) · [完整验证命令](../README.md#构建与质量检查)

只迭代节点实现时，可以先从项目根目录运行 `mvn -f backend/pom.xml -Dtest=GraphStudioTest test`。为自己的节点增加相应测试类，便可使用同样方式单独运行。提交前再执行全量验证。
