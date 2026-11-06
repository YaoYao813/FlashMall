"""槽位不全时追问：明确列出缺哪几项，不要瞎猜参数。"""

from langchain_core.messages import AIMessage

from app.graph.schemas import SLOT_LABELS


def clarify_node(state: dict) -> dict:
    missing = state.get("missing") or []
    if missing:
        labels = "、".join(SLOT_LABELS.get(name, name) for name in missing)
        text = f"要完成这次操作，还需要你补充：{labels}。其余信息我已经记下了。"
    else:
        text = "还差一些信息，请再说得具体一点。"
    return {"reply": text, "messages": [AIMessage(content=text)]}
