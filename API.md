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

## Chybové stavy

| Kód | Kedy nastane |
|---|---|
| `200` s `[]` | Žiadny záznam nevyhovuje filtrom |
| `400 Bad Request` | `departureDate` nie je v tvare `YYYY-MM-DD` |
| `404 Not Found` | Nesprávna cesta (URL) |
| `502 Bad Gateway` | Server nie je dostupný |