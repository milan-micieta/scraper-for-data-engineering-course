# Endpoint: ceny letenkov

```
GET /api/flight/flight-ticket
```

Vráti zoznam záznamov o cenách letenkov. Výsledok sa dá zúžiť voliteľnými parametrami.

**Základná adresa:** `http://scraper.svra-windows-desktop-0090.virtual.cloud.tuke.sk`

## Parametre

Všetky parametre sú voliteľné a posielajú sa v URL (query string).

| Parameter | Typ | Popis | Príklad |
|---|---|---|---|
| `origin` | text | Odletové mesto a krajina vo formáte `Mesto, Krajina`. Presná zhoda, rozlišujú sa veľké a malé písmená. | `Vienna, Austria` |
| `destination` | text | Cieľ vo forme slugu. Presná zhoda, rozlišujú sa veľké a malé písmená. | `milan-italy` |
| `departureDate` | dátum `YYYY-MM-DD` | Deň odletu. Vrátia sa všetky lety v daný deň (od 00:00 do 23:59), nie jeden konkrétny čas. | `2026-11-15` |

### Ako parametre ovplyvňujú výsledok

- Parameter, ktorý neposlješ (alebo je prázdny), sa ignoruje, teda nefiltruje.
- Zadané parametre sa spájajú podmienkou **A ZÁROVEŇ**. Záznam musí spĺňať všetky.
- Bez parametrov sa vráti zoznam všetkých záznamov, obmedzený na prvých 500.
- Pri `origin` a `destination` musí hodnota sedieť presne. `vienna, austria` alebo `Vienna` nič nenájde.
- `departureDate` sa zadáva bez času. Zadaný deň sa porovnáva s časom odletu v záznamoch.

## Zoradenie a limit

- Záznamy sú zoradené podľa času odletu vzostupne (najskorší odlet prvý).
- Pri rovnakom čase odletu je prvý ten s najnovším `scrapedAt`.
- Odpoveď obsahuje najviac **500** záznamov.

Pre jeden let (rovnaký čas odletu) môže byť v odpovedi viac záznamov. Rozlišujú sa časom `scrapedAt` a odrážajú vývoj ceny v čase.

## Odpoveď

Kód `200 OK`, telo je pole JSON objektov.

| Pole | Typ | Popis |
|---|---|---|
| `id` | číslo | Identifikátor záznamu |
| `origin` | text | Odletové mesto a krajina, napr. `Vienna, Austria` |
| `originCountry` | text | Krajina odletu |
| `destination` | text | Cieľ (slug), napr. `milan-italy` |
| `departureTime` | dátum a čas | Čas odletu v tvare `YYYY-MM-DDTHH:MM:SS`, lokálny čas letiska bez časovej zóny |
| `price` | číslo | Cena letenky |
| `currency` | text | Mena ceny, napr. `EUR` |
| `scrapedAt` | dátum a čas | Kedy bola cena zaznamenaná, v UTC (`...Z`) |

### Príklad odpovede

```json
[
  {
    "id": 474,
    "origin": "Budapest, HU",
    "destination": "milan-italy",
    "departureTime": "2026-10-07T06:20:00",
    "price": 48.00,
    "currency": "EUR",
    "source": "kiwi",
    "scrapedAt": "2026-10-06T10:45:09Z"
  },
  {
    "id": 337,
    "origin": "Budapest, HU",
    "destination": "milan-italy",
    "departureTime": "2026-10-07T06:20:00",
    "price": 48.00,
    "currency": "EUR",
    "source": "kiwi",
    "scrapedAt": "2026-10-06T10:28:07Z"
  }
]
```

Ak žiadny záznam nevyhovuje filtrom, vráti sa `200 OK` s prázdnym poľom `[]`.

## Príklady volania

Všetky záznamy s cieľom Miláno:

```
GET /api/flight/flight-ticket?destination=milan-italy
```

Lety z Viedne do Milána v konkrétny deň:

```
GET /api/flight/flight-ticket?origin=Vienna%2C%20Austria&destination=milan-italy&departureDate=2026-11-15
```

Cez `curl`:

