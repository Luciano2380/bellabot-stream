package com.lpopas.bellabotstream.application.port.out;

import com.lpopas.bellabotstream.domain.event.MessageProcessedEvent;

public interface PublishEventPort {

    void publish(MessageProcessedEvent event);
}
