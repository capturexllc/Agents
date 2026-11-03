package com.vamsdest.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record TripRequest(
        @NotBlank @Size(max = 120) String destination,
        @Size(max = 120) String origin,
        @NotNull LocalDate departureDate,
        @NotNull LocalDate returnDate,
        @Size(max = 40) String budget) {
}