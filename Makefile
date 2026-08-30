# SPDX-License-Identifier: MIT

.PHONY: test ci

test:
	python3 -m unittest discover -s tests -p 'test_*.py'

ci: test
	python3 -m compileall -q relay tests
	python3 -m json.tool protocol/alert-v1.schema.json >/dev/null
	python3 -m json.tool protocol/fixtures/test-ping-v1.json >/dev/null
