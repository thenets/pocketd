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
# Prerequisites: curl, unzip, java 17+
# Uses direct curl downloads — no sdkmanager needed.
# =============================================================================

CACHE_DIR        := .cache
ANDROID_SDK_DIR  := $(CACHE_DIR)/android-sdk
GRADLE_DIST_DIR  := $(CACHE_DIR)/gradle-dist
GRADLE_HOME_DIR  := $(CACHE_DIR)/gradle-home

# ---------- versions (update here to upgrade) --------------------------------
GRADLE_VERSION   := 8.11.1
PLATFORM         := android-35
BUILD_TOOLS_VER  := 35.0.0

# ---------- local paths ------------------------------------------------------
PLATFORM_ZIP     := $(CACHE_DIR)/platform-35_r02.zip
BUILD_TOOLS_ZIP  := $(CACHE_DIR)/build-tools_r35_linux.zip
GRADLE_ZIP       := $(CACHE_DIR)/gradle-$(GRADLE_VERSION)-bin.zip
WRAPPER_JAR      := gradle/wrapper/gradle-wrapper.jar
GRADLE_BIN       := $(GRADLE_DIST_DIR)/gradle-$(GRADLE_VERSION)/bin/gradle

# ---------- download URLs ----------------------------------------------------
SDK_BASE_URL     := https://dl.google.com/android/repository
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
       $(ANDROID_SDK_DIR)/licenses/android-sdk-license \
       $(GRADLE_BIN) \
       $(WRAPPER_JAR)
	@echo ""
	@echo "==> Setup complete."
	@echo "    ANDROID_HOME : $(abspath $(ANDROID_SDK_DIR))"
	@echo "    Gradle       : $(GRADLE_VERSION)"
	@echo ""

# -- Android platform ---------------------------------------------------------

$(PLATFORM_ZIP):
	@mkdir -p $(CACHE_DIR)
	@echo "==> Downloading Android platform 35..."
	curl -fL "$(SDK_BASE_URL)/platform-35_r02.zip" -o $@

$(ANDROID_SDK_DIR)/platforms/$(PLATFORM): $(PLATFORM_ZIP)
	@mkdir -p $(ANDROID_SDK_DIR)/platforms
	@echo "==> Extracting Android platform 35..."
	unzip -q -o $< -d $(ANDROID_SDK_DIR)/platforms
	@# Zip extracts to 'android-35/' which is exactly the right directory name.
	@touch $@

# -- Android build-tools ------------------------------------------------------

$(BUILD_TOOLS_ZIP):
	@mkdir -p $(CACHE_DIR)
	@echo "==> Downloading Android build-tools $(BUILD_TOOLS_VER)..."
	curl -fL "$(SDK_BASE_URL)/build-tools_r35_linux.zip" -o $@

$(ANDROID_SDK_DIR)/build-tools/$(BUILD_TOOLS_VER): $(BUILD_TOOLS_ZIP)
	@mkdir -p $(ANDROID_SDK_DIR)/build-tools
	@echo "==> Extracting build-tools $(BUILD_TOOLS_VER)..."
	unzip -q -o $< -d $(ANDROID_SDK_DIR)/build-tools
	@# Zip extracts to 'android-15/' (internal SDK convention) — rename to '35.0.0'
	@mv $(ANDROID_SDK_DIR)/build-tools/android-15 \
	    $(ANDROID_SDK_DIR)/build-tools/$(BUILD_TOOLS_VER)
	@chmod +x $(ANDROID_SDK_DIR)/build-tools/$(BUILD_TOOLS_VER)/aapt2 || true
	@touch $@

# -- SDK licenses (written directly — no sdkmanager needed) -------------------
#    AGP verifies the license hash is present in this file before building.

$(ANDROID_SDK_DIR)/licenses/android-sdk-license:
	@mkdir -p $(ANDROID_SDK_DIR)/licenses
	@printf '\n8933bad161af4178b1185d1a37fbf41ea5269c55\n' >  $@
	@printf 'd56f5187479451eabf01fb78af6dfcb131a6481e\n'   >> $@
	@printf '24333f8a63b6825ea9c5514f83c2829b004d1fee\n'   >> $@

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

# Wrapper JAR — needed by Android Studio / IDE integration
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
