"""图组装。

START → router
  ├─ clarify  → END
  ├─ read     → respond → END
  ├─ plan_write → confirm(interrupt) → execute → respond → END
  └─ respond（unknown）→ END

checkpointer 由调用方传入：FastAPI lifespan 里全局一份，CLI 里自己建一份。
"""

from langgraph.graph import END, START, StateGraph

from app.graph.nodes.clarify import clarify_node
from app.graph.nodes.read import read_node, respond_node
from app.graph.nodes.router import route_after_router, router_node
from app.graph.nodes.write import confirm_node, execute_node, plan_write_node
from app.graph.state import AgentState


def build_graph(checkpointer=None):
    graph = StateGraph(AgentState)

    graph.add_node("router", router_node)
    graph.add_node("clarify", clarify_node)
    graph.add_node("read", read_node)
    graph.add_node("plan_write", plan_write_node)
    graph.add_node("confirm", confirm_node)
    graph.add_node("execute", execute_node)
    graph.add_node("respond", respond_node)

    graph.add_edge(START, "router")
    graph.add_conditional_edges(
        "router",
        route_after_router,
        {
            "clarify": "clarify",
            "read": "read",
            "plan_write": "plan_write",
            "respond": "respond",
        },
    )
    graph.add_edge("clarify", END)
    graph.add_edge("read", "respond")
    graph.add_edge("plan_write", "confirm")
    graph.add_edge("confirm", "execute")
    graph.add_edge("execute", "respond")
    graph.add_edge("respond", END)

    return graph.compile(checkpointer=checkpointer)
