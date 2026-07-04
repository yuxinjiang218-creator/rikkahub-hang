import importlib
import os
import sys
import types

from fastapi.testclient import TestClient


class FakeEmbedder:
    def embed(self, text: str) -> list[float]:
        return self.embed_many([text])[0]

    def embed_many(self, texts: list[str]) -> list[list[float]]:
        return [self._embed_one(text) for text in texts]

    def _embed_one(self, text: str) -> list[float]:
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
    monkeypatch.setenv("CHUNK_MAX_UNITS", "8")
    monkeypatch.setenv("CHUNK_OVERLAP_UNITS", "2")
    monkeypatch.setenv("CHUNK_MIN_UNITS", "2")
    monkeypatch.setenv("EMBED_BATCH_SIZE", "2")

    for name in ["main", "auth", "db", "vector_db", "embedder", "config", "models", "chunker"]:
        sys.modules.pop(name, None)
    sys.modules["embedder"] = types.SimpleNamespace(embedder=FakeEmbedder())
    main = importlib.import_module("main")
    main.embedder = FakeEmbedder()
    return TestClient(main.app)


def login(client, username="boss", password="secret", device_name="test-device"):
    response = client.post(
        "/api/v1/auth/login",
        json={"username": username, "password": password, "deviceName": device_name},
    )
    assert response.status_code == 200
    return response.json()


def auth_headers(client):
    token = login(client)["deviceToken"]
    return {"Authorization": f"Bearer {token}"}


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

    token = login(client, device_name="phone-1")
    assert token["deviceToken"].startswith("rvk_")
    assert token["tokenPrefix"] == token["deviceToken"][:16]
    assert client.post(
        "/api/v1/handshake",
        headers={"Authorization": f"Bearer {token['deviceToken']}"},
    ).status_code == 200
    assert client.post("/api/v1/handshake", auth=("boss", "bad")).status_code == 401


def test_login_rejects_wrong_or_unknown_credentials(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)

    wrong_password = client.post(
        "/api/v1/auth/login",
        json={"username": "boss", "password": "bad", "deviceName": "phone"},
    )
    unknown_user = client.post(
        "/api/v1/auth/login",
        json={"username": "nobody", "password": "secret", "deviceName": "phone"},
    )

    assert wrong_password.status_code == 401
    assert unknown_user.status_code == 401


