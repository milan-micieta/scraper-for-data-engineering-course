package core.service;

import core.entity.Event;
import core.entity.FlightPrice;

import java.util.List;

public interface EventService {
    void addEvent(List<Event> tickets);
    List<Event> getEvents();
}
