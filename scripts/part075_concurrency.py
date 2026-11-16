#!/usr/bin/env python3
"""Real HTTP concurrency lab: different keys, same seat; exactly ONE winner.
WARNING: permanently consumes ONE AVAILABLE demo seat. Default Gateway profile (Redis rate-limit OFF).
"""
import argparse
import concurrent.futures
import os
import sys
import threading
import uuid
from part06_gateway_smoke import GATEWAY,EVENT as DEFAULT_EVENT,request,token
EVENT = os.getenv("SEATLOCK_EVENT_ID", DEFAULT_EVENT)


def run(n):
    access=token('alice',os.getenv('SEATLOCK_ALICE_PASSWORD','dev_only_alice_password'))
    status, seats,_=request(GATEWAY+f'/api/v1/events/{EVENT}/seats?size=50')
    if status!=200: raise AssertionError(f'Seats list returned {status}')
    seat=next((x['id'] for x in seats['content'] if x['status']=='AVAILABLE'),None)
    if not seat: raise RuntimeError('No available seat; do not reset DB for a test')
    gate=threading.Barrier(n)
    def try_booking(i):
        gate.wait(timeout=15)
        return request(GATEWAY+'/api/v1/bookings','POST',token=access,
                       payload={'eventId':EVENT,'seatId':seat},
                       headers={'Idempotency-Key':f'load-{uuid.uuid4()}'})[:2]
    with concurrent.futures.ThreadPoolExecutor(max_workers=n) as executor:
        futures=[executor.submit(try_booking,i) for i in range(n)]
        responses=[f.result(timeout=45) for f in futures]
    from collections import Counter
    counts=Counter(code for code,_ in responses)
    print('Seat:',seat, 'HTTP statuses:',dict(counts))
    winners=[body for code,body in responses if code==201 and body.get('status')=='CONFIRMED']
    if len(winners)!=1: raise AssertionError(f'Exactly 1 winner required; got {len(winners)}. Inspect: {counts}')
    if counts.get(409)!=n-1:
        raise AssertionError(f'Expected {n-1} HTTP 409 conflicts, got {counts}. Disable Redis rate limit for lab.')
    status,listed,_=request(GATEWAY+f'/api/v1/events/{EVENT}/seats?size=50')
    actual=next(x for x in listed['content'] if x['id']==seat)
    assert actual['status']=='RESERVED', actual
    print('PASS: 1 CONFIRMED and',n-1,'conflicts; no double booking')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--clients',type=int,default=20)
    args=parser.parse_args()
    if args.clients<2 or args.clients>50:parser.error('clients must be 2..50')
    try:run(args.clients)
    except (AssertionError,RuntimeError,TimeoutError,KeyError,ValueError) as exc:
        print('FAIL:',exc,file=sys.stderr);sys.exit(1)
