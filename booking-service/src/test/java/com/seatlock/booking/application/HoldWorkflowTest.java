package com.seatlock.booking.application;

import com.seatlock.booking.api.CreateHoldRequest;
import com.seatlock.booking.api.HoldView;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HoldWorkflowTest {
    private final HoldPort port = mock(HoldPort.class);
    private final HoldWorkflow workflow = new HoldWorkflow(port);

    @Test
    void sameAuthenticatedUserAndKeyGenerateStableHoldIdAcrossRetries() {
        UUID owner = UUID.randomUUID(), event = UUID.randomUUID(), seat = UUID.randomUUID();
        String key = "hold-key-123456";
        UUID id = HoldWorkflow.holdId(owner, key);
        HoldView receipt = new HoldView(id, event, seat, owner, Instant.now().plusSeconds(120), "HELD");
        when(port.create(event,seat,owner,id)).thenReturn(new HoldPort.HoldAttempt(receipt,true));
        assertThat(workflow.create(owner,key,new CreateHoldRequest(event,seat)).receipt().holdId())
                .isEqualTo(id);
        assertThat(workflow.create(owner,key,new CreateHoldRequest(event,seat)).receipt().holdId())
                .isEqualTo(id);
        verify(port, times(2)).create(event,seat,owner,id);
        assertThat(HoldWorkflow.holdId(UUID.randomUUID(), key)).isNotEqualTo(id);
    }

    @Test
    void rejectsInvalidIdempotencyKeyBeforeRemoteCall() {
        assertThatThrownBy(() -> workflow.create(UUID.randomUUID(), "bad",
                new CreateHoldRequest(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(port);
    }
}
