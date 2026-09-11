# Duffel: exact-date search

Open `/duffel` from the **More airlines** link on the main page. This is a separate
search for one airport pair, an exact outbound date and an optional return date.
It supports 1–9 adults, economy and up to two connections per direction.

The existing Ryanair calendar and CLI continue to work without Duffel. This first
integration is available in the web UI and JSON API; it does not add a CLI command.

## Start in test mode

Create a Duffel account and generate a **test access token** in its dashboard.
Set these environment variables in the process that launches the application:

```text
DUFFEL_ENABLED=true
DUFFEL_LIVE=false
DUFFEL_ACCESS_TOKEN=<your test token>
```

Then run `./gradlew bootRun` (Windows: `./gradlew.bat bootRun`) and open
<http://localhost:8080/duffel>. Tokens belong only in your server environment or
IDE run configuration. Do not put them in committed files, URLs or browser code.

The application defaults to disabled, requires a matching `duffel_test_` token in
test mode, and rejects a live token before any network call. A test response is
visibly labelled **sample fares**. Test data does not establish real airline coverage.

For live searches, a matching `duffel_live_` token and `DUFFEL_LIVE=true` are both
required. Confirm account access and the suitability of search-only use with Duffel
before enabling it. This integration has been tested with mocked responses; no
real account, test token or live token was used during implementation.

## Request limits

Each accepted search makes exactly one `POST /air/offer_requests` call. It sends
all adults and both directions together. There is no automatic fanout, split-ticket
search, polling or retry. The supplier timeout is 10 seconds, HTTP timeout 25 seconds.
Only one request may run at a time in an application instance.

Defaults:

```properties
airplanner.duffel.daily-limit=20
airplanner.duffel.monthly-limit=500
```

The counters reset by UTC day/month and are persisted at
`~/.airplanner/duffel-usage.json`. Override `airplanner.duffel.usage-file` to use a
persistent shared location for installations using the same account. A sibling
`.lock` file coordinates access. Counters are reserved **before** sending HTTP;
failed or interrupted requests still consume the local allowance because the
supplier may already have processed them. Missing write access, corrupt counters
or unavailable atomic file replacement block searches instead of resetting usage.

These limits count this installation's attempts, not account-wide Duffel usage.
Deleting the file, using different files or running on separate machines can reset
or split that count. They are not a guarantee of a free Duffel bill. Track the
account dashboard too; see [pricing analysis](airlines-and-aggregators.md).

Opening the form and checking status do not search. Successful form submissions
redirect to a results page so refreshing does not repeat the request. Results are
shown once after the redirect, are not persisted as live fares, and are lost on refresh.
Resubmitting the form deliberately starts a new search and consumes another request.

This remains a local application. Session request tokens prevent a third-party web
page from submitting searches using your browser, but are not user authentication.
Add access control before exposing a live-enabled instance on a public network.

## What results mean

- Prices are the provider's total for all requested adults and the whole itinerary,
  including both directions when requested. No per-segment price is invented.
- Offers are grouped by currency and then sorted by amount; no exchange rate is assumed.
- Every segment shows the operating carrier, marketing carrier when different,
  departure/arrival in local airport time zones and any intermediate stops.
- Expired, incomplete, temporally inconsistent or non-matching offers are omitted.
  At most 50 offers are displayed. The supplier timeout can limit coverage.
- There is no booking button, order creation or fake airline booking deep link.
  Baggage and fare conditions still require confirmation before purchase.

## JSON API

1. `GET /api/duffel/status` returns `provider` (ready, liveMode, reason, usage) and
   `csrfToken`. Retain the session cookie. Neither this endpoint nor the page
   returns the Duffel access token.
2. `POST /api/duffel/search`, with that cookie, `X-CSRF-Token` and
   `Content-Type: application/json`:

```json
{
  "origin": "PRG",
  "destination": "LHR",
  "departureDate": "2026-11-06",
  "returnDate": "2026-11-09",
  "adults": 2,
  "maxConnections": 1
}
```

Use future dates; omit/empty `returnDate` for one-way. Responses include `search`,
`fetchedAt`, `liveMode`, `offers`, `notes` and the local `usage`. Errors use 400 for
invalid inputs, 403 for missing session token, 429 for request limits, 503 for
disabled/unconfigured integration or unreadable allowance, and 502 for provider failures.

Reference: [Duffel offer requests](https://duffel.com/docs/api/v2/offer-requests)
and [offers](https://duffel.com/docs/api/v2/offers).
