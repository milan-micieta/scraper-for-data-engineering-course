# Získanie dát z REST API, Apache Kafka, MQTT a RabbitMQ

Tento návod popisuje postupy na získanie aktuálnych dát o letenkách z REST API,
snapshotov predstavení La Scaly z Apache Kafka a dát z MQTT a RabbitMQ.

## REST API: letenky

### Základná adresa

```text
http://scraper.svra-windows-desktop-0090.virtual.cloud.tuke.sk
```

### Historické záznamy

```text
GET /api/flight/flight-ticket
```

Endpoint vracia historické záznamy cien leteniek. Jeden let môže mať viac
záznamov, pretože sa ukladá každý scrape.

Voliteľné parametre:

| Parameter | Význam | Príklad |
|---|---|---|
| `origin` | presný názov odletu | `Vienna, Austria` |
| `destination` | presný cieľový slug | `milan-italy` |
| `departureDate` | deň odletu vo formáte `YYYY-MM-DD` | `2026-11-15` |
| `source` | zdroj dát | `kiwi` alebo `ryanair` |

Parametre sa kombinujú podmienkou **A ZÁROVEŇ**. Odpoveď obsahuje najviac
500 záznamov a je zoradená podľa času odletu.

Príklady:

```text
GET /api/flight/flight-ticket?destination=milan-italy
GET /api/flight/flight-ticket?source=ryanair&departureDate=2026-11-15
GET /api/flight/flight-ticket?origin=Vienna%2C%20Austria&destination=milan-italy&departureDate=2026-11-15
```

### Iba najnovšie aktívne letenky

```text
GET /api/flight/active-flight-ticket
```

Endpoint vracia iba lety, ktoré ešte neodleteli, a pre každú kombináciu
`source`, `origin`, `destination` a `departureTime` vyberie najnovší záznam
podľa `scrapedAt`. Nevyžaduje, aby všetky lety boli scrapnuté v rovnakom čase.

Podporuje rovnaké parametre ako historický endpoint:

```text
GET /api/flight/active-flight-ticket?source=ryanair
GET /api/flight/active-flight-ticket?destination=milan-italy
GET /api/flight/active-flight-ticket?departureDate=2026-11-15
```

Slovo **aktívne** znamená budúci čas odletu. Neznamená automaticky dostupné
miesto, pretože REST dáta o letenkách neobsahujú počet voľných miest.

### Formát odpovede

Oba endpointy vracajú JSON pole objektov:

```json
[
  {
    "id": 474,
    "origin": "Vienna, Austria",
    "destination": "milan-italy",
    "departureTime": "2026-11-15T06:20:00",
    "price": 48.00,
    "currency": "EUR",
    "source": "ryanair",
    "scrapedAt": "2026-10-14T12:40:03Z",
    "simulatedSoldTickets": 3,
    "simulationCapacity": 189,
    "simulationSeed": "42",
    "simulationMethod": "incremental-per-scrape-deterministic"
  }
]
```

Polia `simulatedSoldTickets`, `simulationCapacity`, `simulationSeed` a
`simulationMethod` sú syntetické školské údaje, nie skutočné údaje o predaji
od aerolinky. Pri vypnutej simulácii môžu byť `null`.

Ak nič nevyhovuje filtrom, endpoint vráti:

```json
[]
```

Pri použití `curl` treba URL-enkódovať medzery a čiarky:

```powershell
curl.exe -G "http://scraper.svra-windows-desktop-0090.virtual.cloud.tuke.sk/api/flight/active-flight-ticket" `
  --data-urlencode "origin=Vienna, Austria" `
  --data-urlencode "destination=milan-italy" `
  --data-urlencode "departureDate=2026-11-15"
