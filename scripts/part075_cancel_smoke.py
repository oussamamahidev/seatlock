#!/usr/bin/env python3
"""Dev-only confirmed-booking cancellation and anti-resurrection HTTP test.
WARNING: creates bookings and cancels one on demo event; requires >=1 AVAILABLE seat.
"""
import os
import sys
import time
import uuid
from part06_gateway_smoke import GATEWAY, EVENT as DEFAULT_EVENT, request, token, expect
EVENT = os.getenv("SEATLOCK_EVENT_ID", DEFAULT_EVENT)

def run():
    alice=token('alice', os.getenv('SEATLOCK_ALICE_PASSWORD','dev_only_alice_password'))
    bob=token('bob', os.getenv('SEATLOCK_BOB_PASSWORD','dev_only_bob_password'))
    status,page,_=request(GATEWAY+f'/api/v1/events/{EVENT}/seats?size=50')
    expect('List demo seats',status,200)
    seat=next((s['id'] for s in page['content'] if s['status']=='AVAILABLE'),None)
    if not seat: raise AssertionError('No available demo seat; create a new test event, do not reset database')
    body={'eventId':EVENT,'seatId':seat}
    status,booking,_=request(GATEWAY+'/api/v1/bookings','POST',token=alice,payload=body,
                             headers={'Idempotency-Key':'cancel-'+str(uuid.uuid4())})
    expect('Alice creates confirmed booking',status,201)
    assert booking['status']=='CONFIRMED',booking
    booking_id=booking['id']
    status,_,_=request(GATEWAY+f'/api/v1/bookings/{booking_id}/cancel','POST',token=bob)
    expect('Bob forbidden to cancel Alice booking',status,404)
    status,started,_=request(GATEWAY+f'/api/v1/bookings/{booking_id}/cancel','POST',token=alice)
    expect('Alice requests cancellation',status,202)
    assert started['status']=='CANCEL_PENDING',started
    deadline=time.monotonic()+45
    while time.monotonic()<deadline:
        status,current,_=request(GATEWAY+f'/api/v1/bookings/{booking_id}',token=alice)
        expect('Read cancellation status',status,200)
        if current['status']=='CANCELLED':break
        time.sleep(2)
    else:raise AssertionError('Booking stuck in CANCEL_PENDING; inspect cancellation job / logs')
    status,_,_=request(GATEWAY+f'/api/v1/bookings/{booking_id}/cancel','POST',token=alice)
    expect('Idempotent cancellation replay',status,200)
    status,old,_=request(GATEWAY+f'/api/v1/bookings/{booking_id}/retry','POST',token=alice)
    expect('Stale retry does not restore canceled booking',status,200)
    assert old['status']=='CANCELLED',old
    status,listed,_=request(GATEWAY+f'/api/v1/events/{EVENT}/seats?size=50')
    expect('Inventory after cancellation',status,200)
    assert next(x for x in listed['content'] if x['id']==seat)['status']=='AVAILABLE',listed
    status,new,_=request(GATEWAY+'/api/v1/bookings','POST',token=bob,payload=body,
                         headers={'Idempotency-Key':'new-owner-'+str(uuid.uuid4())})
    expect('Bob can book seat after release',status,201)
    assert new['status']=='CONFIRMED' and new['id']!=booking_id,new
    print('ALL PART 7.5 CANCELLATION TESTS PASSED: canceled booking cannot resurrect')

if __name__=='__main__':
    try:run()
    except (AssertionError,RuntimeError,KeyError,ValueError) as exc:
        print('FAIL:',exc,file=sys.stderr);sys.exit(1)
