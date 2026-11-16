package com.seatlock.inventory.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReserveSeatRequest(@NotNull UUID bookingId) { }
