# Markdown lint & format. Prettier and markdownlint run via npx when Node is
# installed, otherwise through Docker. Vale is a native binary (see README of
# https://vale.sh — `make lint` syncs its style packages on first run).
NPX := $(shell command -v npx 2>/dev/null)

ifdef NPX
PRETTIER := npx --yes prettier@3
MDLINT   := npx --yes markdownlint-cli2
else
PRETTIER := docker run --rm -u $(shell id -u):$(shell id -g) -e HOME=/tmp \
              -v $(CURDIR):/work -w /work node:24-alpine npx --yes prettier@3
MDLINT   := docker run --rm -v $(CURDIR):/workdir davidanson/markdownlint-cli2
endif

.PHONY: lint fmt prose

lint: .vale/styles/write-good  ## fail on format/lint issues; prose style is advisory
	$(PRETTIER) --check "**/*.md"
	$(MDLINT) "**/*.md"
	vale --no-exit README.md posts

fmt:  ## rewrap and format all markdown in place
	$(PRETTIER) --write "**/*.md"

prose: .vale/styles/write-good  ## full Vale prose report
	vale README.md posts

.vale/styles/write-good:
	vale sync
