# Weather and privacy evidence — 2026-10-21

Scope: PhotoPilot 1.1.55–1.1.57 iOS/iPadOS and Android source; active iOS wiring rechecked at57. Version 1.1.53/1.1.54 used automatic wttr.in fallback; since1.1.55 active provider wiring uses Open-Meteo only. This record supports the final policy and the later online App Privacy form; it does not claim the form has been submitted. The full57 entry worksheet, including every category and conditional support handling, is [AppPrivacyQuestionnaire.md](AppPrivacyQuestionnaire.md).

| Actual flow | Leaves device / recipient | Purpose and evidence |
|---|---|---|
| City search | Search text, language, connection IP / Open-Meteo | Find city; do not assume search URLs are excluded from provider logs |
| Forecast | Coordinates rounded to 0.1°, request parameters, IP / Open-Meteo | Optional weather; technical IP processing and troubleshooting logs potentially containing coordinates, deleted after 90 days under published free-API terms |
| System geocoding | Original system location, or fallback city-search text / Apple on iOS, configured system provider on Android | Resolve place names; original location may be more precise than the rounded forecast coordinate |
| Local photo/audio library | No transfer to PhotoPilot backend | Local import, rendering, playback; system picker/cloud provider operations remain governed by the selected source |
| Android update | GitHub Release/API/download requests, IP and headers | Only on user action; no photos/credentials/account identifier sent |
| Support and diagnostics | User-selected report or message / recipient chosen through sharing or email | Optional support; report includes version, OS, schema, date and applicable error code/type, not photo names, NAS addresses or credentials |
| App Store statistics | Apple-defined aggregate download and opt-in/threshold-limited usage reports | No PhotoPilot analytics SDK or custom telemetry endpoint |
| Explicit review NAS | Read requests, connection IP and test account / temporary synthetic-image service | Only when reviewer/tester configures it; not contacted automatically and no upload endpoint |

## App Privacy entry preparation

Apply [Apple's definitions](https://developer.apple.com/app-store/app-privacy-details/) to retained third-party data, not only the app's manifest. Do not select “Data Not Collected.” Prepared categories for the iOS weather flow are **Coarse Location**, **Search History** and **Other Diagnostic Data** (technical IP/server troubleshooting), purpose **App Functionality**, **no tracking**. This is a conservative mapping: Open-Meteo does not publish a distinct city-query log schema. Do not mark Device ID merely because an IP is present.

Open-Meteo says its technical IP information is not linked to user identities. Its notice does not document irreversible de-identification before log storage. For the online form, use the conservative **linked to the user** answer for the retained weather/search/diagnostic categories unless a verified supplier explanation supports Apple's “not linked” criteria. No PhotoPilot account exists; that alone does not establish anonymisation. Optional weather is not a disclosure exemption. Apple-only system collection is distinct; current-location geocoding still belongs in the public policy. Support exports are optional and user-initiated; review the form's optional-support exception when entering the actual form.

## Provider decision

[Open-Meteo terms](https://open-meteo.com/en/terms) distinguish data attribution (CC-BY 4.0) from free-service access. The current free/no-ad/no-purchase app fits the listed non-commercial app example; retain attribution. Published API limits apply by application and IP: 600/minute, 5,000/hour, 10,000/day. Reassess service capacity before broad rollout and commercial access before monetisation; no paid service was activated here.

[wttr.in's official project](https://github.com/chubin/wttr.in) does not establish a verified production retention policy. The chosen resolution removes it from both active forecast provider lists rather than claiming an invented retention period. Dormant implementation and historical records may remain; neither means 1.1.55 sends fallback traffic.

Source inspection: `ios/PhotoPilot/App/AppEnvironment.swift`, `ios/PhotoPilot/Data/Net/WeatherProvider.swift`, `ios/PhotoPilot/Data/WeatherRepository/WeatherAutoLocator.swift`; Android `data/AppEnvironment.kt`, `data/net/OpenMeteoProvider.kt`, `data/net/NativeGeocoderProvider.kt`, `MainActivity.kt`, `data/update/UpdateChecker.kt` and `UpdateDownloader.kt`.

Other primary evidence: [Apple location privacy](https://www.apple.com/legal/privacy/data/en/location-services/), [Apple usage metrics](https://developer.apple.com/help/app-store-connect-analytics/engagement/app-usage), [GitHub privacy](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). Final policy: [PRIVACY.md](../../../PRIVACY.md).

Candidate58 synchronises Coarse Location, Search History and Other Diagnostic Data in the app privacy manifest, App Functionality/linked=true/tracking=false. This changes declarations, not the weather request flow. Actual ASC entries and final Distribution validation remain pending.
