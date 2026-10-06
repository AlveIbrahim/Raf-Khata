from __future__ import annotations

from conftest import login, make_settings
from fastapi.testclient import TestClient

from rafkhata.api.app import create_app


def test_healthz(client: TestClient) -> None:
    resp = client.get("/healthz")
    assert resp.status_code == 200
    assert resp.json()["ok"] is True


def test_requires_auth(client: TestClient) -> None:
    assert client.get("/me").status_code == 401
    assert client.get("/me", headers={"Authorization": "Bearer nonsense"}).status_code == 401


def test_dev_login_and_profile(client: TestClient) -> None:
    headers = login(client, "Karim@Example.com", "Karim")
    me = client.get("/me", headers=headers).json()
    assert me["email"] == "karim@example.com"
    assert me["notes_lang"] == "bn"
    assert me["onboarded"] is False

    resp = client.patch(
        "/me",
        headers=headers,
        json={"university": "BUET", "department": "CSE", "batch": "21", "notes_lang": "mixed", "onboarded": True},
    )
    assert resp.status_code == 200
    assert resp.json()["university"] == "BUET"
    assert resp.json()["notes_lang"] == "mixed"

    assert client.patch("/me", headers=headers, json={"notes_lang": "fr"}).status_code == 422


def test_dev_login_disabled(tmp_path) -> None:
    app = create_app(make_settings(tmp_path, dev_login_enabled=False))
    with TestClient(app) as c:
        assert c.post("/auth/dev-login", json={"email": "a@b.c"}).status_code == 404


def test_google_login_creates_then_reuses_user(client: TestClient) -> None:
    first = client.post("/auth/google", json={"id_token": "google:sub-123:nadia@example.com"})
    assert first.status_code == 200, first.text
    second = client.post("/auth/google", json={"id_token": "google:sub-123:nadia@example.com"})
    assert second.json()["user"]["id"] == first.json()["user"]["id"]
    assert client.post("/auth/google", json={"id_token": "garbage-token"}).status_code == 401


def test_google_login_links_existing_email(client: TestClient) -> None:
    dev = client.post("/auth/dev-login", json={"email": "sumi@example.com"}).json()
    linked = client.post("/auth/google", json={"id_token": "google:sub-9:sumi@example.com"}).json()
    assert linked["user"]["id"] == dev["user"]["id"]


def test_refresh_rotates_and_detects_reuse(client: TestClient) -> None:
    tokens = client.post("/auth/dev-login", json={"email": "r@example.com"}).json()
    first_refresh = tokens["refresh_token"]

    rotated = client.post("/auth/refresh", json={"refresh_token": first_refresh})
    assert rotated.status_code == 200
    new_refresh = rotated.json()["refresh_token"]
    assert new_refresh != first_refresh
    assert client.get("/me", headers={"Authorization": f"Bearer {rotated.json()['access_token']}"}).status_code == 200

    # Re-using the old token revokes every active token of the user.
    assert client.post("/auth/refresh", json={"refresh_token": first_refresh}).status_code == 401
    assert client.post("/auth/refresh", json={"refresh_token": new_refresh}).status_code == 401


def test_logout_revokes_refresh_token(client: TestClient) -> None:
    tokens = client.post("/auth/dev-login", json={"email": "l@example.com"}).json()
    assert client.post("/auth/logout", json={"refresh_token": tokens["refresh_token"]}).status_code == 204
    assert client.post("/auth/refresh", json={"refresh_token": tokens["refresh_token"]}).status_code == 401
