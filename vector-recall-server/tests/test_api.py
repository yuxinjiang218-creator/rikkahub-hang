import importlib
import os
import sys

from fastapi.testclient import TestClient


class FakeEmbedder:
    def embed(self, text: str) -> list[float]:
        value = text.lower()
        if "kotlin" in value:
            return [1.0, 0.0, 0.0]
        if "python" in value:
            return [0.0, 1.0, 0.0]
        return [0.0, 0.0, 1.0]


def build_client(tmp_path, monkeypatch):
    monkeypatch.setenv("DB_PATH", str(tmp_path / "rikka.db"))
    monkeypatch.setenv("VECTOR_USERNAME", "boss")
    monkeypatch.setenv("VECTOR_PASSWORD", "secret")
    monkeypatch.setenv("EMBED_DIMENSION", "3")
    monkeypatch.setenv("EMBED_API_KEY", "test")

    for name in ["main", "auth", "db", "vector_db", "embedder", "config", "models"]:
        sys.modules.pop(name, None)
    main = importlib.import_module("main")
    main.embedder = FakeEmbedder()
    return TestClient(main.app)


def auth():
    return ("boss", "secret")


def upload_payload(conversation_id="conv1", update_at=1000):
    return {
        "assistantId": "assistant1",
        "conversationId": conversation_id,
        "conversationTitle": "Project Notes",
        "conversationUpdateAt": update_at,
        "nodes": [
            {
                "nodeId": "node1",
                "nodeIndex": 0,
                "selectIndex": 1,
                "messages": [
                    {
                        "messageId": "old",
                        "role": "assistant",
                        "text": "Python draft",
                        "createdAt": 900,
                        "isSelected": False,
                    },
                    {
                        "messageId": "msg1",
                        "role": "assistant",
                        "text": "Kotlin vector recall implementation",
                        "createdAt": 1000,
                        "isSelected": True,
                    },
                ],
            }
        ],
    }


def test_handshake_auth(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)

    assert client.post("/api/v1/handshake", auth=auth()).status_code == 200
    assert client.post("/api/v1/handshake", auth=("boss", "bad")).status_code == 401


def test_diff_marks_missing_and_old_conversations_dirty(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    client.post("/api/v1/sync/upload", json=upload_payload(update_at=1000), auth=auth())

    response = client.post(
        "/api/v1/sync/diff",
        json={
            "assistantId": "assistant1",
            "conversations": [
                {"conversationId": "conv1", "updateAt": 1000},
                {"conversationId": "conv2", "updateAt": 10},
                {"conversationId": "conv1", "updateAt": 2000},
            ],
        },
        auth=auth(),
    )

    assert response.status_code == 200
    assert response.json()["dirty"] == ["conv2", "conv1"]


def test_upload_deletes_stale_messages(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    first = client.post("/api/v1/sync/upload", json=upload_payload(), auth=auth())
    assert first.json() == {"synced": 2, "deleted": 0}

    payload = upload_payload()
    payload["nodes"][0]["messages"] = payload["nodes"][0]["messages"][1:]
    second = client.post("/api/v1/sync/upload", json=payload, auth=auth())

    assert second.status_code == 200
    assert second.json() == {"synced": 1, "deleted": 1}


def test_search_filters_role_focus_and_exclude_and_selected_only(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    client.post("/api/v1/sync/upload", json=upload_payload("conv1"), auth=auth())
    client.post("/api/v1/sync/upload", json=upload_payload("conv2"), auth=auth())

    response = client.post(
        "/api/v1/recall/search",
        json={
            "assistantId": "assistant1",
            "query": "kotlin",
            "role": "assistant",
            "limit": 10,
            "excludeConversationId": "conv2",
        },
        auth=auth(),
    )

    assert response.status_code == 200
    items = response.json()["results"]
    assert [item["conversationId"] for item in items] == ["conv1"]
    assert [item["messageId"] for item in items] == ["msg1"]

    focused = client.post(
        "/api/v1/recall/search",
        json={
            "assistantId": "assistant1",
            "query": "kotlin",
            "role": "assistant",
            "limit": 10,
            "focusConversationId": "conv2",
        },
        auth=auth(),
    ).json()["results"]
    assert [item["conversationId"] for item in focused] == ["conv2"]
