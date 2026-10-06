from __future__ import annotations

from conftest import login
from fastapi.testclient import TestClient


def make_space(client: TestClient, headers: dict[str, str], name: str = "CSE-21 Section A") -> dict:
    resp = client.post("/spaces", headers=headers, json={"name": name, "section_label": "A"})
    assert resp.status_code == 201, resp.text
    return resp.json()


def test_space_create_join_roles_and_leave(client: TestClient, alice, bob) -> None:
    space = make_space(client, alice)
    assert space["role"] == "owner"
    assert len(space["invite_code"]) == 8

    joined = client.post("/spaces/join", headers=bob, json={"invite_code": space["invite_code"].lower()})
    assert joined.status_code == 200
    assert joined.json()["role"] == "member"
    assert joined.json()["member_count"] == 2
    # Joining twice is harmless.
    assert client.post("/spaces/join", headers=bob, json={"invite_code": space["invite_code"]}).status_code == 200

    assert client.post("/spaces/join", headers=bob, json={"invite_code": "NOPE1234"}).status_code == 404
    assert [s["name"] for s in client.get("/spaces", headers=bob).json()] == ["CSE-21 Section A"]

    bob_id = client.get("/me", headers=bob).json()["id"]
    # Only the owner can change roles.
    assert client.patch(f"/spaces/{space['id']}/members/{bob_id}", headers=bob, json={"role": "cr"}).status_code == 403
    detail = client.patch(f"/spaces/{space['id']}/members/{bob_id}", headers=alice, json={"role": "cr"}).json()
    assert {m["name"]: m["role"] for m in detail["members"]} == {"Alice": "owner", "Bob": "cr"}

    # CR can rotate the invite code.
    rotated = client.post(f"/spaces/{space['id']}/invite-code", headers=bob)
    assert rotated.status_code == 200
    assert rotated.json()["invite_code"] != space["invite_code"]

    # When the owner leaves, the CR becomes owner.
    assert client.delete(f"/spaces/{space['id']}/members/me", headers=alice).status_code == 204
    assert client.get(f"/spaces/{space['id']}", headers=alice).status_code == 404
    assert client.get(f"/spaces/{space['id']}", headers=bob).json()["role"] == "owner"


def test_course_crud_and_routine(client: TestClient, alice) -> None:
    resp = client.post(
        "/courses",
        headers=alice,
        json={
            "title": "Operating Systems",
            "code": "CSE 313",
            "teacher_name": "Dr. Rahman",
            "glossary": ["deadlock", "Deadlock", " semaphore ", ""],
            "routine": [{"weekday": 6, "start_time": "09:00", "end_time": "10:15", "room": "301"}],
        },
    )
    assert resp.status_code == 201, resp.text
    course = resp.json()
    assert course["glossary"] == ["deadlock", "semaphore"]
    assert course["routine"][0]["weekday"] == 6
    assert course["is_owner"] and course["can_edit"]
    assert course["consent_confirmed"] is False

    bad = client.put(
        f"/courses/{course['id']}/routine",
        headers=alice,
        json={"slots": [{"weekday": 1, "start_time": "11:00", "end_time": "10:00"}]},
    )
    assert bad.status_code == 422

    routine = client.put(
        f"/courses/{course['id']}/routine",
        headers=alice,
        json={
            "slots": [
                {"weekday": 0, "start_time": "08:00", "end_time": "09:00"},
                {"weekday": 2, "start_time": "08:00", "end_time": "09:00"},
            ]
        },
    )
    assert [s["weekday"] for s in routine.json()["routine"]] == [0, 2]

    patched = client.patch(f"/courses/{course['id']}", headers=alice, json={"notes_lang": "en", "title": "OS"})
    assert patched.json()["title"] == "OS"
    assert patched.json()["notes_lang"] == "en"

    assert client.post(f"/courses/{course['id']}/consent", headers=alice).json()["consent_confirmed"] is True
    assert len(client.get("/courses", headers=alice).json()) == 1

    assert client.delete(f"/courses/{course['id']}", headers=alice).status_code == 204
    assert client.get("/courses", headers=alice).json() == []


def test_section_course_permissions(client: TestClient, alice, bob) -> None:
    space = make_space(client, alice)
    course = client.post("/courses", headers=alice, json={"title": "Data Structures", "space_id": space["id"]}).json()
    carol = login(client, "carol@example.com", "Carol")

    # Not a member yet: invisible.
    assert client.get(f"/courses/{course['id']}", headers=bob).status_code == 404
    client.post("/spaces/join", headers=bob, json={"invite_code": space["invite_code"]})
    visible = client.get(f"/courses/{course['id']}", headers=bob).json()
    assert visible["space_name"] == "CSE-21 Section A"
    assert visible["can_edit"] is False
    assert client.patch(f"/courses/{course['id']}", headers=bob, json={"title": "DSA"}).status_code == 403

    # Members can't attach courses to sections they don't belong to.
    assert client.post("/courses", headers=carol, json={"title": "X", "space_id": space["id"]}).status_code == 404

    bob_id = client.get("/me", headers=bob).json()["id"]
    client.patch(f"/spaces/{space['id']}/members/{bob_id}", headers=alice, json={"role": "cr"})
    assert client.patch(f"/courses/{course['id']}", headers=bob, json={"title": "DSA"}).status_code == 200
    # Only the owner may delete.
    assert client.delete(f"/courses/{course['id']}", headers=bob).status_code == 403
