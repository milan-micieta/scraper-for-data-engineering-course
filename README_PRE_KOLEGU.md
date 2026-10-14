# Scraper a dátová platforma: letenky a Teatro alla Scala

## 1. Čo projekt robí

Projekt je Spring Boot aplikácia, ktorá:

- periodicky získava ceny jednosmerných letov z Kiwi.com a Ryanair,
- získava program a cenové zóny Teatro alla Scala,
- ukladá každé pozorovanie do PostgreSQL ako historický snapshot,
- poskytuje letenkové dáta cez REST API,
- voliteľne publikuje La Scala cenové snapshoty do samostatného Kafka topicu,
- obsahuje oddelenú simuláciu predaných lístkov určenú iba na demonštráciu.

Scraping a simulované predaje sú dve odlišné veci. Cena letenky alebo online
dostupnosť predstavenia neznamená počet skutočne predaných lístkov.

## 2. Architektúra

```text
Kiwi.com       ─┐
Ryanair        ─┼─> Python scrapery ─> Spring schedulery ─> PostgreSQL
Teatro Scala   ─┘                                  ├─> REST API (letenky)
                                                   └─> Kafka (iba La Scala)
```

Java/Spring Boot riadi plánovanie, validáciu a ukladanie. Python sa používa
tam, kde je potrebný Playwright alebo parsovanie webového/API výstupu.

Hlavné časti:

| Časť | Úloha |
|---|---|
| `kiwi.py` | Playwright scraper Kiwi.com |
| `ryanair.py` | scraper verejného Ryanair endpointu |
| `la_scala.py` | získanie kalendára a cien La Scaly |
| `KiwiScrapeScheduler` | spúšťa Kiwi zber a ukladá letenky |
| `RyanairScrapeScheduler` | spúšťa Ryanair zber a ukladá letenky |
| `LaScalaScrapeScheduler` | spúšťa La Scala zber a ukladá snapshoty |
| `PythonService` | bezpečne spúšťa Python procesy a číta JSON stdout |
| `LaScalaStorage` | transakčné uloženie La Scala dát |
| `TicketSalesStreamPublisher` | publikuje iba La Scala ceny do Kafka |

## 3. Zber cien leteniek

### Kiwi

Kiwi scheduler sa spúšťa podľa `kiwi.scrape.cron`. Pre každú cieľovú
destináciu a deň v nakonfigurovanom okne zavolá Python scraper. Odletové
mestá posiela scraperu naraz ako zoznam slugov, napríklad:

```text
vienna-austria,budapest-hungary,bratislava-slovakia,kosice-slovakia
```

Kiwi scraper:

1. otvorí vyhľadávanie jednosmerných priamych letov,
2. zachytí GraphQL odpovede,
3. nájde ponuky s cenou, časom a pôvodom,
4. vyberie najlacnejšiu ponuku za každú krajinu odletu,
5. vráti čistý JSON na stdout.

Logy idú na stderr, aby Spring dostal na stdout iba JSON. Scheduler následne
vytvorí entity `FlightPrice`, doplní `scrapedAt` a uloží ich do databázy.

### Ryanair

Ryanair scheduler pracuje s priamymi jednosmernými letmi. Zberá odlety z:

```text
VIE, BUD, BTS, KSC
```

do cieľa nastaveného v konfigurácii. Šesťdesiat dní sa rozdelí na
päťdňové okná, medzi požiadavkami je pauza, aby sa znížilo riziko blokovania.
Výsledky sa ukladajú do rovnakej tabuľky `flight_prices`, ale s hodnotou
`source = ryanair`. Kiwi používa typicky `source = kiwi`.

### Spoločné vlastnosti zberu

- Každý zber vytvára nový historický záznam; staré ceny sa nemažú.
- Cena je uložená spolu s menou, odletom, cieľom a časom odletu.
- Pri chybe jednej trasy alebo jedného dňa scheduler loguje chybu a pokračuje.
- Neúplná ponuka bez času, pôvodu alebo cieľa sa nepoužíva ako platná predajná
  informácia.
- Scraping je periodický snapshot, nie rezervácia ani nákup letenky.

## 4. Zber La Scala

La Scala scraper získava verejný kalendár predstavení a cenové zóny.
Predvolene sa spúšťa pri štarte a v minútach `05, 20, 35, 50` každej hodiny.
Pre každé predstavenie sa uloží:

- identifikátor a názov,
- kategória a začiatok predstavenia,
- časová zóna `Europe/Rome`,
- dostupné miesta, ak ich zdroj poskytuje,
- minimálna cena,
- všetky zóny a tarify vrátane poplatkov,
- čas zberu a pôvodný zdrojový záznam.

Ak web neposkytuje skutočný počet predaných lístkov, `soldTickets` ostáva
`NULL` a `soldTicketsStatus` je `NOT_PUBLISHED`.

## 5. Databáza PostgreSQL

Predvolená databáza sa volá `ticketlake`. Hibernate pri štarte vytvára alebo
aktualizuje tabuľky podľa JPA entít (`spring.jpa.hibernate.ddl-auto=update`).

