# 群聊场景长期记忆系统设计文档

## 1. 概述

本文档描述了针对群聊场景的长期记忆系统的架构和设计。该系统解决了群组对话的独特挑战，包括上下文多样性（多个参与者）、信息噪声以及不同聊天群组之间的隐私边界。

### 1.1 技术栈
- **后端框架**: Kotlin, Ktor
- **依赖注入**: Koin
- **数据库 ORM**: Exposed
- **数据库**: PostgreSQL
- **AI 集成**: Koog

### 1.2 设计目标
- 跨对话维护持久化、结构化的记忆
- 支持多维度记忆分类
- 实现基于语义的检索和更新
- 确保不同作用域之间的隐私隔离
- 允许 LLM 通过工具使用驱动记忆管理

---

## 2. 记忆维度

系统将记忆结构化为五个核心维度，以便 LLM 准确提取和检索：

### 2.1 用户画像 (User Profile)
- **定义**: 静态或半静态的用户属性
- **示例**: 昵称、性别、职业、MBTI 性格类型、沟通风格（正式/随意）、在群组中的角色（管理员/潜水者/活跃成员）
- **更新频率**: 低

### 2.2 偏好设置 (Preferences)
- **定义**: 用户明确要求的交互方式或配置
- **示例**: "晚上10点后不要@我"、"偏好 Markdown 格式回复"、"只关注 Kotlin 相关话题"
- **更新频率**: 中

### 2.3 事实知识 (Facts/Knowledge)
- **定义**: 对话过程中产生的客观信息或达成的共识
- **示例**: "群规禁止发广告"、"项目 A 下周三上线"、"用户王去过南极"
- **更新频率**: 高

### 2.4 待办与意图 (Todos & Intentions)
- **定义**: 需要未来触发或持续关注的动态状态
- **示例**: "明天提醒大家提交周报"、"机器人稍后需要总结这段对话"、"用户 A 承诺下周分享技术见解"
- **更新频率**: 高，具有生命周期管理（创建 → 完成/过期）

### 2.5 摘要与话题 (Summaries & Topics)
- **定义**: 对长对话历史的压缩表示，用于管理 Token 限制
- **示例**: "上周主要讨论了 Koin 依赖注入"、"昨天关于 Tab vs 空格的争论"
- **更新频率**: 中

---

## 3. 记忆作用域

记忆作用域对于维护隐私边界和上下文相关性至关重要。系统定义了三个作用域级别：

### 3.1 全局作用域 (Global Scope)
- **绑定到**: `UserID`
- **用途**: 跨群组的用户特征（如 MBTI、编程语言偏好）
- **隐私说明**: 只有非敏感信息应存储在此级别

### 3.2 群组作用域 (Group Scope)
- **绑定到**: `GroupID`
- **用途**: 群组特定信息（公告、话题、成员关系）
- **隐私说明**: 信息在特定群组之外无意义

### 3.3 成员-群组作用域 (Member-Group Scope)
- **绑定到**: `UserID` + `GroupID`
- **用途**: 用户在特定群组内的行为或身份
- **示例**: 用户在 A 群是"严格的技术专家"，在 B 群是"随意闲聊者"

---

## 4. 数据库模式设计

模式遵循使用 Exposed ORM 的**实体表 + 记忆片段**模式。

### 4.1 基础实体表

#### Users 表
存储基本用户信息和全局设置。

| 字段 | 类型 | 描述 |
|-------|------|-------------|
| id | Long | 主键 |
| external_id | String | 外部系统用户 ID |
| created_at | Timestamp | 账户创建时间 |
| global_settings | JSON | 用户级别偏好设置 |

#### Groups 表
存储群组信息和配置。

| 字段 | 类型 | 描述 |
|-------|------|-------------|
| id | Long | 主键 |
| external_id | String | 外部系统群组 ID |
| name | String | 群组名称 |
| config | JSON | 群组特定设置 |
| created_at | Timestamp | 群组创建时间 |

### 4.2 核心记忆表 (Memories)

存储所有画像、偏好和事实的中心表。

| 字段 | 类型 | 描述 |
|-------|------|-------------|
| id | Long | 主键（暴露给 LLM 用于更新）|
| scope_type | Enum | GLOBAL, GROUP, MEMBER_IN_GROUP |
| user_id | Long? | 可空；纯群组记忆为 null |
| group_id | Long? | 可空；全局记忆为 null |
| category | Enum | PROFILE, PREFERENCE, FACT, SUMMARY |
| content | Text | 记忆内容（如"用户喜欢吃辣"）|
| embedding | Vector | 用于语义搜索的向量数据 |
| confidence | Float | LLM 给出的置信度分数（0.0-1.0）|
| last_updated | Timestamp | 用于基于 LRU 的遗忘机制 |
| created_at | Timestamp | 原始创建时间 |