```

## Apache Kafka: La Scala

### Pripojenie

Kafka topic:

```text
lascala.snapshots
```

Príklad nastavenia consumeru:

```python
{
    "bootstrap.servers": "svra-windows-desktop-0090.virtual.cloud.tuke.sk:9092",
    "security.protocol": "SASL_PLAINTEXT",
    "sasl.mechanism": "PLAIN",
    "sasl.username": "reader",
    "sasl.password": "READER_HESLO",
    "group.id": "vlastny-reader",
    "auto.offset.reset": "earliest",
    "enable.auto.commit": False,
}
```

Skutočné heslo sa nezapisuje do zdrojového kódu. Používa sa environment
premenná alebo iný bezpečný spôsob konfigurácie.

### Obsah správy

Kafka obsahuje všetky snapshoty, nie iba posledný stav. Pri každom scrape sa
odošle nová správa pre každé nájdené predstavenie. Kľúč správy je `event_id`.

Príklad správy:

```json
{
  "schema_version": 1,
  "event_id": "18928",
  "scraped_at": "2026-10-14T12:40:03Z",
  "source_url": "https://www.teatroallascala.org/en/tickets.html",
  "title": "Otello",
  "category": "Opera",
  "starts_at": "2026-12-07T20:00:00+01:00",
  "time_zone": "Europe/Rome",
  "booking_url": "https://example.invalid/booking",
  "currency": "EUR",
  "min_price": 10.00,
  "max_price": 350.00,
  "available_seats": 72,
  "sold_tickets": null,
  "sold_tickets_status": "NOT_PUBLISHED",
  "simulated_sold_tickets": 0,
  "simulated_sold_price": null,
  "simulation_capacity": 2000,
  "simulation_seed": "42",
  "simulation_method": "incremental-per-scrape-deterministic",
  "raw_source": {}
}
```

Skutočné predaje La Scala nie sú publikované. Polia
`simulated_sold_tickets` a `simulated_sold_price` sú syntetická simulácia.

### Python: iba posledný aktívny snapshot každého predstavenia

Nasledujúci skript načíta celý topic, ponechá najnovšiu správu pre každý
`event_id`, vyfiltruje predstavenia, ktoré ešte nezačali, a uloží ich do
`lascala_active_latest.jsonl`.

```python
import json
import time
from datetime import datetime, timezone

from confluent_kafka import Consumer, TopicPartition

TOPIC = "lascala.snapshots"
OUT_FILE = "lascala_active_latest.jsonl"
MAX_WAIT_SECONDS = 60

# True = iba predstavenia, kde ešte existuje aspoň jedno dostupné miesto
ONLY_WITH_AVAILABLE_SEATS = False

c = Consumer({
    "bootstrap.servers": "svra-windows-desktop-0090.virtual.cloud.tuke.sk:9092",
    "security.protocol": "SASL_PLAINTEXT",
    "sasl.mechanism": "PLAIN",
    "sasl.username": "reader",
    "sasl.password": "READER_HESLO",
    "group.id": "kontrola-%d" % int(time.time()),
    "auto.offset.reset": "earliest",
    "enable.auto.commit": False,
})

metadata = c.list_topics(TOPIC, timeout=10)
if TOPIC not in metadata.topics or metadata.topics[TOPIC].error:
    raise SystemExit("Topic sa nenašiel / chyba: %s" % metadata.topics.get(TOPIC))

total = 0
for partition in sorted(metadata.topics[TOPIC].partitions):
    low, high = c.get_watermark_offsets(
        TopicPartition(TOPIC, partition),
        timeout=10,
    )
    print(f"partícia {partition}: offsety {low}..{high} ({high - low} správ)")
    total += high - low

print("V topicu je spolu:", total)

c.subscribe([
    TOPIC
], on_assign=lambda consumer, partitions: print(
    "Priradené partície:",
    [partition.partition for partition in partitions],
))

latest_by_event = {}
count = 0
deadline = time.time() + MAX_WAIT_SECONDS

