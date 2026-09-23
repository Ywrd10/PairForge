package com.pairforge.api.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionEventConsumerTest {
    final ExecutionRepository repository = mock(ExecutionRepository.class);
    final SimpMessagingTemplate sockets = mock(SimpMessagingTemplate.class);
    final ExecutionEventConsumer consumer = new ExecutionEventConsumer(new ObjectMapper(),repository,sockets,new SimpleMeterRegistry());
    final Channel channel = mock(Channel.class);
    final UUID id=UUID.randomUUID(), room=UUID.randomUUID();
    ExecutionEvent event(String status,long revision) { return new ExecutionEvent(1,id,room,status,revision); }
    Message message(String value) { var p=new MessageProperties(); p.setContentType("application/json"); p.setDeliveryTag(1); return new Message(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),p); }
    Message message(ExecutionEvent event) throws Exception { return message(new ObjectMapper().writeValueAsString(event)); }
    void committed(ExecutionStatus status,long revision) {
        var row=mock(ExecutionRepository.Notification.class);
        when(row.getId()).thenReturn(id); when(row.getRoomId()).thenReturn(room); when(row.getStatus()).thenReturn(status); when(row.getStateRevision()).thenReturn(revision);
        when(repository.notification(id)).thenReturn(Optional.of(row));
    }
    @Test void delayedAndDuplicateEventsBroadcastOnlyCurrentCommittedRevision() throws Exception {
        committed(ExecutionStatus.SUCCEEDED,2);
        consumer.onMessage(message(event("QUEUED",0)),channel);
        consumer.onMessage(message(event("RUNNING",1)),channel);
        consumer.onMessage(message(event("SUCCEEDED",2)),channel);
        verify(sockets).convertAndSend("/topic/rooms/"+room+"/executions",event("SUCCEEDED",2));
        verify(channel,times(3)).basicAck(1,false);
    }
    @Test void inventedRoomRevisionOrStatusNeverBroadcasts() throws Exception {
        committed(ExecutionStatus.RUNNING,1);
        for (var value : List.of(new ExecutionEvent(1,id,UUID.randomUUID(),"RUNNING",1),event("SUCCEEDED",2),event("FAILED",1)))
            consumer.onMessage(message(value),channel);
        verifyNoInteractions(sockets);
    }
    @Test void malformedMessagesAreRejectedWithoutRequeue() throws Exception {
        String valid=new ObjectMapper().writeValueAsString(event("QUEUED",0));
        for(String invalid:List.of("{}","null",valid+"{}",valid.replace("}",",\"schemaVersion\":1}")," ".repeat(1025),valid.replace("\"stateRevision\":0","\"stateRevision\":0.5")))
            consumer.onMessage(message(invalid),channel);
        verify(channel,times(6)).basicReject(1,false); verifyNoInteractions(repository,sockets);
    }
    @Test void unavailableDatabaseOrSocketHasBoundedAttemptsAndNoInfiniteRequeue() throws Exception {
        when(repository.notification(id)).thenThrow(new IllegalStateException());
        consumer.onMessage(message(event("QUEUED",0)),channel);
        verify(repository,times(3)).notification(id); verify(channel).basicReject(1,false);
        reset(repository,channel); committed(ExecutionStatus.SUCCEEDED,2);
        doThrow(new IllegalStateException()).when(sockets).convertAndSend(anyString(),any(ExecutionEvent.class));
        consumer.onMessage(message(event("SUCCEEDED",2)),channel);
        verify(sockets,times(3)).convertAndSend(anyString(),any(ExecutionEvent.class)); verify(channel).basicReject(1,false);
    }
    @Test void consumerRestartMayRebroadcastButCannotRegressToAnOldState() throws Exception {
        committed(ExecutionStatus.SUCCEEDED,2);
        consumer.onMessage(message(event("SUCCEEDED",2)),channel);
        new ExecutionEventConsumer(new ObjectMapper(),repository,sockets,new SimpleMeterRegistry()).onMessage(message(event("QUEUED",0)),channel);
        verify(sockets,times(2)).convertAndSend("/topic/rooms/"+room+"/executions",event("SUCCEEDED",2));
    }
}
