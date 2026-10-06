"""Signed upload/download endpoints for the local-disk storage backend (development and tests).

In production (S3/R2) clients talk to the bucket directly and these routes return 404.
"""

from __future__ import annotations

import os
import tempfile

from fastapi import APIRouter, HTTPException, Request, status
from fastapi.responses import FileResponse, Response

from rafkhata.api.deps import StorageDep
from rafkhata.storage import LocalStorage

router = APIRouter(include_in_schema=False)

MAX_UPLOAD_BYTES = 100 * 1024 * 1024


def _local(storage) -> LocalStorage:
    if not isinstance(storage, LocalStorage):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "not found")
    return storage


@router.put("/files/{key:path}")
async def upload(key: str, exp: int, sig: str, request: Request, storage: StorageDep) -> Response:
    local = _local(storage)
    if not local.verify("PUT", key, exp, sig):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "invalid or expired signature")
    dest = local.path_for(key)
    dest.parent.mkdir(parents=True, exist_ok=True)
    size = 0
    fd, tmp_name = tempfile.mkstemp(dir=dest.parent, prefix=".upload-")
    try:
        with os.fdopen(fd, "wb") as fh:
            async for chunk in request.stream():
                size += len(chunk)
                if size > MAX_UPLOAD_BYTES:
                    raise HTTPException(status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, "file too large")
                fh.write(chunk)
        os.replace(tmp_name, dest)
    finally:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)
    return Response(status_code=status.HTTP_200_OK)


@router.get("/files/{key:path}")
def download(key: str, exp: int, sig: str, storage: StorageDep) -> FileResponse:
    local = _local(storage)
    if not local.verify("GET", key, exp, sig):
        raise HTTPException(status.HTTP_403_FORBIDDEN, "invalid or expired signature")
    path = local.path_for(key)
    if not path.is_file():
        raise HTTPException(status.HTTP_404_NOT_FOUND, "not found")
    return FileResponse(path)