**索引**:
- (`user_id`, `group_id`, `scope_type`) 复合索引
- `embedding` 向量索引用于相似度搜索
- `last_updated` 索引用于 LRU 查询

### 4.3 待办事项表 (Todos)

用于任务管理和状态转换的独立表。

| 字段 | 类型 | 描述 |
|-------|------|-------------|
| id | Long | 主键 |
| group_id | Long | 关联群组 |
| creator_id | Long | 任务创建者 |
| assignee_id | Long? | 任务执行者/提醒目标 |
| description | Text | 任务描述 |
| trigger_time | Timestamp? | 触发任务的时间 |
| status | Enum | PENDING, COMPLETED, CANCELLED |
| created_at | Timestamp | 任务创建时间 |
| completed_at | Timestamp? | 任务完成时间 |

---

## 5. LLM 交互架构

### 5.1 双流架构 (Dual-Stream Architecture)

系统采用**双流架构**来分离对话和记忆管理：

#### 流程 1: 聊天流 (Chat Flow) - 记忆检索

**处理流程**:
1. **检索 (Retrieval)**
   - 基于 `user_id` 和 `group_id` 查询数据库
   - 获取全局记忆 + 群组记忆 + 成员-群组记忆
   - **优化**: 如果记忆过多，对用户输入进行向量化并执行向量相似度搜索，仅检索 Top-K 相关记忆

2. **提示词构建 (Prompt Construction)**
   - 系统提示词："你是一个群聊助手。以下是关于当前对话上下文的已知信息：[注入检索到的记忆]..."
   - 用户输入：当前用户消息

3. **响应生成 (Response Generation)**
   - 机器人基于记忆和上下文进行回复

#### 流程 2: 记忆流 (Memory Flow) - 记忆更新

**触发条件**:
- 每 N 条消息（例如每 10 条消息）
- 检测到对话结束或长时间暂停
- 用户明确说"记住 xxx"

**处理流程**:
1. **记忆提取提示词**
   ```
   分析以下对话记录，提取新信息以更新长期记忆。
   
   关注以下维度：用户画像、偏好、事实、待办。
   
   输出格式应包含：操作类型（add/update/delete）、作用域（group/personal）、
   记忆类别、内容。
   
   示例：
   - 用户 A 说"我下周休假" → {Op: add, Scope: group, Type: fact, 
     Content: "用户 A 下周休假"}
   - 用户 B 说"别叫我小王，叫我王总" → {Op: update, Scope: group, 
     Type: preference, Content: "用户 B 希望被称为王总"}
   ```

2. **处理逻辑 (Kotlin/Exposed)**
   - 解析 LLM 返回的结构化数据（JSON）
   - **去重与冲突解决**:
     - 对于 "add"：检查是否存在相似记忆
     - 对于 "update"：找到旧记录，更新 `content` 和 `last_updated`
     - 对于 "delete"：标记或物理删除旧记忆
   - 写入数据库

---

## 6. RAG 增强的记忆管理

### 6.1 检索-比对-操作架构 (Retrieve-Compare-Act)

系统使用三阶段方法来实现 LLM 驱动的记忆 CRUD 操作：

1. **召回 (Recall - Semantic Retrieval)**
   - 对传入的用户消息进行向量化
   - 从数据库检索 Top-K 最相似的现有记忆

2. **推理 (Reasoning - LLM Decision Making)**
   - 向 LLM 提供：[新消息] + [相关的旧记忆及其 ID]
   - LLM 判断：添加新条目、更新现有条目或删除过时条目

3. **行动 (Action - Tool Execution)**
   - LLM 调用 `add_memory`、`update_memory` 或 `delete_memory` 工具
   - Exposed 执行数据库操作

### 6.2 LLM 工具定义

#### 工具: `add_memory`
**描述**: 当用户信息包含全新的事实、偏好或画像，且**当前上下文中不存在冲突的旧记忆**时使用。

**参数**:
- `content` (String): 记忆内容（必须是独立的陈述句）
- `scope` (String): GLOBAL, GROUP 或 MEMBER_IN_GROUP
- `category` (String): PROFILE, PREFERENCE, FACT 或 SUMMARY
- `confidence` (Float): 置信度分数（0.0-1.0）

