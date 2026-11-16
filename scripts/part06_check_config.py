#!/usr/bin/env python3
"""Offline sanity checks; NOT a replacement for Maven + full system tests."""
import pathlib
import sys
import xml.etree.ElementTree as ET
import yaml

root = pathlib.Path(__file__).resolve().parents[1]
yml = yaml.safe_load((root/'gateway-service/src/main/resources/application.yml').read_text())
profile = yaml.safe_load((root/'gateway-service/src/main/resources/application-rate-limit.yml').read_text())
ET.parse(root/'gateway-service/pom.xml')
base = yml['spring']['cloud']['gateway']['server']['webflux']
routes = {r['id']: r for r in base['routes']}
assert set(routes) == {'inventory-public-reads', 'booking-customer-api'}
assert 'RemoveRequestHeader=Authorization' in routes['inventory-public-reads']['filters']
assert 'RemoveRequestHeader=Authorization' not in routes['booking-customer-api']['filters']
assert 'Method=GET' in routes['inventory-public-reads']['predicates']
assert 'Method=GET,POST' in routes['booking-customer-api']['predicates']
assert base['globalcors']['add-to-simple-url-handler-mapping'] is True
assert yml['spring']['security']['oauth2']['resourceserver']['jwt']['audiences'] == ['booking-api']
assert not yml['management']['health']['redis']['enabled']
assert profile['spring']['cloud']['gateway']['server']['webflux']['routes'][1]['filters'][-1]['name'] == 'RequestRateLimiter'
conf = (root/'gateway-service/src/main/java/com/seatlock/gateway/security/GatewaySecurityConfig.java').read_text()
assert '.anyExchange().denyAll()' in conf
assert 'ROLE_customer' in conf
print('PASS: Maven XML, base/profile YAML, route restrictions, security boundary, CORS and optional rate limit config')
