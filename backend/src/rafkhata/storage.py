"""Object storage: S3-compatible (Cloudflare R2, MinIO, AWS) or local disk for development.

Clients never stream audio through the API; they get short-lived presigned URLs instead. The local
backend imitates that with HMAC-signed URLs served by `rafkhata.api.files`.
"""

from __future__ import annotations

import hashlib
import hmac
import re
import shutil
import time
import uuid
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import quote

from rafkhata.config import Settings

_KEY_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9/_.\-]{0,500}$")


def segment_key(lecture_id: uuid.UUID, idx: int) -> str:
    return f"lectures/{lecture_id}/segments/{idx:04d}.aac"


def photo_key(lecture_id: uuid.UUID, photo_id: uuid.UUID, ext: str = "jpg") -> str:
    return f"lectures/{lecture_id}/photos/{photo_id}.{ext}"


def audio_key(lecture_id: uuid.UUID) -> str:
    return f"lectures/{lecture_id}/audio.m4a"


def asr_raw_key(lecture_id: uuid.UUID, provider: str) -> str:
    return f"lectures/{lecture_id}/asr/{provider}.json"


def lecture_prefix(lecture_id: uuid.UUID) -> str:
    return f"lectures/{lecture_id}/"


def segments_prefix(lecture_id: uuid.UUID) -> str:
    return f"lectures/{lecture_id}/segments/"


@dataclass
class PresignedRequest:
    url: str
    method: str
    expires_at: datetime
    headers: dict[str, str] = field(default_factory=dict)


class Storage(ABC):
    @abstractmethod
    def presign_put(self, key: str, content_type: str, expires_s: int) -> PresignedRequest: ...

    @abstractmethod
    def presign_get(self, key: str, expires_s: int) -> str: ...

    @abstractmethod
    def put_file(self, key: str, path: Path, content_type: str) -> None: ...

    @abstractmethod
    def put_bytes(self, key: str, data: bytes, content_type: str) -> None: ...

    @abstractmethod
    def download_file(self, key: str, dest: Path) -> None: ...

    @abstractmethod
    def read_bytes(self, key: str) -> bytes: ...

    @abstractmethod
    def size(self, key: str) -> int | None:
        """Object size in bytes, or None if it does not exist."""

    @abstractmethod
    def delete(self, key: str) -> None: ...

    @abstractmethod
    def delete_prefix(self, prefix: str) -> int: ...


def _check_key(key: str) -> str:
    if not _KEY_RE.match(key) or ".." in key or key.startswith("/"):
        raise ValueError(f"invalid storage key: {key!r}")
    return key


class LocalStorage(Storage):
    def __init__(self, root: Path, base_url: str, secret: str) -> None:
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.base_url = base_url.rstrip("/")
        self._secret = secret.encode()

    def path_for(self, key: str) -> Path:
        path = (self.root / _check_key(key)).resolve()
        if self.root not in path.parents:
            raise ValueError("key escapes storage root")
        return path

    def _sign(self, method: str, key: str, exp: int) -> str:
        msg = f"{method}\n{key}\n{exp}".encode()
        return hmac.new(self._secret, msg, hashlib.sha256).hexdigest()

    def verify(self, method: str, key: str, exp: int, sig: str) -> bool:
        if exp < time.time():
            return False
        return hmac.compare_digest(self._sign(method, key, exp), sig)

    def _signed_url(self, method: str, key: str, expires_s: int) -> tuple[str, int]:
        exp = int(time.time()) + expires_s
        sig = self._sign(method, _check_key(key), exp)
        return f"{self.base_url}/files/{quote(key)}?exp={exp}&sig={sig}", exp

    def presign_put(self, key: str, content_type: str, expires_s: int) -> PresignedRequest:
        url, exp = self._signed_url("PUT", key, expires_s)
        return PresignedRequest(
            url=url, method="PUT", expires_at=datetime.fromtimestamp(exp, UTC), headers={"Content-Type": content_type}
        )

    def presign_get(self, key: str, expires_s: int) -> str:
        return self._signed_url("GET", key, expires_s)[0]

    def put_file(self, key: str, path: Path, content_type: str) -> None:
        dest = self.path_for(key)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, dest)

    def put_bytes(self, key: str, data: bytes, content_type: str) -> None:
        dest = self.path_for(key)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)

    def download_file(self, key: str, dest: Path) -> None:
        src = self.path_for(key)
        if not src.exists():
            raise FileNotFoundError(key)
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dest)

    def read_bytes(self, key: str) -> bytes:
        src = self.path_for(key)
        if not src.exists():
            raise FileNotFoundError(key)
        return src.read_bytes()

    def size(self, key: str) -> int | None:
        path = self.path_for(key)
        return path.stat().st_size if path.is_file() else None

    def delete(self, key: str) -> None:
        self.path_for(key).unlink(missing_ok=True)

    def delete_prefix(self, prefix: str) -> int:
        base = self.path_for(prefix.rstrip("/"))
        if not base.exists():
            return 0
        count = sum(1 for p in base.rglob("*") if p.is_file())
        shutil.rmtree(base)
        return count


