.PHONY: check ios-generate ios-build ios-analyze android-build android-lint android-test fixtures-generate fixtures-check design-check design-freeze docs-check

check:
	python3 tools/check_scaffold.py
	python3 tools/check_contracts.py
	python3 tools/check_strings_freshness.py
	python3 tools/check_schema_parity.py
	python3 tools/check_fixtures.py
	python3 tools/check_design_assets.py
	python3 tools/generate_brand_assets.py --check
	python3 tools/gen_demo_art.py --check
	python3 tools/check_doc_links.py
	python3 -m unittest discover -s tools/tests -v

design-check:
	python3 tools/check_design_assets.py

design-freeze:
	python3 tools/check_design_assets.py --freeze

docs-check:
	python3 tools/check_doc_links.py

fixtures-generate:
	python3 tools/generate_fixtures.py

fixtures-check:
	python3 tools/check_fixtures.py

ios-generate:
	xcodegen generate --spec ios/project.yml

ios-build:
	xcodebuild -project ios/PhotoPilot.xcodeproj -scheme PhotoPilot -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath .build/ios CODE_SIGNING_ALLOWED=NO build

ios-analyze:
	xcodebuild -project ios/PhotoPilot.xcodeproj -scheme PhotoPilot -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' -derivedDataPath .build/ios CODE_SIGNING_ALLOWED=NO analyze

android-build:
	./android/gradlew -p android :app:assembleDebug

android-lint:
	./android/gradlew -p android :app:lintDebug

android-test:
	./android/gradlew -p android :app:testDebugUnitTest
