package core.server.webservice;


import core.entity.Event;
import core.service.EventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/event")
public class EventServiceRest {

    @Autowired
    private EventService eventService;

    @GetMapping("/flight-ticket")
    public List<Event> getEvent() {
        return eventService.getEvents();
    }

}
