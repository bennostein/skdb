# this builds the artifacts of this repository, orchestrating the
# various build systems

all: npm build/skdb build/init.sql

PLAYWRIGHT_REPORTER?="line"
SKARGO_PROFILE?=release
SKARGO_FLAGS=--profile $(SKARGO_PROFILE)

################################################################################
# skdb wasm + js client
################################################################################

npm: sql/js/dist/skdb.wasm sql/js/dist/skdb.js sql/js/dist/skdb-node.js sql/js/dist/skdb-cli.js

sql/target/wasm32/$(SKARGO_PROFILE)/skdb.wasm: sql/src/* skfs/src/*
	cd sql && skargo build $(SKARGO_FLAGS) --target wasm32

sql/js/dist/skdb.wasm: sql/target/wasm32/$(SKARGO_PROFILE)/skdb.wasm
	mkdir -p sql/js/dist
	cp $^ $@

sql/js/node_modules: sql/js/package.json
	cd sql/js && npm install

sql/js/dist/%.js: sql/js/src/%.ts
	cd sql/js && tsc --build tsconfig.json --pretty false

sql/js/dist/%.js: sql/js/src/%.js
	cd sql/js && tsc --build tsconfig.json --pretty false

sql/js/dist/version.js: sql/js/package.json
	./sql/js/create_version.sh
	cd sql/js && tsc --build tsconfig.json --pretty false

sql/js/dist/skdb.js: sql/js/dist/version.js sql/js/src/skdb.ts
	cd sql/js && tsc --build tsconfig.json --pretty false

sql/js/dist/skdb-node.js: sql/js/dist/skdb.js sql/js/src/node_header.js
	mkdir -p sql/js/dist
	cat sql/js/src/node_header.js sql/js/dist/skdb.js \
	| sed 's|let wasmModule =.*||g' \
	| sed 's|let wasmBuffer =.*|let wasmBuffer = fs.readFileSync(new URL("./skdb.wasm", import.meta.url));|g'> $@

sql/js/dist/index.html: sql/js/tests/index.html
	mkdir -p sql/js/dist
	cp $^ $@

################################################################################
# skdb native binary
################################################################################

sql/target/host/$(SKARGO_PROFILE)/skdb: sql/src/* skfs/src/*
	cd sql && skargo build $(SKARGO_FLAGS)

# TODO: keeping this for now as nearly all test scripts refer to build/skdb
build/skdb: sql/target/host/$(SKARGO_PROFILE)/skdb
	mkdir -p build
	cp $^ $@

################################################################################
# skdb server
################################################################################

build/init.sql: sql/privacy/init.sql
	mkdir -p build
	cp $^ $@

################################################################################
# dev workflow orchestration
################################################################################

.PHONY: clean
clean:
	rm -Rf build
	find . -name 'Skargo.toml' -print0 | sed 's|Skargo.toml|target|g' | xargs -0 rm -rf

.PHONY: fmt
fmt:
	find . -path ./compiler/tests -not -prune -or -name '*'.sk -exec sh -c 'echo {}; skfmt -i {}' \;


# test targets

.PHONY: test
test: SKARGO_PROFILE=dev
test: test-native test-wasm

.PHONY: test-native
test-native: build/skdb
	cd sql/ && SKARGO_PROFILE=$(SKARGO_PROFILE) ./test_sql.sh \
	|tee /tmp/native-test.out ; \
	! grep -v '\*\|^[[:blank:]]*$$\|OK\|PASS' /tmp/native-test.out

.PHONY: test-wasm
test-wasm: npm sql/js/node_modules sql/js/dist/index.html
	cd sql/js && npx playwright install && npx playwright test --reporter=$(PLAYWRIGHT_REPORTER)

.PHONY: test-replication
test-replication: build/skdb
	./sql/test/replication/test_pk.py
	./sql/test/replication/test_no_pk.py

.PHONY: test-tpc
test-tpc: test
	@echo ""
	@echo "*******************************************************************************"
	@echo "* TPC-H *"
	@echo "*******************************************************************************"
	@echo ""
	@cd sql/test/TPC-h/ && ./test_tpch.sh

.PHONY: test-server
test-server: SKARGO_FLAGS=
test-server: npm
	./sql/js/tests/test_server_api/run.sh

.PHONY: test-soak
test-soak: build/skdb build/init.sql npm
	./sql/server/test/test_soak.sh


# run targets

.PHONY: run-server
run-server: SKARGO_PROFILE=dev
run-server: build/skdb build/init.sql
	./sql/server/deploy/start.sh --DANGEROUS-no-encryption --dev

.PHONY: run-chaos
run-chaos: build/skdb
	./sql/server/deploy/chaos.sh

# useful for testing in a browser
build/index.html: sql/js/index.html
	mkdir -p build
	cp $^ $@