class S3Storage(Storage):
    def __init__(self, settings: Settings) -> None:
        import boto3
        from botocore.config import Config

        if not settings.s3_bucket:
            raise ValueError("S3_BUCKET is required when STORAGE_BACKEND=s3")
        self.bucket = settings.s3_bucket
        self.client = boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint_url,
            region_name=settings.s3_region,
            aws_access_key_id=settings.s3_access_key_id,
            aws_secret_access_key=settings.s3_secret_access_key,
            config=Config(signature_version="s3v4", s3={"addressing_style": "path"}, retries={"max_attempts": 5}),
        )

    def presign_put(self, key: str, content_type: str, expires_s: int) -> PresignedRequest:
        url = self.client.generate_presigned_url(
            "put_object",
            Params={"Bucket": self.bucket, "Key": _check_key(key), "ContentType": content_type},
            ExpiresIn=expires_s,
        )
        return PresignedRequest(
            url=url,
            method="PUT",
            expires_at=datetime.fromtimestamp(time.time() + expires_s, UTC),
            headers={"Content-Type": content_type},
        )

    def presign_get(self, key: str, expires_s: int) -> str:
        return self.client.generate_presigned_url(
            "get_object", Params={"Bucket": self.bucket, "Key": _check_key(key)}, ExpiresIn=expires_s
        )

    def put_file(self, key: str, path: Path, content_type: str) -> None:
        self.client.upload_file(str(path), self.bucket, _check_key(key), ExtraArgs={"ContentType": content_type})

    def put_bytes(self, key: str, data: bytes, content_type: str) -> None:
        self.client.put_object(Bucket=self.bucket, Key=_check_key(key), Body=data, ContentType=content_type)

    def download_file(self, key: str, dest: Path) -> None:
        dest.parent.mkdir(parents=True, exist_ok=True)
        self.client.download_file(self.bucket, _check_key(key), str(dest))

    def read_bytes(self, key: str) -> bytes:
        return self.client.get_object(Bucket=self.bucket, Key=_check_key(key))["Body"].read()

    def size(self, key: str) -> int | None:
        from botocore.exceptions import ClientError

        try:
            return int(self.client.head_object(Bucket=self.bucket, Key=_check_key(key))["ContentLength"])
        except ClientError as exc:
            if exc.response.get("Error", {}).get("Code") in ("404", "NoSuchKey", "NotFound"):
                return None
            raise

    def delete(self, key: str) -> None:
        self.client.delete_object(Bucket=self.bucket, Key=_check_key(key))

    def delete_prefix(self, prefix: str) -> int:
        deleted = 0
        paginator = self.client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=self.bucket, Prefix=_check_key(prefix)):
            objects = [{"Key": obj["Key"]} for obj in page.get("Contents", [])]
            for start in range(0, len(objects), 1000):
                batch = objects[start : start + 1000]
                self.client.delete_objects(Bucket=self.bucket, Delete={"Objects": batch, "Quiet": True})
                deleted += len(batch)
        return deleted


def make_storage(settings: Settings) -> Storage:
    if settings.storage_backend == "s3":
        return S3Storage(settings)
    return LocalStorage(Path(settings.storage_local_dir), settings.public_base_url, settings.jwt_secret)