try:
    while count < total and time.time() < deadline:
        msg = c.poll(1.0)

        if msg is None:
            continue
        if msg.error():
            print("CHYBA:", msg.error())
            continue

        count += 1
        data = json.loads(msg.value())
        event_id = str(data["event_id"])
        current = latest_by_event.get(event_id)

        if current is None:
            latest_by_event[event_id] = data
            continue

        current_scraped_at = datetime.fromisoformat(
            current["scraped_at"].replace("Z", "+00:00")
        )
        new_scraped_at = datetime.fromisoformat(
            data["scraped_at"].replace("Z", "+00:00")
        )

        if new_scraped_at >= current_scraped_at:
            latest_by_event[event_id] = data
finally:
    c.close()

now = datetime.now(timezone.utc)
active_latest = []

for data in latest_by_event.values():
    starts_at = datetime.fromisoformat(
        data["starts_at"].replace("Z", "+00:00")
    )

    if starts_at <= now:
        continue

    if ONLY_WITH_AVAILABLE_SEATS:
        available_seats = data.get("available_seats")
        if available_seats is None or available_seats <= 0:
            continue

    active_latest.append(data)

active_latest.sort(key=lambda item: item["starts_at"])

with open(OUT_FILE, "w", encoding="utf-8") as out:
    for data in active_latest:
        out.write(json.dumps(data, ensure_ascii=False) + "\n")
        print(
            data["event_id"],
            data["scraped_at"],
            data["starts_at"],
            data["title"],
            "miesta:",
            data["available_seats"],
            "predané(sim):",
            data["simulated_sold_tickets"],
            "ceny:",
            data["min_price"],
            data["max_price"],
        )