### `flight_prices`

Jeden riadok predstavuje jednu ponuku alebo cenu zachytenú v konkrétnom čase.

| Stĺpec | Význam |
|---|---|
| `id` | technický primárny kľúč |
| `origin` | mesto/krajina odletu |
| `destination` | cieľový slug |
| `departure_time` | lokálny čas odletu bez zóny |
| `price` | cena |
| `currency` | mena |
| `source` | `kiwi` alebo `ryanair` |
| `scraped_at` | čas získania záznamu v UTC |
| `simulated_sold_tickets` | voliteľná syntetická hodnota |
| `simulation_capacity` | limit syntetickej simulácie |
| `simulation_seed` | seed simulácie |
| `simulation_method` | označenie použitej simulácie |

Na trase a čase odletu existuje viac riadkov, pretože databáza zachováva
vývoj ceny v čase.

### `lascala`

Jeden riadok predstavuje jedno predstavenie v jednom scrape snapshote.

| Stĺpec | Význam |
|---|---|
| `id` | technický primárny kľúč |
| `event_id` | identifikátor predstavenia |
| `title`, `category` | názov a kategória |
| `starts_at` | začiatok s offsetom |
| `time_zone` | časová zóna |
| `min_price`, `max_price` | minimum a maximum zverejnenej ceny |
| `available_seats` | dostupné miesta, ak sú známe |
| `sold_tickets` | vždy `NULL`, ak zdroj predaje nezverejňuje |
| `sold_tickets_status` | napr. `NOT_PUBLISHED` |
| `simulated_sold_tickets` | `max(previous_available_seats - available_seats, 0)`; pri prvom scrape alebo neznámej dostupnosti je `0` |
| `simulated_sold_price` | syntetická cena predaja v danom scrape; `NULL`, ak je prírastok nulový |
| `simulation_capacity` | metadata pôvodnej simulácie; neovplyvňuje výpočet predaja z dostupnosti |
| `scraped_at` | čas snapshotu |
| `source_url` | zdrojová stránka alebo endpoint |
| `raw_source` | pôvodné dáta pre audit |

Všetky hodnoty sú v jednej tabuľke. Jednotlivé zóny a tarify sa neukladajú
ako samostatná tabuľka; pôvodný payload je zachovaný v `raw_source`.

### Indexy a história

Databáza má indexy na čas zberu, identifikátor predstavenia, začiatok
predstavenia a trasu letenky. Snapshoty sa zámerne nemažú, aby sa dali
porovnávať ceny v čase. Príklady analytických dotazov sú v
`la_scala_queries.sql`.

## 6. REST server a API

Spring Boot server štandardne počúva na porte nastavenom v profile:

- local: `8080`,
- prod: `8081`.

### Letenky

```text
GET /api/flight/flight-ticket
```

Voliteľné parametre:

| Parameter | Význam |
|---|---|
| `origin` | presná hodnota mesta/krajiny odletu |
| `destination` | presný cieľový slug |
| `departureDate` | deň odletu vo formáte `YYYY-MM-DD` |
| `source` | `kiwi` alebo `ryanair` |

Príklad:

```text
GET /api/flight/flight-ticket?source=ryanair&destination=london-united-kingdom&departureDate=2026-11-15
```

Výsledok je zoradený podľa odletu a potom podľa najnovšieho `scrapedAt`.
Endpoint vracia najviac 500 záznamov. Bez filtrov vráti najnovšie dostupné
historické záznamy v rámci tohto limitu.

Pre aktuálne aktívne lety, teda budúce odlety, použite:

```text
GET /api/flight/active-flight-ticket
```

Tento endpoint vracia najviac 500 záznamov a pre každú kombináciu zdroja,
trasy a času odletu vyberie iba záznam s najnovším `scrapedAt`. Podporuje
rovnaké voliteľné parametre `origin`, `destination`, `departureDate` a
`source`; odlety v minulosti automaticky vynechá.

### Stav servera

Actuator health je vystavený cez:

```text
GET /actuator/health
```

Používa sa na kontrolu, či Spring aplikácia beží. REST API neposkytuje
La Scala dáta; tie sa odoberajú iba z Kafka topicu.

## 7. Kafka stream pre La Scala

La Scala ceny sa neposielajú cez REST, MQTT ani RabbitMQ. Ak je zapnuté:

```text
SALES_STREAM_ENABLED=true
```

aplikácia po úspešnom scrape odošle jednu JSON správu za každé predstavenie
do topicu `lascala.prices`. Kafka broker je oddelený od externých školských
brokerov a konfiguruje sa cez `KAFKA_BOOTSTRAP_SERVERS`; predvolený lokálny
broker je `127.0.0.1:19092`.

Správa obsahuje `schema_version`, `event_id`, `scraped_at`, `performance`,
`show_start`, `currency` a pole `prices`. Kafka key je `event_id`. Consumer
má používať vlastnú `group.id`, `auto.offset.reset=earliest` pri prvom
spustení a deduplikovať opakované správy podľa `event_id` a `scraped_at`.

