# airplanner

A command-line trip finder for Ryanair. It searches **several home airports against
several destinations at once**, and filters by **which days of the week** you can fly
and **what time of day** the plane may leave — separately for the outbound and the
return leg.

It reads the same public endpoints ryanair.com calls from the browser. It never books
anything, and prices always need confirming on ryanair.com before you pay.

It comes in two skins over one engine: a web page and a command line tool.

## Web UI

```bash
./gradlew bootRun
```

Then open <http://localhost:8080>. The page has the whole search on it: origins and
destinations, the date range, and a panel per leg where you tick the weekdays and set
the departure window — outbound and return independently.

- *Fly from* has **home airport** chips — Brno, Vienna, Prague, Ostrava, Bratislava, Krakow,
  Katowice, Wroclaw — tick the ones you can drive to, untick the rest. Change the list with
  `airplanner.home-airports` in `application.properties`.
- Both legs default to departing between **08:00 and 22:00**; the *any* preset next to each
  pair opens that back up to the whole day.
- *Fly to* takes named groups — **greek islands**, **canaries**, **balearics** — as one-click
  quick picks or typed straight into the field.
- The URL carries the whole query, so a result page is bookmarkable and shareable.
- **CSV** and **JSON** links next to the summary export exactly what you are looking at.
- Leave *Fly to* empty and it switches to Ryanair's "anywhere" search: the cheapest
  destination from each of your origins, under the same day and time filters.
- Every row has a **book** link that opens ryanair.com with those exact dates filled in.
- Every row has a **stays** menu with free direct searches on **Airbnb**, **Booking.com**,
  **Expedia**, **Hotels.com**, **Vrbo** and **Agoda**.
  Links pass the destination city and default accommodation party: **2 adults and 3 children aged 2, 5 and 7**.
  Configure `airplanner.stay-adults` and `airplanner.stay-child-ages` to change this independently of flight passengers.
  Ages refer to check-in; they are not automatically increased with future travel dates.
  Booking.com, Expedia, Hotels.com, Agoda and Stay22 links include child ages; Airbnb and Vrbo links include child/infant counts.
  Dates run from local arrival to return departure;
  for one-way trips, unknown arrivals or same-day returns, choose dates on the provider's site.
  Hotel searches start with one room, which you can change there. Prices and availability are checked
  on the provider's site; no paid API, API key or accommodation request is needed to render results.
