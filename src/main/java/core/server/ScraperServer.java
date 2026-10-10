package core.server;

import core.service.EventService;
import core.service.EventServiceJPA;
import core.service.FlightPriceService;
import core.service.FlightPriceServiceJPA;
import core.service.FlightTicketSalesGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EntityScan("core.entity")
public class ScraperServer {
    public static void main(String[] args) {
        SpringApplication.run(ScraperServer.class, args);
    }

    @Bean
    public FlightPriceService flightPriceService() {
        return new FlightPriceServiceJPA();
    }

    @Bean
    public FlightTicketSalesGenerator flightTicketSalesGenerator(
            @Value("${flight.sales-simulation.enabled:true}") boolean enabled,
            @Value("${flight.sales-simulation.capacity:189}") int capacity,
            @Value("${flight.sales-simulation.seed:42}") long seed,
            @Value("${flight.sales-simulation.default-interval-seconds:900}")
            long defaultIntervalSeconds) {
        return new FlightTicketSalesGenerator(
                enabled,
                capacity,
                seed,
                defaultIntervalSeconds
        );
    }

    @Bean
    public EventService eventService() {
        return new EventServiceJPA();
    }
}
