from __future__ import annotations

import uuid
from datetime import date, timedelta

from conftest import login
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session
from test_lectures import create_lecture

from rafkhata.models import LECTURE_READY, Deadline, Lecture, Note, StudyPack, Transcript
from rafkhata.pipeline.normalize import normalize_for_match

SEGMENTS = [
    {"id": "s0001", "start": 0.0, "end": 5.0, "speaker": "T", "text": "আজকে আমরা deadlock নিয়ে কথা বলব।"},
    {"id": "s0002", "start": 5.0, "end": 11.0, "speaker": "T", "text": "Deadlock এর ৪টা condition আছে।"},
    {"id": "s0003", "start": 11.0, "end": 15.0, "speaker": "T", "text": "আগামী রবিবার CT হবে।"},
]


def seed_ready_lecture(db: Session, lecture_id: str, course_id: str | None = None) -> None:
    lid = uuid.UUID(lecture_id)
    lecture = db.get(Lecture, lid)
    lecture.status = LECTURE_READY
    lecture.progress = 100
    db.add(
        Transcript(
            lecture_id=lid,
            version=1,
            kind="corrected",
            provider="mock",
            is_current=True,
            language="bn",
            segments=SEGMENTS,
            search_text=normalize_for_match(" ".join(s["text"] for s in SEGMENTS)),
        )
    )
    db.add(
        Note(
            lecture_id=lid,
            lang="bn",
            version=1,
            model="fake",
            content={"title": "Deadlock", "summary": ["Deadlock এর চারটি শর্ত"]},
            html="<h1>Deadlock</h1>",
            markdown="# Deadlock\n\n- Deadlock এর চারটি শর্ত: mutual exclusion",
            search_text=normalize_for_match("Deadlock এর চারটি শর্ত: mutual exclusion"),
            is_current=True,
        )
    )
    db.add(StudyPack(lecture_id=lid, lang="bn", version=1, model="fake", content={"flashcards": []}, is_current=True))
    db.add(
        Deadline(
            lecture_id=lid,
            course_id=uuid.UUID(course_id) if course_id else None,
            kind="ct",
            title="CT on deadlock",
            due_date=date.today() + timedelta(days=5),
            due_text="আগামী রবিবার",
        )
    )
    db.commit()


def test_transcript_notes_study(client: TestClient, alice, db: Session) -> None:
    lecture = create_lecture(client, alice)
    assert client.get(f"/lectures/{lecture['id']}/transcript", headers=alice).status_code == 404
    pending = client.get(f"/lectures/{lecture['id']}/notes", headers=alice)
    assert pending.status_code == 202

    seed_ready_lecture(db, lecture["id"])

    transcript = client.get(f"/lectures/{lecture['id']}/transcript", headers=alice).json()
    assert [s["id"] for s in transcript["segments"]] == ["s0001", "s0002", "s0003"]
    assert transcript["can_edit"] is True

    notes = client.get(f"/lectures/{lecture['id']}/notes", headers=alice)
    assert notes.status_code == 200
    assert notes.json()["content"]["title"] == "Deadlock"
    assert client.get(f"/lectures/{lecture['id']}/notes?lang=en", headers=alice).status_code == 404

    regen = client.post(f"/lectures/{lecture['id']}/notes/regenerate", headers=alice, json={"lang": "en"})
    assert regen.status_code == 202
    assert client.get(f"/lectures/{lecture['id']}/notes?lang=en", headers=alice).status_code == 202

    assert client.get(f"/lectures/{lecture['id']}/study", headers=alice).status_code == 200
    assert client.get(f"/lectures/{lecture['id']}", headers=alice).json()["notes_langs"] == ["bn"]


def test_edit_transcript_permissions(client: TestClient, alice, bob, db: Session) -> None:
    space = client.post("/spaces", headers=alice, json={"name": "Sec"}).json()
    course = client.post("/courses", headers=alice, json={"title": "OS", "space_id": space["id"]}).json()
    lecture = create_lecture(client, alice, course["id"])
    seed_ready_lecture(db, lecture["id"], course["id"])
    client.post("/spaces/join", headers=bob, json={"invite_code": space["invite_code"]})

    url = f"/lectures/{lecture['id']}/transcript/segments/s0002"
    assert client.patch(url, headers=bob, json={"text": "x"}).status_code == 403
    edited = client.patch(url, headers=alice, json={"text": "Deadlock এর  চারটা condition আছে ।"})
    assert edited.status_code == 200
    assert edited.json()["text"] == "Deadlock এর চারটা condition আছে।"
    assert (
        client.patch(
            f"/lectures/{lecture['id']}/transcript/segments/s9999", headers=alice, json={"text": "x"}
        ).status_code
        == 404
    )

    hits = client.get("/search", headers=bob, params={"q": "চারটা"}).json()
    assert hits and hits[0]["segment_id"] == "s0002"


def test_search_and_deadlines(client: TestClient, alice, bob, db: Session) -> None:
    lecture = create_lecture(client, alice)
    seed_ready_lecture(db, lecture["id"])

    # Bangla digits and punctuation don't matter.
    hits = client.get("/search", headers=alice, params={"q": "4টা condition"}).json()
    assert hits[0]["source"] == "transcript"
    assert hits[0]["t_start"] == 5.0
    notes_hits = client.get("/search", headers=alice, params={"q": "MUTUAL exclusion"}).json()
    assert notes_hits[0]["source"] == "notes"
    assert client.get("/search", headers=bob, params={"q": "deadlock"}).json() == []

    deadlines = client.get("/deadlines", headers=alice).json()
    assert [d["kind"] for d in deadlines] == ["ct"]
    assert client.get("/deadlines", headers=bob).json() == []


def test_feedback(client: TestClient, alice) -> None:
    lecture = create_lecture(client, alice)
    url = f"/lectures/{lecture['id']}/feedback"
    assert client.post(url, headers=alice, json={"kind": "rating"}).status_code == 422
    assert client.post(url, headers=alice, json={"kind": "rating", "rating": 5}).status_code == 204
    assert client.post(url, headers=alice, json={"kind": "report", "comment": "wrong formula"}).status_code == 204


def test_export_and_delete_account(client: TestClient, alice, db: Session) -> None:
    space = client.post("/spaces", headers=alice, json={"name": "Shared"}).json()
    dave = login(client, "dave@example.com", "Dave")
    client.post("/spaces/join", headers=dave, json={"invite_code": space["invite_code"]})
    course = client.post("/courses", headers=alice, json={"title": "Math", "space_id": space["id"]}).json()
    lecture = create_lecture(client, alice, course["id"])
    seed_ready_lecture(db, lecture["id"], course["id"])

    export = client.get("/me/export", headers=alice)
    assert export.status_code == 200
    data = export.json()
    assert data["user"]["email"] == "alice@example.com"
    assert data["lectures"][0]["transcript"][0]["id"] == "s0001"
    assert "attachment" in export.headers["content-disposition"]

    assert client.delete("/me", headers=alice).status_code == 204
    assert client.get("/me", headers=alice).status_code == 401
    # The section and its course survive under Dave; Alice's lecture is gone.
    assert client.get(f"/spaces/{space['id']}", headers=dave).json()["role"] == "owner"
    assert client.get(f"/courses/{course['id']}", headers=dave).json()["is_owner"] is True
    assert client.get("/lectures", headers=dave).json() == []
