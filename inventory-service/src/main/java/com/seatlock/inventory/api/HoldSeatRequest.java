package com.seatlock.inventory.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Only machine-authenticated Booking Service can request a hold. */
public record HoldSeatRequest(@NotNull UUID holdId, @NotNull UUID customerId) { }
