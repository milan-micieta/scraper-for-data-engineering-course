package core.server.webservice;


import core.entity.FlightPrice;
import core.service.FlightPriceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/kiwi")
public class KiwiServiceRest {

    @Autowired
    private FlightPriceService flightPriceService;

    @GetMapping("/flight-ticket")
    public List<FlightPrice> getFlightTicket(@RequestParam String origin,
                                             @RequestParam String destination,
                                             @RequestParam LocalDateTime departureTime) {
        return flightPriceService.getFlightTicket(origin, destination, departureTime);
    }
}
