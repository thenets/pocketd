# =============================================================================
# pocketd — Android build toolchain
# =============================================================================
#
# Targets:
#   make setup      Download Android SDK + Gradle into .cache/ (idempotent)
#   make build      Assemble debug APK  (runs setup if needed)
#   make clean      Delete build outputs
#   make distclean  Delete build outputs AND .cache/
#
# Prerequisites: curl, unzip, java 17+  (no Android Studio needed)
# =============================================================================

CACHE_DIR        := .cache
ANDROID_SDK_DIR  := $(CACHE_DIR)/android-sdk
GRADLE_DIST_DIR  := $(CACHE_DIR)/gradle-dist
GRADLE_HOME_DIR  := $(CACHE_DIR)/gradle-home

# ---------- versions ---------------------------------------------------------
#  To upgrade, update these three lines only.
CMDTOOLS_BUILD   := 11076708        # Android cmdline-tools build number
GRADLE_VERSION   := 8.9
PLATFORM         := android-35
BUILD_TOOLS_VER  := 35.0.0

# ---------- derived paths ----------------------------------------------------
CMDTOOLS_ZIP     := $(CACHE_DIR)/cmdtools-linux-$(CMDTOOLS_BUILD).zip
GRADLE_ZIP       := $(CACHE_DIR)/gradle-$(GRADLE_VERSION)-bin.zip
SDKMANAGER       := $(ANDROID_SDK_DIR)/cmdline-tools/latest/bin/sdkmanager
GRADLE_BIN       := $(GRADLE_DIST_DIR)/gradle-$(GRADLE_VERSION)/bin/gradle
WRAPPER_JAR      := gradle/wrapper/gradle-wrapper.jar

# ---------- download URLs ----------------------------------------------------
CMDTOOLS_URL     := https://dl.google.com/android/repository/commandlinetools-linux-$(CMDTOOLS_BUILD)_latest.zip
GRADLE_DIST_URL  := https://services.gradle.org/distributions/gradle-$(GRADLE_VERSION)-bin.zip
WRAPPER_JAR_URL  := https://raw.githubusercontent.com/gradle/gradle/v$(GRADLE_VERSION).0/gradle/wrapper/gradle-wrapper.jar

# ---------- environment exported to every sub-process ------------------------
export ANDROID_HOME     := $(abspath $(ANDROID_SDK_DIR))
export ANDROID_SDK_ROOT := $(abspath $(ANDROID_SDK_DIR))
export GRADLE_USER_HOME := $(abspath $(GRADLE_HOME_DIR))

# =============================================================================

.PHONY: setup build clean distclean

# -- setup --------------------------------------------------------------------

setup: $(ANDROID_SDK_DIR)/platforms/$(PLATFORM) \
       $(ANDROID_SDK_DIR)/build-tools/$(BUILD_TOOLS_VER) \
       $(GRADLE_BIN) \
       $(WRAPPER_JAR)
	@echo ""
	@echo "==> Setup complete."
	@echo "    Android SDK : $(abspath $(ANDROID_SDK_DIR))"
	@echo "    Gradle      : $(GRADLE_VERSION) ($(abspath $(GRADLE_BIN)))"
	@echo "    Gradle home : $(abspath $(GRADLE_HOME_DIR))"
	@echo ""

# -- Android command-line tools -----------------------------------------------

$(CMDTOOLS_ZIP):
	@mkdir -p $(CACHE_DIR)
	@echo "==> Downloading Android command-line tools (build $(CMDTOOLS_BUILD))..."
	curl -fL "$(CMDTOOLS_URL)" -o $@

$(SDKMANAGER): $(CMDTOOLS_ZIP)
	@mkdir -p $(ANDROID_SDK_DIR)/cmdline-tools
	@echo "==> Extracting cmdline-tools..."
	unzip -q -o $< -d $(ANDROID_SDK_DIR)/cmdline-tools
	@# Google's zip nests it as cmdline-tools/cmdline-tools — rename to 'latest'
	@if [ -d "$(ANDROID_SDK_DIR)/cmdline-tools/cmdline-tools" ]; then \
	    mv "$(ANDROID_SDK_DIR)/cmdline-tools/cmdline-tools" \
	       "$(ANDROID_SDK_DIR)/cmdline-tools/latest"; \
	fi
	@chmod +x $@
	@touch $@

# Accept all SDK licenses non-interactively
$(ANDROID_SDK_DIR)/.licenses-accepted: $(SDKMANAGER)
	@echo "==> Accepting Android SDK licenses..."
	@yes | $(SDKMANAGER) --sdk_root="$(abspath $(ANDROID_SDK_DIR))" --licenses \
	    > /dev/null 2>&1 || true
	@touch $@

$(ANDROID_SDK_DIR)/platforms/$(PLATFORM): $(ANDROID_SDK_DIR)/.licenses-accepted
	@echo "==> Installing platform $(PLATFORM)..."
	$(SDKMANAGER) --sdk_root="$(abspath $(ANDROID_SDK_DIR))" "platforms;$(PLATFORM)"

$(ANDROID_SDK_DIR)/build-tools/$(BUILD_TOOLS_VER): $(ANDROID_SDK_DIR)/.licenses-accepted
	@echo "==> Installing build-tools $(BUILD_TOOLS_VER)..."
	$(SDKMANAGER) --sdk_root="$(abspath $(ANDROID_SDK_DIR))" "build-tools;$(BUILD_TOOLS_VER)"

# -- Gradle -------------------------------------------------------------------

$(GRADLE_ZIP):
	@mkdir -p $(CACHE_DIR)
	@echo "==> Downloading Gradle $(GRADLE_VERSION)..."
	curl -fL "$(GRADLE_DIST_URL)" -o $@

$(GRADLE_BIN): $(GRADLE_ZIP)
	@mkdir -p $(GRADLE_DIST_DIR)
	@echo "==> Extracting Gradle..."
	unzip -q -o $< -d $(GRADLE_DIST_DIR)
	@chmod +x $@

# Gradle wrapper JAR — required by Android Studio and IDE integrations
$(WRAPPER_JAR):
	@mkdir -p gradle/wrapper
	@echo "==> Downloading gradle-wrapper.jar..."
	curl -fL "$(WRAPPER_JAR_URL)" -o $@

# =============================================================================
# Build
# =============================================================================

build: setup
	@echo "==> Building debug APK..."
	$(GRADLE_BIN) :app:assembleDebug
	@echo ""
	@echo "==> APK: app/build/outputs/apk/debug/app-debug.apk"

# =============================================================================
# Clean
# =============================================================================

clean:
	@rm -rf app/build
	@echo "Cleaned build outputs."

distclean: clean
	@rm -rf $(CACHE_DIR) $(WRAPPER_JAR)
	@echo "Cleaned cache."
