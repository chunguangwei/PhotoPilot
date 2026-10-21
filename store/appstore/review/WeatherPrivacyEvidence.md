# Weather and analytics privacy evidence — 2026-10-21

Applies to implemented 1.1.52 data flows. This is a preparation worksheet, not a submitted or legally approved App Privacy answer.

| Flow | Data leaving the device | Recipient/purpose | Retention evidence |
|---|---|---|---|
| Manual city search | Search text, language, connection IP | Open-Meteo geocoding; find a city | Published free-API terms describe technical IP processing and troubleshooting logs potentially containing coordinates, removed after90days. Do not assume this establishes a separate search-log schema |
| Forecast | Coordinates rounded to0.1°, request parameters, connection IP | Open-Meteo; optional weather | Same published free-API notice; provider says not linked to user identities |
| Forecast fallback | Rounded coordinates, request parameters/language and connection IP | wttr.in; weather fallback | Official project source documents logging, but no verified production retention/use policy was located. A source-code cache TTL is not an access-log retention guarantee |
| System geocoding | System location before weather rounding, or fallback city-search text | Apple geocoder on iOS; configured system provider on Android | Governed by platform/provider terms. Do not describe the original geocoder location as already rounded |
| App Store statistics | Platform-defined download/opt-in usage metrics | Apple's platform reports | No PhotoPilot analytics SDK or custom telemetry endpoint; Apple usage visibility is opt-in/threshold limited |
| NAS review server | Explicit source requests/connection IP and the dedicated test account | Temporary synthetic-image service | Not automatically contacted by installs; not a photo-upload or production analytics backend. Dedicated service logs rotate; underlying host security logs are separate |

## App Privacy preparation

- Do not select “Data Not Collected” merely because PhotoPilot has no photo backend or manifest collection list is empty.
- Coarse Location used by weather is a likely disclosure candidate: App Functionality, not tracking, not linked to a PhotoPilot account. The actual provider retention qualifies the evidence; do not claim all requests are processed only in real time.
- Check whether retained city queries require Search History disclosure and how technical IP retention maps to Apple's current categories. Do not invent a stable device ID, or ignore stored IPs because there is no IDFA.
- Apple-system-only collection is distinct from developer/third-party collection. Inspect the live App Privacy form and actual suppliers before finalising entries.
- Optional weather does not automatically meet every condition for omitting data disclosure. Default-off is a user control, not a blanket disclosure exemption.
- The app sends no photos, filenames or NAS credentials to the weather services. Tracking/advertising use is not implemented.

## Remaining resolution

wttr.in requires authoritative production retention/use evidence, or an explicitly approved provider/design change with matching code, policy and tests. No supplier contact has been sent and no service removed in this documentation task. Do not remove the policy's unresolved item and call it verified.

The current free/no-ad/no-purchase decision supports evaluating the non-commercial Open-Meteo tier; it does not reserve commercial access for a future paid version. Confirm application-level/IP rate limits as adoption increases.

Sources: [Open-Meteo terms and free API privacy](https://open-meteo.com/en/terms), [wttr.in official source](https://github.com/chubin/wttr.in), [Apple App Privacy](https://developer.apple.com/app-store/app-privacy-details/), [Apple usage metrics](https://developer.apple.com/help/app-store-connect-analytics/engagement/app-usage).
