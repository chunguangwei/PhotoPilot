# Task 2 Android report

## Implemented

- Source completion now distinguishes Welcome first-use intent, retained reference editor and source management. Management connection/reauthorization refreshes the source list and reports success. NAS connection no longer forces a new frame or replaces the source editor with import progress.
- First-use confirmation creates a default-named frame only on confirmation and opens Player directly. Name, sync/cache and manual-order controls are hidden for this flow. Exact-file results retain their URI-derived ID range and become preselected draft content. Folder and album browsing retains its range. Changing sources preserves selected draft references.
- Existing-frame source additions keep the target and reference editor instance. The editor exposes Photos and Files/folder alongside NAS. Returning from a picker merges its explicit-file selection into the draft. An in-flight previous photo page is invalidated before switching its scope.
- Local editor transitions into reference selection, reference directories or AI require a dirty-draft Save/Discard/Cancel decision. Save atomically persists the full current local draft, then opens the next editor with that target. The underlying editor reloads after a reference save. This explicit gate is the chosen cross-editor strategy.
- Local save creation, rename, selection, rules, order and cover updates are enclosed in one Room transaction. Reference save retains its transaction and applies only locally changed name, mode, cover and directory rules to fresh frame metadata. Selection deltas use the current transaction version, retaining concurrent untouched selections and exclusions. Newly added inaccessible references fail before frame creation.
- Both editors block duplicate saves, provide saving feedback and retain editable drafts after errors. Reference Back now checks selection/rules/name/mode/cover/order against persisted state, including restored drafts. System Back uses the same guard. First-use confirmation and local editor drafts survive configuration restoration.
- Reference confirmation scrolls its complete content including photo rows and confirmation. The local editor's compact layout also handles large-font portrait, keeping a weighted photo grid while controls and Save remain scrollable.
- No schema, SDK, shared localization, iOS, root16, signing, release or source-original mutation changes. New user text follows the existing inline English/Chinese convention.

## Verification

Final command logs and emulator evidence are retained under `.build/photo-flow-android-*`.

- `make check` passed, including schema parity17 tables and the final58-test Python group.
- `make android-build android-lint android-test` passed with Android Studio JBR and the installed Android SDK. JVM tests440, failures0, skipped0. Lint reports205 warnings and0 errors.
- Five focused flow tests cover first-use scoped/preselected selection and double confirmation/direct playback, source management and picker cancellation, target draft merging/concurrent metadata, forced transaction failure/rollback/retry and dirty cross-editor/back/save gating.
- The scoped first-use test additionally uses2 of3 synthetic photos, exercises actual photo-row touches and Back/Cancel, confirms twice and verifies one frame plus Player.
- Native reference repository regressions13/13 and remote metadata/storage1/1 passed. The legacy migration/order/exclusion/AI ownership regression passed1/1 after replacing its fixed600ms readiness delay with bounded polling.

- Existing native layout instrumentation passed36 combinations, English/Chinese,100%/145%/200% fonts and six portrait/landscape sizes including640×1600 and1600×640 pixels. It asserts a noncollapsed grid and reachable Save/Back. Log `.build/photo-flow-android-layout-tests.log`.
- Actual Android system file-picker journey passed1/1 in94.304 seconds. It chose a generated image through DocumentsUI, asserted source management did not open a frame, explicitly entered confirmation, saved the reference and decoded the original. Log `.build/photo-flow-android-saf-tests.log`.
- Actual narrow UI run passed1/1 in41.311 seconds. Dedicated emulator override640×1600 pixels,density320 gives320×800dp,font_scale2.0. Its actual photo row was touched, Back/Cancel retained its draft and double confirmation produced one frame and Player. Display size,density and font scale were restored afterward.
- Screenshots were exported and inspected under `.build/photo-flow-android-evidence/`. They include scoped confirmation, existing-target draft, forced-failure retry, dirty cross-editor prompt and direct Player. The first Player capture preceded bitmap arrival. The final same-variant run waited for an actual BitmapDrawable in Player and passed1/1 in49.549 seconds. Its screenshot shows the decoded gold synthetic photo beneath Android’s first-use fullscreen education overlay. That system overlay is retained in the evidence. Log `.build/photo-flow-android-decoded-player-tests.log`.
- Final three-line stale-page invalidation is covered by the final build/lint/JVM checks. The existing-target UI run preceded that guard.
- SAF persisted-grant/process-restart/revocation/deletion cleanup passed1/1 in4.924 seconds, confirmed a different process PID and removed only its generated photo and frame. Log `.build/photo-flow-android-saf-restart-tests.log`.
- Five distinct new flow tests passed across the focused runs. The final native reference/legacy/storage group has15 distinct passing tests. Real SAF preparation/restart adds2 passing tests. The layout instrument separately exercised36 combinations.
- `git diff --check -- android` passed.

## Verification corrections

- Initial flow run passed4/5. The dirty-editor test queried accessibility after a fixed300ms wait. Bounded polling replaced that wait.
- A second dirty-editor harness attempt matched the title containing “unsaved” during its Save search. Exact clickable-label matching corrected it, and the dirty-editor/back/save test then passed.
- The existing legacy regression read its asynchronous draft after a fixed600ms. The dedicated emulator had not completed the initial query. Readiness polling and a bounded15-second save wait passed the unchanged migration assertions.

## Limits

- The emulator is the existing dedicated `photopilot_ai_test`, serial `emulator-5554`. No personal Android device was connected or operated.
- Exact multi-file scope tests use synthetic source-completion callbacks with real MediaStore originals. Real system-picker verification selected one file. A real system tree-picker journey and full physical rotation/restoration journey were not executed in this task.
- No live NAS, third-party Files provider, physical-device, network-loss, store/M0 acceptance, production signing or full-model inference validation was performed. No design-acceptance claim is made.
- Main agent owns root16 and integrated review. This report records executed checks and explicit limits.
