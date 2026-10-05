package core.server;

import core.service.EventService;
import core.service.EventServiceJPA;
import core.service.FlightPriceService;
import core.service.FlightPriceServiceJPA;
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
    public EventService eventService() {
        return new EventServiceJPA();
    }
}
