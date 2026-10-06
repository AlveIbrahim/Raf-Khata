from __future__ import annotations

from conftest import new_id
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.orm import Session

from rafkhata import jobs
from rafkhata.models import Job


def upload_segment(
    client: TestClient, headers: dict, lecture_id: str, idx: int, data: bytes = b"\xff\xf1fake-aac"
) -> None:
    url = client.post(
        f"/lectures/{lecture_id}/segments/{idx}/upload-url",
        headers=headers,
        json={"size_bytes": len(data), "content_type": "audio/aac"},
    )
    assert url.status_code == 200, url.text
    body = url.json()
    assert body["method"] == "PUT"
    put = client.put(body["url"], content=data, headers=body["headers"])
    assert put.status_code == 200, put.text
    done = client.post(
        f"/lectures/{lecture_id}/segments/{idx}/complete",
        headers=headers,
        json={"size_bytes": len(data), "duration_s": 300},
    )
    assert done.status_code == 204, done.text


def create_lecture(client: TestClient, headers: dict, course_id: str | None = None, **extra) -> dict:
    lecture_id = new_id()
    resp = client.put(
        f"/lectures/{lecture_id}",
        headers=headers,
        json={"course_id": course_id, "title": "Deadlock", "consent_confirmed": True, **extra},
    )
    assert resp.status_code == 200, resp.text
    return resp.json()


def test_upload_and_finalize_flow(client: TestClient, alice, db: Session) -> None:
    course = client.post("/courses", headers=alice, json={"title": "OS", "notes_lang": "mixed"}).json()
    lecture = create_lecture(client, alice, course["id"])
    assert lecture["status"] == "created"
    assert lecture["notes_lang"] == "mixed"
    assert lecture["course_title"] == "OS"

    # Creating again with the same id is idempotent.
    again = client.put(
        f"/lectures/{lecture['id']}", headers=alice, json={"course_id": course["id"], "title": "Deadlock"}
    )
    assert again.status_code == 200

    upload_segment(client, alice, lecture["id"], 0)
    missing = client.post(
        f"/lectures/{lecture['id']}/finalize", headers=alice, json={"segment_count": 2, "duration_s": 600}
    )
    assert missing.status_code == 409
    assert missing.json()["detail"]["missing"] == [1]

    upload_segment(client, alice, lecture["id"], 1)
    final = client.post(
        f"/lectures/{lecture['id']}/finalize", headers=alice, json={"segment_count": 2, "duration_s": 600}
    )
    assert final.status_code == 200
    assert final.json()["status"] == "queued"

    # Finalizing twice does not queue twice.
    client.post(f"/lectures/{lecture['id']}/finalize", headers=alice, json={"segment_count": 2, "duration_s": 600})
    queued = db.scalars(select(Job).where(Job.kind == jobs.PROCESS_LECTURE)).all()
    assert len(queued) == 1

    # No more uploads after finalize.
    late = client.post(f"/lectures/{lecture['id']}/segments/2/upload-url", headers=alice, json={"size_bytes": 10})
    assert late.status_code == 409

    assert client.get("/courses", headers=alice).json()[0]["consent_confirmed"] is True


def test_segment_complete_requires_uploaded_file(client: TestClient, alice) -> None:
    lecture = create_lecture(client, alice)
    client.post(f"/lectures/{lecture['id']}/segments/0/upload-url", headers=alice, json={"size_bytes": 10})
    resp = client.post(
        f"/lectures/{lecture['id']}/segments/0/complete", headers=alice, json={"size_bytes": 10, "duration_s": 5}
    )
    assert resp.status_code == 409


def test_signed_urls_are_checked(client: TestClient, alice) -> None:
    lecture = create_lecture(client, alice)
    body = client.post(f"/lectures/{lecture['id']}/segments/0/upload-url", headers=alice, json={"size_bytes": 3}).json()
    tampered = body["url"].replace("sig=", "sig=0")
    assert client.put(tampered, content=b"abc").status_code == 403
    other_key = body["url"].replace("0000.aac", "0001.aac")
    assert client.put(other_key, content=b"abc").status_code == 403


def test_lecture_visibility(client: TestClient, alice, bob) -> None:
    mine = create_lecture(client, alice)
    assert client.get(f"/lectures/{mine['id']}", headers=bob).status_code == 404
    assert client.put(f"/lectures/{mine['id']}", headers=bob, json={"title": "hijack"}).status_code == 409

    space = client.post("/spaces", headers=alice, json={"name": "EEE-22"}).json()
    course = client.post("/courses", headers=alice, json={"title": "Circuits", "space_id": space["id"]}).json()
    shared = create_lecture(client, alice, course["id"])
    client.post("/spaces/join", headers=bob, json={"invite_code": space["invite_code"]})

    seen = client.get(f"/lectures/{shared['id']}", headers=bob).json()
    assert seen["is_mine"] is False
    assert seen["recorded_by_name"] == "Alice"
    assert {lec["id"] for lec in client.get("/lectures", headers=bob).json()} == {shared["id"]}
    # Only the recorder can upload or delete.
    assert (
        client.post(f"/lectures/{shared['id']}/segments/0/upload-url", headers=bob, json={"size_bytes": 3}).status_code
        == 403
    )
    assert client.delete(f"/lectures/{shared['id']}", headers=bob).status_code == 403

    # Bob can record into the section course too.
    bobs = create_lecture(client, bob, course["id"])
    assert bobs["space_id"] == space["id"]
    assert len(client.get(f"/lectures?course_id={course['id']}", headers=alice).json()) == 2


def test_bookmarks_and_photos(client: TestClient, alice) -> None:
    lecture = create_lecture(client, alice)
    resp = client.put(
        f"/lectures/{lecture['id']}/bookmarks",
        headers=alice,
        json={"bookmarks": [{"t_offset_s": 61.5, "kind": "important"}, {"t_offset_s": 300, "kind": "confused"}]},
    )
    assert resp.status_code == 200
    assert len(resp.json()) == 2

    photo_id = new_id()
    url = client.post(
        f"/lectures/{lecture['id']}/photos",
        headers=alice,
        json={"photo_id": photo_id, "t_offset_s": 120, "content_type": "image/jpeg", "size_bytes": 4},
    ).json()
    assert client.put(url["url"], content=b"jpeg", headers=url["headers"]).status_code == 200
    assert client.post(f"/lectures/{lecture['id']}/photos/{photo_id}/complete", headers=alice).status_code == 204


def test_delete_lecture_queues_storage_cleanup(client: TestClient, alice, db: Session) -> None:
    lecture = create_lecture(client, alice)
    upload_segment(client, alice, lecture["id"], 0)
    assert client.delete(f"/lectures/{lecture['id']}", headers=alice).status_code == 204
    assert client.get(f"/lectures/{lecture['id']}", headers=alice).status_code == 404
    cleanup = db.scalars(select(Job).where(Job.kind == jobs.DELETE_PREFIX)).all()
    assert [j.payload["prefix"] for j in cleanup] == [f"lectures/{lecture['id']}/"]


def test_retry_only_failed(client: TestClient, alice) -> None:
    lecture = create_lecture(client, alice)
    assert client.post(f"/lectures/{lecture['id']}/retry", headers=alice).status_code == 409
