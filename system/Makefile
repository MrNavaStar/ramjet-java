BUILD_DIR := build
IMAGE := static-c-builder

.PHONY: build clean

image:
	docker build -t $(IMAGE) .

build: image
	docker run --rm \
		--user "$$(id -u):$$(id -g)" \
		-v "$$(pwd)":/src \
		-w /src \
		$(IMAGE) \
		sh -c 'cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXE_LINKER_FLAGS="-static" && cmake --build build -j$$(nproc)'

clean:
	rm -rf $(BUILD_DIR)