#### 工具: `update_memory`
**描述**: 当用户信息**纠正、补充或改变**现有记忆时使用。**必须**引用"现有记忆"列表中的 ID。

**参数**:
- `memory_id` (Long): **必须对应召回上下文中的真实 ID**
- `new_content` (String): 更新后的内容
- `reason` (String): 为何需要此更新（例如"用户改变了主意"）

#### 工具: `delete_memory`
**描述**: 当现有记忆被证明不正确、用户明确要求遗忘或信息完全过时时使用。

**参数**:
- `memory_id` (Long): 召回上下文中的 ID

### 6.3 提示词工程示例

**场景**: 用户说："别再叫我小王了，叫我王总。另外，我下周不去爬山了。"

**步骤 1: 向量搜索**
系统执行余弦相似度搜索并找到：
- 记忆 A (ID=101): "用户 A 的昵称是小王" (分数: 0.85)
- 记忆 B (ID=102): "用户 A 计划下周去爬山" (分数: 0.91)

**步骤 2: 构建提示词**
```
系统：你是一个专业的群聊记忆管理员。你的任务是根据最新的用户对话，
通过调用工具来维护长期记忆数据库。

【现有记忆】
[ID: 101] 内容：用户 A 的昵称是小王
[ID: 102] 内容：用户 A 计划下周去爬山

【当前对话】
用户 A：别再叫我小王了，叫我王总。另外，我下周不去爬山了。

【指令】
1. 分析当前对话与现有记忆之间的关系。
2. 如果是新信息，调用 add_memory。
3. 如果与现有记忆冲突或是状态更新，调用 update_memory（必须使用对应的 ID）。
4. 如果是取消或失效，调用 delete_memory（必须使用对应的 ID）。
5. 即使是细微的语义变化（如名称变化）也应视为更新。
```

**步骤 3: 预期的 LLM 输出**
- **工具调用 1**: `update_memory(memory_id=101, new_content="用户 A 希望被称为王总", reason="用户要求更改称呼")`
- **工具调用 2**: `delete_memory(memory_id=102)` 或 `update_memory(memory_id=102, new_content="用户 A 取消了下周的爬山计划", reason="计划变更")`

---

## 7. 实现考虑

### 7.1 向量嵌入 (Vector Embedding)
- 使用与 PostgreSQL 向量扩展兼容的嵌入模型（如 pgvector）
- 考虑维度权衡（384、768 或 1536 维）
- 随着嵌入模型改进，为旧记忆实施定期重新嵌入

### 7.2 遗忘机制 (Forgetting Mechanism)
- 对低置信度或很少访问的记忆实施基于 LRU 的遗忘
- 设置可配置的自动记忆清理阈值
- 在删除前维护审计日志以便潜在恢复

### 7.3 冲突解决 (Conflict Resolution)
- 当多个记忆冲突时，优先级顺序：置信度分数 > 最近性 > 具体性
- 为关键记忆实施版本控制
- 允许显式用户命令解决冲突

### 7.4 隐私与安全 (Privacy & Security)
- 静态加密敏感记忆内容
- 在记忆检索前实施严格的作用域验证
- 审计记录所有跨作用域访问尝试
- 提供面向用户的记忆删除 API 以符合 GDPR

### 7.5 性能优化 (Performance Optimization)
- 在 Redis 中缓存频繁访问的记忆
- 实施批量嵌入操作
- 对数据库访问使用连接池
- 考虑为向量相似度搜索使用只读副本

---

## 8. 未来增强

### 8.1 记忆整合 (Memory Consolidation)
- 定期后台作业合并相似记忆
- 从事实集群自动生成摘要

### 8.2 多模态记忆 (Multi-Modal Memory)
- 支持图像和文件附件作为记忆触发器
- 存储视觉嵌入和文本嵌入

### 8.3 记忆导出/导入 (Memory Export/Import)
- 允许用户导出其记忆数据
- 实现不同机器人实例间的记忆转移

### 8.4 高级分析 (Advanced Analytics)
- 记忆随时间增长的趋势
- 话题聚类和演化跟踪
- 基于共享记忆的用户关系图谱

---

## 9. 参考资料

- Exposed 框架文档
- PostgreSQL pgvector 扩展
- LLM 工具调用最佳实践
- RAG（检索增强生成）模式
