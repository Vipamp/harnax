BINARY := harnax
VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
LDFLAGS := -ldflags "-s -w -X main.version=$(VERSION)"
GO := go
GOPROXY := https://goproxy.cn,direct

.PHONY: build test lint clean install

build:
	GOPROXY=$(GOPROXY) $(GO) build $(LDFLAGS) -o bin/$(BINARY) .

test:
	GOPROXY=$(GOPROXY) $(GO) test ./... -v -race

lint:
	golangci-lint run

clean:
	rm -rf bin/

install: build
	cp bin/$(BINARY) $(GOPATH)/bin/
