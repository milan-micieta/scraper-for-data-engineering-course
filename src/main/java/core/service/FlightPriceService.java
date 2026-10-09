package core.service;

import core.entity.FlightPrice;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface FlightPriceService {
    void addFlightTicket(List<FlightPrice> prices);
    List<FlightPrice> getFlightTicket(String origin, String destination, LocalDate departureDate, String source);

}
