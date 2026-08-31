BUILD_DIR := build
VCPKG_ROOT ?= $(CURDIR)/vcpkg
CMAKE ?= cmake
CMAKE_ARGS := -DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_TOOLCHAIN_FILE="$(VCPKG_ROOT)/scripts/buildsystems/vcpkg.cmake" \
	-DVCPKG_TARGET_TRIPLET=x64-linux

.PHONY: build clean configure

configure:
	$(CMAKE) -S . -B $(BUILD_DIR) $(CMAKE_ARGS)

build: configure
	$(CMAKE) --build $(BUILD_DIR) --parallel $$(nproc)
	ls -lh $(BUILD_DIR)/system

clean:
	rm -rf $(BUILD_DIR)

# Native, direnv-first workflow:
#   direnv allow
#   make build