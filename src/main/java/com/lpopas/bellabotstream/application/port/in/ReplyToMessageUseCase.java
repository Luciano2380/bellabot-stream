package com.lpopas.bellabotstream.application.port.in;

import com.lpopas.bellabotstream.domain.model.IncomingMessage;


public interface ReplyToMessageUseCase {

    void reply(IncomingMessage message);

}
