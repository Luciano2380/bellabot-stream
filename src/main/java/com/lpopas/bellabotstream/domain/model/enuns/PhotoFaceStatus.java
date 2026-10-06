package com.lpopas.bellabotstream.domain.model.enuns;

public enum PhotoFaceStatus {
    CLEAR_SINGLE_FACE,   // 1. A foto possui um rosto nítido
    BLURRY,              // 2. A foto não está nítida
    MULTIPLE_FACES,      // 3. A foto possui vários rostos
    NO_FACE              // 4. A foto não possui um rosto
}
