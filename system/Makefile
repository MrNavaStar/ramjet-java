BUILD_DIR := build
VCPKG_ROOT ?= $(CURDIR)/vcpkg
VCPKG_DEFAULT_TRIPLET ?= x64-linux
CMAKE ?= cmake
MUSL_TOOLCHAIN ?= $(CURDIR)/musl-toolchain.cmake
CMAKE_ARGS := -DCMAKE_BUILD_TYPE=Release \
	-DCMAKE_TOOLCHAIN_FILE="$(VCPKG_ROOT)/scripts/buildsystems/vcpkg.cmake" \
	-DVCPKG_TARGET_TRIPLET=$(VCPKG_DEFAULT_TRIPLET) \
	-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE="$(MUSL_TOOLCHAIN)"

ifneq ($(shell command -v musl-gcc 2>/dev/null),)
CMAKE_ARGS += -DCMAKE_C_COMPILER=musl-gcc -DCMAKE_CXX_COMPILER=musl-g++
endif

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