package core.server.webservice;


import core.entity.FlightPrice;
import core.service.FlightPriceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/flight")
public class KiwiServiceRest {

    @Autowired
    private FlightPriceService flightPriceService;

    @GetMapping("/flight-ticket")
    public List<FlightPrice> getFlightTicket(@RequestParam(required = false) String origin,
                                             @RequestParam(required = false) String destination,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                             LocalDate departureDate) {
        return flightPriceService.getFlightTicket(origin, destination, departureDate);
    }
}
