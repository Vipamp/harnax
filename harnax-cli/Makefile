BINARY := harnax
VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo "dev")
LDFLAGS := -ldflags "-s -w -X main.version=$(VERSION)"
GO := go
GOPROXY := https://goproxy.cn,direct

# A package takes its identity from plugin.yaml, not from git: admin keys the `cli` row on `name` and
# the file has to be named `<name>-<version>.harnaxcli.zip` for the scanner to pick it up.
PKG_NAME := $(shell awk -F': *' '/^name:/{print $$2; exit}' plugin.yaml)
PKG_VERSION := $(shell awk -F': *' '/^version:/{print $$2; exit}' plugin.yaml)
PKG := dist/$(PKG_NAME)-$(PKG_VERSION).harnaxcli.zip
STAGE := dist/stage
PAYLOAD_BIN := $(STAGE)/payload/usr/local/bin/$(BINARY)
# Agent sandboxes are linux/amd64 containers; override for another host architecture.
GOOS ?= linux
GOARCH ?= amd64

.PHONY: build test lint clean install package

build:
	GOPROXY=$(GOPROXY) $(GO) build $(LDFLAGS) -o bin/$(BINARY) .

test:
	GOPROXY=$(GOPROXY) $(GO) test ./... -v -race

lint:
	golangci-lint run

# The standard CLI package: drop the result into admin's harnax.cli.package-dir (docker-new stages this
# into the admin image) and admin registers the CLI plus the skill it ships on its next startup.
# Modes matter as much as content — the platform reads the execute bit out of the zip's central
# directory, so the binary is chmod'd before it is packed and `zip -X` records it.
package:
	rm -rf $(STAGE)
	# The whole shelf, not just $(PKG): an older zip left behind by a previous version is a second
	# package declaring the same CLI name, which admin then has to arbitrate instead of just upgrading.
	rm -f dist/*.harnaxcli.zip
	mkdir -p $(STAGE)/skill $(dir $(PAYLOAD_BIN))
	CGO_ENABLED=0 GOOS=$(GOOS) GOARCH=$(GOARCH) GOPROXY=$(GOPROXY) \
		$(GO) build -buildvcs=false -ldflags "-s -w -X main.version=$(PKG_VERSION)" -o $(PAYLOAD_BIN) .
	cp plugin.yaml $(STAGE)/plugin.yaml
	cp SKILL.md $(STAGE)/skill/SKILL.md
	chmod 755 $(PAYLOAD_BIN)
	cd $(STAGE) && zip -X -q -r ../../$(PKG) plugin.yaml skill payload
	@echo "$(PKG) ($$(wc -c < $(PKG) | tr -d ' ') bytes)"

clean:
	rm -rf bin/ dist/

install: build
	cp bin/$(BINARY) $(GOPATH)/bin/
