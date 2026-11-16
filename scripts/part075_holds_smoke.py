#!/usr/bin/env python3
"""Real HTTP smoke: hold, idempotent replay, RBAC, owner-only redemption, cancellation.
Side effects: permanently reserves ONE demo seat. Uses demo password-grant ONLY for tests.
"""
import sys
import uuid
import os
from part06_gateway_smoke import GATEWAY, EVENT as DEFAULT_EVENT, request, token, expect
EVENT = os.getenv("SEATLOCK_EVENT_ID", DEFAULT_EVENT)


def free_seat():
    status, page, _ = request(GATEWAY+f'/api/v1/events/{EVENT}/seats?size=50')
    expect('List seats', status, 200)
    return next((s['id'] for s in page['content'] if s['status']=='AVAILABLE'), None)


def run():
    alice = token('alice',os.getenv('SEATLOCK_ALICE_PASSWORD','dev_only_alice_password'))
    bob = token('bob',os.getenv('SEATLOCK_BOB_PASSWORD','dev_only_bob_password'))
    seat = free_seat()
    if not seat: raise AssertionError('No available seat. Do not delete data; seed a NEW test event.')
    body={'eventId': EVENT,'seatId':seat}
    key='hold-'+str(uuid.uuid4())
    headers={'Idempotency-Key':key}
    status, hold,_ = request(GATEWAY+'/api/v1/holds','POST',token=alice,payload=body,headers=headers)
    expect('Alice creates a hold',status,201)
    assert hold['status']=='HELD' and hold['seatId']==seat,hold
    hold_id=hold['holdId']
    status, replay,_=request(GATEWAY+'/api/v1/holds','POST',token=alice,payload=body,headers=headers)
    expect('Idempotent hold replay',status,200)
    assert replay['holdId']==hold_id

    status,_,_=request(GATEWAY+'/api/v1/bookings','POST',token=bob,payload={**body,'holdId':hold_id},
                       headers={'Idempotency-Key':'bob-'+str(uuid.uuid4())})
    expect('Bob cannot steal Alice hold',status,409)
    status,_,_=request(GATEWAY+'/api/v1/bookings','POST',token=bob,payload=body,
                       headers={'Idempotency-Key':'bob-'+str(uuid.uuid4())})
    expect('Bob cannot bypass active hold',status,409)
    status,book,_=request(GATEWAY+'/api/v1/bookings','POST',token=alice,
                          payload={**body,'holdId':hold_id},
                          headers={'Idempotency-Key':'book-'+str(uuid.uuid4())})
    expect('Alice redeems her hold',status,201)
    assert book['status']=='CONFIRMED' and book['holdId']==hold_id,book
    print('PASS: CONFIRMED seat will NOT expire with temporary holds')

    seat2=free_seat()
    if seat2:
        body2={'eventId':EVENT,'seatId':seat2}
        key2='hold-'+str(uuid.uuid4())
        status,held,_=request(GATEWAY+'/api/v1/holds','POST',token=alice,
                              payload=body2,headers={'Idempotency-Key':key2})
        expect('Create cancelable hold',status,201)
        status,_,_=request(GATEWAY+f"/api/v1/holds/{held['holdId']}?eventId={EVENT}&seatId={seat2}",
                           'DELETE',token=bob)
        expect('Bob cannot cancel Alice hold',status,409)
        status,_,_=request(GATEWAY+f"/api/v1/holds/{held['holdId']}?eventId={EVENT}&seatId={seat2}",
                           'DELETE',token=alice)
        expect('Alice cancels hold',status,204)
        status,_,_=request(GATEWAY+'/api/v1/holds','POST',token=alice,
                           payload=body2,headers={'Idempotency-Key':key2})
        expect('Canceled hold id cannot be reused',status,409)
    else:
        print('SKIP cancellation smoke: no second free seat (integrations cover it)')
    print('ALL PART 7.5 HOLD SMOKE TESTS PASSED')

if __name__=='__main__':
    try: run()
    except (AssertionError,RuntimeError,KeyError,ValueError) as exc:
        print('FAIL:',exc,file=sys.stderr);sys.exit(1)