def test_multiple_devices_and_revoke_one_token(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    first = login(client, device_name="phone-1")
    second = login(client, device_name="phone-2")
    first_headers = {"Authorization": f"Bearer {first['deviceToken']}"}
    second_headers = {"Authorization": f"Bearer {second['deviceToken']}"}

    assert client.post("/api/v1/handshake", headers=first_headers).status_code == 200
    assert client.post("/api/v1/handshake", headers=second_headers).status_code == 200

    import main
    from auth import now_millis

    assert main.db.revoke_device_token(first["tokenPrefix"], now_millis()) == 1
    assert client.post("/api/v1/handshake", headers=first_headers).status_code == 401
    assert client.post("/api/v1/handshake", headers=second_headers).status_code == 200


def test_login_rate_limit(tmp_path, monkeypatch):
    monkeypatch.setenv("LOGIN_FAIL_LIMIT", "2")
    client = build_client(tmp_path, monkeypatch)

    for _ in range(2):
        assert client.post(
            "/api/v1/auth/login",
            json={"username": "boss", "password": "bad", "deviceName": "phone"},
        ).status_code == 401

    blocked = client.post(
        "/api/v1/auth/login",
        json={"username": "boss", "password": "secret", "deviceName": "phone"},
    )
    assert blocked.status_code == 429


def test_request_body_limit(tmp_path, monkeypatch):
    monkeypatch.setenv("REQUEST_MAX_BYTES", "200")
    client = build_client(tmp_path, monkeypatch)

    response = client.post(
        "/api/v1/recall/search",
        json={"assistantId": "assistant1", "query": "x" * 500, "role": "assistant", "limit": 10},
        headers=auth_headers(client),
    )

    assert response.status_code == 413


def test_default_admin_password_refuses_startup(tmp_path, monkeypatch):
    monkeypatch.setenv("DB_PATH", str(tmp_path / "rikka.db"))
    monkeypatch.delenv("VECTOR_USERNAME", raising=False)
    monkeypatch.delenv("VECTOR_PASSWORD", raising=False)
    monkeypatch.delenv("ALLOW_INSECURE_DEFAULTS", raising=False)
    monkeypatch.setenv("EMBED_API_KEY", "test")

    for name in ["main", "auth", "db", "vector_db", "embedder", "config", "models", "chunker"]:
        sys.modules.pop(name, None)
    sys.modules["embedder"] = types.SimpleNamespace(embedder=FakeEmbedder())

    import pytest

    with pytest.raises(RuntimeError, match="admin/admin"):
        importlib.import_module("main")


def test_diff_marks_missing_and_old_conversations_dirty(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    client.post("/api/v1/sync/upload", json=upload_payload(update_at=1000), headers=auth_headers(client))

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
        headers=auth_headers(client),
    )

    assert response.status_code == 200
    assert response.json()["dirty"] == ["conv2", "conv1"]


def test_upload_deletes_stale_messages(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    first = client.post("/api/v1/sync/upload", json=upload_payload(), headers=auth_headers(client))
    assert first.json() == {"synced": 2, "deleted": 0}

    payload = upload_payload()
    payload["nodes"][0]["messages"] = payload["nodes"][0]["messages"][1:]
    second = client.post("/api/v1/sync/upload", json=payload, headers=auth_headers(client))

    assert second.status_code == 200
    assert second.json() == {"synced": 1, "deleted": 1}


def test_delete_conversation_removes_messages_chunks_and_vectors(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    client.post("/api/v1/sync/upload", json=upload_payload("conv1"), headers=auth_headers(client))

    response = client.post(
        "/api/v1/sync/delete",
        json={"assistantId": "assistant1", "conversationId": "conv1"},
        headers=auth_headers(client),
    )

    assert response.status_code == 200
    assert response.json() == {"deleted": 2}
    search = client.post(
        "/api/v1/recall/search",
        json={"assistantId": "assistant1", "query": "kotlin", "role": "assistant", "limit": 10},
        headers=auth_headers(client),
    ).json()["results"]
    assert search == []


def test_diff_deletes_server_conversations_missing_locally(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    client.post("/api/v1/sync/upload", json=upload_payload("conv1"), headers=auth_headers(client))

    response = client.post(
        "/api/v1/sync/diff",
        json={"assistantId": "assistant1", "conversations": []},
        headers=auth_headers(client),
    )

    assert response.status_code == 200
    assert response.json() == {"dirty": [], "deleted": 2}
    search = client.post(
        "/api/v1/recall/search",
        json={"assistantId": "assistant1", "query": "kotlin", "role": "assistant", "limit": 10},
        headers=auth_headers(client),
    ).json()["results"]
    assert search == []


def test_search_filters_role_focus_and_exclude_and_selected_only(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    client.post("/api/v1/sync/upload", json=upload_payload("conv1"), headers=auth_headers(client))
    client.post("/api/v1/sync/upload", json=upload_payload("conv2"), headers=auth_headers(client))

    response = client.post(
        "/api/v1/recall/search",
        json={
            "assistantId": "assistant1",
            "query": "kotlin",
            "role": "assistant",
            "limit": 10,
            "excludeConversationId": "conv2",
        },
        headers=auth_headers(client),
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
        headers=auth_headers(client),
    ).json()["results"]
    assert [item["conversationId"] for item in focused] == ["conv2"]


def test_long_message_chunks_return_best_chunk_snippet_and_message_ref(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    payload = upload_payload()
    payload["nodes"][0]["messages"][1]["text"] = (
        "alpha beta gamma delta epsilon zeta eta theta. "
        "python archive details live here. "
        "iota kappa lambda mu nu xi omicron pi. "
        "kotlin vector recall implementation lives in this exact section."
    )
    client.post("/api/v1/sync/upload", json=payload, headers=auth_headers(client))

    response = client.post(
        "/api/v1/recall/search",
        json={"assistantId": "assistant1", "query": "kotlin", "role": "assistant", "limit": 10},
        headers=auth_headers(client),
    )

    assert response.status_code == 200
    items = response.json()["results"]
    assert len([item for item in items if item["messageId"] == "msg1"]) == 1
    assert items[0]["messageId"] == "msg1"
    assert "kotlin vector recall" in items[0]["snippet"].lower()
    assert "python archive" not in items[0]["snippet"].lower()


def test_upload_rebuilds_chunks_for_updated_message(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    client.post("/api/v1/sync/upload", json=upload_payload(), headers=auth_headers(client))

    payload = upload_payload(update_at=2000)
    payload["nodes"][0]["messages"][1]["text"] = "Python replacement section"
    client.post("/api/v1/sync/upload", json=payload, headers=auth_headers(client))

    kotlin_results = client.post(
        "/api/v1/recall/search",
        json={"assistantId": "assistant1", "query": "kotlin", "role": "assistant", "limit": 10},
        headers=auth_headers(client),
    ).json()["results"]
    python_results = client.post(
        "/api/v1/recall/search",
        json={"assistantId": "assistant1", "query": "python", "role": "assistant", "limit": 10},
        headers=auth_headers(client),
    ).json()["results"]

    assert all("kotlin vector recall" not in item["snippet"].lower() for item in kotlin_results)
    assert [item["messageId"] for item in python_results] == ["msg1"]


def test_select_index_switch_removes_old_selected_vectors(tmp_path, monkeypatch):
    client = build_client(tmp_path, monkeypatch)
    client.post("/api/v1/sync/upload", json=upload_payload(), headers=auth_headers(client))

    payload = upload_payload(update_at=2000)
    payload["nodes"][0]["selectIndex"] = 0
    payload["nodes"][0]["messages"][0]["isSelected"] = True
    payload["nodes"][0]["messages"][1]["isSelected"] = False
    client.post("/api/v1/sync/upload", json=payload, headers=auth_headers(client))

    kotlin_results = client.post(
        "/api/v1/recall/search",
        json={"assistantId": "assistant1", "query": "kotlin", "role": "assistant", "limit": 10},
        headers=auth_headers(client),
    ).json()["results"]
    python_results = client.post(
        "/api/v1/recall/search",
        json={"assistantId": "assistant1", "query": "python", "role": "assistant", "limit": 10},
        headers=auth_headers(client),
    ).json()["results"]

    assert "msg1" not in [item["messageId"] for item in kotlin_results]
    assert [item["messageId"] for item in python_results] == ["old"]


def test_old_message_vector_schema_is_rebuilt_to_chunks(tmp_path, monkeypatch):
    db_path = tmp_path / "rikka.db"
    import sqlite3
    from passlib.hash import bcrypt

    conn = sqlite3.connect(db_path)
    conn.executescript(
        """
        CREATE TABLE users (
            id TEXT PRIMARY KEY,
            username TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );
        CREATE TABLE messages (
            conversation_id TEXT NOT NULL,
            node_id TEXT NOT NULL,
            message_id TEXT NOT NULL,
            assistant_id TEXT NOT NULL,
            user_id TEXT NOT NULL,
            node_index INTEGER NOT NULL,
            select_index INTEGER NOT NULL,
            is_selected INTEGER NOT NULL,
            role TEXT NOT NULL,
            text TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            conversation_title TEXT NOT NULL,
            conversation_update_at INTEGER NOT NULL,
            PRIMARY KEY (conversation_id, node_id, message_id)
        );
        CREATE TABLE vector_entries(
            message_pk TEXT PRIMARY KEY,
            rowid_value INTEGER UNIQUE NOT NULL,
            embedding_json TEXT NOT NULL
        );
        """
    )
    conn.execute(
        "INSERT INTO users VALUES ('legacy-user', 'boss', ?, 1)",
        (bcrypt.hash("secret"),),
    )
    conn.execute(
        """
        INSERT INTO messages VALUES (
            'conv1', 'node1', 'msg1', 'assistant1', 'legacy-user', 0, 0, 1, 'assistant',
            'legacy kotlin chunk content', 1000, 'Legacy', 1000
        )
        """
    )
    conn.execute(
        "INSERT INTO vector_entries VALUES ('conv1:node1:msg1', 1, '[1.0, 0.0, 0.0]')"
    )
    conn.commit()
    conn.close()

    client = build_client(tmp_path, monkeypatch)
    response = client.post(
        "/api/v1/recall/search",
        json={"assistantId": "assistant1", "query": "kotlin", "role": "assistant", "limit": 10},
        headers=auth_headers(client),
    )

    assert response.status_code == 200
    assert [item["messageId"] for item in response.json()["results"]] == ["msg1"]