print(f"Prečítaných správ: {count} z {total}")
print(f"Najnovších eventov: {len(latest_by_event)}")
print(f"Aktívnych najnovších eventov: {len(active_latest)}")
print("Uložené do:", OUT_FILE)
```

Pred spustením je potrebné nainštalovať balík:

```powershell
pip install confluent-kafka
```

Skript je určený na načítanie celej histórie s `auto.offset.reset=earliest`.
Pri bežnom dlhodobom consumeri sa používa stabilný `group.id` a ukladajú sa
offsety.

## MQTT: odber dát

Potrebné je Python 3.10+, prihlasovacie údaje k CloudAMQP a **názov skupiny**.

### Názov skupiny

Každá skupina má vlastný názov, napríklad:

```text
SKUPINA1
SKUPINA2
SKUPINA6
```

Pri čítaní z MQTT sa názov skupiny používa na vytvorenie MQTT `client_id`:

```text
<názov-skupiny>-consumer      napr. skupina2-consumer
```

**Každá skupina musí použiť iný názov.** Dve pripojenia s rovnakým MQTT
`client_id` sa navzájom odpájajú, takže pri zhodnom názve skupiny by
predchádzajúce pripojenie prestalo prijímať dáta.

### Inštalácia

```powershell
pip install paho-mqtt
```

### Použité pripojenie

| Služba | Hostiteľ a port | Používateľ | Odber |
|---|---|---|---|
| MQTT/TLS | `swallow.lmq.cloudamqp.com:8883` | `ohcweerr:ohcweerr` | `di/team9/flights`, QoS 1 |

### Spustenie prijímača

Skript sa pri spustení opýta na názov skupiny. Zadá sa napríklad `SKUPINA2`.
Názov skupiny a ďalšie hodnoty sa dajú zadať aj vopred cez environment
premenné v aktuálnom PowerShelli:

```powershell
$env:CLOUDAMQP_CONSUMER_ID = "SKUPINA2"
$env:CLOUDAMQP_CLIENT_ID = "skupina2-consumer"
$env:CLOUDAMQP_PASSWORD = "<CloudAMQP heslo>"
python .\scripts\prijimac_team9.py
```

Hodnoty pre jednotlivé skupiny:

| Skupina | `CLOUDAMQP_CONSUMER_ID` | `CLOUDAMQP_CLIENT_ID` |
|---|---|---|
| SKUPINA1 | `SKUPINA1` | `skupina1-consumer` |
| SKUPINA2 | `SKUPINA2` | `skupina2-consumer` |
| SKUPINA6 | `SKUPINA6` | `skupina6-consumer` |

Používateľské meno pre MQTT je v skripte nastavené ako `ohcweerr:ohcweerr`.
Tým istým skriptom sa spúšťa aj odber z RabbitMQ (pozri nižšie).

### Výstup

Dáta z MQTT sa ukladajú do:

```text
team9_letenky.jsonl
```

MQTT broker nemusí spätne doručiť staršie správy, preto má prijímač bežať
nepretržite.

Skutočné heslo sa nedáva do Git repozitára ani do Markdown súboru, uchováva
sa bezpečným spôsobom (napr. v environment premennej alebo správcovi hesiel).

## RabbitMQ: odber dát

Potrebné je Python 3.10+, prihlasovacie údaje k CloudAMQP, názov skupiny
a existujúca queue pre danú skupinu.

### Queue pre každú skupinu

Pre každú skupinu musí byť vytvorená **vlastná RabbitMQ queue** a naviazaná
na exchange `di.team9.theatre` s binding key `theatre.sales`:

```text
Exchange:    di.team9.theatre
Binding key: theatre.sales
```

Názvy queue:

| Skupina | Queue |
|---|---|
| SKUPINA1 | `theatre.SKUPINA1` |
| SKUPINA2 | `theatre.SKUPINA2` |
| SKUPINA6 | `theatre.SKUPINA6` |

Názov queue má tvar `theatre.<názov-skupiny>`.

**Každá skupina musí čítať zo svojej queue.** Ak by rovnakú queue čítalo viac
prijímačov, RabbitMQ rozdelí správy medzi nich a každý dostane len časť.
Vďaka vlastnej queue pre každú skupinu dostane každá skupina vlastnú kópiu
každej novej správy.

### Inštalácia

```powershell
pip install pika
```

### Použité pripojenie

| Služba | Hostiteľ a port | Používateľ | Odber |
|---|---|---|---|
| RabbitMQ/TLS | `swallow.lmq.cloudamqp.com:5671` | `ohcweerr`, vhost `ohcweerr` | `theatre.<názov-skupiny>` |

### Spustenie prijímača

Skript sa pri spustení opýta na názov skupiny (napr. `SKUPINA2`) a číta
z queue `theatre.SKUPINA2`. Hodnoty sa dajú zadať aj vopred cez environment
premenné v aktuálnom PowerShelli:

```powershell
$env:CLOUDAMQP_CONSUMER_ID = "SKUPINA2"
$env:CLOUDAMQP_QUEUE = "theatre.SKUPINA2"
$env:CLOUDAMQP_PASSWORD = "<CloudAMQP heslo>"
python .\scripts\prijimac_team9.py
```

Hodnoty pre jednotlivé skupiny:

| Skupina | `CLOUDAMQP_CONSUMER_ID` | `CLOUDAMQP_QUEUE` |
|---|---|---|
| SKUPINA1 | `SKUPINA1` | `theatre.SKUPINA1` |
| SKUPINA2 | `SKUPINA2` | `theatre.SKUPINA2` |
| SKUPINA6 | `SKUPINA6` | `theatre.SKUPINA6` |

Používateľské meno pre RabbitMQ je v skripte nastavené ako `ohcweerr`. Tým
istým skriptom sa spúšťa aj odber z MQTT (pozri vyššie).

### Výstup

RabbitMQ po potvrdení uloží spracované správy do súboru a odstráni ich
z queue danej skupiny. Dáta z RabbitMQ sa ukladajú do:

```text
team9_vstupenky.jsonl
```

Ak sa skript vypne, správy zostanú vo vlastnej queue skupiny a doručia sa po
ďalšom spustení.

Skutočné heslo sa nedáva do Git repozitára ani do Markdown súboru, uchováva
sa bezpečným spôsobom (napr. v environment premennej alebo správcovi hesiel).