# Kiwi + Teatro alla Scala scraper

## Spustenie
1. Nainstaluj JDK 17 alebo novsi, Maven, PostgreSQL a Python 3.9 alebo novsi.
   JAVA_HOME musi ukazovat na JDK 17 alebo novsi. Over: java -version a mvn -version.
2. V pgAdmine vytvor databazu ticketlake a pouzivatela s pravom vytvarat tabulky.
3. V koreni rozbaleneho projektu vytvor prostredie:

   macOS/Linux:
   python3 -m venv .venv
   .venv/bin/python -m pip install -r requirements.txt
   .venv/bin/python -m playwright install chromium

   Windows:
   py -m venv .venv
   .venv\Scripts\python.exe -m pip install -r requirements.txt
   .venv\Scripts\python.exe -m playwright install chromium

4. Skopiruj src/main/resources/application-local.properties.example na
   src/main/resources/application-local.properties a vypln vlastne meno/heslo.
   Na Windows nastav kiwi.python.exe=.venv/Scripts/python.exe.
5. Z korena projektu spusti: mvn spring-boot:run
   V IDE spustaj core.server.ScraperServer, pracovny adresar = koren projektu.

## Data v pgAdmine
Databaza ticketlake > Schemas > public > Tables > Refresh.
- flight_prices: Kiwi letenky.
- la_scala_events: jeden zaznam pre kazdy termin a kazdy zber (historia).
- la_scala_prices: ceny zon/tarif, spojenie cez snapshot_id = la_scala_events.id.
SQL dotazy na najnovsie zaznamy su v la_scala_queries.sql.

La Scala sa zbiera pri starte a v minutach 05,20,35,50 kazdej hodiny.
Kiwi sa spusta v minutach 00,15,30,45 a jeho zber moze trvat dlhsie.
Pre samotnu La Scalu nastav kiwi.scrape.cron=-.
Zdroj: https://www.teatroallascala.org/en/tickets.html
Pokrytie: vsetky terminy publikovane na tejto stranke, nie cely archiv divadla.

## Simulovane predane listky
sold_tickets = NULL, sold_tickets_status = NOT_PUBLISHED: realne predaje nie su zname.
simulated_sold_tickets = NAHODNE TESTOVACIE DATA, nie odhad predaja.
Generovanie: rovnomerne cele cislo od 0 po simulation.capacity vratane.
Predvoleny limit 2000 je vymysleny testovaci parameter, nie kapacita La Scaly.
Rovnaky event_id + seed + limit dava rovnaky vysledok pri dalsom zbere.
Nie je to simulacia rastuceho predaja v case. Nespaja sa s online dostupnostou.
Nastavenia v application.properties:
  lascala.simulation.enabled=true
  lascala.simulation.capacity=2000
  lascala.simulation.seed=42
Vypnutie ponecha simulovane stlpce NULL v novych zaznamoch; historiu nemeni.

## Testy
macOS/Linux: .venv/bin/python -m unittest discover -s src/test/python -v
Windows: .venv\Scripts\python.exe -m unittest discover -s src/test/python -v

ZIP neobsahuje hesla povodneho autora, .git, .venv, target ani databazove data.
Vlastny application-local.properties nikdy necommituj.
