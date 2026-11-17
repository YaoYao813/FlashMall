# Git 协作流程

本文约定日常开发的分支、提交、PR 和 Review 流程。目标只有两个：
**master 随时可发布**，以及**每一次改动都能被追溯和回滚**。

## 1. 分支

从最新 master 切出，命名格式 `类型/简短描述`：

| 前缀 | 用途 | 示例 |
|---|---|---|
| `feat/` | 新功能 | `feat/user-seckill-page` |
| `fix/` | 修缺陷 | `fix/order-pagination` |
| `docs/` | 只改文档 | `docs/git-workflow` |
| `chore/` | 构建、依赖、配置 | `chore/upgrade-spring-boot` |

规则：

- 不要直接向 master 提交
- 分支尽量短命，合并后删除
- 开始前先 `git switch master && git pull`，避免从过期代码切分支

```bash
git switch master
git pull
git switch -c docs/git-workflow
```

## 2. 提交

一个提交只做一件事。信息格式：

```
类型(范围): 一句话说明

为什么改、改了什么、有什么取舍。
```

示例：

```
fix(order): 补 MyBatis-Plus 分页插件

selectPage 之前返回全部记录但 total=0，前端订单页显示「共 0 个订单」。
加 PaginationInnerInterceptor 后 total/pages 正确。
```

提交前先自己看一遍：

```bash
git status
git diff              # 未暂存的改动
git add <文件>
git diff --cached     # 即将提交的内容
git commit -m "..."
```

## 3. PR（Pull Request）

推送分支后，在 GitHub 上向 master 发起 PR：

```bash
git push -u origin docs/git-workflow
```

PR 描述至少包含三块：

1. **做了什么**：一句话概括
2. **为什么**：对应的问题或需求
3. **怎么验证**：跑过的命令、看到的输出、截图

约定：

- PR 尽量小，一次只解决一个问题；超过 400 行考虑拆分
- CI / 测试没过不请求 Review
- 合并前先解决冲突，不要在 PR 里顺手改无关文件

## 4. Review

Reviewer 关注优先级：正确性 > 可维护性 > 风格。

作为作者：

- 先自己 review 一遍 diff，再 @ 人
- 每条评论都要回复：修改了、或说明为什么不改
- 追加修改用新提交，不要 force-push 正在被 review 的分支

作为 Reviewer：

- 指出问题时给出依据（复现步骤、边界条件），不要只说"感觉不对"
- 区分「必须改」和「建议」；必须改的写清楚原因
- 批准前确认：改动范围、测试证据、回滚方式

## 5. 合并与收尾

- 合并方式按仓库约定：功能分支用 squash 或 merge commit，保持 master 线性可读
- 合并后删除远端分支
- 本地同步并清理：

```bash
git switch master
git pull
git branch -d docs/git-workflow
```

如果分支被 squash 合并，`git branch -d` 可能提示"未完全合并"，确认内容已进 master 后用 `git branch -D` 删除本地分支。

## 6. 出问题时

| 场景 | 做法 |
|---|---|
| 改错了还没 commit | `git restore <文件>` |
| 已经 add 了 | `git restore --staged <文件>` 再 restore |
| commit 信息写错、还没 push | `git commit --amend` |
| 已经 push 到自己的分支 | 追加一个修正提交，不要改写公共历史 |
| 切分支时工作区有未完成改动 | 先 commit，或 `git stash` 暂存 |

**永远不要**对 master 或别人正在使用的分支执行 `push --force`。
