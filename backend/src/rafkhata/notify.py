"""Push notifications through Firebase Cloud Messaging (HTTP v1). A no-op when FCM isn't configured."""

from __future__ import annotations

import logging
from typing import Protocol

import httpx

from rafkhata.config import Settings

log = logging.getLogger(__name__)
FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


class Notifier(Protocol):
    def send(self, tokens: list[str], title: str, body: str, data: dict[str, str]) -> list[str]:
        """Send to each token. Returns tokens FCM says are no longer valid."""
        ...


class NullNotifier:
    def __init__(self) -> None:
        self.sent: list[tuple[list[str], str, str, dict[str, str]]] = []

    def send(self, tokens: list[str], title: str, body: str, data: dict[str, str]) -> list[str]:
        self.sent.append((tokens, title, body, data))
        return []


class FCMNotifier:
    def __init__(self, project_id: str, service_account_file: str) -> None:
        from google.oauth2 import service_account

        self.project_id = project_id
        self.credentials = service_account.Credentials.from_service_account_file(
            service_account_file, scopes=[FCM_SCOPE]
        )

    def _token(self) -> str:
        from google.auth.transport.requests import Request

        if not self.credentials.valid:
            self.credentials.refresh(Request())
        return self.credentials.token

    def send(self, tokens: list[str], title: str, body: str, data: dict[str, str]) -> list[str]:
        url = f"https://fcm.googleapis.com/v1/projects/{self.project_id}/messages:send"
        headers = {"Authorization": f"Bearer {self._token()}"}
        invalid: list[str] = []
        with httpx.Client(timeout=15) as client:
            for token in tokens:
                message = {
                    "message": {
                        "token": token,
                        "notification": {"title": title, "body": body},
                        "data": data,
                        "android": {"priority": "high", "notification": {"channel_id": "notes_ready"}},
                    }
                }
                try:
                    resp = client.post(url, json=message, headers=headers)
                except httpx.HTTPError as exc:
                    log.warning("FCM send failed: %s", exc)
                    continue
                if resp.status_code == 404 or (resp.status_code == 400 and "UNREGISTERED" in resp.text):
                    invalid.append(token)
                elif resp.status_code >= 300:
                    log.warning("FCM error %s: %s", resp.status_code, resp.text[:300])
        return invalid


def make_notifier(settings: Settings) -> Notifier:
    if settings.fcm_project_id and settings.fcm_service_account_file:
        return FCMNotifier(settings.fcm_project_id, settings.fcm_service_account_file)
    return NullNotifier()
