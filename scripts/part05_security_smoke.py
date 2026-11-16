#!/usr/bin/env python3
"""Run after: Keycloak + Inventory + Booking are online. Dev-only password grant tests."""
import json, uuid, urllib.request, urllib.parse, urllib.error, sys, os
KC=os.environ.get('KEYCLOAK_URL','http://localhost:8090')
IB=os.environ.get('INVENTORY_URL','http://localhost:8082')
BB=os.environ.get('BOOKING_URL','http://localhost:8083')
SECRET=os.environ.get('BOOKING_MACHINE_SECRET','dev_only_booking_machine_secret_2026')


def api(url, method='GET', token=None, body=None, headers=None, form=False):
    h=dict(headers or {})
    data=None
    if token: h['Authorization']='Bearer '+token
    if body is not None:
        if form:
            data=urllib.parse.urlencode(body).encode();h['Content-Type']='application/x-www-form-urlencoded'
        else:
            data=json.dumps(body).encode();h['Content-Type']='application/json'
    req=urllib.request.Request(url,data=data,headers=h,method=method)
    try:
        with urllib.request.urlopen(req,timeout=15) as r: return r.status,json.loads(r.read() or b'{}')
    except urllib.error.HTTPError as e:
        try: payload=json.loads(e.read() or b'{}')
        except ValueError: payload={}
        return e.code,payload


def token(username,password):
    status,body=api(KC+'/realms/seatlock/protocol/openid-connect/token','POST',body={'client_id':'seatlock-cli','grant_type':'password','username':username,'password':password},form=True)
    assert status==200, ('token '+username,status,body)
    return body['access_token']


def expect(label,actual,expected):
    assert actual==expected, f'{label}: expected {expected}, got {actual}'
    print('PASS',label,actual)

# No usable JWT from client-controlled identity headers.
status,_=api(BB+'/api/v1/bookings','POST',body={'eventId':str(uuid.uuid4()),'seatId':str(uuid.uuid4())},headers={'X-Demo-User':str(uuid.uuid4()),'Idempotency-Key':'fake-header-test-01'})
expect('Fake X-Demo-User cannot authenticate',status,401)

alice=token('alice','dev_only_alice_password')
bob=token('bob','dev_only_bob_password')
observer=token('observer','dev_only_observer_password')

def claims(t):
    import base64
    chunk=t.split('.')[1]
    return json.loads(base64.urlsafe_b64decode(chunk + '='*(-len(chunk)%4)))

for username, access_token in (('alice',alice),('bob',bob),('observer',observer)):
    assert claims(access_token).get('sub'), f'{username} token missing subject: run scripts/part05_bootstrap.py'
assert 'booking-api' in claims(alice).get('aud', []), 'Keycloak booking-audience scope not active for Alice'
assert 'customer' in claims(alice).get('realm_access', {}).get('roles', []), 'Alice missing customer realm role'
status, machine=api(KC+'/realms/seatlock/protocol/openid-connect/token','POST',body={
    'grant_type':'client_credentials', 'client_id':'seatlock-booking-service', 'client_secret':SECRET
},form=True)
assert status==200, 'Machine client cannot obtain token: check secret + realm import'
assert 'inventory-api' in claims(machine['access_token']).get('aud', []), 'Machine token missing inventory-api audience'
assert 'inventory.reserve' in claims(machine['access_token']).get('realm_access', {}).get('roles', []), 'Run scripts/part05_bootstrap.py to assign inventory.reserve'
print('PASS Keycloak user and machine token claims')
status,_=api(BB+'/api/v1/bookings','POST',observer,{'eventId':str(uuid.uuid4()),'seatId':str(uuid.uuid4())},headers={'Idempotency-Key':'observer-denied-01'})
expect('Authenticated observer without customer role denied',status,403)

status,_=api(IB+'/actuator/health');expect('Inventory health',status,200)
status,_=api(BB+'/actuator/health');expect('Booking health',status,200)
event='11111111-1111-4111-8111-111111111111'
status,listing=api(IB+'/api/v1/events/'+event+'/seats?size=50')
expect('Public seats are readable',status,200)
seat=next((x['id'] for x in listing['content'] if x['status']=='AVAILABLE'),None)
assert seat,'No AVAILABLE demo seat: use a fresh event or a development-only database reset'
reservation_url=f'{IB}/api/v1/events/{event}/seats/{seat}/reservations'
status,_=api(reservation_url,'POST',alice,{'bookingId':str(uuid.uuid4())})
expect('Customer cannot call machine-only reservation',status,401) # rejected at audience validation
status,_=api(reservation_url,'POST',body={'bookingId':str(uuid.uuid4())})
expect('Anonymous cannot reserve inventory',status,401)

key='secure-'+str(uuid.uuid4())
body={'eventId':event,'seatId':seat}
status,created=api(BB+'/api/v1/bookings','POST',alice,body,{'Idempotency-Key':key})
expect('Alice creates booking via service credentials',status,201)
assert created.get('status')=='CONFIRMED',created
id=created['id']
status,replay=api(BB+'/api/v1/bookings','POST',alice,body,{'Idempotency-Key':key})
expect('Idempotency replay',status,200)
assert replay['id']==id
status,_=api(BB+'/api/v1/bookings/'+id,'GET',bob)
expect('Bob cannot read Alice booking (conceal existence)',status,404)
status,_=api(BB+'/api/v1/bookings/'+id+'/retry','POST',bob)
expect('Bob cannot retry Alice booking',status,404)
status,_=api(BB+'/api/v1/bookings/'+id,'GET',alice)
expect('Alice can read own booking',status,200)
print('ALL PART 5 SECURITY SMOKE TESTS PASSED')
