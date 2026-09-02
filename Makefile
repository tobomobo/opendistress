# SPDX-License-Identifier: MIT

.PHONY: test ci

test:
	python3 -m unittest discover -s tests -p 'test_*.py'
	node --test recipient/recipient.test.js
	node --test recipient/mailbox.test.js

ci: test
	python3 -m compileall -q relay scripts tests
	node --check recipient/recipient.js
	node --check recipient/mailbox.js
	python3 -m json.tool protocol/alert-v1.schema.json >/dev/null
	python3 -m json.tool protocol/incident-v2.schema.json >/dev/null
	python3 -m json.tool protocol/status-v2.schema.json >/dev/null
	python3 -m json.tool protocol/mailbox-message-v1.schema.json >/dev/null
	python3 -m json.tool protocol/mailbox-ack-v1.schema.json >/dev/null
	python3 -m json.tool protocol/fixtures/test-ping-v1.json >/dev/null
	python3 -m json.tool protocol/fixtures/live-trigger-v2.json >/dev/null
	python3 -m json.tool protocol/fixtures/location-updated-v2.json >/dev/null
	python3 -m json.tool protocol/fixtures/status-query-v2.json >/dev/null
	python3 -m json.tool protocol/fixtures/mailbox-message-v1.json >/dev/null
	python3 -m json.tool relay/routes.example.json >/dev/null
	python3 -m json.tool relay/mailboxes.example.json >/dev/null
	python3 -m json.tool recipient/config.example.json >/dev/null
