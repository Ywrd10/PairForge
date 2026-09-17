package com.pairforge.api.collaboration;

import com.pairforge.api.collaboration.DocumentMessages.*;
import java.util.UUID;
import org.springframework.messaging.handler.annotation.*;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

@Controller
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class CollaborationController {
    private final CollaborationService service;
    public CollaborationController(CollaborationService service) { this.service = service; }
    @MessageMapping("/rooms/{room}/snapshot")
    @SendToUser(value = "/queue/collaboration", broadcast = false)
    public Event snapshot(@DestinationVariable UUID room, @Header("simpSessionId") String session) {
        return service.snapshot(room, session);
    }
    @MessageMapping("/rooms/{room}/update")
    @SendToUser(value = "/queue/collaboration", broadcast = false)
    public Event update(@DestinationVariable UUID room, @Header("simpSessionId") String session, Update update) {
        return service.update(room, session, update);
    }
    @MessageExceptionHandler(Exception.class)
    @SendToUser(value = "/queue/collaboration", broadcast = false)
    public Event failure(Exception error) {
        org.slf4j.LoggerFactory.getLogger(CollaborationController.class)
                .warn("Collaboration request failed type={}", error.getClass().getSimpleName());
        String code = error instanceof IllegalArgumentException
                || error instanceof org.springframework.messaging.converter.MessageConversionException
                ? "INVALID_UPDATE" : "COLLABORATION_UNAVAILABLE";
        return new Event("ERROR", null, null, null, code);
    }
}
