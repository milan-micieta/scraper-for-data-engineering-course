-- Latest observation of every performance. Time shown in Milan local time.
SELECT DISTINCT ON (event_id)
       event_id, title, category,
       starts_at AT TIME ZONE 'Europe/Rome' AS milan_date_time,
       min_available_price, currency, available_seats,
       sold_tickets, sold_tickets_status, simulated_sold_tickets,
       simulation_capacity, simulation_method, scraped_at
FROM la_scala_events
ORDER BY event_id, scraped_at DESC;

-- Latest published tariffs for every zone, including sold-out zones.
-- Availability is per zone: do not add it across multiple tariffs in that zone.
WITH latest AS (
    SELECT DISTINCT ON (event_id) *
    FROM la_scala_events
    ORDER BY event_id, scraped_at DESC
)
SELECT e.title, e.starts_at AT TIME ZONE 'Europe/Rome' AS milan_date_time,
       p.zone_name, p.tariff_name, p.price, p.presale_fee, p.commission,
       e.currency, p.available_seats, e.scraped_at
FROM latest e
JOIN la_scala_prices p ON p.snapshot_id = e.id
ORDER BY e.starts_at, e.title, p.zone_id, p.tariff_id;

-- sold_tickets is NULL because the source does not publish sales counts.
-- Online availability changes include holds, releases and channel allocation;
-- they must not be labelled as sales.