Príklad:

```python
import json
from confluent_kafka import Consumer

consumer = Consumer({
    "bootstrap.servers": "127.0.0.1:19092",
    "group.id": "lascala-readers",
    "auto.offset.reset": "earliest",
})
consumer.subscribe(["lascala.prices"])

try:
    while True:
        message = consumer.poll(1.0)
        if message is None:
            continue
        if message.error():
            print(message.error())
            continue
        print(json.loads(message.value()))
finally:
    consumer.close()
```

## 8. Syntetická simulácia predaja

Simulácia nie je údaj zo zdroja. Je oddelená od reálnych cien a dostupnosti.
Pre La Scala sa dá zapnúť cez:

```properties
lascala.simulation.enabled=true
lascala.simulation.capacity=2000
lascala.simulation.seed=42
```

Pri rovnakom `event_id`, seede a limite je výsledok opakovateľný. Hodnota
predstavuje syntetický testovací údaj, nie odhad predaja, obsadenosti alebo
reálnej kapacity divadla.

## 9. Test Kafka toku cez Python

Kompletný lokálny round-trip test je v
`src/test/python/test_kafka_local.py`. Vytvorí testovací La Scala snapshot,
odošle ho do Kafka topicu `lascala.prices`, vytvorí dočasnú consumer group,
správu načíta, overí `schema_version`, `event_id` a cenu a nakoniec potvrdí
offset.

Najprv musí bežať lokálny Kafka broker na `127.0.0.1:19092`. Potom:

```powershell
python -m pip install -r requirements.txt
python src/test/python/test_kafka_local.py
```

Úspešný test vypíše `Kafka round trip succeeded`. Ak broker nie je dostupný,
skript skončí chybou namiesto toho, aby test predstieral úspech.

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
- lascala: jeden zaznam pre kazdy termin a kazdy zber (historia), vratane min/max ceny a syntetickych predajov.
SQL dotazy na najnovsie zaznamy su v la_scala_queries.sql.

La Scala sa zbiera pri starte a v minutach 05,20,35,50 kazdej hodiny.
Kiwi sa spusta v minutach 00,15,30,45 a jeho zber moze trvat dlhsie.
Pre samotnu La Scalu nastav kiwi.scrape.cron=-.
Zdroj: https://www.teatroallascala.org/en/tickets.html
Pokrytie: vsetky terminy publikovane na tejto stranke, nie cely archiv divadla.

## Ryanair
Ryanair zbiera priame jednosmerne lety z Viedne (VIE), Budapesti (BUD),
Bratislavy (BTS) a Kosic (KSC) do Londyna-Stansted (STN) na nasledujucich
60 dni, v minutach 00, 15, 30 a 45 kazdej hodiny. Ceny vypisuje priebezne.
Verejne ceny sa zbieraju po 5-dnovych oknach s 5-sekundovou pauzou medzi poziadavkami.
Zaznamy idu do `flight_prices` s `source = ryanair`; na API ich vyfiltruj
parametrom `source=ryanair`. Ciel sa uklada pod rovnakym oznacenim ako pri Kiwi:
`london-united-kingdom`. 

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

## Odber skolskych streamov

Aktualne zadanie poskytuje letenky cez MQTT a vstupenky cez RSS. Odber zabezpecuje
`src/main/python/stream_subscriber.py`; existujuci scraper sa k tymto streamom
nepripaja.

```powershell
python -m pip install -r requirements.txt
python src/main/python/stream_subscriber.py
```

Skript odoberá `DI/plane_new` a `DI/plane_upd` na `178.143.18.120:1883` s
prihlasenim `student`/`somsuper` a kazdych 15 minut nacita RSS z
`http://178.143.18.120:1884/`. Vystup uklada do `flights.jsonl` a
`theatre.jsonl`; adresar je mozne zmenit cez `--output` alebo
`STREAM_OUTPUT_DIR`. Opakovane MQTT snapshoty a RSS polozky sa deduplikuju
podla obsahu a deduplikacia prezije restart procesu.

## Poskytovanie cien La Scaly

Ak je nastavené `SALES_STREAM_ENABLED=true`, aplikacia publikuje iba ceny
La Scaly do Kafka topicu `lascala.prices`. Letenkove data sa do Kafka ani
RabbitMQ neposielaju. Broker je samostatny a nie je prevzaty z referencnych
markdownov: predvolene je `127.0.0.1:19092`, nastavuje sa cez
`KAFKA_BOOTSTRAP_SERVERS`. Topic sa nastavuje cez `LASCALA_TOPIC`.

## Testy
macOS/Linux: .venv/bin/python -m unittest discover -s src/test/python -v
Windows: .venv\Scripts\python.exe -m unittest discover -s src/test/python -v

ZIP neobsahuje hesla povodneho autora, .git, .venv, target ani databazove data.
Vlastny application-local.properties nikdy necommituj.
