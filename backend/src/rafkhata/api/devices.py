"""Push-notification device registration (FCM tokens)."""

from __future__ import annotations

from fastapi import APIRouter, status
from sqlalchemy import select

from rafkhata.api.deps import DbDep, UserDep
from rafkhata.api.schemas import DeviceIn
from rafkhata.models import Device

router = APIRouter(prefix="/devices", tags=["devices"])


@router.post("", status_code=status.HTTP_204_NO_CONTENT)
def register_device(body: DeviceIn, user: UserDep, db: DbDep) -> None:
    device = db.scalars(select(Device).where(Device.fcm_token == body.fcm_token)).first()
    if device is None:
        device = Device(fcm_token=body.fcm_token, user_id=user.id)
        db.add(device)
    device.user_id = user.id  # a token moves with whoever signed in last on that phone
    device.platform = body.platform
    device.app_version = body.app_version
    device.locale = body.locale
    db.commit()


@router.delete("/{fcm_token}", status_code=status.HTTP_204_NO_CONTENT)
def unregister_device(fcm_token: str, user: UserDep, db: DbDep) -> None:
    device = db.scalars(select(Device).where(Device.fcm_token == fcm_token, Device.user_id == user.id)).first()
    if device is not None:
        db.delete(device)
        db.commit()