```
curl.exe -G "http://scraper.svra-windows-desktop-0090.virtual.cloud.tuke.sk/api/flight/flight-ticket" --data-urlencode "origin=Vienna, Austria" --data-urlencode "destination=milan-italy" --data-urlencode "departureDate=2026-11-15"
```

V URL zapisuj medzeru ako `%20` (nie `+`) a čiarku ako `%2C`.

## La Scala: Kafka stream

La Scala sa neposkytuje cez REST API. Po úspešnom scrape aplikácia publikuje
ceny predstavení priamo do Apache Kafka. Publikovanie je predvolene vypnuté a
zapína sa premennou:

```text
SALES_STREAM_ENABLED=true
```

Použitý Kafka broker a topic:

| Nastavenie | Premenná | Predvolená hodnota |
|---|---|---|
| Kafka broker | `KAFKA_BOOTSTRAP_SERVERS` | `127.0.0.1:19092` |
| Topic | `LASCALA_TOPIC` | `lascala.prices` |
| Security protocol | `KAFKA_SECURITY_PROTOCOL` | `PLAINTEXT` |
| SASL mechanizmus | `KAFKA_SASL_MECHANISM` | `PLAIN` |
| Používateľ | `KAFKA_USERNAME` | prázdny |
| Heslo | `KAFKA_PASSWORD` | prázdne |

Broker a port sú konfigurovateľné a nie sú preberané z externých školských
streamov. Heslo sa nastavuje iba cez environment premennú, nie do zdrojového
kódu alebo dokumentácie.

### Ako sa správa vytvára

Pri každom úspešnom zbere La Scala sa do topicu odošle jedna JSON správa za
každé predstavenie. Kafka key je `event_id`, takže správy jedného predstavenia
majú stabilný kľúč. Správa obsahuje aktuálny snapshot cien:

```json
{
  "schema_version": 1,
  "event_id": "12345",
  "scraped_at": "2026-10-12T12:05:00Z",
  "performance": "La traviata",
  "show_start": "2026-12-06T20:00:00+01:00",
  "currency": "EUR",
  "prices": [
    {
      "zoneId": "1",
      "zoneName": "Stalls",
      "availableSeats": 20,
      "tariffId": "a",
      "tariffName": "Full",
      "price": 123.45,
      "presaleFee": 1.00,
      "commission": 0.00
    }
  ]
}
```

Význam hlavných polí:

| Pole | Význam |
|---|---|
| `schema_version` | Verzia formátu; aktuálne `1` |
| `event_id` | Identifikátor predstavenia a Kafka message key |
| `scraped_at` | Čas, kedy bol snapshot získaný |
| `performance` | Názov predstavenia |
| `show_start` | Začiatok predstavenia vrátane časovej zóny |
| `currency` | Mena cien, zvyčajne `EUR` |
| `prices` | Zoznam zón a tarifov s cenou a dostupnosťou |

### Odber dát

Pomocou Kafka konzolového consumeru možno čítať nové aj staršie správy:

```sh
kafka-console-consumer \
  --bootstrap-server 127.0.0.1:19092 \
  --topic lascala.prices \
  --from-beginning \
  --group lascala-readers
```

Vlastný consumer má používať samostatnú `group.id`. Kafka potom uchováva jeho
offset a po reštarte pokračuje od poslednej potvrdenej pozície. Pri prvom
spustení použite `auto.offset.reset=earliest`, aby sa načítali aj správy
publikované pred pripojením consumera.

Príklad v Pythone s balíkom `confluent-kafka`:

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
        snapshot = json.loads(message.value())
        print(snapshot["event_id"], snapshot["performance"], snapshot["prices"])
finally:
    consumer.close()
```

Consumer musí počítať s opakovaným doručením. Správy deduplikujte podľa
kombinácie `event_id` a `scraped_at`; neznáme nové polia ignorujte.

## Chybové stavy

| Kód | Kedy nastane |
|---|---|
| `200` s `[]` | Žiadny záznam nevyhovuje filtrom |
| `400 Bad Request` | `departureDate` nie je v tvare `YYYY-MM-DD` |
| `404 Not Found` | Nesprávna cesta (URL) |
| `502 Bad Gateway` | Server nie je dostupný |