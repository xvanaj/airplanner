# Aerolinky a agregátory

Analýza k 9. 9. 2026. Ceny a dostupnost účtu je před integrací potřeba znovu potvrdit.

## Stav projektu

Java 21, Spring Boot, Thymeleaf a picocli sdílejí `FlightSearchService`.
Ryanair dodává ceny přes webové endpointy; nejde o smluvní veřejné API se SLA.
Wizz Air dodává síť tras a odkazy, nikoliv ceny. Přidání položky do `Airline`
samo o sobě další ceny nezpřístupní.

Silné stránky: společný engine, disková cache, omezení souběhu, filtry dnů a časů,
exporty, testy bez sítě. Slabší místa pro rozšíření:

- `FlightSearchService` je přímo závislý na `RyanairClient` a jeho kalendáři.
- `Flight` představuje jediný přímý let a neobsahuje dopravce ani zdroj nabídky.
- `Trip.bookingUrl()` vždy vede na Ryanair. Agregátor potřebuje vlastní způsob rezervace.
- `AirportDirectory` vychází z Ryanairu, takže nepokrývá nezávisle světová letiště.
- Cena se skládá ze dvou jednosměrných tarifů. Agregátor může ocenit zpáteční
  cestu jako celek; rozdělení a opětovné sečtení může cenu znehodnotit.
- Kalendářové ceny nejsou potvrzením dostupnosti stejné ceny pro celou skupinu.
- Pobyt se počítá z lokálních časů; přechod letního času může výsledek posunout.
- Síťové statistiky jsou sdílené a u souběžných webových hledání se mohou překrývat.

## Co lze přidat

| Dopravce | Ověřená cesta k cenám | Poznámka |
| --- | --- | --- |
| easyJet | Duffel | Samostatná produktová stránka potvrzuje search/book. |
| Vueling, Transavia | Duffel Direct Connect | Uvedeny v katalogu dopravců. |
| Eurowings | Duffel přes Travelport | Nezaměňovat s Discover Airlines. |
| Pegasus | Duffel přes Travelport | Uveden v katalogu; ověřit konkrétní trasy a tarify. |
| Austrian, Lufthansa, SWISS, LOT | Duffel | Zajímavé pro odlety z VIE/PRG a navazující dálkové lety. |
| Wizz Air, Ryanair, Smartwings | Další smluvní ověření | Pro jejich ceny zde neslibujeme pokrytí Duffelem. |

