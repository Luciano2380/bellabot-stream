package com.lpopas.bellabotstream.domain.model;

import com.lpopas.bellabotstream.domain.model.enuns.PhotoFaceStatus;

public record PictureMessage(PhotoFaceStatus photoFaceStatus, String message) {
}
