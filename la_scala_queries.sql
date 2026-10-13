-- Latest observation of every performance. Time shown in Milan local time.
SELECT DISTINCT ON (event_id)
       event_id, title, category,
       starts_at AT TIME ZONE 'Europe/Rome' AS milan_date_time,
       min_price, max_price, currency, available_seats,
       sold_tickets, sold_tickets_status, simulated_sold_tickets, simulated_sold_price,
       simulation_method, scraped_at
FROM lascala
ORDER BY event_id, scraped_at DESC;

-- The source does not publish real sales counts. Synthetic values are explicitly
-- marked and describe tickets sold since the previous scrape.
