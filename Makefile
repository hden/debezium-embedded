.PHONY: coverage crap quality deploy

coverage:
	lein cloverage --lcov

crap: coverage
	@report=$$(mktemp); \
	trap 'rm -f "$$report"' EXIT; \
	if ! clj -M:crap --use-existing-coverage --lcov target/coverage/lcov.info --source-root src > "$$report"; then \
	  cat "$$report"; \
	  exit 1; \
	fi; \
	cat "$$report"; \
	sh scripts/check-crap-threshold 30 < "$$report"

quality: crap

deploy: quality
	lein deploy clojars
