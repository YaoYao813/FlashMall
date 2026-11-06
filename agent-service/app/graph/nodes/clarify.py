"""槽位不全时追问：明确列出缺哪几项，不要瞎猜参数。"""

from langchain_core.messages import AIMessage

from app.graph.schemas import SLOT_LABELS


def clarify_node(state: dict) -> dict:
    missing = state.get("missing") or []
    invalid = state.get("invalid") or []
    if invalid:
        # 槽位都填了，但组合起来不合法（例如开始时间等于结束时间），必须说清哪里错了
        text = "这次操作不能执行：" + "；".join(invalid) + "。请修正后再说一次。"
    elif missing:
        labels = "、".join(SLOT_LABELS.get(name, name) for name in missing)
        text = f"要完成这次操作，还需要你补充：{labels}。其余信息我已经记下了。"
    else:
        text = "还差一些信息，请再说得具体一点。"
    return {"reply": text, "messages": [AIMessage(content=text)]}