- Set `STAY22_AID` (or `airplanner.stay22-aid`) to also show **Stay22** using its recommended `/allez/roam` endpoint.
  [Stay22 requires an affiliate ID](https://dev.stay22.com/docs/allez/parameters), available through free registration.
  Without it, all six direct provider links remain available. Direct search URLs are website links, not stable APIs;
  providers can change their format or reset filters, so confirm the search details after opening them.

Endpoints, if you want to script against it: `GET /api/search`, `GET /export.csv`
(both take the same query parameters as the page) and `GET /api/airports`.

Settings live in `src/main/resources/application.properties` (`airplanner.market`,
`airplanner.fare-cache-ttl`, `airplanner.cache`, `airplanner.max-refine`).

## Command line

```bash
./gradlew installDist
./build/install/airplanner/bin/airplanner --help        # airplanner.bat on Windows
```

Or without installing: `./gradlew run --args="search -f DUB -t STN --weekend"`.

Java 21 is required for both.

## The search you probably came for

Leave Friday or Saturday after 15:00, come back Sunday or Monday morning, from either
of two home airports, to any of three cities, some time in November:

```bash
airplanner search -f VIE,BTS -t BCN,AGP,NAP --month 2026-11 \
    --out-days fri,sat --out-time 15:00-23:59 \
    --back-days sun,mon --back-time morning \
    --nights 2-3
```

```
#  ROUTE    DESTINATION       OUTBOUND                RETURN                  NTS   STAY       PRICE
1  VIE-BCN  Barcelona, Spain  Sat 07 Nov 17:20-19:45  Mon 09 Nov 08:55-11:25    2  1d13h   57.12 EUR
2  BTS-BCN  Barcelona, Spain  Fri 27 Nov 18:10-20:40  Mon 30 Nov 09:05-11:30    3  2d12h   61.98 EUR
...
```

## Commands

| Command | What it does |
| --- | --- |
| `search` | The full origin × destination scan with every filter. |
| `explore` | "Where can I go?" — cheapest destination from each origin, one request per origin. |
| `calendar` | Month price grid for a route, so you can see which dates are cheap. |
| `airports` | Find airports, list the routes one serves, the named groups, or the destinations several origins share. |
| `watch` | Re-run a search on a timer, remember the cheapest price seen, alert on drops. |
| `cache` | Show or clear the local response cache. |

Every command prints `--help` with its own options and examples.

## Filters

| Option | Meaning |
| --- | --- |
| `-f, --from` | Origin airports: `DUB,STN`, or names — `vienna,bratislava`, `country:sk`. |
| `-t, --to` | Destinations; same syntax, plus groups like `"greek islands"`. Omit to search everywhere. |
| `--airlines` | `ryanair`, `wizzair` or both (default). See [Airlines](#airlines). |
| `-d/-D` or `--month` | Outbound date window: `-d 2026-11-01 -D 2026-12-15`, `-d +14d`, or `--month 2026-11`. |
| `--out-time`, `--back-time` | Departure window per leg: `07:00-12:00`, or `morning`, `afternoon`, `evening`, `night`, `early`, `business`, `afterwork`. |
| `--out-days`, `--back-days` | Weekdays per leg: `fri,sat`, `weekend`, `weekdays`, `any`. |
| `--nights` | Nights away: `3` or `2-5`. `0` allows a same-day return. |
| `--min-stay` | Minimum time on the ground, e.g. `36h` — stops "land at 23:00, fly back at 06:00". |
| `--budget` | Maximum price per adult for the whole trip. |
| `--weekend` | Preset for Fri/Sat out, Sun/Mon back, 1–3 nights. |
| `--one-way` | Outbound only. |
| `-p, --passengers` | Adults. Prices stay per adult; a party total is added. |
| `--sort` | `PRICE` (default), `VALUE` (per night), `DEPARTURE`, `LENGTH`, `DESTINATION`. |
| `-n, --limit`, `--per-route` | How many trips to show, and how many any single route may contribute. |

## Airlines

Both carriers are ticked by default, but they are not equal partners:

| Airline | What this tool can read | Why |
| --- | --- | --- |
| **Ryanair** | Full fares, times and calendars | It publishes the endpoints its own website calls |
| **Wizz Air** | Route network only — no prices | Its fare API (`/search/timetable`, `/search/search`) answers 400 or 429 to anything that is not their browser, and issues no session cookies |

So a result row is always a Ryanair price. When Wizz Air is ticked and it flies the same
route, the row gets a **W6** link that opens wizzair.com on those exact dates, so you can
price it yourself in one click. On the command line that is an `ALSO` column:

```
#  ROUTE    DESTINATION       OUTBOUND                RETURN                  NTS   PRICE       ALSO
1  BTS-BCN  Barcelona, Spain  Fri 02 Oct 07:00-09:35  Sun 04 Oct 22:05-00:35    2   81.98 EUR   W6
3  VIE-BCN  Barcelona, Spain  Fri 16 Oct 08:40-11:05  Sun 18 Oct 05:45-08:10    2  132.46 EUR
```

Wizz's route map is exact — Vienna shows no Wizz routes above because they closed that
base. Select airlines with `--airlines ryanair,wizzair` on the CLI, or the checkboxes on
the page.

### How far these airlines actually go

Neither carrier leaves the short/medium-haul world, so the Maldives, Seychelles, Mauritius
and the Caribbean are simply not searchable here — no amount of plumbing changes that:

- **Ryanair's** furthest destinations are Morocco (Agadir, Marrakesh, Dakhla, …) and Amman,
  plus the Atlantic islands (Canaries, Madeira). The `winter sun` group is exactly that set.
- **Wizz Air** goes a little further — Dubai, Abu Dhabi, Jeddah, the Egyptian Red Sea
  (Hurghada, Sharm El Sheikh, Marsa Alam), Tel Aviv — but publishes no fares, so those show
  up only as W6 links on routes Ryanair also flies. Wizz Air Abu Dhabi, which did fly to
  Malé, closed in 2025.

For genuinely long-haul beaches you need an aggregator with a licensed feed; see below.

### More airlines and aggregators

An optional **Duffel exact-date search** is available at `/duffel`, linked from the
main page. It supports one route, one-way/return trips, multiple adults and connections.
It is disabled by default, requires a server-side token and starts in test mode.
See [Duffel setup](docs/duffel-setup.md) for configuration, request limits and the JSON API.

Duffel lists easyJet, Vueling, Eurowings and Pegasus, among others. Access, coverage
and search costs need checking before enabling live searches; a blocked airline website
does not rule out a partner API.

See [the airline and aggregator analysis](docs/airlines-and-aggregators.md) for verified
sources, pricing, access restrictions and a concrete integration design (Czech,
reviewed 2026-09-09). Amadeus Self-Service does not cover low-cost carriers according
to its FAQ; Kiwi Tequila is invitation-only, and Skyscanner has commercial entry criteria.

Every result has an **all** link to Google Flights for the same route and dates.
Coverage varies; this is a comparison link, not another source of prices in this app.

## Saying where you want to go

Anywhere an airport is expected you can write an IATA code, a city, a country, a region,
or a **named group**:

```bash
airplanner search -f VIE -t "greek islands" --month 2026-10 --out-days fri,sat --nights 2-4
```

```
#  ROUTE    DESTINATION              OUTBOUND                RETURN                  NTS   STAY       PRICE
1  VIE-CFU  Corfu, Greece            Fri 16 Oct 10:45-13:35  Sun 18 Oct 07:00-07:55    2  1d17h   62.90 EUR
2  VIE-ZTH  Zakynthos, Greece        Fri 16 Oct 13:15-16:25  Mon 19 Oct 14:00-15:10    3  2d21h  103.65 EUR
...
Notes:
  - No direct Ryanair route VIE-JSI, skipped.
```

`airplanner airports --groups` lists them all:

| Group | Also known as | Covers |
| --- | --- | --- |
| `winter sun` | warm, sun | Canaries, Madeira, Morocco, Cyprus, Jordan — as far as these airlines go |
| `greek islands` | greek isles, islands of greece | Every Greek island Ryanair flies to |
| `canary islands` | canaries, canarias | Lanzarote, Fuerteventura, Gran Canaria, Tenerife |
| `balearic islands` | balearics, baleares | Mallorca, Ibiza, Menorca |
| `italian islands` | islands of italy | Sicily and Sardinia |
| `spanish islands` | islands of spain | The Canaries and the Balearics |
| `mediterranean islands` | med islands | Greek, Italian and Balearic islands, Malta, Cyprus |
| `atlantic islands` | | The Canaries and Madeira |

Most groups are a filter over Ryanair's own data — the airline tags Corfu, Rhodes and the
rest with the region *Greek Islands*, so an island added next summer joins the group by
itself. Codes are only listed by hand where the published region is unusable (Palma is
filed under "Default ES"). Regions work on their own too: `-t sicily`, `-t "region:costa
del sol"`. Routes an origin does not serve are skipped with a note rather than failing.

## Output

- `--format table` (default), `csv`, `json`, or `html`; `-o FILE` writes to a file.
- `--links` prints a ryanair.com deep link per result, pre-filled with the exact dates.
- `--open N` opens result N in your browser.

## How the time-of-day filter stays honest

Ryanair's cheap "fare per day" endpoint returns only the *cheapest* flight on each day,
which may well leave at 06:00 when you asked for evenings. So the scan runs in two
phases:

1. one request per route and month builds the full price calendar;
2. for each day that passes your weekday filter but whose cheapest flight falls outside
   your time window, a second, day-specific request asks Ryanair for the best fare
   *inside* the window.

That keeps a wide search to a handful of requests per route without silently dropping
days. `--fast` skips phase 2 (fewer requests, may miss trips); `--max-refine` caps it.

## Being a good citizen

Responses are cached under `~/.airplanner/` (30 minutes for fares, 24 hours for the
airport and route lists), requests are rate limited and capped at six in flight, and
failures are retried with a backoff. `--no-cache` and `--cache-ttl` override the
caching; `airplanner cache --clear` empties it.

## Saved searches

The web form can save up to 20 named searches in this browser. Use **Save current search**,
then choose a saved entry to **Load** or **Delete** it. Reusing a name replaces that entry.
Dates are saved as entered; loading runs a fresh search. Browser storage must be available.


picocli argument files work out of the box — put the options in a text file and pass it
with `@`:

```
# weekend.txt
search
-f VIE,BTS
-t BCN,AGP,NAP
--out-days fri,sat
--back-days sun,mon
--nights 2-3
```

```bash
airplanner @weekend.txt --month 2026-11
```

## Tests

```bash
./gradlew test
```

The suite covers the filter parsing and, against a stubbed API, the day/time filtering,
trip assembly, budget rules and booking-link construction, plus the web form mapping and
the page rendering through MockMvc — no network needed.

## Layout

| Package | What lives there |
| --- | --- |
| `model` | Airports, flights, trips, and the filter value types. |
| `client` | The HTTP client (cache, rate limit, retries) and the Ryanair endpoints. |
| `search` | The airport directory and the two-phase search itself. |
| `output` | Table, CSV, JSON, HTML and calendar rendering for the CLI. |
| `cli` | The picocli commands. |
| `web` | The Spring Boot app: form, controller, Thymeleaf page. |

The web layer is a front end over the same `FlightSearchService` the CLI uses; nothing
about the search is duplicated.
