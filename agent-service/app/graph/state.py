"""图状态。

`messages` 用 `add_messages` 归约：节点只返回新增消息，历史自动累加。
其余字段都是「覆盖」语义，节点返回什么就是什么。
"""

from typing import Annotated, Any, TypedDict

from langgraph.graph.message import add_messages


class AgentState(TypedDict, total=False):
    messages: Annotated[list[Any], add_messages]

    intent: str
    slots: dict[str, Any]
    missing: list[str]

    # 读分支：router 选好工具与参数，read 节点只负责调用
    tool_name: str | None
    tool_args: dict[str, Any]
    tool_result: dict[str, Any] | None

    # 写分支：plan_write 组装、confirm 挂起、execute 执行
    pending_action: dict[str, Any] | None
    approved: bool | None
    feedback: str

    result: dict[str, Any] | None
    reply: str
