# Distribution rights register — 2026-10-21

Scope: PhotoPilot 1.1.55 iOS/iPadOS and Android candidate. Free, no ads, no subscriptions or IAP; contact **chunguangwee@gmail.com**. This register records provenance and adopted permissions. It does not claim a trademark registration, third-party authorisation that was never obtained, or completed App Store submission.

| Material | Evidence and adopted treatment |
|---|---|
| PhotoPilot app/source | Root LICENSE remains non-commercial; third-party licences and the LGPL own-use modification/debugging exception remain independent. [UserTerms.md](UserTerms.md) adopts the Standard EULA route for Apple distribution |
| iOS AMSMB2/libsmb2 | Exact locked commits, public 218-file source archive/manifest in v1.1.53 (same library revision in 1.1.55), embedded licence texts and [SMBDistribution.md](SMBDistribution.md). Wrapper MIT; combined library LGPL 2.1, with libsmb2 lib/include LGPL-2.1-or-later. Source-level modified-library rebuild/runtime proven; final signed delivery/replacement check belongs to the joint release step |
| Android libraries | README, resolved Gradle dependencies and app licence page identify actual Apache/MIT/Bouncy Castle/public-domain notices. PhotoPilot's non-commercial restriction does not narrow those licences |
| Weather | Open-Meteo attribution/data CC-BY 4.0 plus separate free-service terms. Current free/no-ad app fits the published non-commercial example. Version 1.1.55 active wiring does not use wttr.in; see [WeatherPrivacyEvidence.md](WeatherPrivacyEvidence.md) |
| Six review PNGs | `ios/PhotoPilotUITests/SeedMedia`, `ReviewPhotos.zip`, review-service hash checks: synthetic test art, no customer photographs or stock-photo licence |
| Eight bundled demo images | Version 1.1.55 uses original local geometric/gradient artwork from `tools/gen_demo_art.py`; stable demo IDs and offline demo behaviour retained. No model inference, network photograph, likeness or trademark input. Current hashes: `AssetEvidence.json`; historical Qwen image records remain historical |
| Fourteen music tracks | `tools/gen_music.py`, the track register below, actual bundled hashes in `AssetEvidence.json`. Original synthesis/arrangements, no copied recordings, samples, vocals or lyrics. Nine procedural melodies plus five historical melodies |
| Current logo/icons | Versioned `design/export/brand-v1.2` geometric sources and generator; original frame/light-dot design approved for this project. No third-party logo is claimed or licensed. No trademark-registration or uniqueness warranty is asserted |
| Store screenshots | `ScreenshotEvidence.json`; screenshots must match the final candidate and use the synthetic media. Re-capture after demo changes where those images appear; hashes alone do not establish their accuracy |
| Fonts | No TTF/OTF embedded in the audited app archive; system rendering and vector brand paths. Preserve this scope rather than claiming rights to unrelated historical concept boards |

## Music actually shipped in 1.1.55

| IDs | Melody/source and action |
|---|---|
| `birthday_01`, `christmas_01`, `qixi_01` | New deterministic project melodies using seeds 101/113/114 and existing waltz/flowing synthesis. Removed the prior Happy Birthday / We Wish You / Mo Li Hua note arrays. No borrowed note array remains for these tracks. Names remain thematic “Birthday wishes”, “Merry Christmas”, “Qixi Festival”; no promise to play a particular song |
| `travel_01`, `coffee_01`, `lounge_01`, `morning_01`, `night_01`, `nature_01` | Existing deterministic procedural melodies; unchanged |
| `wedding_01` | Short Pachelbel Canon melody excerpt, own synthesis/harmony. Pachelbel died in 1706; historical composition, no modern score/recording imported. [Library of Congress composer record](https://www.loc.gov/item/2023870398/) establishes dates; it is not a licence for that archive's digital image |
| `spring_festival_01` | Traditional Clementine melody as used by New Year song, with no Chinese/English lyrics, no modern arrangement or recording imported. Nineteenth-century source: [1885 Bradford score scan (Library of Congress copy, mirrored)](https://upload.wikimedia.org/wikipedia/commons/b/b9/Bradford_Clementine.pdf). The historical source is evidence of the tune's age, not permission to copy later arrangements |
| `valentine_01` | Greensleeves traditional melody, own synthesis/harmony, no later orchestration or recording. The [Greensleeves Project's historical exhibition](https://greensleevesproject.uk/GSPexhibit.html) documents its Elizabethan context; no exhibition assets are bundled |
| `festive_01` | Jingle Bells melody only, own synthesis/harmony. [Library of Congress original 1857 score](https://www.loc.gov/item/2023812429/) identifies Pierpont's historical composition; no recording/modern arrangement copied |
| `classical_01` | Beethoven Ode to Joy melody only, own synthesis/harmony, no lyrics. [Beethoven-Haus](https://internet.beethoven.de/en/exhibition/beethoven-on-postage-stamps/) records the 1824 Ninth Symphony and Beethoven's 1827 death; no modern European-anthem arrangement/recording used |

Retain these five historical melodies on that provenance basis; do not require a new permission for the project's original synthesized recordings merely because a later protected recording of the same tune exists. This is not a declaration that every later arrangement or every jurisdiction's special rights have been cleared. No third-party score edition, lyric text, sampled instrument library or recorded performance is distributed. If the eventual territory/rights declaration raises a specific additional right, assess that right rather than substituting a blanket “worldwide public domain” statement.

The three replacements preserve all 14 choices, IDs, seeds, timbres and encoded durations: birthday **36.060590s**, Christmas **25.774150s**, Qixi **50.596281s**. Files are AAC, stereo, 44.1kHz at the existing 96kbps target; matching iOS/Android bytes. Evidence: `.build/rights-music-55/verification.json`. Other eleven bundled tracks are unchanged.

## Why the historical demo/music claims were narrowed

The previous Happy Birthday array is identifiable, not an invented project melody; [University of Louisville's manuscript account](https://louisville.edu/music/news/national-treasure-found-early-version-of-2018happy-birthday2019-song-discovered-at-uofl) documents the tune's origin. Replacing it avoids relying on a US court result as worldwide clearance. Mo Li Hua has multiple historical and modern versions; [the local cultural authority's account](https://www.njlh.gov.cn/lhgln/lhly/fyxm/202107/t20210713_3035715.html) records changes in 1957/1959. The old register did not identify an exact pre-modern score matching its array. The Christmas array similarly lacked an identified early tune source separate from a later arrangement. Procedural replacements resolve these specific provenance gaps without claiming infringement or dropping holiday music.

The self-hosted image record named Qwen-Image-2.1 but retained no separate deployment agreement. Its [official main-branch licence](https://huggingface.co/Qwen/Qwen-Image-2.1/blob/main/LICENSE) is the September 2026 Qwen Research licence: its non-commercial definition is research/evaluation, not every free app. Model terms and image-output rights are distinct; that text does not itself establish a broad output ownership grant. Hosted Qwen web-service terms do not establish rights for a private deployment. No model weights are shipped and no infringement conclusion is asserted. Replacing the eight bundled outputs with original local artwork removes this candidate's dependence on the missing deployment/output-rights evidence; historical model use is not retroactively declared authorised.

## Remaining release declarations

Use the actual App Store account publisher identity and selected territories at the joint online step. The Standard EULA route needs no invented custom-EULA postal/phone placeholders. Verify the final signed LGPL source/rebuild delivery and updated screenshots against the actual distributed candidate. Original assets do not require fabricated external permission letters or a generic lawyer signature. No email, account creation, payment or App Store submission was performed by this documentation task.