Zdroje: [katalog Duffel](https://duffel.com/flights/airlines),
[easyJet](https://duffel.com/flights/airlines/easyjet),
[Eurowings](https://duffel.com/flights/airlines/eurowings).
Katalog je vodítko, nikoliv záruka dostupnosti každého letu pro konkrétní účet a trh.
HTTP 403 na webu aerolinky nevylučuje oficiální distribuci přes partnera.

## Je agregátor placený?

Obvykle ano v produkci, ale liší se účtování i možnost získat přístup.

| Služba | Cena / přístup | Vhodnost pro tento projekt |
| --- | --- | --- |
| Duffel | Veřejný USD ceník: $3 za objednávku, Managed Content 1 % hodnoty; $0.005 za hledání nad poměr 1500:1. | Kandidát pro více dopravců, nutno potvrdit režim pouze hledání bez rezervací. |
| Amadeus Self-Service | Dokumentace uvádí měsíční bezplatnou kvótu i v produkci, další volání placená podle API. | FAQ vylučuje low-cost dopravce, také AA, Delta a BA; není náhrada za Ryanair/Wizz. Dostupnost nového účtu ověřit před implementací. |
| Skyscanner Travel API | Schvalované komerční partnerství; kontaktní stránka požaduje minimálně 100 tisíc měsíčních aktivních uživatelů. | Pro osobní plánovač nereálný výchozí bod. Cena není prostý veřejný tarif za volání. |
| Kiwi Tequila | Nová partnerství pouze na pozvání dle oznámení z roku 2024. | Nespoléhat na staré návody k volně dostupnému klíči. |

Zdroje: [Duffel ceník](https://duffel.com/pricing),
[Amadeus ceny](https://admin.developers.amadeus.com/self-service/apis-docs/guides/developer-guides/pricing/),
[Amadeus FAQ](https://admin.developers.amadeus.com/self-service/apis-docs/guides/developer-guides/faq/),
[Skyscanner podmínky přístupu](https://www.partners.skyscanner.net/contact/travel-api),
[Kiwi oznámení](https://media.kiwi.com/articles-and-interviews/better-for-business-kiwi-com-takes-a-new-approach-to-partnerships/).

### Proč široké hledání může být drahé

Při modelu jednoho požadavku na trasu a dvojici dat znamená 8 letišť × 20 destinací
× 30 odletových dnů × 4 délky pobytu až **19 200 požadavků** před omezením podle dnů
a tras. Při dosud nevyužité měsíční kvótě a nulových rezervacích vychází excess
search podle veřejného ceníku na **(19 200 − 1 500) × $0.005 = $88.50**.
Pokud je kvóta již vyčerpaná, dalších 19 200 hledání vychází na $96.

Upřesnění: článek 2.3 [Services Agreement](https://duffel.com/services-agreement)
výslovně počítá nulu objednávek jako jednu pro účely poměru hledání k objednávkám.
Společně s měsíčním ceníkem to znamená základ 1 500 hledání bez excess poplatku
i bez rezervace. Nejde však o neomezené oprávnění provozovat kalendářový metavyhledávač:
Duffel si vyhrazuje omezení provozu a vhodnost tohoto použití je potřeba ověřit.

## Návrh napojení

1. Nejprve sandbox a jediná konkrétní trasa/dvojice dat. Ověřit obsah, měnu,
   cestující, časové zóny a oprávnění zobrazovat nabídky bez rezervace.
2. Zavést `FareProvider` oddělený od dopravce. Schopnosti zdroje explicitně:
   kalendář / pevná data / kamkoliv / přestupy / rezervace. Ryanair může zachovat
   svůj efektivní kalendářový algoritmus.
3. Nový model `Offer`: provider, offer ID, expirace, cena za celou skupinu, měna,
   seznam cest s jednotlivými segmenty, marketingový a provozující dopravce,
   zavazadla, časové zóny a rezervační odkaz jen pokud ho zdroj opravdu poskytne.
4. Pro Duffel serverově poslat `POST /air/offer_requests`: seznam `slices`
   s origin/destination/departure_date a `passengers` s typem adult.
   Autentizace Bearer tokenem a `Duffel-Version: v2`. Token pouze na backendu
   v proměnné prostředí, nikdy v URL, uloženém hledání ani JavaScriptu.
5. Odlety a návraty filtrovat i lokálně. Zachovat cenu celé nabídky, nepřičítat
   libovolné segmenty a nesrovnávat částky různých měn jako stejné jednotky.
6. Nezávislý katalog letišť a přestupů; samostatně zobrazit dobu cesty,
   přestupy a provozující dopravce každého segmentu.
7. Před voláním odhadnout počet dotazů. Zavést pevný limit na hledání a den,
   omezení souběhu, rušení dotazů a cache podle podmínek poskytovatele.
   Široký kalendář rozšířit až po ověření ceny provozu.
8. Před nákupem znovu potvrdit cenu a dostupnost. Testy nad uloženými anonymními
   odpověďmi: zpáteční celková cena, více dospělých, expirace, přestupy a výpadek zdroje.

Dokumentace: [Duffel Offer Requests](https://duffel.com/docs/api/v2/offer-requests).

Následně byla implementována volitelná integrace Duffel pro pevná data na `/duffel`,
s vlastním modelem nabídek, segmenty a celkovou cenou. Je vypnutá, výchozí režim je
testovací a vyžaduje token. Žádný placený zdroj nebyl aktivován ani volán.
Viz [zprovoznění a limity](duffel-setup.md). Výše uvedené body pro obecný provider
model a široké kalendářové hledání zůstávají návrhem dalšího rozšíření.

## Provedené úpravy

- Společné finální filtrování běžného hledání a režimu kamkoliv: dny, časy,
  datum, noci, minimální pobyt, rozpočet a počet výsledků na trasu.
- Odmítnutí návratu před příletem i u nočních letů.
- Stabilnější pořadí stejně oceněných tras.
- Až 20 pojmenovaných hledání v prohlížeči, načtení a smazání; bez ukládání cen.
- Obnovení tlačítka hledání při návratu zpět v prohlížeči.
- Upřesnění textů o skutečném pokrytí dopravců.

Režim kamkoliv stále vrací kandidáty poskytovatele, nikoliv úplný kalendář všech
letů. Po lokálním filtrování nemusí najít dražší alternativu, kterou upstream nevrátil.
